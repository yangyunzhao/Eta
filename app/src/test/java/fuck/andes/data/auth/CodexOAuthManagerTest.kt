package fuck.andes.data.auth

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.resume

class CodexOAuthManagerTest {
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `device login publishes awaiting state then saves before authorized`() {
        val delay = ControlledDelay()
        val store = InMemoryCredentialStore()
        val protocol = FakeDeviceProtocol(
            pollResults = ArrayDeque(
                listOf(
                    CodexDevicePollResult.Pending,
                    CodexDevicePollResult.Authorized(AUTHORIZATION_CODE),
                ),
            ),
            tokenResult = CodexTokenResult.Success(TOKEN_SET),
        )
        val manager = manager(protocol, store, delayMillis = delay::delay)

        manager.beginDeviceLogin(PROVIDER_ID)

        assertEquals(
            CodexLoginState.AwaitingUser(USER_CODE, "https://auth.openai.com/codex/device"),
            awaitState(manager) { it is CodexLoginState.AwaitingUser },
        )
        assertNull(store.load(PROVIDER_ID))
        delay.resumeNext()
        delay.resumeNext()

        assertEquals(CodexLoginState.Authorized(null), awaitState(manager) { it is CodexLoginState.Authorized })
        assertEquals(CREDENTIAL, store.load(PROVIDER_ID))
        assertEquals(2, protocol.pollCount.get())
    }

    @Test
    fun `login times out monotonically and preserves existing credential`() {
        val store = InMemoryCredentialStore().apply { save(PROVIDER_ID, OLD_CREDENTIAL) }
        val manager = manager(
            protocol = FakeDeviceProtocol(pollResults = ArrayDeque(listOf(CodexDevicePollResult.Pending))),
            store = store,
            loginTimeoutMillis = 50L,
        )

        manager.beginDeviceLogin(PROVIDER_ID)

        assertTrue(awaitState(manager) { it == CodexLoginState.Failed(CodexAuthFailure.TIMEOUT) } is CodexLoginState.Failed)
        assertEquals(OLD_CREDENTIAL, store.load(PROVIDER_ID))
    }

    @Test
    fun `production login timeout is fifteen minutes`() {
        assertEquals(15 * 60 * 1_000L, CODEX_LOGIN_TIMEOUT_MILLIS)
    }

    @Test
    fun `cancel login returns to idle and preserves existing credential`() {
        val store = InMemoryCredentialStore().apply { save(PROVIDER_ID, OLD_CREDENTIAL) }
        val manager = manager(
            protocol = FakeDeviceProtocol(pollResults = ArrayDeque(listOf(CodexDevicePollResult.Pending))),
            store = store,
        )
        manager.beginDeviceLogin(PROVIDER_ID)
        awaitState(manager) { it is CodexLoginState.AwaitingUser }

        manager.cancelLogin()

        assertEquals(CodexLoginState.Idle, awaitState(manager) { it == CodexLoginState.Idle })
        assertEquals(OLD_CREDENTIAL, store.load(PROVIDER_ID))
    }

    @Test
    fun `provider scoped state recovers persisted login without exposing credential`() {
        val store = InMemoryCredentialStore().apply { save(PROVIDER_ID, OLD_CREDENTIAL) }
        val manager = manager(store = store)

        assertEquals(CodexLoginState.Idle, manager.loginState.value)
        assertEquals(CodexLoginState.Authorized(null), manager.loginStateFor(PROVIDER_ID))
        assertEquals(CodexLoginState.Idle, manager.loginStateFor(OTHER_PROVIDER_ID))
        SECRET_VALUES.forEach { secret ->
            assertFalse(manager.loginStateFor(PROVIDER_ID).toString().contains(secret))
        }
    }

    @Test
    fun `provider scoped state flow emits idle after recovered credential is logged out`() {
        val store = InMemoryCredentialStore().apply { save(PROVIDER_ID, OLD_CREDENTIAL) }
        val manager = manager(store = store)
        val state = manager.loginStateFlowFor(PROVIDER_ID)

        assertEquals(CodexLoginState.Authorized(null), state.value)

        manager.logout(PROVIDER_ID)

        repeat(1_000) {
            if (state.value == CodexLoginState.Idle) return
            Thread.sleep(5)
        }
        error("Timed out waiting for provider scoped logout state; current=${state.value}")
    }

