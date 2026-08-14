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
            ProviderClientFactory.getClient(codexConfig())
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertTrue(thrown?.message.orEmpty().contains("credential", ignoreCase = true))
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
}
