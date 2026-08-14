package fuck.andes.data.auth

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

internal const val CODEX_LOGIN_TIMEOUT_MILLIS = 15 * 60 * 1_000L

internal sealed interface CodexLoginState {
    data object Idle : CodexLoginState

    data class AwaitingUser(
        val userCode: String,
        val verificationUrl: String,
    ) : CodexLoginState {
        override fun toString(): String =
            "AwaitingUser(userCode=<redacted>, verificationUrl=$verificationUrl)"
    }

    data class Authorized(val accountLabel: String?) : CodexLoginState {
        override fun toString(): String = "Authorized(accountLabel=<redacted>)"
    }

    data class Failed(val reason: CodexAuthFailure) : CodexLoginState
}

internal enum class CodexAuthFailure {
    NOT_AUTHENTICATED,
    TIMEOUT,
    CANCELLED,
    UNSUPPORTED,
    EXPIRED,
    NETWORK_FAILURE,
    PROTOCOL_FAILURE,
    HTTP_FAILURE,
    TRANSIENT_FAILURE,
    REAUTHENTICATION_REQUIRED,
    CREDENTIAL_STORE_FAILURE,
}

internal class CodexAuthException(
    val failure: CodexAuthFailure,
) : IllegalStateException("Codex OAuth authentication failed: ${failure.name.lowercase()}")

internal interface CodexCredentialProvider {
    fun requireValidCredential(providerId: String): CodexOAuthCredential

    fun refreshAfterUnauthorized(
        providerId: String,
        rejectedAccessToken: String,
    ): CodexOAuthCredential

    fun invalidateAfterUnauthorized(
        providerId: String,
        rejectedAccessToken: String,
    ): Boolean
}

internal enum class CodexTokenRefreshFailure {
    UNAUTHORIZED,
    INVALID_GRANT,
    REVOKED,
    NETWORK_FAILURE,
    SERVER_FAILURE,
    PROTOCOL_FAILURE,
    HTTP_FAILURE,
    ;

    val isPermanent: Boolean
        get() = this == UNAUTHORIZED || this == INVALID_GRANT || this == REVOKED
}

internal sealed interface CodexTokenRefreshResult {
    data class Success(
        val accessToken: String? = null,
        val refreshToken: String? = null,
        val idToken: String? = null,
    ) : CodexTokenRefreshResult {
        override fun toString(): String =
            "Success(accessToken=<redacted>, refreshToken=<redacted>, idToken=<redacted>)"
    }

    data class Failure(val reason: CodexTokenRefreshFailure) : CodexTokenRefreshResult
}

internal fun interface CodexTokenRefreshProtocol {
    suspend fun refresh(refreshToken: String): CodexTokenRefreshResult
}