    @Test
    fun `provider scoped cancel cannot cancel another provider login`() {
        val manager = manager(
            protocol = FakeDeviceProtocol(pollResults = ArrayDeque(listOf(CodexDevicePollResult.Pending))),
        )
        manager.beginDeviceLogin(PROVIDER_ID)
        awaitState(manager) { it is CodexLoginState.AwaitingUser }

        assertFalse(manager.cancelLogin(OTHER_PROVIDER_ID))
        assertTrue(manager.loginState.value is CodexLoginState.AwaitingUser)
        assertTrue(manager.cancelLogin(PROVIDER_ID))
        assertEquals(CodexLoginState.Idle, awaitState(manager) { it == CodexLoginState.Idle })
    }

    @Test
    fun `credential changes for another provider preserve active login state`() = runBlocking {
        val store = InMemoryCredentialStore().apply { save(OTHER_PROVIDER_ID, OLD_CREDENTIAL) }
        val manager = manager(
            protocol = FakeDeviceProtocol(pollResults = ArrayDeque(listOf(CodexDevicePollResult.Pending))),
            store = store,
            refreshProtocol = FakeRefreshProtocol(
                CodexTokenRefreshResult.Failure(CodexTokenRefreshFailure.INVALID_GRANT),
            ),
        )
        manager.beginDeviceLogin(PROVIDER_ID)
        awaitState(manager) { it is CodexLoginState.AwaitingUser }

        manager.logout(OTHER_PROVIDER_ID)
        assertTrue(manager.loginState.value is CodexLoginState.AwaitingUser)

        store.save(OTHER_PROVIDER_ID, OLD_CREDENTIAL)
        manager.credentialProvider.invalidateAfterUnauthorized(
            OTHER_PROVIDER_ID,
            OLD_CREDENTIAL.accessToken,
        )
        assertTrue(manager.loginState.value is CodexLoginState.AwaitingUser)

        store.save(OTHER_PROVIDER_ID, OLD_CREDENTIAL)
        runCatching {
            manager.refreshAfterUnauthorized(OTHER_PROVIDER_ID, OLD_CREDENTIAL.accessToken)
        }
        assertTrue(manager.loginState.value is CodexLoginState.AwaitingUser)
    }

    @Test
    fun `failed token exchange never overwrites old credential`() {
        val store = InMemoryCredentialStore().apply { save(PROVIDER_ID, OLD_CREDENTIAL) }
        val manager = manager(
            protocol = FakeDeviceProtocol(
                pollResults = ArrayDeque(listOf(CodexDevicePollResult.Authorized(AUTHORIZATION_CODE))),
                tokenResult = CodexTokenResult.Failure(CodexDeviceAuthFailure.PROTOCOL_FAILURE),
            ),
            store = store,
            delayMillis = {},
        )

        manager.beginDeviceLogin(PROVIDER_ID)

        assertEquals(
            CodexLoginState.Failed(CodexAuthFailure.PROTOCOL_FAILURE),
            awaitState(manager) { it is CodexLoginState.Failed },
        )
        assertEquals(OLD_CREDENTIAL, store.load(PROVIDER_ID))
    }

    @Test
    fun `protocol cancellation is classified without overwriting old credential`() {
        val store = InMemoryCredentialStore().apply { save(PROVIDER_ID, OLD_CREDENTIAL) }
        val manager = manager(
            protocol = FakeDeviceProtocol(
                authorizationResult = CodexDeviceAuthorizationResult.Failure(
                    CodexDeviceAuthFailure.CANCELLED,
                ),
            ),
            store = store,
        )

        manager.beginDeviceLogin(PROVIDER_ID)

        assertEquals(
            CodexLoginState.Failed(CodexAuthFailure.CANCELLED),
            awaitState(manager) { it is CodexLoginState.Failed },
        )
        assertEquals(OLD_CREDENTIAL, store.load(PROVIDER_ID))
    }

