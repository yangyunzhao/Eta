package fuck.andes.data.auth

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
    fun `request authorization sends only public client id and parses device authorization`() = runBlocking {
        server.enqueue(jsonResponse("""
            {"device_auth_id":"device-auth-id","user_code":"ABCD-EFGH","interval":5}
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
                        """{"device_auth_id":"device-auth-id","user_code":"ABCD-EFGH","interval":5}"""
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
            {"authorization_code":"authorization-code","code_verifier":"code-verifier"}
        """.trimIndent()))

        val result = client().pollOnce(authorization())

        assertEquals(
            CodexDevicePollResult.Authorized(
                CodexAuthorizationCode("authorization-code", "code-verifier"),
            ),
            result,
        )
    }

    @Test
    fun `exchange token sends authorization code and verifier then parses complete token set`() = runBlocking {
        server.enqueue(jsonResponse("""
            {"access_token":"access-token","refresh_token":"refresh-token","id_token":"id-token","expires_in":3600,"account_id":"account-id"}
        """.trimIndent()))

        val result = client().exchangeToken(CodexAuthorizationCode("authorization-code", "code-verifier"))

        assertEquals(
            CodexTokenResult.Success(
                CodexTokenSet(
                    accessToken = "access-token",
                    refreshToken = "refresh-token",
                    idToken = "id-token",
                    expiresInSeconds = 3600,
                    accountId = "account-id",
                ),
            ),
            result,
        )
        val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/oauth/token", request.url.encodedPath)
        val requestJson = JSONObject(requireNotNull(request.body).utf8())
        assertEquals("authorization_code", requestJson.getString("grant_type"))
        assertEquals("app_EMoamEEZ73f0CkXaXp7hrann", requestJson.getString("client_id"))
        assertEquals("authorization-code", requestJson.getString("code"))
        assertEquals("code-verifier", requestJson.getString("code_verifier"))
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
    fun `cancelling request authorization cancels its in flight call`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("delayed").bodyDelay(60, TimeUnit.SECONDS).build())
        val job = launch(Dispatchers.Default) { client().requestAuthorization() }

        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        withTimeout(5_000) { job.cancelAndJoin() }

        assertTrue(job.isCancelled)
    }

    @Test
    fun `protocol models redact temporary authorization materials and tokens from toString`() {
        val rendered = listOf(
            CodexDeviceAuthorization("device-auth-id", "ABCD-EFGH", 5),
            CodexAuthorizationCode("authorization-code", "code-verifier"),
            CodexTokenSet("access-token", "refresh-token", "id-token", 3600, "account-id"),
        ).joinToString()

        listOf(
            "device-auth-id",
            "ABCD-EFGH",
            "authorization-code",
            "code-verifier",
            "access-token",
            "refresh-token",
            "id-token",
            "account-id",
        ).forEach { secret -> assertFalse(rendered.contains(secret)) }
    }

    private fun client(): CodexDeviceAuthClient = CodexDeviceAuthClient(
        httpClient = httpClient,
        endpointBaseUrl = server.url("/"),
    )

    private fun authorization(): CodexDeviceAuthorization =
        CodexDeviceAuthorization("device-auth-id", "ABCD-EFGH", 5)

    private fun jsonResponse(body: String): MockResponse =
        MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()
}
