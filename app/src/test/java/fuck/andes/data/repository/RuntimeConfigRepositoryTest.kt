package fuck.andes.data.repository

import fuck.andes.agent.model.AgentModelClient
import fuck.andes.data.model.CustomHeader
import fuck.andes.data.model.Model
import fuck.andes.data.model.ModelReasoningCapabilities
import fuck.andes.data.model.OpenAiCompatibleProviderSetting
import fuck.andes.data.model.OpenAiEndpointMode
import fuck.andes.data.model.ProviderAuthModes
import fuck.andes.data.model.ProviderTypes
import fuck.andes.data.model.ProviderSourceTypes
import fuck.andes.data.model.ReasoningEffort
import fuck.andes.data.provider.BuiltinProviders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeConfigRepositoryTest {
    @Test
    fun buildsStructuredRuntimeConfigFromProviderAndModel() {
        val provider = OpenAiCompatibleProviderSetting(
            id = "p1",
            name = "Provider",
            baseUrl = "https://api.example.com/v1",
            apiKey = "key",
            sourceType = ProviderSourceTypes.OPENAI,
            customHeaders = listOf(CustomHeader("x-provider", "1"))
        )
        val model = Model(
            id = "m1",
            modelId = "gpt-5.5",
            displayName = "GPT-5.5",
            contextWindow = 1_000_000,
            contextWindowOverride = 256_000,
            reasoning = true,
            reasoningOverride = true,
            reasoningCapabilitiesOverride = ModelReasoningCapabilities(
                supportedEfforts = listOf(ReasoningEffort.MINIMAL),
                canDisable = true,
            ),
            customHeaders = listOf(CustomHeader("x-model", "2"))
        )

        val config = RuntimeConfigRepository.buildRuntimeConfig(provider, model)
        val raw = RuntimeConfigRepository.runtimeConfigJson(config)
        val root = Json.parseToJsonElement(raw).jsonObject

        assertEquals(ProviderTypes.OPENAI_COMPATIBLE, root.getValue("providerType").jsonPrimitive.content)
        assertEquals("gpt-5.5", root.getValue("model").jsonPrimitive.content)
        assertEquals(256_000, config.contextWindow)
        assertEquals(listOf("x-provider", "x-model"), config.customHeaders.map { it.name })
        assertEquals(ReasoningEffort.DEFAULT, config.reasoningEffort)
        assertEquals(true, config.thinkingEnabled)
        assertEquals(
            listOf(
                ReasoningEffort.OFF,
                ReasoningEffort.DEFAULT,
                ReasoningEffort.MINIMAL,
            ),
            config.reasoningCapabilities?.selectableEfforts,
        )
        assertEquals(config, Json.decodeFromString<AgentModelClient.ModelConfig>(raw))
    }

    @Test
    fun codexOAuthRuntimeConfigKeepsOnlyNonSecretAuthMetadata() {
        val provider = OpenAiCompatibleProviderSetting(
            id = BuiltinProviders.OPENAI_ID,
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            sourceType = ProviderSourceTypes.OPENAI,
            apiKey = "legacy-api-key-must-not-cross-runtime",
            authMode = ProviderAuthModes.CODEX_OAUTH,
            isBuiltIn = true,
            endpointMode = OpenAiEndpointMode.RESPONSES,
        )
        val model = Model(id = "codex-model", modelId = "gpt-5.5", displayName = "GPT-5.5")

        val config = RuntimeConfigRepository.buildRuntimeConfig(provider, model)
        val raw = RuntimeConfigRepository.runtimeConfigJson(config)
        val root = Json.parseToJsonElement(raw).jsonObject

        assertEquals(ProviderAuthModes.CODEX_OAUTH, config.authMode)
        assertEquals(BuiltinProviders.OPENAI_ID, config.providerId)
        assertEquals("", config.apiKey)
        assertEquals(ProviderAuthModes.CODEX_OAUTH, root.getValue("authMode").jsonPrimitive.content)
        assertEquals("", root.getValue("apiKey").jsonPrimitive.content)
        assertFalse(raw.contains("legacy-api-key-must-not-cross-runtime"))
        listOf("accessToken", "refreshToken", "idToken", "accountId", "deviceCode", "pkce")
            .forEach { forbiddenField -> assertFalse(raw.contains(forbiddenField, ignoreCase = true)) }
        assertEquals(config, Json.decodeFromString<AgentModelClient.ModelConfig>(raw))
    }

    @Test
    fun codexOAuthRuntimeAlwaysProjectsResponsesWithoutChangingStoredApiEndpoint() {
        val provider = OpenAiCompatibleProviderSetting(
            id = BuiltinProviders.OPENAI_ID,
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            sourceType = ProviderSourceTypes.OPENAI,
            apiKey = "preserved-api-key",
            authMode = ProviderAuthModes.CODEX_OAUTH,
            isBuiltIn = true,
            endpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS,
        )

        val config = RuntimeConfigRepository.buildRuntimeConfig(
            provider,
            Model(id = "model", modelId = "gpt-5.5", displayName = "GPT-5.5"),
        )

        assertEquals(OpenAiEndpointMode.RESPONSES, config.openAiEndpointMode)
        assertEquals(OpenAiEndpointMode.CHAT_COMPLETIONS, provider.endpointMode)
        assertEquals("preserved-api-key", provider.apiKey)
        config.validateForTest()
    }

    @Test
    fun codexOAuthValidationFailsClosedWhileLegacyApiKeyValidationRemainsUnchanged() {
        codexConfig().validateForTest()
        listOf(
            codexConfig().copy(providerId = "custom-openai"),
            codexConfig().copy(providerType = ProviderTypes.CUSTOM),
            codexConfig().copy(providerSourceType = ProviderSourceTypes.CUSTOM),
            codexConfig().copy(openAiEndpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS),
            codexConfig().copy(apiKey = "must-not-be-used"),
            codexConfig().copy(model = ""),
            codexConfig().copy(authMode = "future_auth_mode"),
        ).forEach { config ->
            assertThrows(IllegalArgumentException::class.java) { config.validateForTest() }
        }

        codexConfig().copy(
            authMode = "",
            providerId = "custom-openai",
            providerSourceType = ProviderSourceTypes.CUSTOM,
            openAiEndpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS,
            apiKey = "test-key",
        ).validateForTest()
    }

    private fun codexConfig() = AgentModelClient.ModelConfig(
        providerId = BuiltinProviders.OPENAI_ID,
        providerName = "OpenAI",
        providerType = ProviderTypes.OPENAI_COMPATIBLE,
        providerSourceType = ProviderSourceTypes.OPENAI,
        baseUrl = "https://api.openai.com/v1",
        apiKey = "",
        model = "gpt-5.5",
        systemPrompt = "system",
        openAiEndpointMode = OpenAiEndpointMode.RESPONSES,
        authMode = ProviderAuthModes.CODEX_OAUTH,
    )
}
