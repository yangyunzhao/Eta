package fuck.andes.agent.model

import fuck.andes.agent.runtime.AgentRunCancelledException
import fuck.andes.agent.runtime.AgentRunController
import fuck.andes.data.auth.CodexAuthException
import fuck.andes.data.auth.CodexAuthFailure
import fuck.andes.data.auth.CodexCredentialProvider
import fuck.andes.data.auth.CodexOAuthCredential
import fuck.andes.data.model.CustomBody
import fuck.andes.data.model.CustomHeader
import fuck.andes.data.model.ModelReasoningCapabilities
import fuck.andes.data.model.OpenAiEndpointMode
import fuck.andes.data.model.ProviderAuthModes
import fuck.andes.data.model.ProviderSourceTypes
import fuck.andes.data.model.ProviderTypes
import fuck.andes.data.model.ReasoningEffort
import fuck.andes.data.provider.BuiltinProviders
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CodexResponsesProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var redirectServer: MockWebServer
    private lateinit var httpClient: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        redirectServer = MockWebServer()
        redirectServer.start()
        httpClient = OkHttpClient()
    }

    @After
    fun tearDown() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
        server.close()
        redirectServer.close()
    }

    @Test
    fun `request fixes Codex route headers and protected body while preserving conversation`() {
        server.enqueue(sseResponse(completedSse(text = "ok")))
        val provider = provider(
            credentialProvider = SequenceCredentialProvider(listOf(credential("access-one", "account-one"))),
        )
        val messages = JSONArray()
            .put(JSONObject().put("role", "user").put("content", "first"))
            .put(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", "calling")
                    .put(
                        "tool_calls",
                        JSONArray().put(
                            JSONObject()
                                .put("id", "call-1")
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject().put("name", "device_info").put("arguments", "{}"),
                                ),
                        ),
                    ),
            )
            .put(
                JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", "call-1")
                    .put("content", "{\"ok\":true}"),
            )
        val tools = JSONArray().put(
            JSONObject().put("type", "function").put(
                "function",
                JSONObject()
                    .put("name", "device_info")
                    .put("description", "device")
                    .put("parameters", JSONObject().put("type", "object")),
            ),
        )

        provider.complete(
            ProviderRequest(
                config = codexConfig().copy(
                    reasoningEffort = ReasoningEffort.ULTRA,
                    reasoningCapabilities = ModelReasoningCapabilities(
                        supportedEfforts = listOf(ReasoningEffort.ULTRA),
                    ),
                    customHeaders = listOf(
                        CustomHeader("Authorization", "Bearer attacker"),
                        CustomHeader("Originator", "attacker"),
                        CustomHeader("ChatGPT-Account-ID", "attacker-account"),
                    ),
                    extraBodyJson =
                        """{"model":"wrong","stream":false,"store":true,"include":["wrong"]}""",
                    customBody = listOf(
                        CustomBody("input", JsonPrimitive("wrong")),
                        CustomBody("tools", JsonPrimitive("wrong")),
                        CustomBody("reasoning", JsonPrimitive("wrong")),
                    ),
                ),
                messages = messages,
                tools = tools,
            ),
            AgentRunController(),
        )

        val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/backend-api/codex/responses", request.url.encodedPath)
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals("text/event-stream", request.headers["Accept"])
        assertEquals("Bearer access-one", request.headers["Authorization"])
        assertEquals("account-one", request.headers["ChatGPT-Account-ID"])
        assertEquals("codex_cli_rs", request.headers["originator"])
        assertEquals("eta_codex_oauth/1", request.headers["User-Agent"])
        assertEquals(null, request.headers["version"])
        assertEquals(null, request.headers["OpenAI-Beta"])
        val body = JSONObject(requireNotNull(request.body).utf8())
        assertEquals("gpt-5.5", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertFalse(body.getBoolean("store"))
        assertEquals("ultra", body.getJSONObject("reasoning").getString("effort"))
        assertEquals("reasoning.encrypted_content", body.getJSONArray("include").getString(0))
        assertEquals(4, body.getJSONArray("input").length())
        assertEquals("function_call", body.getJSONArray("input").getJSONObject(2).getString("type"))
        assertEquals("function_call_output", body.getJSONArray("input").getJSONObject(3).getString("type"))
        assertEquals("device_info", body.getJSONArray("tools").getJSONObject(0).getString("name"))
    }

    @Test
    fun `default endpoint ignores configured base URL and remains fixed HTTPS`() {
        val requested = mutableListOf<String>()
        val interceptingClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requested += chain.request().url.toString()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "text/event-stream")
                    .body(completedSse("ok").toResponseBody("text/event-stream".toMediaType()))
                    .build()
            }
            .build()
        val provider = CodexResponsesProvider.forTest(
            credentialProvider = SequenceCredentialProvider(listOf(credential("access-one"))),
            httpClient = interceptingClient,
        )

        provider.complete(basicRequest(), AgentRunController())

        assertEquals("https://chatgpt.com/backend-api/codex/responses", requested.single())
        interceptingClient.dispatcher.executorService.shutdown()
        interceptingClient.connectionPool.evictAll()
    }

    @Test
    fun `missing response Content-Type still parses a valid SSE body`() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(completedSse("ok"))
                .build(),
        )

        val response = provider(
            SequenceCredentialProvider(listOf(credential("access-one"))),
        ).complete(basicRequest(), AgentRunController())

        assertEquals("ok", response.assistantMessage.getString("content"))
    }

    @Test
    fun `SSE text reasoning tool usage and terminal output reuse Responses semantics`() {
        val output = JSONArray()
            .put(
                JSONObject()
                    .put("id", "rs-1")
                    .put("type", "reasoning")
                    .put("encrypted_content", "opaque")
                    .put("summary", JSONArray().put(JSONObject().put("text", "final reasoning"))),
            )
            .put(
                JSONObject()
                    .put("id", "fc-1")
                    .put("type", "function_call")
                    .put("call_id", "call-1")
                    .put("name", "device_info")
                    .put("arguments", "{\"detail\":true}"),
            )
        val terminal = JSONObject()
            .put("status", "completed")
            .put("output", output)
            .put(
                "usage",
                JSONObject()
                    .put("input_tokens", 5)
                    .put("output_tokens", 7)
                    .put("total_tokens", 12)
                    .put("output_tokens_details", JSONObject().put("reasoning_tokens", 3)),
            )
        val sse = buildString {
            append(event("response.reasoning_summary_text.delta", JSONObject().put("delta", "partial")))
            append(event("response.output_text.delta", JSONObject().put("delta", "hello")))
            append(
                event(
                    "response.output_item.added",
                    JSONObject().put(
                        "item",
                        JSONObject()
                            .put("id", "fc-1")
                            .put("type", "function_call")
                            .put("call_id", "call-1")
                            .put("name", "device_info"),
                    ),
                ),
            )
            append(
                event(
                    "response.function_call_arguments.delta",
                    JSONObject().put("item_id", "fc-1").put("delta", "{\"detail\":true}"),
                ),
            )
            append(event("response.completed", JSONObject().put("response", terminal)))
        }
        server.enqueue(sseResponse(sse))
        val events = mutableListOf<ProviderEvent>()

        val response = provider(
            SequenceCredentialProvider(listOf(credential("access-one"))),
        ).complete(basicRequest(), AgentRunController(), events::add)

        assertEquals("final reasoning", response.assistantMessage.getString("reasoning_content"))
        assertEquals("call-1", response.assistantMessage.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("tool_calls", response.assistantMessage.getString("finish_reason"))
        assertEquals(12, events.filterIsInstance<ProviderEvent.Usage>().single().usage.contextTokens)
        assertEquals(3, events.filterIsInstance<ProviderEvent.Usage>().single().usage.reasoningTokens)
    }

    @Test
    fun `first 401 refreshes and retries once with rotated bearer`() {
        server.enqueue(MockResponse.Builder().code(401).body("rejected secret response").build())
        server.enqueue(sseResponse(completedSse("ok")))
        val credentials = SequenceCredentialProvider(
            listOf(credential("access-one"), credential("access-two")),
        )

        val response = provider(credentials).complete(basicRequest(), AgentRunController())

        assertEquals("ok", response.assistantMessage.getString("content"))
        val firstRequest = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("Bearer access-one", firstRequest.headers["Authorization"])
        assertEquals(null, firstRequest.headers["ChatGPT-Account-ID"])
        val retriedRequest = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("Bearer access-two", retriedRequest.headers["Authorization"])
        assertEquals(null, retriedRequest.headers["ChatGPT-Account-ID"])
        assertEquals(1, credentials.refreshCount)
        assertEquals(0, credentials.invalidateCount)
    }

    @Test
    fun `second 401 invalidates only rejected credential and requires login without leaking bodies`() {
        server.enqueue(MockResponse.Builder().code(401).body("first-sensitive-body").build())
        server.enqueue(MockResponse.Builder().code(401).body("second-sensitive-body").build())
        val credentials = SequenceCredentialProvider(
            listOf(credential("access-one"), credential("access-two")),
        )

        val thrown = runCatching {
            provider(credentials).complete(basicRequest(), AgentRunController())
        }.exceptionOrNull()

        assertTrue(thrown is CodexAuthException)
        assertEquals(CodexAuthFailure.REAUTHENTICATION_REQUIRED, (thrown as CodexAuthException).failure)
        assertEquals(2, server.requestCount)
        assertEquals(1, credentials.invalidateCount)
        assertEquals("access-two", credentials.invalidatedAccessToken)
        assertFalse(thrown.toString().contains("first-sensitive-body"))
        assertFalse(thrown.toString().contains("second-sensitive-body"))
        assertFalse(thrown.toString().contains("access-two"))
    }

    @Test
    fun `second 401 preserves concurrently rotated credential without claiming reauthentication`() {
        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(MockResponse.Builder().code(401).build())
        val credentials = SequenceCredentialProvider(
            credentials = listOf(credential("access-one"), credential("access-two")),
            invalidateResult = false,
        )

        val thrown = runCatching {
            provider(credentials).complete(basicRequest(), AgentRunController())
        }.exceptionOrNull()

        assertTrue(thrown is CodexResponsesException)
        assertEquals(
            CodexResponsesFailure.AUTHENTICATION_RETRY_REQUIRED,
            (thrown as CodexResponsesException).failure,
        )
        assertEquals(2, server.requestCount)
        assertEquals(1, credentials.invalidateCount)
    }

    @Test
    fun `invalid credential header values fail with stable redacted protocol error`() {
        listOf(
            credential("access\r\naccess-secret-sentinel"),
            credential("access-one", "account\r\naccount-secret-sentinel"),
        ).forEach { unsafeCredential ->
            val thrown = runCatching {
                provider(
                    SequenceCredentialProvider(listOf(unsafeCredential)),
                ).complete(basicRequest(), AgentRunController())
            }.exceptionOrNull()

            assertTrue(thrown is CodexResponsesException)
            assertEquals(CodexResponsesFailure.PROTOCOL_FAILURE, (thrown as CodexResponsesException).failure)
            assertFalse(thrown.toString().contains("access-secret-sentinel"))
            assertFalse(thrown.toString().contains("account-secret-sentinel"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `rate limit and malformed SSE use stable redacted failures`() {
        listOf(
            MockResponse.Builder()
                .code(429)
                .body(
                    """{"error":{"type":"rate_limit_error","code":"usage_limit_reached","message":"quota-sensitive-body"}}""",
                )
                .build() to CodexResponsesFailure.USAGE_LIMIT_REACHED,
            MockResponse.Builder()
                .code(429)
                .body(
                    """{"error":{"code":"rate_limit_exceeded","message":"rate-sensitive-body"}}""",
                )
                .build() to CodexResponsesFailure.RATE_LIMITED,
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("content-type-sensitive-body")
                .build() to CodexResponsesFailure.PROTOCOL_FAILURE,
            sseResponse("data: not-json\n\n") to CodexResponsesFailure.PROTOCOL_FAILURE,
        ).forEach { (mockResponse, expectedFailure) ->
            server.enqueue(mockResponse)
            val thrown = runCatching {
                provider(
                    SequenceCredentialProvider(listOf(credential("access-one"))),
                ).complete(basicRequest(), AgentRunController())
            }.exceptionOrNull()

            assertTrue(thrown is CodexResponsesException)
            assertEquals(expectedFailure, (thrown as CodexResponsesException).failure)
            assertFalse(thrown.toString().contains("rate-sensitive-body"))
            assertFalse(thrown.toString().contains("quota-sensitive-body"))
            assertFalse(thrown.toString().contains("content-type-sensitive-body"))
            assertFalse(thrown.toString().contains("not-json"))
            assertFalse(thrown.toString().contains("access-one"))
        }
    }

    @Test
    fun `debug trace distinguishes HTTP and SSE protocol failure stages without credentials`() {
        val lines = mutableListOf<String>()
        val logger = CodexProtocolDebugLogger(enabled = true) { _, line -> lines += line }
        val tracedProvider = CodexResponsesProvider.forTest(
            credentialProvider = SequenceCredentialProvider(
                listOf(credential("access-secret-sentinel", "account-secret-sentinel")),
            ),
            httpClient = httpClient,
            endpointUrl = server.url("/backend-api/codex/responses"),
            debugLogger = logger,
        )
        val responses = listOf(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("not-sse")
                .build(),
            sseResponse("data: invalid-auth-sentinel\n\n"),
            sseResponse(
                event(
                    "error",
                    JSONObject()
                        .put("code", "bad_request")
                        .put("authorization", "top-level-auth-sentinel"),
                ),
            ),
            sseResponse(
                event(
                    "response.failed",
                    JSONObject().put(
                        "response",
                        JSONObject()
                            .put("status", "failed")
                            .put("error", JSONObject().put("access_token", "failed-auth-sentinel")),
                    ),
                ),
            ),
            sseResponse(completedSse("ok")),
        )

        responses.forEach { response ->
            server.enqueue(response)
            runCatching {
                tracedProvider.complete(basicRequest(), AgentRunController())
            }
        }

        val trace = lines.joinToString("\n")
        listOf(
            "content_type_mismatch",
            "sse_invalid_json",
            "sse_top_level_error",
            "sse_response_failed",
            "sse_complete",
        ).forEach { stage -> assertTrue("missing stage $stage", trace.contains(stage)) }
        listOf(
            "access-secret-sentinel",
            "account-secret-sentinel",
            "invalid-auth-sentinel",
            "top-level-auth-sentinel",
            "failed-auth-sentinel",
        ).forEach { secret -> assertFalse("leaked $secret", trace.contains(secret)) }
    }

    @Test
    fun `redirect is not followed`() {
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", redirectServer.url("/escaped"))
                .build(),
        )

        val thrown = runCatching {
            provider(
                SequenceCredentialProvider(listOf(credential("access-one"))),
            ).complete(basicRequest(), AgentRunController())
        }.exceptionOrNull()

        assertTrue(thrown is CodexResponsesException)
        assertEquals(CodexResponsesFailure.HTTP_FAILURE, (thrown as CodexResponsesException).failure)
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals(null, redirectServer.takeRequest(250, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `cancelling run cancels exact in flight HTTP call`() {
        val cancelled = CountDownLatch(1)
        val deltaReceived = CountDownLatch(1)
        val cancellationClient = OkHttpClient.Builder()
            .eventListener(
                object : EventListener() {
                    override fun canceled(call: Call) {
                        cancelled.countDown()
                    }
                },
            )
            .build()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .throttleBody(256, 30, TimeUnit.SECONDS)
                .body(
                    event("response.output_text.delta", JSONObject().put("delta", "started")) +
                        ":${"x".repeat(4_096)}\n\n" +
                        completedSse("late"),
                )
                .build(),
        )
        val provider = CodexResponsesProvider.forTest(
            credentialProvider = SequenceCredentialProvider(listOf(credential("access-one"))),
            httpClient = cancellationClient,
            endpointUrl = server.url("/backend-api/codex/responses"),
        )
        val runController = AgentRunController()
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit<Throwable?> {
            runCatching {
                provider.complete(basicRequest(), runController) { event ->
                    if (event is ProviderEvent.BlockDelta) deltaReceived.countDown()
                }
            }.exceptionOrNull()
        }

        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertTrue(deltaReceived.await(5, TimeUnit.SECONDS))
        runController.cancel()

        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
        assertTrue(future.get(5, TimeUnit.SECONDS) is AgentRunCancelledException)
        executor.shutdownNow()
        cancellationClient.dispatcher.executorService.shutdown()
        cancellationClient.connectionPool.evictAll()
    }

    private fun provider(credentialProvider: CodexCredentialProvider): CodexResponsesProvider =
        CodexResponsesProvider.forTest(
            credentialProvider = credentialProvider,
            httpClient = httpClient,
            endpointUrl = server.url("/backend-api/codex/responses"),
        )

    private fun basicRequest() = ProviderRequest(
        config = codexConfig(),
        messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hello")),
        tools = JSONArray(),
    )

    private fun codexConfig() = AgentModelClient.ModelConfig(
        providerId = BuiltinProviders.OPENAI_ID,
        providerName = "OpenAI",
        providerType = ProviderTypes.OPENAI_COMPATIBLE,
        providerSourceType = ProviderSourceTypes.OPENAI,
        baseUrl = "http://attacker.invalid/v1",
        apiKey = "",
        model = "gpt-5.5",
        systemPrompt = "system",
        openAiEndpointMode = OpenAiEndpointMode.RESPONSES,
        authMode = ProviderAuthModes.CODEX_OAUTH,
    )

    private fun credential(accessToken: String, accountId: String? = null) = CodexOAuthCredential(
        accessToken = accessToken,
        refreshToken = "refresh-token",
        idToken = "id-token",
        accountId = accountId,
        expiresAtEpochMillis = Long.MAX_VALUE,
    )

    private fun completedSse(text: String): String = event(
        "response.completed",
        JSONObject().put(
            "response",
            JSONObject().put("status", "completed").put(
                "output",
                JSONArray().put(
                    JSONObject()
                        .put("id", "msg-1")
                        .put("type", "message")
                        .put(
                            "content",
                            JSONArray().put(JSONObject().put("type", "output_text").put("text", text)),
                        ),
                ),
            ),
        ),
    )

    private fun event(type: String, fields: JSONObject): String =
        "event: $type\ndata: ${JSONObject(fields.toString()).put("type", type)}\n\n"

    private fun sseResponse(body: String): MockResponse = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "text/event-stream")
        .body(body)
        .build()

    private class SequenceCredentialProvider(
        private val credentials: List<CodexOAuthCredential>,
        private val invalidateResult: Boolean? = null,
    ) : CodexCredentialProvider {
        var refreshCount = 0
            private set
        var invalidateCount = 0
            private set
        var invalidatedAccessToken: String? = null
            private set
        private var current = 0

        override fun requireValidCredential(providerId: String): CodexOAuthCredential =
            credentials[current].also { require(providerId == BuiltinProviders.OPENAI_ID) }

        override fun refreshAfterUnauthorized(
            providerId: String,
            rejectedAccessToken: String,
        ): CodexOAuthCredential {
            require(providerId == BuiltinProviders.OPENAI_ID)
            require(rejectedAccessToken == credentials[current].accessToken)
            refreshCount++
            current = (current + 1).coerceAtMost(credentials.lastIndex)
            return credentials[current]
        }

        override fun invalidateAfterUnauthorized(
            providerId: String,
            rejectedAccessToken: String,
        ): Boolean {
            require(providerId == BuiltinProviders.OPENAI_ID)
            invalidateCount++
            invalidatedAccessToken = rejectedAccessToken
            return invalidateResult ?: (credentials[current].accessToken == rejectedAccessToken)
        }
    }
}
