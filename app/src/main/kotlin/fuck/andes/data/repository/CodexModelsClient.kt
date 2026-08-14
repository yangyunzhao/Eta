package fuck.andes.data.repository

import fuck.andes.agent.model.AgentHttpClient
import fuck.andes.data.auth.CodexAuthException
import fuck.andes.data.auth.CodexAuthFailure
import fuck.andes.data.auth.CodexCredentialProvider
import fuck.andes.data.auth.CodexOAuthCredential
import fuck.andes.data.auth.CODEX_PROTOCOL_COMPAT_VERSION
import fuck.andes.data.model.Model
import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal class CodexModelsException(
    val failure: Failure,
) : IllegalStateException("Codex OAuth models request failed: ${failure.name.lowercase()}") {
    internal enum class Failure {
        AUTHENTICATION_RETRY_REQUIRED,
        NETWORK_FAILURE,
        PROTOCOL_FAILURE,
        HTTP_FAILURE,
    }
}

/** Fixed Codex model-directory boundary. Provider-configured network fields are intentionally ignored. */
internal class CodexModelsClient private constructor(
    private val credentialProvider: CodexCredentialProvider,
    httpClient: OkHttpClient,
    private val endpointUrl: HttpUrl,
) {
    private val callFactory = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    constructor(credentialProvider: CodexCredentialProvider) : this(
        credentialProvider = credentialProvider,
        httpClient = AgentHttpClient.client,
        endpointUrl = FIXED_ENDPOINT,
    )

    fun fetch(providerId: String): List<Model> {
        val firstCredential = credentialProvider.requireValidCredential(providerId)
        return when (val firstAttempt = executeOnce(firstCredential)) {
            is AttemptResult.Success -> parseModels(firstAttempt.body)
            AttemptResult.Unauthorized -> {
                val refreshed = credentialProvider.refreshAfterUnauthorized(
                    providerId = providerId,
                    rejectedAccessToken = firstCredential.accessToken,
                )
                when (val secondAttempt = executeOnce(refreshed)) {
                    is AttemptResult.Success -> parseModels(secondAttempt.body)
                    AttemptResult.Unauthorized -> {
                        val invalidated = credentialProvider.invalidateAfterUnauthorized(
                            providerId = providerId,
                            rejectedAccessToken = refreshed.accessToken,
                        )
                        if (invalidated) {
                            throw CodexAuthException(CodexAuthFailure.REAUTHENTICATION_REQUIRED)
                        }
                        throw CodexModelsException(
                            CodexModelsException.Failure.AUTHENTICATION_RETRY_REQUIRED,
                        )
                    }
                }
            }
        }
    }

    private fun parseModels(body: String): List<Model> =
        try {
            RemoteModelFetcher.parseCodexModels(body)
        } catch (_: Exception) {
            throw CodexModelsException(CodexModelsException.Failure.PROTOCOL_FAILURE)
        }

    private fun executeOnce(credential: CodexOAuthCredential): AttemptResult {
        val request = try {
            val accessToken = credential.accessToken.requireSafeHeaderValue(MAX_ACCESS_TOKEN_CHARS)
            val accountId = credential.accountId
                ?.takeIf(String::isNotBlank)
                ?.requireSafeHeaderValue(MAX_ACCOUNT_ID_CHARS)
            Request.Builder()
                .url(endpointUrl)
                .headers(
                    okhttp3.Headers.Builder()
                        .add("Accept", "application/json")
                        .add("Authorization", "Bearer $accessToken")
                        .apply { accountId?.let { add("ChatGPT-Account-ID", it) } }
                        .build(),
                )
                .get()
                .build()
        } catch (_: IllegalArgumentException) {
            throw CodexModelsException(CodexModelsException.Failure.PROTOCOL_FAILURE)
        }
        return try {
            callFactory.newCall(request).execute().use { response ->
                when {
                    response.code == 401 -> AttemptResult.Unauthorized
                    !response.isSuccessful -> throw CodexModelsException(CodexModelsException.Failure.HTTP_FAILURE)
                    else -> AttemptResult.Success(response.body.string())
                }
            }
        } catch (failure: IOException) {
            throw CodexModelsException(CodexModelsException.Failure.NETWORK_FAILURE)
        }
    }

    private fun String.requireSafeHeaderValue(maxChars: Int): String {
        if (isEmpty() || length > maxChars || any { it.code !in SAFE_HEADER_CHAR_RANGE }) {
            throw IllegalArgumentException("unsafe header value")
        }
        return this
    }

    private sealed interface AttemptResult {
        data class Success(val body: String) : AttemptResult
        data object Unauthorized : AttemptResult
    }

    companion object {
        private val FIXED_ENDPOINT = HttpUrl.Builder()
            .scheme("https")
            .host("chatgpt.com")
            .addPathSegments("backend-api/codex/models")
            .addQueryParameter("client_version", CODEX_PROTOCOL_COMPAT_VERSION)
            .build()
        private val TEST_LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
        private val SAFE_HEADER_CHAR_RANGE = 0x21..0x7e
        private const val MAX_ACCESS_TOKEN_CHARS = 8_192
        private const val MAX_ACCOUNT_ID_CHARS = 512

        internal fun forTest(
            credentialProvider: CodexCredentialProvider,
            httpClient: OkHttpClient,
            endpointUrl: HttpUrl = FIXED_ENDPOINT,
        ): CodexModelsClient {
            require(endpointUrl == FIXED_ENDPOINT || endpointUrl.host in TEST_LOOPBACK_HOSTS) {
                "Codex models test endpoint must be fixed or loopback"
            }
            return CodexModelsClient(credentialProvider, httpClient, endpointUrl)
        }
    }
}
