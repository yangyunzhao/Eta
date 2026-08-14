package fuck.andes.agent.model

import fuck.andes.data.auth.CodexCredentialProvider
import fuck.andes.data.auth.CodexAuthException
import fuck.andes.data.auth.CodexAuthFailure
import fuck.andes.data.model.CodexOAuthFeaturePolicy
import fuck.andes.data.model.ProviderTypes
import fuck.andes.data.model.OpenAiEndpointMode
import fuck.andes.data.model.ProviderAuthModes

internal object ProviderClientFactory {

    fun getClient(
        config: AgentModelClient.ModelConfig,
        codexCredentialProvider: CodexCredentialProvider? = null,
        codexOAuthEnabled: Boolean = CodexOAuthFeaturePolicy.isEnabled,
    ): AgentProviderClient {
        config.validateForTest()
        return when (config.authMode) {
            "" -> when (config.providerType) {
                ProviderTypes.OPENAI_COMPATIBLE -> when (config.openAiEndpointMode) {
                    OpenAiEndpointMode.RESPONSES -> OpenAiResponsesProvider
                    else -> OpenAiChatCompletionsProvider
                }
                ProviderTypes.ANTHROPIC -> AnthropicMessagesProvider
                else -> error("不支持的 Provider 协议类型：${config.providerType}")
            }

            ProviderAuthModes.CODEX_OAUTH -> {
                if (!codexOAuthEnabled) {
                    throw CodexAuthException(CodexAuthFailure.UNSUPPORTED)
                }
                CodexResponsesProvider(
                    credentialProvider = checkNotNull(codexCredentialProvider) {
                        "Codex OAuth credential provider is not initialized"
                    },
                )
            }

            else -> throw IllegalArgumentException("不支持的认证模式")
        }
    }
}
