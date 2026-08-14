package fuck.andes.data.auth

import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.EventListener
import okio.Timeout
import kotlin.reflect.KClass
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CodexDeviceAuthClientTest {
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
    fun `request authorization sends only public client id and parses device authorization`() = runBlocking {
        server.enqueue(jsonResponse("""
            {"device_auth_id":"device-auth-id","user_code":"ABCD-EFGH","interval":"5"}
        """.trimIndent()))

        val result = client().requestAuthorization()

        assertEquals(
            CodexDeviceAuthorizationResult.Success(
                CodexDeviceAuthorization("device-auth-id", "ABCD-EFGH", 5),
            ),
            result,
        )
        val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/api/accounts/deviceauth/usercode", request.url.encodedPath)
        assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
        val requestJson = JSONObject(requireNotNull(request.body).utf8())
        assertEquals("app_EMoamEEZ73f0CkXaXp7hrann", requestJson.getString("client_id"))
        assertEquals(setOf("client_id"), requestJson.keys().asSequence().toSet())
    }

    @Test
    fun `request authorization accepts official usercode alias and string interval`() = runBlocking {
        server.enqueue(jsonResponse("""
            {"device_auth_id":"device-auth-id","usercode":"ABCD-EFGH","interval":"5"}
        """.trimIndent()))

        val result = client().requestAuthorization()

        assertEquals(
            CodexDeviceAuthorizationResult.Success(
                CodexDeviceAuthorization("device-auth-id", "ABCD-EFGH", 5),
            ),
            result,
        )
    }

    @Test
    fun `default client targets fixed HTTPS auth host`() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val fixedHostClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestedUrls += chain.request().url.toString()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{"device_auth_id":"device-auth-id","user_code":"ABCD-EFGH","interval":"5"}"""
                            .toResponseBody(),
                    )
                    .build()
            }
            .build()

        val result = CodexDeviceAuthClient(httpClient = fixedHostClient).requestAuthorization()

        assertTrue(result is CodexDeviceAuthorizationResult.Success)
        assertEquals("https://auth.openai.com/api/accounts/deviceauth/usercode", requestedUrls.single())
        fixedHostClient.dispatcher.executorService.shutdown()
        fixedHostClient.connectionPool.evictAll()
    }

    @Test
    fun `poll treats 403 and 404 as pending without reading response body`() = runBlocking {
        listOf(403, 404).forEach { status ->
            server.enqueue(MockResponse.Builder().code(status).body("sensitive body").build())

            val result = client().pollOnce(authorization())

            assertEquals(CodexDevicePollResult.Pending, result)
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/api/accounts/deviceauth/token", request.url.encodedPath)
            assertEquals(
                "device-auth-id",
                JSONObject(requireNotNull(request.body).utf8()).getString("device_auth_id"),
            )
        }
    }

    @Test
    fun `poll parses authorization code and PKCE verifier`() = runBlocking {
        server.enqueue(jsonResponse("""
            {"authorization_code":"authorization-code","code_challenge":"code-challenge","code_verifier":"code-verifier"}
        """.trimIndent()))

        val result = client().pollOnce(authorization())

        assertEquals(
            CodexDevicePollResult.Authorized(
                CodexAuthorizationCode("authorization-code", "code-challenge", "code-verifier"),
            ),
            result,
        )
    }

    @Test
    fun `exchange token sends exact URL encoded form and parses ID token claims`() = runBlocking {
        server.enqueue(jsonResponse("""
            {"access_token":"access-token","refresh_token":"refresh-token","id_token":"${syntheticIdToken()}","expires_in":"ignored","account_id":"ignored-account"}
        """.trimIndent()))

        val result = client().exchangeToken(
            CodexAuthorizationCode("authorization-code", "code-challenge", "code-verifier"),
        )

        assertEquals(
            CodexTokenResult.Success(
                CodexTokenSet(
                    accessToken = "access-token",
                    refreshToken = "refresh-token",
                    idToken = syntheticIdToken(),
                    expiresAtEpochMillis = 1_900_000_000_000L,
                    accountId = "account-id",
                ),
            ),
            result,
        )
        val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/oauth/token", request.url.encodedPath)
        assertEquals("application/x-www-form-urlencoded", request.headers["Content-Type"])
        val formPairs = requireNotNull(request.body).utf8().split("&")
        assertEquals(5, formPairs.size)
        assertEquals(
            mapOf(
                "grant_type" to "authorization_code",
                "client_id" to "app_EMoamEEZ73f0CkXaXp7hrann",
                "code" to "authorization-code",
                "code_verifier" to "code-verifier",
                "redirect_uri" to "https://auth.openai.com/deviceauth/callback",
            ),
            formPairs.associate { pair ->
                val (key, value) = pair.split("=", limit = 2)
                java.net.URLDecoder.decode(key, Charsets.UTF_8) to
                    java.net.URLDecoder.decode(value, Charsets.UTF_8)
            },
        )
    }

    @Test
    fun `missing required response fields return protocol failure without response body`() = runBlocking {
        server.enqueue(jsonResponse("""{"device_auth_id":"device-auth-id"}"""))

        val result = client().requestAuthorization()

        assertEquals(
            CodexDeviceAuthorizationResult.Failure(CodexDeviceAuthFailure.PROTOCOL_FAILURE),
            result,
        )
        assertTrue(result.toString().contains("sensitive body").not())
    }

    @Test
    fun `non JSON response returns protocol failure without response body`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("sensitive body").build())

        val result = client().requestAuthorization()

        assertEquals(
            CodexDeviceAuthorizationResult.Failure(CodexDeviceAuthFailure.PROTOCOL_FAILURE),
            result,
        )
        assertTrue(result.toString().contains("sensitive body").not())
    }

    @Test
    fun `cancelling request authorization cancels its exact in flight call`() = runBlocking {
        val factory = RecordingCallFactory()
        val job = launch(Dispatchers.Default) {
            CodexDeviceAuthClient(callFactory = factory).requestAuthorization()
        }

        assertTrue(factory.started.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) { job.cancelAndJoin() }

        assertTrue(job.isCancelled)
        assertTrue(requireNotNull(factory.call).isCanceled())
    }

    @Test
    fun `protocol models redact temporary authorization materials and tokens from toString`() {
        val rendered = listOf(
            CodexDeviceAuthorization("device-auth-id", "ABCD-EFGH", 5),
            CodexAuthorizationCode("authorization-code", "code-challenge", "code-verifier"),
            CodexTokenSet("access-token", "refresh-token", "id-token", 1_900_000_000_000L, "account-id"),
        ).joinToString()

        listOf(
            "device-auth-id",
            "ABCD-EFGH",
            "authorization-code",
            "code-challenge",
            "code-verifier",
            "access-token",
            "refresh-token",
            "id-token",
            "account-id",
        ).forEach { secret -> assertFalse(rendered.contains(secret)) }
    }

    @Test
    fun `redirect responses fail without following another host or HTTP`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", redirectServer.url("/escaped"))
                .build(),
        )

        val result = client().requestAuthorization()

        assertEquals(
            CodexDeviceAuthorizationResult.Failure(CodexDeviceAuthFailure.HTTP_FAILURE),
            result,
        )
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals(null, redirectServer.takeRequest(250, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `strict response validation rejects malformed values at every protocol stage`() = runBlocking {
        val invalidAccountClaimToken = syntheticIdToken("""{"chatgpt_account_id":1,"exp":1900000000}""")
        val cases = listOf(
            StageCase("initial wrong identifier type", """{"device_auth_id":true,"user_code":"code","interval":"5"}""") {
                client().requestAuthorization()
            },
            StageCase("initial blank user code", """{"device_auth_id":"id","user_code":"","interval":"5"}""") {
                client().requestAuthorization()
            },
            StageCase("initial null interval", """{"device_auth_id":"id","user_code":"code","interval":null}""") {
                client().requestAuthorization()
            },
            StageCase("initial interval overflow", """{"device_auth_id":"id","user_code":"code","interval":"2147483648"}""") {
                client().requestAuthorization()
            },
            StageCase("initial interval non-string", """{"device_auth_id":"id","usercode":"code","interval":5}""") {
                client().requestAuthorization()
            },
            StageCase("initial fractional interval", """{"device_auth_id":"id","user_code":"code","interval":"1.5"}""") {
                client().requestAuthorization()
            },
            StageCase("poll missing challenge", """{"authorization_code":"code","code_verifier":"verifier"}""") {
                client().pollOnce(authorization())
            },
            StageCase("poll null challenge", """{"authorization_code":"code","code_challenge":null,"code_verifier":"verifier"}""") {
                client().pollOnce(authorization())
            },
            StageCase("poll wrong authorization code type", """{"authorization_code":1,"code_challenge":"challenge","code_verifier":"verifier"}""") {
                client().pollOnce(authorization())
            },
            StageCase("poll blank verifier", """{"authorization_code":"code","code_challenge":"challenge","code_verifier":""}""") {
                client().pollOnce(authorization())
            },
            StageCase("token wrong token type", """{"access_token":1,"refresh_token":"refresh","id_token":"${syntheticIdToken()}"}""") {
                client().exchangeToken(CodexAuthorizationCode("code", "challenge", "verifier"))
            },
            StageCase("token blank refresh token", """{"access_token":"access","refresh_token":"","id_token":"${syntheticIdToken()}"}""") {
                client().exchangeToken(CodexAuthorizationCode("code", "challenge", "verifier"))
            },
            StageCase("token null ID token", """{"access_token":"access","refresh_token":"refresh","id_token":null}""") {
                client().exchangeToken(CodexAuthorizationCode("code", "challenge", "verifier"))
            },
            StageCase("token malformed id token", """{"access_token":"access","refresh_token":"refresh","id_token":"not-a-jwt"}""") {
                client().exchangeToken(CodexAuthorizationCode("code", "challenge", "verifier"))
            },
            StageCase("token wrong account claim type", """{"access_token":"access","refresh_token":"refresh","id_token":"$invalidAccountClaimToken"}""") {
                client().exchangeToken(CodexAuthorizationCode("code", "challenge", "verifier"))
            },
        )

        cases.forEach { case ->
            server.enqueue(jsonResponse(case.body))
            val result = case.invoke()
            assertTrue(case.name, result.toString().contains("PROTOCOL_FAILURE"))
        }
    }

    private fun client(): CodexDeviceAuthClient = CodexDeviceAuthClient(
        httpClient = httpClient,
        endpointBaseUrl = server.url("/"),
    )

    private fun authorization(): CodexDeviceAuthorization =
        CodexDeviceAuthorization("device-auth-id", "ABCD-EFGH", 5)

    private fun syntheticIdToken(
        payload: String = """{"chatgpt_account_id":"account-id","exp":1900000000}""",
    ): String = listOf(
        base64Url("{}"),
        base64Url(payload),
        "signature",
    ).joinToString(".")

    private fun base64Url(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun jsonResponse(body: String): MockResponse =
        MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    private data class StageCase(
        val name: String,
        val body: String,
        val invoke: suspend () -> Any,
    )

    private class RecordingCallFactory : Call.Factory {
        val started = CountDownLatch(1)
        var call: RecordingCall? = null

        override fun newCall(request: Request): Call = RecordingCall(request, started).also { call = it }
    }

    private class RecordingCall(
        private val recordedRequest: Request,
        private val started: CountDownLatch,
    ) : Call {
        private var cancelled = false
        private var executed = false

        override fun request(): Request = recordedRequest

        override fun execute(): Response = error("execute is not used")

        override fun enqueue(responseCallback: Callback) {
            executed = true
            started.countDown()
        }

        override fun cancel() {
            cancelled = true
        }

        override fun isExecuted(): Boolean = executed

        override fun isCanceled(): Boolean = cancelled

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = RecordingCall(recordedRequest, started)

        override fun addEventListener(eventListener: EventListener) = Unit

        override fun <T : Any> tag(type: KClass<T>): T? = null

        override fun <T> tag(type: Class<out T>): T? = null

        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()

        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
    }
}