    @Test
    fun `cancel while token exchange is in flight never saves stale login`() {
        val store = InMemoryCredentialStore().apply { save(PROVIDER_ID, OLD_CREDENTIAL) }
        val protocol = FakeDeviceProtocol(
            pollResults = ArrayDeque(listOf(CodexDevicePollResult.Authorized(AUTHORIZATION_CODE))),
            tokenResult = CodexTokenResult.Success(TOKEN_SET),
            blockExchange = true,
        )
        val manager = manager(protocol = protocol, store = store, delayMillis = {})
        manager.beginDeviceLogin(PROVIDER_ID)
        assertTrue(protocol.exchangeStarted.await(5, TimeUnit.SECONDS))

        assertTrue(manager.cancelLogin(PROVIDER_ID))
        protocol.releaseExchange.countDown()

        assertTrue(protocol.exchangeFinished.await(5, TimeUnit.SECONDS))
        assertEquals(CodexLoginState.Idle, awaitState(manager) { it == CodexLoginState.Idle })
        assertEquals(OLD_CREDENTIAL, store.load(PROVIDER_ID))
    }

    @Test
    fun `cancel waits for an already committing login before returning idle`() {
        val store = BlockingSaveCredentialStore()
        val manager = manager(
            protocol = FakeDeviceProtocol(
                pollResults = ArrayDeque(listOf(CodexDevicePollResult.Authorized(AUTHORIZATION_CODE))),
            ),
            store = store,
            delayMillis = {},
        )
        store.blockNextSave()
        manager.beginDeviceLogin(PROVIDER_ID)
        assertTrue(store.saveStarted.await(5, TimeUnit.SECONDS))
        val executor = Executors.newSingleThreadExecutor()
        val cancelReturned = CountDownLatch(1)
        executor.submit {
            manager.cancelLogin(PROVIDER_ID)
            cancelReturned.countDown()
        }

        assertFalse(cancelReturned.await(100, TimeUnit.MILLISECONDS))
        store.releaseSave.countDown()
        assertTrue(cancelReturned.await(5, TimeUnit.SECONDS))
        val savesAtCancelReturn = store.saveCount.get()
        Thread.sleep(50)

        executor.shutdownNow()
        assertEquals(savesAtCancelReturn, store.saveCount.get())
        assertEquals(CodexLoginState.Idle, manager.loginState.value)
        assertNull(store.load(PROVIDER_ID))
    }

    @Test
    fun `cancel during credential commit restores previous credential`() {
        val store = BlockingSaveCredentialStore(initialCredential = OLD_CREDENTIAL)
        val manager = manager(
            protocol = FakeDeviceProtocol(
                pollResults = ArrayDeque(listOf(CodexDevicePollResult.Authorized(AUTHORIZATION_CODE))),
            ),
            store = store,
            delayMillis = {},
        )
        store.blockNextSave()
        manager.beginDeviceLogin(PROVIDER_ID)
        assertTrue(store.saveStarted.await(5, TimeUnit.SECONDS))
        val executor = Executors.newSingleThreadExecutor()
        val cancelReturned = CountDownLatch(1)
        executor.submit {
            manager.cancelLogin(PROVIDER_ID)
            cancelReturned.countDown()
        }

        assertFalse(cancelReturned.await(100, TimeUnit.MILLISECONDS))
        store.releaseSave.countDown()
        assertTrue(cancelReturned.await(5, TimeUnit.SECONDS))

        executor.shutdownNow()
        assertEquals(OLD_CREDENTIAL, store.load(PROVIDER_ID))
        assertEquals(CodexLoginState.Idle, manager.loginState.value)
    }

    @Test
    fun `new login waits for prior login commit check and write`() {
        val store = BlockingSaveCredentialStore()
        val manager = manager(
            protocol = FakeDeviceProtocol(
                pollResults = ArrayDeque(listOf(CodexDevicePollResult.Authorized(AUTHORIZATION_CODE))),
            ),
            store = store,
            delayMillis = {},
        )
        store.blockNextSave()
        manager.beginDeviceLogin(PROVIDER_ID)
        assertTrue(store.saveStarted.await(5, TimeUnit.SECONDS))
        val executor = Executors.newSingleThreadExecutor()
        val beginReturned = CountDownLatch(1)
        executor.submit {
            manager.beginDeviceLogin(OTHER_PROVIDER_ID)
            beginReturned.countDown()
        }

        assertFalse(beginReturned.await(100, TimeUnit.MILLISECONDS))
        store.releaseSave.countDown()
        assertTrue(beginReturned.await(5, TimeUnit.SECONDS))

        executor.shutdownNow()
    }

