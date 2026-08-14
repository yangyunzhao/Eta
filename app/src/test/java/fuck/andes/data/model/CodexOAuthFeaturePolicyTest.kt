package fuck.andes.data.model

import fuck.andes.data.provider.BuiltinProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexOAuthFeaturePolicyTest {
    private val builtInOpenAi = requireNotNull(
        BuiltinProviders.providerById(BuiltinProviders.OPENAI_ID),
    )

    @Test
    fun `raw Gradle property drives the production policy default`() {
        val expectedEnabled = expectedCompiledFlag()

        assertEquals(
            expectedEnabled,
            CodexOAuthFeaturePolicy.isEnabled,
        )
        assertEquals(
            expectedEnabled,
            CodexOAuthFeaturePolicy.supportsProvider(builtInOpenAi),
        )
    }

    @Test
    fun `disabled build never exposes Codex OAuth for a provider`() {
        assertFalse(
            CodexOAuthFeaturePolicy.supportsProvider(
                provider = builtInOpenAi,
                enabled = false,
            ),
        )
    }

    @Test
    fun `enabled build exposes Codex OAuth only for built in OpenAI`() {
        assertTrue(
            CodexOAuthFeaturePolicy.supportsProvider(
                provider = builtInOpenAi,
                enabled = true,
            ),
        )
        assertFalse(
            CodexOAuthFeaturePolicy.supportsProvider(
                provider = requireNotNull(
                    BuiltinProviders.providerById(BuiltinProviders.DEEPSEEK_ID),
                ),
                enabled = true,
            ),
        )
    }

    @Test
    fun `disabled build resolves no Codex credential dependency`() {
        assertFalse(
            CodexOAuthFeaturePolicy.shouldResolveCredential(
                authMode = ProviderAuthModes.CODEX_OAUTH,
                enabled = false,
            ),
        )
        assertFalse(
            CodexOAuthFeaturePolicy.shouldResolveCredential(
                authMode = "",
                enabled = true,
            ),
        )
        assertTrue(
            CodexOAuthFeaturePolicy.shouldResolveCredential(
                authMode = ProviderAuthModes.CODEX_OAUTH,
                enabled = true,
            ),
        )
    }

    private fun expectedCompiledFlag(): Boolean = when (
        val rawProperty = checkNotNull(System.getProperty(TEST_BUILD_PROPERTY)) {
            "Gradle must expose the raw eta.codexOAuthEnabled property to unit tests"
        }
    ) {
        PROPERTY_UNSET -> true
        "true" -> true
        "false" -> false
        else -> error("unexpected test build property: $rawProperty")
    }

    private companion object {
        const val TEST_BUILD_PROPERTY = "eta.test.codexOAuthBuildProperty"
        const val PROPERTY_UNSET = "<unset>"
    }
}
