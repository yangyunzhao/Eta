package fuck.andes.data.auth

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal class CodexDeviceAuthClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val endpointBaseUrl: HttpUrl = CodexDeviceAuthDefaults.endpointBaseUrl,
) : CodexDeviceAuthProtocol {
    override suspend fun requestAuthorization(): CodexDeviceAuthorizationResult =
        try {
            requestOnce(
                endpointBaseUrl.newBuilder()
                    .addPathSegments("api/accounts/deviceauth/usercode")
                    .build(),
                JSONObject().put("client_id", CodexDeviceAuthDefaults.CLIENT_ID),
            ) { response ->
                if (!response.isSuccessful) return@requestOnce CodexDeviceAuthorizationResult.Failure(
                    failureForHttpStatus(response.code),
                )
                val json = response.jsonObjectOrNull()
                    ?: return@requestOnce CodexDeviceAuthorizationResult.Failure(
                        CodexDeviceAuthFailure.PROTOCOL_FAILURE,
                    )
                val deviceAuthId = json.requiredString("device_auth_id")
                val userCode = json.requiredString("user_code")
                val interval = json.requiredPositiveLong("interval")
                if (deviceAuthId == null || userCode == null || interval == null) {
                    CodexDeviceAuthorizationResult.Failure(CodexDeviceAuthFailure.PROTOCOL_FAILURE)
                } else {
                    CodexDeviceAuthorizationResult.Success(
                        CodexDeviceAuthorization(deviceAuthId, userCode, interval.toInt()),
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
                    .put("user_code", auth.userCode),
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
                val codeVerifier = json.requiredString("code_verifier")
                if (authorizationCode == null || codeVerifier == null) {
                    CodexDevicePollResult.Failure(CodexDeviceAuthFailure.PROTOCOL_FAILURE)
                } else {
                    CodexDevicePollResult.Authorized(
                        CodexAuthorizationCode(authorizationCode, codeVerifier),
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
                JSONObject()
                    .put("grant_type", "authorization_code")
                    .put("client_id", CodexDeviceAuthDefaults.CLIENT_ID)
                    .put("code", code.value)
                    .put("code_verifier", code.codeVerifier),
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
                val expiresInSeconds = json.requiredPositiveLong("expires_in")
                if (accessToken == null || refreshToken == null || idToken == null || expiresInSeconds == null) {
                    CodexTokenResult.Failure(CodexDeviceAuthFailure.PROTOCOL_FAILURE)
                } else {
                    CodexTokenResult.Success(
                        CodexTokenSet(
                            accessToken = accessToken,
                            refreshToken = refreshToken,
                            idToken = idToken,
                            expiresInSeconds = expiresInSeconds,
                            accountId = json.optionalString("account_id"),
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
        body: JSONObject,
        handleResponse: (Response) -> T,
    ): T = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val call = httpClient.newCall(request)
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

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

private fun JSONObject.requiredString(name: String): String? =
    if (!has(name)) null else optString(name).takeIf(String::isNotBlank)

private fun JSONObject.optionalString(name: String): String? =
    requiredString(name)

private fun JSONObject.requiredPositiveLong(name: String): Long? =
    if (!has(name)) null else optLong(name).takeIf { it > 0L }
