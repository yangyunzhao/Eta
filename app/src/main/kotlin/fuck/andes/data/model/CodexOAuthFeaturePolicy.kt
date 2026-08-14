package fuck.andes.data.model

import fuck.andes.BuildConfig
import fuck.andes.data.provider.BuiltinProviders

/** Codex OAuth 编译期开关的唯一生产策略入口。 */
internal object CodexOAuthFeaturePolicy {
    val isEnabled: Boolean
        get() = BuildConfig.CODEX_OAUTH_ENABLED

    fun supportsProvider(
        provider: ProviderSetting,
        enabled: Boolean = isEnabled,
    ): Boolean =
        enabled &&
            provider is OpenAiCompatibleProviderSetting &&
            provider.isBuiltIn &&
            provider.id == BuiltinProviders.OPENAI_ID &&
            provider.sourceType == ProviderSourceTypes.OPENAI

    fun authModeForSave(
        provider: ProviderSetting,
        requestedAuthMode: String,
        enabled: Boolean = isEnabled,
    ): String = when {
        supportsProvider(provider, enabled) &&
            requestedAuthMode == ProviderAuthModes.CODEX_OAUTH -> ProviderAuthModes.CODEX_OAUTH

        !enabled &&
            provider.authMode == ProviderAuthModes.CODEX_OAUTH &&
            requestedAuthMode == ProviderAuthModes.CODEX_OAUTH -> ProviderAuthModes.CODEX_OAUTH

        else -> ""
    }

    fun shouldResolveCredential(
        authMode: String,
        enabled: Boolean = isEnabled,
    ): Boolean = enabled && authMode == ProviderAuthModes.CODEX_OAUTH
}
