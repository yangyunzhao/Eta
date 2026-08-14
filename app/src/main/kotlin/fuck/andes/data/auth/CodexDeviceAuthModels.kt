package fuck.andes.data.auth

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

internal object CodexDeviceAuthDefaults {
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val REDIRECT_URI = "https://auth.openai.com/deviceauth/callback"

    val endpointBaseUrl: HttpUrl = "https://auth.openai.com/".toHttpUrl()
}

internal data class CodexDeviceAuthorization(
    val deviceAuthId: String,
    val userCode: String,
    val intervalSeconds: Int,
) {
    override fun toString(): String = "CodexDeviceAuthorization(intervalSeconds=$intervalSeconds)"
}

internal data class CodexAuthorizationCode(
    val value: String,
    val codeChallenge: String,
    val codeVerifier: String,
) {
    override fun toString(): String = "CodexAuthorizationCode()"
}

internal data class CodexTokenSet(
    val accessToken: String,
    val refreshToken: String,
    val idToken: String,
    val expiresAtEpochMillis: Long,
    val accountId: String?,
) {
    override fun toString(): String = "CodexTokenSet(expiresAtEpochMillis=$expiresAtEpochMillis)"
}

internal enum class CodexDeviceAuthFailure {
    UNSUPPORTED,
    EXPIRED,
    CANCELLED,
    NETWORK_FAILURE,
    PROTOCOL_FAILURE,
    HTTP_FAILURE,
}

internal sealed interface CodexDeviceAuthorizationResult {
    data class Success(val authorization: CodexDeviceAuthorization) : CodexDeviceAuthorizationResult

    data class Failure(val reason: CodexDeviceAuthFailure) : CodexDeviceAuthorizationResult
}

internal sealed interface CodexDevicePollResult {
    data object Pending : CodexDevicePollResult

    data class Authorized(val code: CodexAuthorizationCode) : CodexDevicePollResult

    data class Failure(val reason: CodexDeviceAuthFailure) : CodexDevicePollResult
}

internal sealed interface CodexTokenResult {
    data class Success(val tokens: CodexTokenSet) : CodexTokenResult

    data class Failure(val reason: CodexDeviceAuthFailure) : CodexTokenResult
}

internal interface CodexDeviceAuthProtocol {
    suspend fun requestAuthorization(): CodexDeviceAuthorizationResult

    suspend fun pollOnce(auth: CodexDeviceAuthorization): CodexDevicePollResult

    suspend fun exchangeToken(code: CodexAuthorizationCode): CodexTokenResult
}