/** Refresh wire contract pinned to the official Codex CLI 0.147.0 implementation. */
internal class CodexTokenRefreshClient(
    httpClient: OkHttpClient = OkHttpClient(),
    private val endpointBaseUrl: HttpUrl = CodexDeviceAuthDefaults.endpointBaseUrl,
    private val callFactory: Call.Factory = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) : CodexTokenRefreshProtocol {
    override suspend fun refresh(refreshToken: String): CodexTokenRefreshResult =
        try {
            val requestBody = JSONObject()
                .put("client_id", CodexDeviceAuthDefaults.CLIENT_ID)
                .put("grant_type", "refresh_token")
                .put("refresh_token", refreshToken)
                .toString()
                .toByteArray(StandardCharsets.UTF_8)
                .toRequestBody(JSON_MEDIA_TYPE)
            requestOnce(
                Request.Builder()
                    .url(endpointBaseUrl.newBuilder().addPathSegments("oauth/token").build())
                    .post(requestBody)
                    .build(),
            ) { response -> response.toRefreshResult() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            CodexTokenRefreshResult.Failure(CodexTokenRefreshFailure.NETWORK_FAILURE)
        } catch (_: Throwable) {
            CodexTokenRefreshResult.Failure(CodexTokenRefreshFailure.PROTOCOL_FAILURE)
        }

    private suspend fun <T> requestOnce(
        request: Request,
        handleResponse: (Response) -> T,
    ): T = suspendCancellableCoroutine { continuation ->
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

    private fun Response.toRefreshResult(): CodexTokenRefreshResult {
        if (code == 401) {
            return CodexTokenRefreshResult.Failure(CodexTokenRefreshFailure.UNAUTHORIZED)
        }
        if (code >= 500) {
            return CodexTokenRefreshResult.Failure(CodexTokenRefreshFailure.SERVER_FAILURE)
        }
        if (!isSuccessful) {
            val error = runCatching { JSONObject(body.string()).optString("error") }
                .getOrNull()
                ?.trim()
                ?.lowercase()
            val failure = when (error) {
                "invalid_grant" -> CodexTokenRefreshFailure.INVALID_GRANT
                "invalid_token", "token_revoked", "revoked_token" -> CodexTokenRefreshFailure.REVOKED
                else -> CodexTokenRefreshFailure.HTTP_FAILURE
            }
            return CodexTokenRefreshResult.Failure(failure)
        }

        val json = runCatching { JSONObject(body.string()) }.getOrNull()
            ?: return CodexTokenRefreshResult.Failure(CodexTokenRefreshFailure.PROTOCOL_FAILURE)
        val accessToken = json.optionalToken("access_token")
            ?: return CodexTokenRefreshResult.Failure(CodexTokenRefreshFailure.PROTOCOL_FAILURE)
        val refreshToken = json.optionalToken("refresh_token")
            ?: return CodexTokenRefreshResult.Failure(CodexTokenRefreshFailure.PROTOCOL_FAILURE)
        val idToken = json.optionalToken("id_token")
            ?: return CodexTokenRefreshResult.Failure(CodexTokenRefreshFailure.PROTOCOL_FAILURE)
        return CodexTokenRefreshResult.Success(
            accessToken = accessToken.value,
            refreshToken = refreshToken.value,
            idToken = idToken.value,
        )
    }

    private fun JSONObject.optionalToken(name: String): OptionalToken? {
        if (!has(name) || isNull(name)) return OptionalToken(null)
        return (get(name) as? String)
            ?.takeIf(String::isNotBlank)
            ?.let(::OptionalToken)
    }

    private data class OptionalToken(val value: String?)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

internal class CodexOAuthManager(
    private val deviceAuthProtocol: CodexDeviceAuthProtocol,
    private val credentialStore: CodexCredentialStore,
    private val refreshProtocol: CodexTokenRefreshProtocol,
    private val scope: CoroutineScope,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val loginTimeoutMillis: Long = CODEX_LOGIN_TIMEOUT_MILLIS,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutableLoginState = MutableStateFlow<CodexLoginState>(CodexLoginState.Idle)
    val loginState: StateFlow<CodexLoginState> = mutableLoginState.asStateFlow()

    private val loginLock = Any()
    private val loginGeneration = AtomicLong()
    private var loginJob: Job? = null
    private var loginProviderId: String? = null
    private val refreshLocks = ConcurrentHashMap<String, Mutex>()
    private val providerMutationLocks = ConcurrentHashMap<String, Any>()
    private val providerEpochs = ConcurrentHashMap<String, AtomicLong>()
    private val recentRefreshes = ConcurrentHashMap<String, RefreshStamp>()

    val credentialProvider: CodexCredentialProvider = object : CodexCredentialProvider {
        override fun requireValidCredential(providerId: String): CodexOAuthCredential =
            runBlocking { this@CodexOAuthManager.requireValidCredential(providerId) }

        override fun refreshAfterUnauthorized(
            providerId: String,
            rejectedAccessToken: String,
        ): CodexOAuthCredential = runBlocking {
            this@CodexOAuthManager.refreshAfterUnauthorized(providerId, rejectedAccessToken)
        }

        override fun invalidateAfterUnauthorized(
            providerId: String,
            rejectedAccessToken: String,
        ): Boolean = runBlocking {
            this@CodexOAuthManager.invalidateAfterUnauthorized(providerId, rejectedAccessToken)
        }
    }

    fun beginDeviceLogin(providerId: String) {
        require(providerId.isNotBlank()) { "Codex OAuth provider id is required" }
        synchronized(loginLock) {
            loginJob?.cancel()
            val generation = loginGeneration.incrementAndGet()
            loginProviderId = providerId
            mutableLoginState.value = CodexLoginState.Idle
            loginJob = scope.launch(start = CoroutineStart.LAZY) {
                runLogin(providerId, generation)
            }.also(Job::start)
        }
    }

    fun cancelLogin() {
        val job = synchronized(loginLock) {
            loginGeneration.incrementAndGet()
            loginProviderId = null
            mutableLoginState.value = CodexLoginState.Idle
            loginJob.also { loginJob = null }
        }
        job?.cancel()
    }

    fun cancelLogin(providerId: String): Boolean {
        validateProviderId(providerId)
        val job = synchronized(loginLock) {
            if (loginProviderId != providerId) return false
            loginGeneration.incrementAndGet()
            loginProviderId = null
            mutableLoginState.value = CodexLoginState.Idle
            loginJob.also { loginJob = null }
        }
        job?.cancel()
        return true
    }

    fun loginStateFor(providerId: String): CodexLoginState {
        validateProviderId(providerId)
        synchronized(loginLock) {
            if (loginProviderId == providerId) return mutableLoginState.value
        }
        return try {
            if (credentialStore.load(providerId) == null) {
                CodexLoginState.Idle
            } else {
                CodexLoginState.Authorized(accountLabel = null)
            }
        } catch (_: Throwable) {
            CodexLoginState.Failed(CodexAuthFailure.CREDENTIAL_STORE_FAILURE)
        }
    }

    suspend fun requireValidCredential(providerId: String): CodexOAuthCredential {
        validateProviderId(providerId)
        val observed = loadCredential(providerId)
        val observedAt = nowEpochMillis()
        if (!observed.expiresWithin(REFRESH_WINDOW_MILLIS, observedAt) ||
            wasRecentlyRefreshed(providerId, observed, observedAt)
        ) {
            return observed
        }
        return refreshLock(providerId).withLock {
            val snapshot = loadCredentialSnapshot(providerId)
            val current = snapshot.credential
            val currentTime = nowEpochMillis()
            if (current.tokenSetDiffersFrom(observed) ||
                !current.expiresWithin(REFRESH_WINDOW_MILLIS, currentTime) ||
                wasRecentlyRefreshed(providerId, current, currentTime)
            ) {
                current
            } else {
                refreshLocked(providerId, snapshot)
            }
        }
    }

    suspend fun refreshAfterUnauthorized(
        providerId: String,
        rejectedAccessToken: String,
    ): CodexOAuthCredential {
        validateProviderId(providerId)
        if (rejectedAccessToken.isBlank()) {
            throw CodexAuthException(CodexAuthFailure.PROTOCOL_FAILURE)
        }
        return refreshLock(providerId).withLock {
            val snapshot = loadCredentialSnapshot(providerId)
            val credential = snapshot.credential
            if (credential.accessToken != rejectedAccessToken) {
                credential
            } else {
                refreshLocked(providerId, snapshot)
            }
        }
    }

    suspend fun invalidateAfterUnauthorized(
        providerId: String,
        rejectedAccessToken: String,
    ): Boolean {
        validateProviderId(providerId)
        if (rejectedAccessToken.isBlank()) {
            throw CodexAuthException(CodexAuthFailure.PROTOCOL_FAILURE)
        }
        return refreshLock(providerId).withLock {
            synchronized(providerMutationLock(providerId)) {
                val current = try {
                    credentialStore.load(providerId)
                } catch (_: Throwable) {
                    throw CodexAuthException(CodexAuthFailure.CREDENTIAL_STORE_FAILURE)
                }
                if (current == null || current.accessToken != rejectedAccessToken) {
                    false
                } else {
                    try {
                        credentialStore.clear(providerId)
                    } catch (_: Throwable) {
                        throw CodexAuthException(CodexAuthFailure.CREDENTIAL_STORE_FAILURE)
                    }
                    providerEpoch(providerId).incrementAndGet()
                    recentRefreshes.remove(providerId)
                    publishCredentialFailure(CodexAuthFailure.REAUTHENTICATION_REQUIRED)
                    true
                }
            }
        }
    }

    fun logout(providerId: String) {
        validateProviderId(providerId)
        val cancelledLogin = cancelLogin(providerId)
        synchronized(providerMutationLock(providerId)) {
            try {
                credentialStore.clear(providerId)
            } catch (_: Throwable) {
                throw CodexAuthException(CodexAuthFailure.CREDENTIAL_STORE_FAILURE)
            }
            providerEpoch(providerId).incrementAndGet()
            recentRefreshes.remove(providerId)
        }
        if (!cancelledLogin) {
            synchronized(loginLock) {
                if (loginProviderId == null) mutableLoginState.value = CodexLoginState.Idle
            }
        }
    }

    private suspend fun runLogin(providerId: String, generation: Long) {
        try {
            withTimeout(loginTimeoutMillis) {
                when (val authorization = deviceAuthProtocol.requestAuthorization()) {
                    is CodexDeviceAuthorizationResult.Failure -> {
                        publish(generation, CodexLoginState.Failed(authorization.reason.toManagerFailure()))
                    }

                    is CodexDeviceAuthorizationResult.Success -> {
                        publish(
                            generation,
                            CodexLoginState.AwaitingUser(
                                userCode = authorization.authorization.userCode,
                                verificationUrl = VERIFICATION_URL,
                            ),
                        )
                        pollUntilComplete(providerId, generation, authorization.authorization)
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            publish(generation, CodexLoginState.Failed(CodexAuthFailure.TIMEOUT))
        } catch (_: CancellationException) {
            publish(generation, CodexLoginState.Idle)
        } catch (_: Throwable) {
            publish(generation, CodexLoginState.Failed(CodexAuthFailure.CREDENTIAL_STORE_FAILURE))
        } finally {
            synchronized(loginLock) {
                if (loginGeneration.get() == generation) {
                    loginJob = null
                    loginProviderId = null
                }
            }
        }
    }

    private suspend fun pollUntilComplete(
        providerId: String,
        generation: Long,
        authorization: CodexDeviceAuthorization,
    ) {
        while (true) {
            delayMillis(authorization.intervalSeconds.toLong() * MILLIS_PER_SECOND)
            when (val poll = deviceAuthProtocol.pollOnce(authorization)) {
                CodexDevicePollResult.Pending -> Unit
                is CodexDevicePollResult.Failure -> {
                    publish(generation, CodexLoginState.Failed(poll.reason.toManagerFailure()))
                    return
                }

                is CodexDevicePollResult.Authorized -> {
                    exchangeAndSave(providerId, generation, poll.code)
                    return
                }
            }
        }
    }

    private suspend fun exchangeAndSave(
        providerId: String,
        generation: Long,
        code: CodexAuthorizationCode,
    ) {
        when (val result = deviceAuthProtocol.exchangeToken(code)) {
            is CodexTokenResult.Failure -> {
                publish(generation, CodexLoginState.Failed(result.reason.toManagerFailure()))
            }

            is CodexTokenResult.Success -> {
                val tokens = result.tokens
                synchronized(providerMutationLock(providerId)) {
                    synchronized(loginLock) {
                        if (loginGeneration.get() != generation ||
                            loginProviderId != providerId
                        ) {
                            return
                        }
                        credentialStore.save(
                            providerId,
                            CodexOAuthCredential(
                                accessToken = tokens.accessToken,
                                refreshToken = tokens.refreshToken,
                                idToken = tokens.idToken,
                                accountId = tokens.accountId,
                                expiresAtEpochMillis = tokens.expiresAtEpochMillis,
                            ),
                        )
                        providerEpoch(providerId).incrementAndGet()
                        recentRefreshes.remove(providerId)
                        mutableLoginState.value = CodexLoginState.Authorized(accountLabel = null)
                    }
                }
            }
        }
    }

    private suspend fun refreshLocked(
        providerId: String,
        snapshot: CredentialSnapshot,
    ): CodexOAuthCredential = when (
        val result = refreshProtocol.refresh(snapshot.credential.refreshToken)
    ) {
        is CodexTokenRefreshResult.Success -> {
            val current = snapshot.credential
            val claims = result.idToken?.parseRefreshClaimsOrNull()
            if (result.idToken != null && claims == null) {
                throw CodexAuthException(CodexAuthFailure.PROTOCOL_FAILURE)
            }
            val updated = current.copy(
                accessToken = result.accessToken ?: current.accessToken,
                refreshToken = result.refreshToken ?: current.refreshToken,
                idToken = result.idToken ?: current.idToken,
                accountId = claims?.accountId ?: current.accountId,
                expiresAtEpochMillis = claims?.expiresAtEpochMillis ?: current.expiresAtEpochMillis,
            )
            synchronized(providerMutationLock(providerId)) {
                if (providerEpoch(providerId).get() != snapshot.providerEpoch) {
                    throw CodexAuthException(CodexAuthFailure.NOT_AUTHENTICATED)
                }
                try {
                    credentialStore.save(providerId, updated)
                } catch (_: Throwable) {
                    throw CodexAuthException(CodexAuthFailure.CREDENTIAL_STORE_FAILURE)
                }
                recentRefreshes[providerId] = RefreshStamp(
                    accessToken = updated.accessToken,
                    refreshedAtEpochMillis = nowEpochMillis(),
                )
            }
            updated
        }

        is CodexTokenRefreshResult.Failure -> {
            if (result.reason.isPermanent) {
                synchronized(providerMutationLock(providerId)) {
                    if (providerEpoch(providerId).get() != snapshot.providerEpoch) {
                        throw CodexAuthException(CodexAuthFailure.NOT_AUTHENTICATED)
                    }
                    try {
                        credentialStore.clear(providerId)
                    } catch (_: Throwable) {
                        throw CodexAuthException(CodexAuthFailure.CREDENTIAL_STORE_FAILURE)
                    }
                    providerEpoch(providerId).incrementAndGet()
                    recentRefreshes.remove(providerId)
                }
                publishCredentialFailure(CodexAuthFailure.REAUTHENTICATION_REQUIRED)
                throw CodexAuthException(CodexAuthFailure.REAUTHENTICATION_REQUIRED)
            }
            val failure = when (result.reason) {
                CodexTokenRefreshFailure.PROTOCOL_FAILURE -> CodexAuthFailure.PROTOCOL_FAILURE
                else -> CodexAuthFailure.TRANSIENT_FAILURE
            }
            throw CodexAuthException(failure)
        }
    }

    private fun loadCredential(providerId: String): CodexOAuthCredential =
        try {
            credentialStore.load(providerId)
                ?: throw CodexAuthException(CodexAuthFailure.NOT_AUTHENTICATED)
        } catch (exception: CodexAuthException) {
            throw exception
        } catch (_: Throwable) {
            throw CodexAuthException(CodexAuthFailure.CREDENTIAL_STORE_FAILURE)
        }

    private fun loadCredentialSnapshot(providerId: String): CredentialSnapshot =
        synchronized(providerMutationLock(providerId)) {
            CredentialSnapshot(
                credential = loadCredential(providerId),
                providerEpoch = providerEpoch(providerId).get(),
            )
        }

    private fun refreshLock(providerId: String): Mutex =
        refreshLocks.computeIfAbsent(providerId) { Mutex() }

    private fun providerMutationLock(providerId: String): Any =
        providerMutationLocks.computeIfAbsent(providerId) { Any() }

    private fun providerEpoch(providerId: String): AtomicLong =
        providerEpochs.computeIfAbsent(providerId) { AtomicLong() }

    private fun publish(generation: Long, state: CodexLoginState) {
        synchronized(loginLock) {
            if (loginGeneration.get() == generation) mutableLoginState.value = state
        }
    }

    private fun publishCredentialFailure(failure: CodexAuthFailure) {
        synchronized(loginLock) {
            if (loginProviderId == null) mutableLoginState.value = CodexLoginState.Failed(failure)
        }
    }

    private fun validateProviderId(providerId: String) {
        if (providerId.isBlank()) throw CodexAuthException(CodexAuthFailure.PROTOCOL_FAILURE)
    }

    private fun CodexOAuthCredential.expiresWithin(windowMillis: Long, nowMillis: Long): Boolean =
        expiresAtEpochMillis <= nowMillis.saturatingAdd(windowMillis)

    private fun CodexOAuthCredential.tokenSetDiffersFrom(other: CodexOAuthCredential): Boolean =
        accessToken != other.accessToken ||
            refreshToken != other.refreshToken ||
            idToken != other.idToken

    private fun wasRecentlyRefreshed(
        providerId: String,
        credential: CodexOAuthCredential,
        nowMillis: Long,
    ): Boolean {
        val stamp = recentRefreshes[providerId] ?: return false
        val elapsed = nowMillis - stamp.refreshedAtEpochMillis
        return stamp.accessToken == credential.accessToken &&
            elapsed >= 0L &&
            elapsed < REFRESH_RETRY_SUPPRESSION_MILLIS
    }

    private fun Long.saturatingAdd(other: Long): Long =
        if (this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

    private companion object {
        const val VERIFICATION_URL = "https://auth.openai.com/codex/device"
        const val REFRESH_WINDOW_MILLIS = 60_000L
        const val REFRESH_RETRY_SUPPRESSION_MILLIS = 60_000L
        const val MILLIS_PER_SECOND = 1_000L
    }

    private data class CredentialSnapshot(
        val credential: CodexOAuthCredential,
        val providerEpoch: Long,
    )

    private data class RefreshStamp(
        val accessToken: String,
        val refreshedAtEpochMillis: Long,
    ) {
        override fun toString(): String =
            "RefreshStamp(accessToken=<redacted>, refreshedAtEpochMillis=$refreshedAtEpochMillis)"
    }
}

private fun CodexDeviceAuthFailure.toManagerFailure(): CodexAuthFailure = when (this) {
    CodexDeviceAuthFailure.UNSUPPORTED -> CodexAuthFailure.UNSUPPORTED
    CodexDeviceAuthFailure.EXPIRED -> CodexAuthFailure.EXPIRED
    CodexDeviceAuthFailure.CANCELLED -> CodexAuthFailure.CANCELLED
    CodexDeviceAuthFailure.NETWORK_FAILURE -> CodexAuthFailure.NETWORK_FAILURE
    CodexDeviceAuthFailure.PROTOCOL_FAILURE -> CodexAuthFailure.PROTOCOL_FAILURE
    CodexDeviceAuthFailure.HTTP_FAILURE -> CodexAuthFailure.HTTP_FAILURE
}

private data class RefreshIdTokenClaims(
    val accountId: String?,
    val expiresAtEpochMillis: Long,
)

private fun String.parseRefreshClaimsOrNull(): RefreshIdTokenClaims? = runCatching {
    val parts = split('.')
    require(parts.size == 3)
    val payload = String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
    val json = JSONObject(payload)
    val expiration = when (val value = json.opt("exp")) {
        is Int -> value.toLong().takeIf { it > 0L }
        is Long -> value.takeIf { it > 0L }
        null -> null
        else -> null
    } ?: return null
    val accountId = when {
        !json.has("chatgpt_account_id") -> null
        else -> (json.opt("chatgpt_account_id") as? String)?.takeIf(String::isNotBlank)
            ?: return null
    }
    RefreshIdTokenClaims(
        accountId = accountId,
        expiresAtEpochMillis = Math.multiplyExact(expiration, 1_000L),
    )
}.getOrNull()