    @Test
    fun `cancelled commit rollback does not delete replacement provider login`() {
        val store = BlockingSaveCredentialStore(initialCredential = OLD_CREDENTIAL)
        val manager = manager(
            protocol = FakeDeviceProtocol(
                pollResults = ArrayDeque(listOf(CodexDevicePollResult.Authorized(AUTHORIZATION_CODE))),
            ),
            store = store,
            delayMillis = {},
        )
        store.blockNextSave()
        manager.beginDeviceLogin(PROVIDER_ID)
        assertTrue(store.saveStarted.await(5, TimeUnit.SECONDS))
        val executor = Executors.newSingleThreadExecutor()
        val replacementStarted = CountDownLatch(1)
        executor.submit {
            manager.beginDeviceLogin(OTHER_PROVIDER_ID)
            replacementStarted.countDown()
        }

        assertFalse(replacementStarted.await(100, TimeUnit.MILLISECONDS))
        store.releaseSave.countDown()
        assertTrue(replacementStarted.await(5, TimeUnit.SECONDS))
        assertEquals(
            CodexLoginState.Authorized(null),
            awaitState(manager) { it is CodexLoginState.Authorized },
        )

        executor.shutdownNow()
        assertEquals(OLD_CREDENTIAL, store.load(PROVIDER_ID))
        assertEquals(CREDENTIAL, store.load(OTHER_PROVIDER_ID))
    }

    @Test
    fun `credential expiring in 59 seconds refreshes while 61 seconds does not`() = runBlocking {
        val store = InMemoryCredentialStore()
        val refresh = FakeRefreshProtocol(
            result = CodexTokenRefreshResult.Success(accessToken = "rotated-access"),
        )
        val manager = manager(store = store, refreshProtocol = refresh, nowEpochMillis = { NOW })
        store.save(PROVIDER_ID, OLD_CREDENTIAL.copy(expiresAtEpochMillis = NOW + 59_000L))

        val refreshed = manager.requireValidCredential(PROVIDER_ID)

        assertEquals("rotated-access", refreshed.accessToken)
        assertEquals(1, refresh.callCount.get())

        store.save(PROVIDER_ID, OLD_CREDENTIAL.copy(expiresAtEpochMillis = NOW + 61_000L))
        val stillValid = manager.requireValidCredential(PROVIDER_ID)

        assertEquals(OLD_CREDENTIAL.accessToken, stillValid.accessToken)
        assertEquals(1, refresh.callCount.get())
    }

    @Test
    fun `access only refresh is not repeated immediately despite preserved expiry`() = runBlocking {
        val store = InMemoryCredentialStore().apply {
            save(PROVIDER_ID, OLD_CREDENTIAL.copy(expiresAtEpochMillis = NOW + 59_000L))
        }
        val refresh = FakeRefreshProtocol(
            result = CodexTokenRefreshResult.Success(accessToken = "rotated-access"),
        )
        val manager = manager(store = store, refreshProtocol = refresh, nowEpochMillis = { NOW })

        val first = manager.requireValidCredential(PROVIDER_ID)
        val second = manager.requireValidCredential(PROVIDER_ID)

        assertEquals("rotated-access", first.accessToken)
        assertEquals(first, second)
        assertEquals(1, refresh.callCount.get())
    }

    @Test
    fun `ten concurrent expiring credential requests refresh once`() {
        val store = InMemoryCredentialStore().apply {
            save(PROVIDER_ID, OLD_CREDENTIAL.copy(expiresAtEpochMillis = NOW + 59_000L))
        }
        val refresh = FakeRefreshProtocol(
            result = CodexTokenRefreshResult.Success(accessToken = "rotated-access"),
            blockFirstCall = true,
        )
        val provider = manager(store = store, refreshProtocol = refresh, nowEpochMillis = { NOW })
            .credentialProvider
        val executor = Executors.newFixedThreadPool(10)

        val futures = (1..10).map {
            executor.submit<CodexOAuthCredential> { provider.requireValidCredential(PROVIDER_ID) }
        }
        assertTrue(refresh.firstCallStarted.await(5, TimeUnit.SECONDS))
        refresh.releaseFirstCall.countDown()
        val credentials = futures.map { it.get(5, TimeUnit.SECONDS) }
        executor.shutdownNow()

        assertEquals(1, refresh.callCount.get())
        assertTrue(credentials.all { it.accessToken == "rotated-access" })
    }

