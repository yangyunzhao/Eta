package fuck.andes.ui.pages.providers

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fuck.andes.data.auth.CodexAuthFailure
import fuck.andes.data.auth.CodexLoginState
import fuck.andes.data.model.CustomProviderSetting
import fuck.andes.data.model.OpenAiCompatibleProviderSetting
import fuck.andes.data.model.OpenAiEndpointMode
import fuck.andes.data.model.ProviderAuthModes
import fuck.andes.data.provider.BuiltinProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CodexDeviceLoginContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun apiKeyModeKeepsExistingConnectionFieldsAndHidesCodexLogin() {
        setAuthenticationContent(
            authMode = "",
            loginState = CodexLoginState.Idle,
            baseUrl = "https://api.openai.com/v1",
            apiKey = "synthetic-api-key",
        )

        composeRule.onNode(hasText("Base URL") and hasSetTextAction()).assertIsDisplayed()
        composeRule.onNode(hasText("API Key") and hasSetTextAction()).assertIsDisplayed()
        composeRule.onNodeWithText("使用 Codex 设备码登录").assertDoesNotExist()
    }

    @Test
    fun codexModeHidesApiKeyFieldsAndIdleStartsProviderLogin() {
        var beginCount = 0
        setAuthenticationContent(
            authMode = "codex_oauth",
            loginState = CodexLoginState.Idle,
            onBeginLogin = { beginCount++ },
        )

        composeRule.onNode(hasText("Base URL") and hasSetTextAction()).assertDoesNotExist()
        composeRule.onNode(hasText("API Key") and hasSetTextAction()).assertDoesNotExist()
        composeRule.onNodeWithText("使用 Codex 设备码登录").performClick()

        composeRule.runOnIdle { assertEquals(1, beginCount) }
    }

    @Test
    fun codexAuthenticationIsOfferedOnlyForTheBuiltInOpenAiProvider() {
        assertEquals(
            true,
            supportsCodexOAuth(
                BuiltinProviders.providerById(BuiltinProviders.OPENAI_ID)!!,
                codexOAuthEnabled = true,
            ),
        )
        assertEquals(
            false,
            supportsCodexOAuth(
                BuiltinProviders.providerById(BuiltinProviders.DEEPSEEK_ID)!!,
                codexOAuthEnabled = true,
            ),
        )
        assertEquals(
            false,
            supportsCodexOAuth(
                CustomProviderSetting(
                    id = "custom-openai-lookalike",
                    name = "OpenAI copy",
                    baseUrl = "https://api.openai.com/v1",
                ),
                codexOAuthEnabled = true,
            ),
        )
    }

    @Test
    fun disabledBuildHidesOAuthEntryAndKeepsExistingCodexProviderFields() {
        val openAi = (BuiltinProviders.providerById(BuiltinProviders.OPENAI_ID) as OpenAiCompatibleProviderSetting)
            .copy(
                baseUrl = "https://preserved.example/v1",
                apiKey = "preserved-api-key",
                authMode = ProviderAuthModes.CODEX_OAUTH,
                endpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS,
            )

        assertFalse(supportsCodexOAuth(openAi, codexOAuthEnabled = false))
        val saved = buildUpdatedProvider(
            source = openAi,
            name = openAi.name,
            baseUrl = openAi.baseUrl,
            apiKey = openAi.apiKey,
            authMode = openAi.authMode,
            systemPrompt = openAi.systemPrompt.orEmpty(),
            isEnabled = openAi.isEnabled,
            endpointMode = openAi.endpointMode,
            hostedWebSearchEnabled = openAi.hostedWebSearchEnabled,
            anthropicVersion = "",
            codexOAuthEnabled = false,
        ) as OpenAiCompatibleProviderSetting

        assertEquals(ProviderAuthModes.CODEX_OAUTH, saved.authMode)
        assertEquals("preserved-api-key", saved.apiKey)
        assertEquals("https://preserved.example/v1", saved.baseUrl)
        assertEquals(OpenAiEndpointMode.CHAT_COMPLETIONS, saved.endpointMode)
    }

    @Test
    fun disabledBuildRequiresExplicitSwitchBeforeShowingApiKeyFields() {
        var requestedAuthMode: String? = null
        composeRule.setContent {
            ProviderAuthenticationContent(
                supportsCodexOAuth = false,
                codexOAuthFeatureEnabled = false,
                authMode = ProviderAuthModes.CODEX_OAUTH,
                baseUrl = "https://preserved.example/v1",
                apiKey = "preserved-api-key",
                apiKeyVisible = false,
                loginState = CodexLoginState.Idle,
                launcher = RecordingLauncher(),
                onAuthModeChange = { requestedAuthMode = it },
                onBaseUrlChange = {},
                onApiKeyChange = {},
                onToggleApiKeyVisibility = {},
                onBeginLogin = {},
                onCancelLogin = {},
                onLogout = {},
            )
        }

        composeRule.onNodeWithText("此构建已关闭 Codex OAuth").assertIsDisplayed()
        composeRule.onNodeWithText("认证方式").assertDoesNotExist()
        composeRule.onNodeWithText("使用 Codex 设备码登录").assertDoesNotExist()
        composeRule.onNode(hasText("Base URL") and hasSetTextAction()).assertDoesNotExist()
        composeRule.onNode(hasText("API Key") and hasSetTextAction()).assertDoesNotExist()
        composeRule.onNodeWithText("切换到 API Key").performClick()
        composeRule.runOnIdle { assertEquals("", requestedAuthMode) }
    }

    @Test
    fun disabledBuildDoesNotOptAnApiKeyProviderIntoCodexOAuth() {
        val openAi = BuiltinProviders.providerById(BuiltinProviders.OPENAI_ID) as OpenAiCompatibleProviderSetting

        val saved = buildUpdatedProvider(
            source = openAi,
            name = openAi.name,
            baseUrl = openAi.baseUrl,
            apiKey = openAi.apiKey,
            authMode = ProviderAuthModes.CODEX_OAUTH,
            systemPrompt = openAi.systemPrompt.orEmpty(),
            isEnabled = openAi.isEnabled,
            endpointMode = openAi.endpointMode,
            hostedWebSearchEnabled = openAi.hostedWebSearchEnabled,
            anthropicVersion = "",
            codexOAuthEnabled = false,
        )

        assertEquals("", saved.authMode)
        assertEquals(openAi.apiKey, saved.apiKey)
        assertEquals(openAi.baseUrl, saved.baseUrl)
    }

    @Test
    fun unsupportedProviderNeverHidesApiKeyFieldsEvenWithUnknownPersistedMode() {
        composeRule.setContent {
            ProviderAuthenticationContent(
                supportsCodexOAuth = false,
                codexOAuthFeatureEnabled = true,
                authMode = "codex_oauth",
                baseUrl = "https://api.example.test/v1",
                apiKey = "synthetic-key",
                apiKeyVisible = false,
                loginState = CodexLoginState.Idle,
                launcher = RecordingLauncher(),
                onAuthModeChange = {},
                onBaseUrlChange = {},
                onApiKeyChange = {},
                onToggleApiKeyVisibility = {},
                onBeginLogin = {},
                onCancelLogin = {},
                onLogout = {},
            )
        }

        composeRule.onNodeWithText("认证方式").assertDoesNotExist()
        composeRule.onNode(hasText("Base URL") and hasSetTextAction()).assertIsDisplayed()
        composeRule.onNode(hasText("API Key") and hasSetTextAction()).assertIsDisplayed()
    }

    @Test
    fun savingAuthenticationModeNormalizesUnsupportedAndUnknownModesWithoutClearingApiSettings() {
        val openAi = (BuiltinProviders.providerById(BuiltinProviders.OPENAI_ID) as OpenAiCompatibleProviderSetting)
            .copy(
                baseUrl = "https://api.openai.com/v1",
                apiKey = "preserved-api-key",
                endpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS,
            )

        val oauth = buildUpdatedProvider(
            source = openAi,
            name = openAi.name,
            baseUrl = openAi.baseUrl,
            apiKey = openAi.apiKey,
            authMode = ProviderAuthModes.CODEX_OAUTH,
            systemPrompt = openAi.systemPrompt.orEmpty(),
            isEnabled = openAi.isEnabled,
            endpointMode = openAi.endpointMode,
            hostedWebSearchEnabled = openAi.hostedWebSearchEnabled,
            anthropicVersion = "",
            codexOAuthEnabled = true,
        ) as OpenAiCompatibleProviderSetting
        assertEquals(ProviderAuthModes.CODEX_OAUTH, oauth.authMode)
        assertEquals("preserved-api-key", oauth.apiKey)
        assertEquals("https://api.openai.com/v1", oauth.baseUrl)
        assertEquals(OpenAiEndpointMode.CHAT_COMPLETIONS, oauth.endpointMode)

        val unknown = buildUpdatedProvider(
            source = openAi,
            name = openAi.name,
            baseUrl = openAi.baseUrl,
            apiKey = openAi.apiKey,
            authMode = "future_auth_mode",
            systemPrompt = openAi.systemPrompt.orEmpty(),
            isEnabled = openAi.isEnabled,
            endpointMode = openAi.endpointMode,
            hostedWebSearchEnabled = openAi.hostedWebSearchEnabled,
            anthropicVersion = "",
            codexOAuthEnabled = true,
        )
        assertEquals("", unknown.authMode)

        val unsupported = CustomProviderSetting(
            id = "custom-provider",
            name = "Custom",
            baseUrl = "https://api.example.test/v1",
            apiKey = "custom-key",
        )
        val normalizedUnsupported = buildUpdatedProvider(
            source = unsupported,
            name = unsupported.name,
            baseUrl = unsupported.baseUrl,
            apiKey = unsupported.apiKey,
            authMode = ProviderAuthModes.CODEX_OAUTH,
            systemPrompt = "",
            isEnabled = true,
            endpointMode = unsupported.endpointMode,
            hostedWebSearchEnabled = false,
            anthropicVersion = "",
            codexOAuthEnabled = true,
        )
        assertEquals("", normalizedUnsupported.authMode)
        assertEquals("custom-key", normalizedUnsupported.apiKey)
    }

    @Test
    fun awaitingStateShowsUserCodeWithoutCopyActionAndInvokesBrowserAndProviderCancel() {
        val launcher = RecordingLauncher()
        var cancelCount = 0
        setAuthenticationContent(
            authMode = "codex_oauth",
            loginState = CodexLoginState.AwaitingUser(
                userCode = "ABCD-EFGH",
                verificationUrl = "https://malicious.example/callback?device_secret=never-show",
            ),
            launcher = launcher,
            onCancelLogin = { cancelCount++ },
        )

        composeRule.onNodeWithText("ABCD-EFGH").assertIsDisplayed()
        composeRule.onNodeWithText("复制验证码").assertDoesNotExist()
        composeRule.onNodeWithText("打开系统浏览器").performClick()
        composeRule.onNodeWithText("取消登录").performClick()

        composeRule.runOnIdle {
            assertEquals(1, launcher.openCount)
            assertEquals(1, cancelCount)
        }
        composeRule.onNodeWithText("never-show", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("device_secret", substring = true).assertDoesNotExist()
    }

    @Test
    fun authorizedStateDoesNotExposeAccountLabelAndLogoutIsProviderScoped() {
        var logoutCount = 0
        setAuthenticationContent(
            authMode = "codex_oauth",
            loginState = CodexLoginState.Authorized("acct-secret-123"),
            onLogout = { logoutCount++ },
        )

        composeRule.onNodeWithText("已登录 Codex").assertIsDisplayed()
        composeRule.onNodeWithText("acct-secret-123", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("退出 Codex 登录").performClick()
        composeRule.runOnIdle { assertEquals(1, logoutCount) }
    }

    @Test
    fun failureStateUsesSafeMessageAndAllowsRetry() {
        setAuthenticationContent(
            authMode = "codex_oauth",
            loginState = CodexLoginState.Failed(CodexAuthFailure.REAUTHENTICATION_REQUIRED),
        )

        composeRule.onNodeWithText("登录已失效，请重新登录").assertIsDisplayed()
        composeRule.onNodeWithText("重新登录").assertIsDisplayed()
    }

    @Test
    fun productionLauncherAlwaysStartsOneFixedViewIntentWithoutExtras() {
        val application = RuntimeEnvironment.getApplication()
        val launcher = AndroidCodexVerificationPageLauncher(application)

        assertEquals(true, launcher.open())

        val intent = shadowOf(application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://auth.openai.com/codex/device", intent.dataString)
        assertFalse(intent.hasExtra("url"))
        assertNull(intent.component)
        assertNull(intent.`package`)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    private fun setAuthenticationContent(
        authMode: String,
        loginState: CodexLoginState,
        baseUrl: String = "https://api.example.test/v1",
        apiKey: String = "synthetic-key",
        launcher: CodexVerificationPageLauncher = RecordingLauncher(),
        onBeginLogin: () -> Unit = {},
        onCancelLogin: () -> Unit = {},
        onLogout: () -> Unit = {},
    ) {
        composeRule.setContent {
            ProviderAuthenticationContent(
                supportsCodexOAuth = true,
                codexOAuthFeatureEnabled = true,
                authMode = authMode,
                baseUrl = baseUrl,
                apiKey = apiKey,
                apiKeyVisible = false,
                loginState = loginState,
                launcher = launcher,
                onAuthModeChange = {},
                onBaseUrlChange = {},
                onApiKeyChange = {},
                onToggleApiKeyVisibility = {},
                onBeginLogin = onBeginLogin,
                onCancelLogin = onCancelLogin,
                onLogout = onLogout,
            )
        }
    }

    private class RecordingLauncher : CodexVerificationPageLauncher {
        var openCount = 0

        override fun open(): Boolean {
            openCount++
            return true
        }
    }
}
