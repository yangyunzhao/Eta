package fuck.andes.agent.model

import fuck.andes.data.auth.CodexAuthException
import fuck.andes.data.auth.CodexAuthFailure
import fuck.andes.data.auth.CodexCredentialProvider
import fuck.andes.data.auth.CodexOAuthCredential
import fuck.andes.data.model.OpenAiEndpointMode
import fuck.andes.data.model.ProviderAuthModes
import fuck.andes.data.model.ProviderSourceTypes
import fuck.andes.data.model.ProviderTypes
import fuck.andes.data.provider.BuiltinProviders
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderClientFactoryTest {
    @Test
    fun `empty auth mode keeps existing API key Responses provider`() {
        val client = ProviderClientFactory.getClient(apiKeyConfig())

        assertSame(OpenAiResponsesProvider, client)
    }

    @Test
    fun `Codex OAuth selects dedicated provider only with explicit credential dependency`() {
        val client = ProviderClientFactory.getClient(
            config = codexConfig(),
            codexCredentialProvider = UnauthenticatedCredentialProvider,
            codexOAuthEnabled = true,
        )

        assertTrue(client is CodexResponsesProvider)
        assertTrue(client !== OpenAiResponsesProvider)
        assertEquals("codex_oauth_responses", client.id)
        val thrown = runCatching {
            client.complete(
                ProviderRequest(codexConfig(), org.json.JSONArray(), org.json.JSONArray()),
                fuck.andes.agent.runtime.AgentRunController(),
            )
        }.exceptionOrNull()
        assertTrue(thrown is CodexAuthException)
    }

    @Test
    fun `Codex OAuth without credential dependency fails closed instead of using API key provider`() {
        val thrown = runCatching {
            ProviderClientFactory.getClient(codexConfig(), codexOAuthEnabled = true)
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertTrue(thrown?.message.orEmpty().contains("credential", ignoreCase = true))
    }

    @Test
    fun `disabled Codex OAuth fails closed before credentials or HTTP can be used`() {
        val credentialProvider = RecordingCredentialProvider()

        val thrown = runCatching {
            ProviderClientFactory.getClient(
                config = codexConfig(),
                codexCredentialProvider = credentialProvider,
                codexOAuthEnabled = false,
            )
        }.exceptionOrNull()

        assertTrue(thrown is CodexAuthException)
        assertEquals(CodexAuthFailure.UNSUPPORTED, (thrown as CodexAuthException).failure)
        assertEquals(0, credentialProvider.callCount)
    }

    @Test
    fun `disabled Codex OAuth leaves API key Anthropic and custom routing unchanged`() {
        assertSame(
            OpenAiResponsesProvider,
            ProviderClientFactory.getClient(apiKeyConfig(), codexOAuthEnabled = false),
        )
        assertSame(
            AnthropicMessagesProvider,
            ProviderClientFactory.getClient(
                apiKeyConfig().copy(
                    providerType = ProviderTypes.ANTHROPIC,
                    providerSourceType = ProviderSourceTypes.ANTHROPIC,
                    openAiEndpointMode = "",
                ),
                codexOAuthEnabled = false,
            ),
        )
        assertSame(
            OpenAiChatCompletionsProvider,
            ProviderClientFactory.getClient(
                apiKeyConfig().copy(openAiEndpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS),
                codexOAuthEnabled = false,
            ),
        )
    }

    @Test
    fun `raw Gradle property drives the default Factory Codex gate`() {
        val expectedEnabled = when (
            val rawProperty = checkNotNull(System.getProperty("eta.test.codexOAuthBuildProperty")) {
                "Gradle must expose the raw eta.codexOAuthEnabled property to unit tests"
            }
        ) {
            "<unset>" -> true
            "true" -> true
            "false" -> false
            else -> error("unexpected test build property: $rawProperty")
        }

        val result = runCatching {
            ProviderClientFactory.getClient(
                config = codexConfig(),
                codexCredentialProvider = UnauthenticatedCredentialProvider,
            )
        }
        if (expectedEnabled) {
            assertTrue(result.getOrThrow() is CodexResponsesProvider)
        } else {
            val failure = result.exceptionOrNull()
            assertTrue(failure is CodexAuthException)
            assertEquals(CodexAuthFailure.UNSUPPORTED, (failure as CodexAuthException).failure)
        }
    }

    @Test
    fun `unknown auth mode fails closed before endpoint fallback`() {
        val thrown = runCatching {
            ProviderClientFactory.getClient(apiKeyConfig().copy(authMode = "future_auth"))
        }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
        assertTrue(thrown?.message.orEmpty().contains("future_auth").not())
    }

    private fun apiKeyConfig() = AgentModelClient.ModelConfig(
        providerId = "custom-provider",
        providerName = "Custom",
        providerType = ProviderTypes.OPENAI_COMPATIBLE,
        providerSourceType = ProviderSourceTypes.CUSTOM,
        baseUrl = "https://example.com/v1",
        apiKey = "test-api-key",
        model = "test-model",
        systemPrompt = "system",
        openAiEndpointMode = OpenAiEndpointMode.RESPONSES,
    )

    private fun codexConfig() = AgentModelClient.ModelConfig(
        providerId = BuiltinProviders.OPENAI_ID,
        providerName = "OpenAI",
        providerType = ProviderTypes.OPENAI_COMPATIBLE,
        providerSourceType = ProviderSourceTypes.OPENAI,
        baseUrl = "https://malicious.invalid/v1",
        apiKey = "",
        model = "gpt-5.5",
        systemPrompt = "system",
        openAiEndpointMode = OpenAiEndpointMode.RESPONSES,
        authMode = ProviderAuthModes.CODEX_OAUTH,
    )

    private object UnauthenticatedCredentialProvider : CodexCredentialProvider {
        override fun requireValidCredential(providerId: String): CodexOAuthCredential =
            throw CodexAuthException(CodexAuthFailure.NOT_AUTHENTICATED)

        override fun refreshAfterUnauthorized(
            providerId: String,
            rejectedAccessToken: String,
        ): CodexOAuthCredential = throw CodexAuthException(CodexAuthFailure.NOT_AUTHENTICATED)

        override fun invalidateAfterUnauthorized(
            providerId: String,
            rejectedAccessToken: String,
        ): Boolean = false
    }

    private class RecordingCredentialProvider : CodexCredentialProvider {
        var callCount: Int = 0

        override fun requireValidCredential(providerId: String): CodexOAuthCredential {
            callCount++
            error("disabled feature must not load credentials")
        }

        override fun refreshAfterUnauthorized(
            providerId: String,
            rejectedAccessToken: String,
        ): CodexOAuthCredential {
            callCount++
            error("disabled feature must not refresh credentials")
        }

        override fun invalidateAfterUnauthorized(
            providerId: String,
            rejectedAccessToken: String,
        ): Boolean {
            callCount++
            error("disabled feature must not invalidate credentials")
        }
    }
}