    @Test
    fun `concurrent unauthorized retries compare rejected token and refresh once`() {
        val store = InMemoryCredentialStore().apply { save(PROVIDER_ID, OLD_CREDENTIAL) }
        val refresh = FakeRefreshProtocol(
            result = CodexTokenRefreshResult.Success(accessToken = "rotated-access"),
            blockFirstCall = true,
        )
        val provider = manager(store = store, refreshProtocol = refresh).credentialProvider
        val executor = Executors.newFixedThreadPool(10)

        val futures = (1..10).map {
            executor.submit<CodexOAuthCredential> {
                provider.refreshAfterUnauthorized(PROVIDER_ID, OLD_CREDENTIAL.accessToken)
            }
        }
        assertTrue(refresh.firstCallStarted.await(5, TimeUnit.SECONDS))
        refresh.releaseFirstCall.countDown()
        val credentials = futures.map { it.get(5, TimeUnit.SECONDS) }
        executor.shutdownNow()

        assertEquals(1, refresh.callCount.get())
        assertTrue(credentials.all { it.accessToken == "rotated-access" })
    }

    @Test
    fun `second unauthorized compare and clear removes only still rejected credential`() {
        val rejectedStore = InMemoryCredentialStore().apply { save(PROVIDER_ID, OLD_CREDENTIAL) }
        val rejectedProvider = manager(store = rejectedStore).credentialProvider

        assertTrue(
            rejectedProvider.invalidateAfterUnauthorized(PROVIDER_ID, OLD_CREDENTIAL.accessToken),
        )
        assertNull(rejectedStore.load(PROVIDER_ID))

        val rotated = OLD_CREDENTIAL.copy(accessToken = "rotated-access")
        val rotatedStore = InMemoryCredentialStore().apply { save(PROVIDER_ID, rotated) }
        val rotatedProvider = manager(store = rotatedStore).credentialProvider

        assertFalse(
            rotatedProvider.invalidateAfterUnauthorized(PROVIDER_ID, OLD_CREDENTIAL.accessToken),
        )
        assertEquals(rotated, rotatedStore.load(PROVIDER_ID))
    }

    @Test
    fun `refresh rotates only returned fields and reparses changed id token claims`() = runBlocking {
        val store = InMemoryCredentialStore().apply { save(PROVIDER_ID, OLD_CREDENTIAL) }
        val rotatedIdToken = syntheticIdToken(exp = 2_000_000_000L, accountId = "new-account")
        val manager = manager(
            store = store,
            refreshProtocol = FakeRefreshProtocol(
                CodexTokenRefreshResult.Success(
                    refreshToken = "rotated-refresh",
                    idToken = rotatedIdToken,
                ),
            ),
        )

        val refreshed = manager.refreshAfterUnauthorized(PROVIDER_ID, OLD_CREDENTIAL.accessToken)

        assertEquals(OLD_CREDENTIAL.accessToken, refreshed.accessToken)
        assertEquals("rotated-refresh", refreshed.refreshToken)
        assertEquals(rotatedIdToken, refreshed.idToken)
        assertEquals("new-account", refreshed.accountId)
        assertEquals(2_000_000_000_000L, refreshed.expiresAtEpochMillis)
    }

    @Test
    fun `permanent refresh failure clears credential while transient failure preserves it`() = runBlocking {
        val permanentStore = InMemoryCredentialStore().apply { save(PROVIDER_ID, OLD_CREDENTIAL) }
        val permanentManager = manager(
            store = permanentStore,
            refreshProtocol = FakeRefreshProtocol(
                CodexTokenRefreshResult.Failure(CodexTokenRefreshFailure.INVALID_GRANT),
            ),
        )

        val permanentFailure = runCatching {
            permanentManager.refreshAfterUnauthorized(PROVIDER_ID, OLD_CREDENTIAL.accessToken)
        }.exceptionOrNull()

        assertTrue(permanentFailure is CodexAuthException)
        assertEquals(CodexAuthFailure.REAUTHENTICATION_REQUIRED, (permanentFailure as CodexAuthException).failure)
        assertNull(permanentStore.load(PROVIDER_ID))

        val transientStore = InMemoryCredentialStore().apply { save(PROVIDER_ID, OLD_CREDENTIAL) }
        val transientManager = manager(
            store = transientStore,
            refreshProtocol = FakeRefreshProtocol(
                CodexTokenRefreshResult.Failure(CodexTokenRefreshFailure.NETWORK_FAILURE),
            ),
        )

        val transientFailure = runCatching {
            transientManager.refreshAfterUnauthorized(PROVIDER_ID, OLD_CREDENTIAL.accessToken)
        }.exceptionOrNull()

        assertTrue(transientFailure is CodexAuthException)
        assertEquals(CodexAuthFailure.TRANSIENT_FAILURE, (transientFailure as CodexAuthException).failure)
        assertEquals(OLD_CREDENTIAL, transientStore.load(PROVIDER_ID))
    }

