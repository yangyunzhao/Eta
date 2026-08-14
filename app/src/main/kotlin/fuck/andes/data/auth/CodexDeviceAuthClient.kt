package fuck.andes.data.auth

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal class CodexDeviceAuthClient(
    httpClient: OkHttpClient = OkHttpClient(),
    private val endpointBaseUrl: HttpUrl = CodexDeviceAuthDefaults.endpointBaseUrl,
    private val callFactory: Call.Factory = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) : CodexDeviceAuthProtocol {
    override suspend fun requestAuthorization(): CodexDeviceAuthorizationResult =
        try {
            requestOnce(
                endpointBaseUrl.newBuilder()
                    .addPathSegments("api/accounts/deviceauth/usercode")
                    .build(),
                JSONObject().put("client_id", CodexDeviceAuthDefaults.CLIENT_ID).toJsonBody(),
            ) { response ->
                if (!response.isSuccessful) return@requestOnce CodexDeviceAuthorizationResult.Failure(
                    failureForHttpStatus(response.code),
                )
                val json = response.jsonObjectOrNull()
                    ?: return@requestOnce CodexDeviceAuthorizationResult.Failure(
                        CodexDeviceAuthFailure.PROTOCOL_FAILURE,
                    )
                val deviceAuthId = json.requiredString("device_auth_id")
                val userCode = json.requiredUserCode()
                val interval = json.requiredIntervalSeconds("interval")
                if (deviceAuthId == null || userCode == null || interval == null) {
                    CodexDeviceAuthorizationResult.Failure(CodexDeviceAuthFailure.PROTOCOL_FAILURE)
                } else {
                    CodexDeviceAuthorizationResult.Success(
                        CodexDeviceAuthorization(deviceAuthId, userCode, interval),
                    )
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            CodexDeviceAuthorizationResult.Failure(CodexDeviceAuthFailure.NETWORK_FAILURE)
        } catch (_: Throwable) {
            CodexDeviceAuthorizationResult.Failure(CodexDeviceAuthFailure.PROTOCOL_FAILURE)
        }

    override suspend fun pollOnce(auth: CodexDeviceAuthorization): CodexDevicePollResult =
        try {
            requestOnce(
                endpointBaseUrl.newBuilder()
                    .addPathSegments("api/accounts/deviceauth/token")
                    .build(),
                JSONObject()
                    .put("device_auth_id", auth.deviceAuthId)
                    .put("user_code", auth.userCode)
                    .toJsonBody(),
            ) { response ->
                if (response.code == 403 || response.code == 404) return@requestOnce CodexDevicePollResult.Pending
                if (!response.isSuccessful) return@requestOnce CodexDevicePollResult.Failure(
                    failureForHttpStatus(response.code),
                )
                val json = response.jsonObjectOrNull()
                    ?: return@requestOnce CodexDevicePollResult.Failure(
                        CodexDeviceAuthFailure.PROTOCOL_FAILURE,
                    )
                val authorizationCode = json.requiredString("authorization_code")
                val codeChallenge = json.requiredString("code_challenge")
                val codeVerifier = json.requiredString("code_verifier")
                if (authorizationCode == null || codeChallenge == null || codeVerifier == null) {
                    CodexDevicePollResult.Failure(CodexDeviceAuthFailure.PROTOCOL_FAILURE)
                } else {
                    CodexDevicePollResult.Authorized(
                        CodexAuthorizationCode(authorizationCode, codeChallenge, codeVerifier),
                    )
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            CodexDevicePollResult.Failure(CodexDeviceAuthFailure.NETWORK_FAILURE)
        } catch (_: Throwable) {
            CodexDevicePollResult.Failure(CodexDeviceAuthFailure.PROTOCOL_FAILURE)
        }

    override suspend fun exchangeToken(code: CodexAuthorizationCode): CodexTokenResult =
        try {
            requestOnce(
                endpointBaseUrl.newBuilder().addPathSegments("oauth/token").build(),
                FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("client_id", CodexDeviceAuthDefaults.CLIENT_ID)
                    .add("code", code.value)
                    .add("redirect_uri", CodexDeviceAuthDefaults.REDIRECT_URI)
                    .add("code_verifier", code.codeVerifier)
                    .build(),
            ) { response ->
                if (!response.isSuccessful) return@requestOnce CodexTokenResult.Failure(
                    failureForHttpStatus(response.code),
                )
                val json = response.jsonObjectOrNull()
                    ?: return@requestOnce CodexTokenResult.Failure(
                        CodexDeviceAuthFailure.PROTOCOL_FAILURE,
                    )
                val accessToken = json.requiredString("access_token")
                val refreshToken = json.requiredString("refresh_token")
                val idToken = json.requiredString("id_token")
                val idTokenClaims = idToken?.parseClaimsOrNull()
                if (accessToken == null || refreshToken == null || idToken == null || idTokenClaims == null) {
                    CodexTokenResult.Failure(CodexDeviceAuthFailure.PROTOCOL_FAILURE)
                } else {
                    CodexTokenResult.Success(
                        CodexTokenSet(
                            accessToken = accessToken,
                            refreshToken = refreshToken,
                            idToken = idToken,
                            expiresAtEpochMillis = idTokenClaims.expiresAtEpochMillis,
                            accountId = idTokenClaims.accountId,
                        ),
                    )
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            CodexTokenResult.Failure(CodexDeviceAuthFailure.NETWORK_FAILURE)
        } catch (_: Throwable) {
            CodexTokenResult.Failure(CodexDeviceAuthFailure.PROTOCOL_FAILURE)
        }

    private suspend fun <T> requestOnce(
        url: HttpUrl,
        body: RequestBody,
        handleResponse: (Response) -> T,
    ): T = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()
        val call = callFactory.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use(handleResponse) }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        })
    }

    private fun failureForHttpStatus(status: Int): CodexDeviceAuthFailure = when (status) {
        410 -> CodexDeviceAuthFailure.EXPIRED
        400, 401 -> CodexDeviceAuthFailure.UNSUPPORTED
        else -> CodexDeviceAuthFailure.HTTP_FAILURE
    }

    private fun Response.jsonObjectOrNull(): JSONObject? {
        return runCatching { JSONObject(body.string()) }.getOrNull()
    }

}

private fun JSONObject.requiredString(name: String): String? =
    if (!has(name)) null else (get(name) as? String)?.takeIf(String::isNotBlank)

private fun JSONObject.requiredIntervalSeconds(name: String): Int? {
    val value = requiredString(name) ?: return null
    if (!POSITIVE_INTEGER.matches(value)) return null
    return value.toLongOrNull()?.takeIf { it <= Int.MAX_VALUE }?.toInt()
}

private fun JSONObject.requiredUserCode(): String? =
    if (has("user_code")) requiredString("user_code") else requiredString("usercode")

private fun JSONObject.toJsonBody(): RequestBody =
    toString().toRequestBody(JSON_MEDIA_TYPE)

private fun String.parseClaimsOrNull(): IdTokenClaims? = runCatching {
    val parts = split('.')
    require(parts.size == 3)
    val payload = String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
    val json = JSONObject(payload)
    val expiration = json.requiredPositiveIntegralLong("exp") ?: return null
    val accountId = when {
        !json.has("chatgpt_account_id") -> null
        else -> json.requiredString("chatgpt_account_id") ?: return null
    }
    IdTokenClaims(
        accountId = accountId,
        expiresAtEpochMillis = Math.multiplyExact(expiration, MILLIS_PER_SECOND),
    )
}.getOrNull()

private fun JSONObject.requiredPositiveIntegralLong(name: String): Long? = when (val value = getOrNull(name)) {
    is Int -> value.toLong().takeIf { it > 0L }
    is Long -> value.takeIf { it > 0L }
    else -> null
}

private fun JSONObject.getOrNull(name: String): Any? =
    if (has(name)) get(name) else null

private data class IdTokenClaims(
    val accountId: String?,
    val expiresAtEpochMillis: Long,
)

private val JSON_MEDIA_TYPE = "application/json".toMediaType()
private val POSITIVE_INTEGER = Regex("[1-9][0-9]*")
private const val MILLIS_PER_SECOND = 1_000L
