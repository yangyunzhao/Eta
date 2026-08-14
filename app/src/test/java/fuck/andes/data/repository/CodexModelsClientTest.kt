package fuck.andes.data.repository

import fuck.andes.data.auth.CodexAuthException
import fuck.andes.data.auth.CodexAuthFailure
import fuck.andes.data.auth.CodexCredentialProvider
import fuck.andes.data.auth.CodexOAuthCredential
import fuck.andes.data.auth.CODEX_PROTOCOL_COMPAT_VERSION
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

class CodexModelsClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `fetch uses fixed models path and only OAuth headers`() {
        server.enqueue(jsonResponse(modelsBody()))
        val client = client(credentialProvider = credentials(credential("access-current", "account-current")))

        val models = client.fetch("provider-id")

        assertEquals(listOf("gpt-test"), models.map { it.modelId })
        val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/models?client_version=0.147.0", request.target)
        assertEquals("Bearer access-current", request.headers["Authorization"])
        assertEquals("account-current", request.headers["ChatGPT-Account-ID"])
        assertEquals("application/json", request.headers["Accept"])
    }

    @Test
    fun `fetch retries once with refreshed credential after first unauthorized`() {
        server.enqueue(MockResponse.Builder().code(401).body("not for errors").build())
        server.enqueue(jsonResponse(modelsBody()))
        val credentialProvider = credentials(
            credential("access-old"),
            credential("access-new"),
        )

        val models = client(credentialProvider).fetch("provider-id")

        assertEquals(listOf("gpt-test"), models.map { it.modelId })
        assertEquals(1, credentialProvider.refreshCount)
        assertEquals(0, credentialProvider.invalidateCount)
        assertEquals(
            "Bearer access-old",
            requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).headers["Authorization"],
        )
        assertEquals(
            "Bearer access-new",
            requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).headers["Authorization"],
        )
    }

    @Test
    fun `fetch clears rejected current credential after second unauthorized`() {
        server.enqueue(MockResponse.Builder().code(401).body("first-body").build())
        server.enqueue(MockResponse.Builder().code(401).body("server-body-sentinel").build())
        val credentialProvider = credentials(
            credential("access-old"),
            credential("access-new", "account-sentinel"),
            invalidateResult = true,
        )

        val failure = runCatching { client(credentialProvider).fetch("provider-id") }.exceptionOrNull()

        assertTrue(failure is CodexAuthException)
        assertEquals(CodexAuthFailure.REAUTHENTICATION_REQUIRED, (failure as CodexAuthException).failure)
        assertEquals(1, credentialProvider.invalidateCount)
        assertFalse(failure.message.orEmpty().contains("access-new"))
        assertFalse(failure.message.orEmpty().contains("account-sentinel"))
        assertFalse(failure.message.orEmpty().contains("server-body-sentinel"))
    }

    @Test
    fun `fetch reports retry required when concurrent rotation retains second rejected token`() {
        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(MockResponse.Builder().code(401).build())
        val credentialProvider = credentials(
            credential("access-old"),
            credential("access-new"),
            invalidateResult = false,
        )

        val failure = runCatching { client(credentialProvider).fetch("provider-id") }.exceptionOrNull()

        assertEquals(CodexModelsException.Failure.AUTHENTICATION_RETRY_REQUIRED, (failure as CodexModelsException).failure)
    }

    @Test
    fun `fetch redacts malformed server response from protocol failure`() {
        server.enqueue(jsonResponse("{\"error\":\"server-body-sentinel\""))

        val failure = runCatching {
            client(credentials(credential("access-sentinel", "account-sentinel"))).fetch("provider-id")
        }.exceptionOrNull()

        assertEquals(CodexModelsException.Failure.PROTOCOL_FAILURE, (failure as CodexModelsException).failure)
        assertFalse(failure.message.orEmpty().contains("access-sentinel"))
        assertFalse(failure.message.orEmpty().contains("account-sentinel"))
        assertFalse(failure.message.orEmpty().contains("server-body-sentinel"))
    }

    @Test
    fun `fetch rejects successful top level array as protocol failure`() {
        assertProtocolFailure("[\"server-body-sentinel\"]")
    }

    @Test
    fun `fetch rejects successful response without models as protocol failure`() {
        assertProtocolFailure("{\"status\":\"server-body-sentinel\"}")
    }

    @Test
    fun `fetch rejects successful response with non array models as protocol failure`() {
        assertProtocolFailure("{\"models\":{\"body\":\"server-body-sentinel\"}}")
    }

    private fun assertProtocolFailure(responseBody: String) {
        server.enqueue(jsonResponse(responseBody))

        val failure = runCatching {
            client(credentials(credential("access-sentinel", "account-sentinel"))).fetch("provider-id")
        }.exceptionOrNull()

        assertEquals(CodexModelsException.Failure.PROTOCOL_FAILURE, (failure as CodexModelsException).failure)
        assertFalse(failure.message.orEmpty().contains("access-sentinel"))
        assertFalse(failure.message.orEmpty().contains("account-sentinel"))
        assertFalse(failure.message.orEmpty().contains("server-body-sentinel"))
    }

    private fun client(credentialProvider: RecordingCredentialProvider): CodexModelsClient =
        CodexModelsClient.forTest(
            credentialProvider = credentialProvider,
            httpClient = OkHttpClient(),
            endpointUrl = server.url("/models?client_version=$CODEX_PROTOCOL_COMPAT_VERSION"),
        )

    private fun credentials(
        vararg values: CodexOAuthCredential,
        invalidateResult: Boolean? = null,
    ) = RecordingCredentialProvider(values.toList(), invalidateResult)

    private fun credential(accessToken: String, accountId: String? = null) = CodexOAuthCredential(
        accessToken = accessToken,
        refreshToken = "refresh-token",
        idToken = "id-token",
        accountId = accountId,
        expiresAtEpochMillis = Long.MAX_VALUE,
    )

    private fun jsonResponse(body: String): MockResponse = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun modelsBody(): String =
        """{"models":[{"slug":"gpt-test","display_name":"GPT Test","visibility":"list"}]}"""

    private class RecordingCredentialProvider(
        private val credentials: List<CodexOAuthCredential>,
        private val invalidateResult: Boolean? = null,
    ) : CodexCredentialProvider {
        var refreshCount = 0
            private set
        var invalidateCount = 0
            private set
        private var current = 0

        override fun requireValidCredential(providerId: String): CodexOAuthCredential = credentials[current]

        override fun refreshAfterUnauthorized(
            providerId: String,
            rejectedAccessToken: String,
        ): CodexOAuthCredential {
            require(rejectedAccessToken == credentials[current].accessToken)
            refreshCount++
            current = (current + 1).coerceAtMost(credentials.lastIndex)
            return credentials[current]
        }

        override fun invalidateAfterUnauthorized(
            providerId: String,
            rejectedAccessToken: String,
        ): Boolean {
            invalidateCount++
            return invalidateResult ?: (credentials[current].accessToken == rejectedAccessToken)
        }
    }
}