    @Test
    fun `logout during refresh prevents credential resurrection`() {
        val store = InMemoryCredentialStore().apply {
            save(PROVIDER_ID, OLD_CREDENTIAL.copy(expiresAtEpochMillis = NOW + 59_000L))
        }
        val refresh = FakeRefreshProtocol(
            result = CodexTokenRefreshResult.Success(accessToken = "rotated-access"),
            blockFirstCall = true,
        )
        val manager = manager(store = store, refreshProtocol = refresh, nowEpochMillis = { NOW })
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit<CodexOAuthCredential> {
            manager.credentialProvider.requireValidCredential(PROVIDER_ID)
        }
        assertTrue(refresh.firstCallStarted.await(5, TimeUnit.SECONDS))

        manager.logout(PROVIDER_ID)
        refresh.releaseFirstCall.countDown()

        val failure = runCatching { future.get(5, TimeUnit.SECONDS) }.exceptionOrNull()
        executor.shutdownNow()
        assertTrue(failure != null)
        assertNull(store.load(PROVIDER_ID))
    }

    @Test
    fun `logout clears only requested provider and returns login state to idle`() {
        val store = InMemoryCredentialStore().apply {
            save(PROVIDER_ID, OLD_CREDENTIAL)
            save(OTHER_PROVIDER_ID, CREDENTIAL)
        }
        val manager = manager(store = store)

        manager.logout(PROVIDER_ID)

        assertNull(store.load(PROVIDER_ID))
        assertEquals(CREDENTIAL, store.load(OTHER_PROVIDER_ID))
        assertEquals(CodexLoginState.Idle, manager.loginState.value)
    }

    @Test
    fun `states and failures redact device and credential secrets`() = runBlocking {
        val awaiting = CodexLoginState.AwaitingUser(USER_CODE, "https://auth.openai.com/codex/device")
        val store = InMemoryCredentialStore()
        val manager = manager(store = store)
        val failure = runCatching { manager.requireValidCredential(PROVIDER_ID) }.exceptionOrNull()

        assertFalse(awaiting.toString().contains(USER_CODE))
        assertTrue(failure is CodexAuthException)
        SECRET_VALUES.forEach { secret ->
            assertFalse(awaiting.toString().contains(secret))
            assertFalse(failure.toString().contains(secret))
        }
    }

    private fun manager(
        protocol: CodexDeviceAuthProtocol = FakeDeviceProtocol(),
        store: CodexCredentialStore = InMemoryCredentialStore(),
        refreshProtocol: CodexTokenRefreshProtocol = FakeRefreshProtocol(),
        nowEpochMillis: () -> Long = { NOW },
        loginTimeoutMillis: Long = 15 * 60 * 1_000L,
        delayMillis: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    ): CodexOAuthManager = CodexOAuthManager(
        deviceAuthProtocol = protocol,
        credentialStore = store,
        refreshProtocol = refreshProtocol,
        scope = scope,
        nowEpochMillis = nowEpochMillis,
        loginTimeoutMillis = loginTimeoutMillis,
        delayMillis = delayMillis,
    )

    private fun awaitState(
        manager: CodexOAuthManager,
        predicate: (CodexLoginState) -> Boolean,
    ): CodexLoginState {
        repeat(1_000) {
            manager.loginState.value.let { state -> if (predicate(state)) return state }
            Thread.sleep(5)
        }
        error("Timed out waiting for login state; current=${manager.loginState.value}")
    }

    private class ControlledDelay {
        private val continuations = java.util.concurrent.LinkedBlockingQueue<CancellableContinuation<Unit>>()

        suspend fun delay(@Suppress("UNUSED_PARAMETER") millis: Long) {
            suspendCancellableCoroutine { continuation -> continuations.put(continuation) }
        }

        fun resumeNext() {
            val continuation = continuations.poll(5, TimeUnit.SECONDS)
                ?: error("No pending delay")
            continuation.resume(Unit)
        }
    }

