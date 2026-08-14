package fuck.andes.agent.model

import fuck.andes.agent.runtime.AgentRunCancelledException
import fuck.andes.agent.runtime.AgentRunController
import fuck.andes.data.auth.CodexAuthException
import fuck.andes.data.auth.CodexAuthFailure
import fuck.andes.data.auth.CodexCredentialProvider
import fuck.andes.data.auth.CodexOAuthCredential
import fuck.andes.data.model.ProviderAuthModes
import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal enum class CodexResponsesFailure {
    USAGE_LIMIT_REACHED,
    RATE_LIMITED,
    AUTHENTICATION_RETRY_REQUIRED,
    NETWORK_FAILURE,
    PROTOCOL_FAILURE,
    HTTP_FAILURE,
}

internal class CodexResponsesException(
    val failure: CodexResponsesFailure,
) : IllegalStateException("Codex Responses request failed: ${failure.name.lowercase()}")

internal class CodexResponsesProvider private constructor(
    private val credentialProvider: CodexCredentialProvider,
    httpClient: OkHttpClient,
    private val endpointUrl: HttpUrl,
) : AgentProviderClient {
    private val callFactory = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    constructor(credentialProvider: CodexCredentialProvider) : this(
        credentialProvider = credentialProvider,
        httpClient = AgentHttpClient.client,
        endpointUrl = FIXED_ENDPOINT,
    )

    override val id: String = "codex_oauth_responses"

    override val capabilities = ProviderCapabilities(
        endpoint = EndpointKind.RESPONSES,
        streamingText = true,
        streamingToolCalls = true,
        imageInput = true,
        toolResultImages = false,
        strictTools = false,
        parallelToolCalls = true,
    )

    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit,
    ): ProviderResponse {
        validateRequest(request)
        val body = ResponsesRequestBuilder.buildCodex(
            request.config,
            request.messages,
            request.tools,
        ).toString()
        val firstCredential = credentialProvider.requireValidCredential(request.config.providerId)
        return when (val firstAttempt = executeOnce(body, firstCredential, runController, onEvent)) {
            is AttemptResult.Success -> firstAttempt.response
            AttemptResult.Unauthorized -> {
                val refreshed = credentialProvider.refreshAfterUnauthorized(
                    providerId = request.config.providerId,
                    rejectedAccessToken = firstCredential.accessToken,
                )
                when (val secondAttempt = executeOnce(body, refreshed, runController, onEvent)) {
                    is AttemptResult.Success -> secondAttempt.response
                    AttemptResult.Unauthorized -> {
                        val invalidated = credentialProvider.invalidateAfterUnauthorized(
                            providerId = request.config.providerId,
                            rejectedAccessToken = refreshed.accessToken,
                        )
                        if (invalidated) {
                            throw CodexAuthException(CodexAuthFailure.REAUTHENTICATION_REQUIRED)
                        }
                        throw CodexResponsesException(
                            CodexResponsesFailure.AUTHENTICATION_RETRY_REQUIRED,
                        )
                    }
                }
            }
        }
    }

    internal fun buildRequestJson(
        config: AgentModelClient.ModelConfig,
        messages: JSONArray,
        tools: JSONArray,
    ): JSONObject = ResponsesRequestBuilder.buildCodex(config, messages, tools)

    private fun validateRequest(request: ProviderRequest) {
        request.config.validateForTest()
        require(request.config.authMode == ProviderAuthModes.CODEX_OAUTH) {
            "Codex Responses Provider requires Codex OAuth"
        }
        check(endpointUrl.scheme == "https" || endpointUrl.host in TEST_LOOPBACK_HOSTS) {
            "Codex Responses endpoint must use HTTPS"
        }
    }

    private fun executeOnce(
        body: String,
        credential: CodexOAuthCredential,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit,
    ): AttemptResult {
        val httpRequest = try {
            val accessToken = credential.accessToken.requireSafeHeaderValue(MAX_ACCESS_TOKEN_CHARS)
            val accountId = credential.accountId
                ?.takeIf(String::isNotBlank)
                ?.requireSafeHeaderValue(MAX_ACCOUNT_ID_CHARS)
            val headers = okhttp3.Headers.Builder()
                .add("Content-Type", "application/json")
                .add("Accept", "text/event-stream")
                .add("Authorization", "Bearer $accessToken")
                .add("originator", ORIGINATOR)
                .add("User-Agent", USER_AGENT)
                .add("version", PROTOCOL_VERSION)
                .apply {
                    accountId?.let { add("ChatGPT-Account-ID", it) }
                }
                .build()
            Request.Builder()
                .url(endpointUrl)
                .headers(headers)
                .post(body.toRequestBody(null))
                .build()
        } catch (_: IllegalArgumentException) {
            throw CodexResponsesException(CodexResponsesFailure.PROTOCOL_FAILURE)
        }
        val call = callFactory.newCall(httpRequest)
        val binding = runController.register(call::cancel)
        try {
            runController.throwIfCancelled()
            onEvent(ProviderEvent.RequestStarted)
            call.execute().use { response ->
                onEvent(ProviderEvent.ResponseHeaders(response.code))
                runController.throwIfCancelled()
                when (response.code) {
                    401 -> return AttemptResult.Unauthorized
                    429 -> throw CodexResponsesException(response.classifyRateLimit())
                }
                if (!response.isSuccessful) {
                    throw CodexResponsesException(CodexResponsesFailure.HTTP_FAILURE)
                }
                val contentType = response.header("Content-Type")
                if (contentType?.substringBefore(';')?.trim()?.lowercase() != "text/event-stream") {
                    throw CodexResponsesException(CodexResponsesFailure.PROTOCOL_FAILURE)
                }
                val assistant = try {
                    ResponsesSseParser.parse(
                        stream = response.body.byteStream(),
                        runController = runController,
                        onEvent = onEvent,
                    )
                } catch (cancelled: AgentRunCancelledException) {
                    throw cancelled
                } catch (_: IOException) {
                    runCatching { runController.throwIfCancelled() }
                        .getOrElse { interruption -> throw interruption }
                    throw CodexResponsesException(CodexResponsesFailure.NETWORK_FAILURE)
                } catch (_: Exception) {
                    runCatching { runController.throwIfCancelled() }
                        .getOrElse { interruption -> throw interruption }
                    throw CodexResponsesException(CodexResponsesFailure.PROTOCOL_FAILURE)
                }
                onEvent(ProviderEvent.Completed(assistant.optString("finish_reason").ifBlank { null }))
                return AttemptResult.Success(ProviderResponse(assistant))
            }
        } catch (_: IOException) {
            runCatching { runController.throwIfCancelled() }
                .getOrElse { interruption -> throw interruption }
            throw CodexResponsesException(CodexResponsesFailure.NETWORK_FAILURE)
        } finally {
            binding.close()
        }
    }

    private fun String.requireSafeHeaderValue(maxChars: Int): String {
        if (isEmpty() || length > maxChars || any { it.code !in SAFE_HEADER_CHAR_RANGE }) {
            throw CodexResponsesException(CodexResponsesFailure.PROTOCOL_FAILURE)
        }
        return this
    }

    private fun okhttp3.Response.classifyRateLimit(): CodexResponsesFailure {
        val signal = runCatching {
            val json = JSONObject(peekBody(MAX_RATE_LIMIT_SIGNAL_BYTES).string())
            val error = json.optJSONObject("error") ?: json
            sequenceOf(error.optString("type"), error.optString("code"))
                .filter(String::isNotBlank)
                .joinToString(" ")
                .lowercase()
        }.getOrDefault("")
        return if (
            "usage_limit" in signal ||
            "quota" in signal ||
            "insufficient_quota" in signal
        ) {
            CodexResponsesFailure.USAGE_LIMIT_REACHED
        } else {
            CodexResponsesFailure.RATE_LIMITED
        }
    }

    private sealed interface AttemptResult {
        data class Success(val response: ProviderResponse) : AttemptResult
        data object Unauthorized : AttemptResult
    }

    companion object {
        private val FIXED_ENDPOINT = HttpUrl.Builder()
            .scheme("https")
            .host("chatgpt.com")
            .addPathSegments("backend-api/codex/responses")
            .build()
        private val TEST_LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
        private val SAFE_HEADER_CHAR_RANGE = 0x21..0x7e
        private const val MAX_ACCESS_TOKEN_CHARS = 8_192
        private const val MAX_ACCOUNT_ID_CHARS = 512
        private const val MAX_RATE_LIMIT_SIGNAL_BYTES = 16_384L
        private const val ORIGINATOR = "codex_cli_rs"
        private const val USER_AGENT = "eta_codex_oauth/1"
        private const val PROTOCOL_VERSION = "1"

        internal fun forTest(
            credentialProvider: CodexCredentialProvider,
            httpClient: OkHttpClient,
            endpointUrl: HttpUrl = FIXED_ENDPOINT,
        ): CodexResponsesProvider {
            require(endpointUrl == FIXED_ENDPOINT || endpointUrl.host in TEST_LOOPBACK_HOSTS) {
                "Codex Responses test endpoint must be fixed or loopback"
            }
            return CodexResponsesProvider(credentialProvider, httpClient, endpointUrl)
        }
    }
}
