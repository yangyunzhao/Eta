package fuck.andes.ui.pages.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import fuck.andes.data.auth.CodexAuthFailure
import fuck.andes.data.auth.CodexLoginState
import fuck.andes.data.model.CodexOAuthFeaturePolicy
import fuck.andes.data.model.ProviderAuthModes
import fuck.andes.data.model.ProviderSetting
import fuck.andes.ui.components.StatusError
import fuck.andes.ui.components.StatusSuccess
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ProviderAuthenticationContent(
    supportsCodexOAuth: Boolean,
    codexOAuthFeatureEnabled: Boolean = CodexOAuthFeaturePolicy.isEnabled,
    authMode: String,
    baseUrl: String,
    apiKey: String,
    apiKeyVisible: Boolean,
    loginState: CodexLoginState,
    launcher: CodexVerificationPageLauncher,
    onAuthModeChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onToggleApiKeyVisibility: () -> Unit,
    onBeginLogin: () -> Unit,
    onCancelLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    val codexMode = supportsCodexOAuth && authMode == ProviderAuthModes.CODEX_OAUTH
    val disabledCodexMode =
        !codexOAuthFeatureEnabled && authMode == ProviderAuthModes.CODEX_OAUTH
    if (supportsCodexOAuth) {
        WindowSpinnerPreference(
            items = listOf(
                DropdownItem(text = "API Key"),
                DropdownItem(text = "Codex 设备码"),
            ),
            selectedIndex = if (codexMode) 1 else 0,
            title = "认证方式",
            summary = if (codexMode) "使用 Codex 订阅共享额度" else "使用现有 API Key 配置",
            onSelectedIndexChange = { selectedIndex ->
                onAuthModeChange(if (selectedIndex == 1) ProviderAuthModes.CODEX_OAUTH else "")
            },
        )
    }

    if (disabledCodexMode) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = "此构建已关闭 Codex OAuth", color = StatusError)
            Text("现有认证配置保持不变；切换后保存才会改用 API Key。")
            TextButton(
                text = "切换到 API Key",
                onClick = { onAuthModeChange("") },
            )
        }
    } else if (codexMode) {
        CodexDeviceLoginContent(
            state = loginState,
            launcher = launcher,
            onBeginLogin = onBeginLogin,
            onCancelLogin = onCancelLogin,
            onLogout = onLogout,
        )
    } else {
        Column(modifier = Modifier.padding(16.dp)) {
            TextField(
                value = baseUrl,
                onValueChange = onBaseUrlChange,
                label = "Base URL",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = "API Key",
                singleLine = true,
                visualTransformation = if (apiKeyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = onToggleApiKeyVisibility) {
                        Icon(
                            painter = painterResource(
                                if (apiKeyVisible) {
                                    LucideR.drawable.lucide_ic_eye
                                } else {
                                    LucideR.drawable.lucide_ic_eye_off
                                },
                            ),
                            contentDescription = if (apiKeyVisible) "隐藏" else "显示",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun CodexDeviceLoginContent(
    state: CodexLoginState,
    launcher: CodexVerificationPageLauncher,
    onBeginLogin: () -> Unit,
    onCancelLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    var browserLaunchFailed by remember(state) { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (state) {
            CodexLoginState.Idle -> {
                Text("尚未登录 Codex")
                TextButton(text = "使用 Codex 设备码登录", onClick = onBeginLogin)
            }

            is CodexLoginState.AwaitingUser -> {
                Text("在系统浏览器中输入验证码")
                Text(
                    text = state.userCode,
                    style = MiuixTheme.textStyles.title1,
                )
                TextButton(
                    text = "打开系统浏览器",
                    onClick = { browserLaunchFailed = !launcher.open() },
                )
                if (browserLaunchFailed) {
                    Text(
                        text = "无法打开系统浏览器，请检查是否安装了浏览器",
                        color = StatusError,
                    )
                }
                TextButton(text = "取消登录", onClick = onCancelLogin)
            }

            is CodexLoginState.Authorized -> {
                Text(text = "已登录 Codex", color = StatusSuccess)
                TextButton(text = "退出 Codex 登录", onClick = onLogout)
            }

            is CodexLoginState.Failed -> {
                Text(text = state.reason.safeUserMessage(), color = StatusError)
                TextButton(text = "重新登录", onClick = onBeginLogin)
            }
        }
    }
}

internal fun supportsCodexOAuth(
    provider: ProviderSetting,
    codexOAuthEnabled: Boolean = CodexOAuthFeaturePolicy.isEnabled,
): Boolean = CodexOAuthFeaturePolicy.supportsProvider(provider, codexOAuthEnabled)

internal fun effectiveProviderAuthMode(
    provider: ProviderSetting,
    requestedAuthMode: String,
    codexOAuthEnabled: Boolean = CodexOAuthFeaturePolicy.isEnabled,
): String = CodexOAuthFeaturePolicy.authModeForSave(
    provider = provider,
    requestedAuthMode = requestedAuthMode,
    enabled = codexOAuthEnabled,
)

private fun CodexAuthFailure.safeUserMessage(): String = when (this) {
    CodexAuthFailure.NOT_AUTHENTICATED -> "尚未登录 Codex"
    CodexAuthFailure.TIMEOUT -> "登录已超时，请重试"
    CodexAuthFailure.CANCELLED -> "登录已取消"
    CodexAuthFailure.UNSUPPORTED -> "当前 Codex 登录协议暂不可用"
    CodexAuthFailure.EXPIRED -> "验证码已过期，请重试"
    CodexAuthFailure.NETWORK_FAILURE,
    CodexAuthFailure.TRANSIENT_FAILURE -> "网络暂时不可用，请稍后重试"
    CodexAuthFailure.REAUTHENTICATION_REQUIRED -> "登录已失效，请重新登录"
    CodexAuthFailure.PROTOCOL_FAILURE,
    CodexAuthFailure.HTTP_FAILURE -> "Codex 登录服务返回异常，请稍后重试"
    CodexAuthFailure.CREDENTIAL_STORE_FAILURE -> "无法安全保存登录状态，请重试"
}