    private class FakeDeviceProtocol(
        private val authorizationResult: CodexDeviceAuthorizationResult =
            CodexDeviceAuthorizationResult.Success(AUTHORIZATION),
        private val pollResults: ArrayDeque<CodexDevicePollResult> =
            ArrayDeque(listOf(CodexDevicePollResult.Pending)),
        private val tokenResult: CodexTokenResult = CodexTokenResult.Success(TOKEN_SET),
        private val blockExchange: Boolean = false,
    ) : CodexDeviceAuthProtocol {
        val pollCount = AtomicInteger()
        val exchangeStarted = CountDownLatch(1)
        val releaseExchange = CountDownLatch(1)
        val exchangeFinished = CountDownLatch(1)

        override suspend fun requestAuthorization(): CodexDeviceAuthorizationResult = authorizationResult

        override suspend fun pollOnce(auth: CodexDeviceAuthorization): CodexDevicePollResult {
            pollCount.incrementAndGet()
            return if (pollResults.size > 1) pollResults.removeFirst() else pollResults.first()
        }

        override suspend fun exchangeToken(code: CodexAuthorizationCode): CodexTokenResult {
            if (blockExchange) {
                exchangeStarted.countDown()
                assertTrue(releaseExchange.await(5, TimeUnit.SECONDS))
            }
            exchangeFinished.countDown()
            return tokenResult
        }
    }

    private class FakeRefreshProtocol(
        private val result: CodexTokenRefreshResult = CodexTokenRefreshResult.Success(),
        private val blockFirstCall: Boolean = false,
    ) : CodexTokenRefreshProtocol {
        val callCount = AtomicInteger()
        val firstCallStarted = CountDownLatch(1)
        val releaseFirstCall = CountDownLatch(1)

        override suspend fun refresh(refreshToken: String): CodexTokenRefreshResult {
            val call = callCount.incrementAndGet()
            if (blockFirstCall && call == 1) {
                firstCallStarted.countDown()
                assertTrue(releaseFirstCall.await(5, TimeUnit.SECONDS))
            }
            return result
        }
    }

    private class InMemoryCredentialStore : CodexCredentialStore {
        private val values = ConcurrentHashMap<String, CodexOAuthCredential>()

        override fun load(providerId: String): CodexOAuthCredential? = values[providerId]

        override fun save(providerId: String, credential: CodexOAuthCredential) {
            values[providerId] = credential
        }

        override fun clear(providerId: String) {
            values.remove(providerId)
        }
    }

    private class BlockingSaveCredentialStore(
        initialCredential: CodexOAuthCredential? = null,
    ) : CodexCredentialStore {
        private val values = ConcurrentHashMap<String, CodexOAuthCredential>()
        private val shouldBlock = java.util.concurrent.atomic.AtomicBoolean()
        val saveStarted = CountDownLatch(1)
        val releaseSave = CountDownLatch(1)
        val saveCount = AtomicInteger()

        init {
            if (initialCredential != null) values[PROVIDER_ID] = initialCredential
        }

        fun blockNextSave() {
            shouldBlock.set(true)
        }

        override fun load(providerId: String): CodexOAuthCredential? = values[providerId]

        override fun save(providerId: String, credential: CodexOAuthCredential) {
            if (shouldBlock.compareAndSet(true, false)) {
                saveStarted.countDown()
                assertTrue(releaseSave.await(5, TimeUnit.SECONDS))
            }
            values[providerId] = credential
            saveCount.incrementAndGet()
        }

        override fun clear(providerId: String) {
            values.remove(providerId)
        }
    }

    private companion object {
        const val PROVIDER_ID = "builtin-openai"
        const val OTHER_PROVIDER_ID = "other-provider"
        const val USER_CODE = "ABCD-EFGH"
        const val NOW = 1_800_000_000_000L
        val AUTHORIZATION = CodexDeviceAuthorization("device-auth-id", USER_CODE, 1)
        val AUTHORIZATION_CODE = CodexAuthorizationCode("authorization-code", "challenge", "verifier")
        val TOKEN_SET = CodexTokenSet(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            idToken = syntheticIdToken(1_900_000_000L, "account-id"),
            expiresAtEpochMillis = 1_900_000_000_000L,
            accountId = "account-id",
        )
        val CREDENTIAL = CodexOAuthCredential(
            accessToken = TOKEN_SET.accessToken,
            refreshToken = TOKEN_SET.refreshToken,
            idToken = TOKEN_SET.idToken,
            accountId = TOKEN_SET.accountId,
            expiresAtEpochMillis = TOKEN_SET.expiresAtEpochMillis,
        )
        val OLD_CREDENTIAL = CodexOAuthCredential(
            accessToken = "old-access",
            refreshToken = "old-refresh",
            idToken = syntheticIdToken(1_850_000_000L, "old-account"),
            accountId = "old-account",
            expiresAtEpochMillis = 1_850_000_000_000L,
        )
        val SECRET_VALUES = listOf(
            USER_CODE,
            "device-auth-id",
            "authorization-code",
            "verifier",
            CREDENTIAL.accessToken,
            CREDENTIAL.refreshToken,
            CREDENTIAL.idToken,
            "account-id",
        )

        fun syntheticIdToken(exp: Long, accountId: String): String {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            fun encode(value: String): String =
                encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))
            return "${encode("""{"alg":"none"}""")}.${encode("""{"exp":$exp,"chatgpt_account_id":"$accountId"}""")}.signature"
        }
    }
}

class CodexTokenRefreshClientTest {
    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        httpClient = OkHttpClient()
    }

    @After
    fun tearDown() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
        server.close()
    }

    @Test
    fun `refresh sends official CLI 01470 exact JSON contract`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).body("""{"access_token":"rotated-access"}""").build(),
        )
        val client = CodexTokenRefreshClient(httpClient, server.url("/"))

        val result = client.refresh("synthetic-refresh")

        assertEquals(CodexTokenRefreshResult.Success(accessToken = "rotated-access"), result)
        val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/oauth/token", request.url.encodedPath)
        assertEquals("application/json", request.headers["Content-Type"])
        val json = JSONObject(requireNotNull(request.body).utf8())
        assertEquals(
            setOf("client_id", "grant_type", "refresh_token"),
            json.keys().asSequence().toSet(),
        )
        assertEquals(CodexDeviceAuthDefaults.CLIENT_ID, json.getString("client_id"))
        assertEquals("refresh_token", json.getString("grant_type"))
        assertEquals("synthetic-refresh", json.getString("refresh_token"))
    }

    @Test
    fun `refresh classifies 401 invalid and revoked as permanent without body leakage`() = runBlocking {
        val cases = listOf(
            401 to "sensitive unauthorized body",
            400 to """{"error":"invalid_grant","error_description":"sensitive invalid body"}""",
            400 to """{"error":"token_revoked","error_description":"sensitive revoked body"}""",
        )
        cases.forEach { (code, body) -> server.enqueue(MockResponse.Builder().code(code).body(body).build()) }
        val client = CodexTokenRefreshClient(httpClient, server.url("/"))

        val results = cases.map { client.refresh("synthetic-refresh") }

        assertEquals(
            listOf(
                CodexTokenRefreshFailure.UNAUTHORIZED,
                CodexTokenRefreshFailure.INVALID_GRANT,
                CodexTokenRefreshFailure.REVOKED,
            ),
            results.map { (it as CodexTokenRefreshResult.Failure).reason },
        )
        results.forEach { result ->
            assertFalse(result.toString().contains("sensitive"))
            assertFalse(result.toString().contains("synthetic-refresh"))
        }
    }

    @Test
    fun `refresh treats server failure as transient and rejects malformed optional fields`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).body("sensitive outage").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"access_token":42}""").build())
        val client = CodexTokenRefreshClient(httpClient, server.url("/"))

        assertEquals(
            CodexTokenRefreshResult.Failure(CodexTokenRefreshFailure.SERVER_FAILURE),
            client.refresh("synthetic-refresh"),
        )
        assertEquals(
            CodexTokenRefreshResult.Failure(CodexTokenRefreshFailure.PROTOCOL_FAILURE),
            client.refresh("synthetic-refresh"),
        )
    }

    @Test
    fun `default refresh endpoint is fixed HTTPS auth host`() = runBlocking {
        val urls = mutableListOf<String>()
        val interceptedClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                urls += chain.request().url.toString()
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{}".toResponseBody())
                    .build()
            }
            .build()

        val result = CodexTokenRefreshClient(interceptedClient).refresh("synthetic-refresh")

        assertEquals(CodexTokenRefreshResult.Success(), result)
        assertEquals("https://auth.openai.com/oauth/token", urls.single())
        interceptedClient.dispatcher.executorService.shutdown()
        interceptedClient.connectionPool.evictAll()
    }
}
