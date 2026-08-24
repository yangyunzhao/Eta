# Codex OAuth 设备码登录设计

## 1. 目标

在保留 Eta 现有 API Key Provider 行为的前提下，为内置 OpenAI Provider 增加 `CODEX_OAUTH` 认证方式，使用户可以通过 ChatGPT/Codex 设备码登录，并让 Eta 的现有多轮 Agent Loop 使用 Codex 订阅额度。

本设计的硬约束如下：

- 只新增 `CODEX_OAUTH`；未设置认证模式时仍执行现有 API Key 路径，API Key 的输入、存储、模型配置和调用行为不变。
- 只允许设备码登录。浏览器不回调 Eta，不注册自定义 URI，不启动本地 HTTP 回调服务器。
- Codex OAuth 只用于固定的 Codex Responses 后端，不允许用户修改 OAuth 请求的 Base URL。
- Eta 继续负责多轮对话、工具执行、权限控制和 transcript；Codex OAuth 只增加一种模型认证与传输路径。
- OAuth access token、refresh token 和 ID token 不进入 Room Provider 表、RemotePreferences、Binder Bundle、日志或崩溃信息。
- 现有 API Key Provider 必须保持兼容，数据库升级不能改变既有 Provider 的认证行为。

> **实施状态（2026-08-24）：** `v2.6.0.znmlr.1`（`versionCode 26001`）已发布，包括核心认证、Codex Responses 传输、固定 OAuth 模型目录、Provider 设备码设置页和编译期开关。真机已验证设备码登录、最小问答、一次本地只读工具回合、进程重启凭据恢复、7 个模型拉取及切换调用。当前本地 `main` 已合入上游 `v2.6.2`（`bd4a14c`），构建基线为候选 `2.6.2.znmlr.1` / `26201`；本次合并同时将 Room schema 升至 v16，以兼容已发布下游 OAuth v15 与上游模型能力覆盖 v15 的两种历史数据库。发布前最新官方 Codex CLI 已为 `rust-v0.149.1`（peeled `ff29a44391deccde0aba0f8390337d7f3c319ea4`）；设备码、刷新、Responses 与普通 OAuth 模型目录契约无必须迁移的变化，故 `CODEX_PROTOCOL_COMPAT_VERSION` 继续表示完整验证过的 `0.147.0` 基线，不机械改名。AndroidKeyStore instrumentation 和注销后的敏感日志计数尚未完成，作为已知验证缺口保留。

下游 CI/发布防护已经实现并通过代码审查：在 `main`、`v*.znmlr.*` tag 和手动触发时运行，构建前执行 unit test 与 lint，精确校验 tag、APK 和版本 metadata，并使用版本化资产名。该流程不会自动创建 tag、GitHub Release 或执行 push，也不会把未通过的门禁或 Task 9 人工验收视为成功。

## 2. 非目标

- 不把 Codex CLI、SDK 或 App Server 嵌入 Android。
- 不实现 OAuth 浏览器回调、Authorization Code + redirect URI 登录或 WebView 登录。
- 不新增通用 OAuth Provider 框架。
- 不代理 OpenAI Platform API，也不把 Codex OAuth token 当作 Platform API Key。
- 不改变 Eta 的 Agent Loop、工具定义、会话存储格式和 Root/LSPosed 权限模型。
- 不提供可配置的通用 OAuth 模型代理；内置 OpenAI 的 Codex OAuth 模式只读取固定 Codex 模型目录，API Key、Anthropic 和自定义 Provider 继续使用原有模型拉取路径。

## 3. 方案比较

### 方案 A：在现有 `OpenAiResponsesProvider` 中增加 OAuth 分支

改动文件少，但 API Key 与 Codex 私有协议会共享请求构建、Header 和错误处理。两条协议的端点、Content-Type、认证刷新及账号 Header 不同，长期容易形成难以测试的条件分支。

### 方案 B：独立 `CodexResponsesProvider`，复用 Responses 消息转换（采用）

新增独立 Provider Client 和独立 OAuth 子系统，只复用 Eta 已验证的消息、工具和 SSE 解析语义。协议耦合值集中管理，现有 API Key 路径不变，Codex 后端发生变化时也能单独停用或修复。

### 方案 C：电脑或服务器运行 Codex App Server，Eta 通过网络连接

官方程序化边界更稳定，但要求常驻伴随服务，违背手机独立运行目标，也扩大部署和网络安全范围。本阶段不采用。

## 4. 总体架构

```text
Provider 设置页
    |
    | 选择 CODEX_OAUTH
    v
CodexDeviceLoginCoordinator
    |-- 请求 device_auth_id + user_code
    |-- 展示验证码和 https://auth.openai.com/codex/device
    |-- 按服务端 interval 轮询
    |-- 用 authorization_code + PKCE 换取 token
    v
CodexCredentialStore
    |-- AndroidKeyStore AES/GCM 加密
    |-- 应用私有存储只保存密文、IV 和非敏感元数据
    v
CodexOAuthManager
    |-- 有效期检查
    |-- refresh token 单飞刷新
    |-- 注销和失效清理
    v
CodexResponsesProvider
    |-- 固定 Codex Responses HTTPS 端点和 Header
    |-- 复用 ResponsesRequestBuilder 的历史/工具转换
    |-- 复用或抽取现有 SSE 事件解析器
    v
现有 AgentLoop -> 本地工具执行 -> 下一轮完整历史
```

设备码流程中，浏览器只访问 OpenAI 验证页面。Eta 通过轮询得知授权结果；浏览器不会导航回 Eta。Token exchange 请求中即使协议要求携带固定 `redirect_uri` 参数，它也只是服务端校验字段，不构成应用回调。

## 5. Provider 数据模型

在 `ProviderSetting` 和 `ProviderEntity` 增加默认空字符串的 `authMode` 字段，只定义一个新模式：

```kotlin
internal object ProviderAuthModes {
    const val CODEX_OAUTH = "codex_oauth"
}
```

`authMode` 默认值和数据库迁移默认值均为 `""`。空值继续表示 Eta 现有 API Key 行为；不增加 `API_KEY` 常量，不重写既有认证逻辑。只有用户明确选择设备码登录时才写入 `codex_oauth`。

运行时 `ModelConfig` 只携带：

- `authMode`；
- `providerId`，同时作为 OAuth credential lookup key；
- 现有非敏感模型配置。

当 `authMode == CODEX_OAUTH` 时：

- `apiKey` 可以为空；
- `baseUrl` 不参与 Codex 请求寻址；
- `openAiEndpointMode` 必须为 `responses`；
- Runtime 进程通过 `providerId` 从加密凭据仓库获取 token；
- `AgentRuntimeWire` 不新增任何 token 字段。

## 6. 设备码登录协议

所有服务耦合常量集中在 `CodexDeviceAuthDefaults`，避免散落在 UI 和 Provider 中。第一版采用以下已验证的 Codex 设备授权流程：

1. `POST https://auth.openai.com/api/accounts/deviceauth/usercode`，请求设备授权 ID、用户码和轮询间隔。
2. UI 展示用户码，并让用户在系统浏览器打开 `https://auth.openai.com/codex/device`。
3. Eta 按服务端返回的 interval 轮询 `POST https://auth.openai.com/api/accounts/deviceauth/token`。
4. 授权完成后，使用返回的 authorization code、code verifier 和 code challenge 请求 `POST https://auth.openai.com/oauth/token`。
5. 校验 access token、refresh token、ID token、有效期和账号 ID后，一次性提交加密凭据。

客户端标识固定为 Codex 设备授权使用的公开 client ID `app_EMoamEEZ73f0CkXaXp7hrann`。它不是客户端秘密，但属于服务耦合常量，必须由协议契约测试固定。

协调器负责以下行为：

- 整个登录最长 15 分钟；
- 严格服从服务端 interval，不做高频轮询；
- 页面退出、用户取消或生命周期销毁时取消 OkHttp Call 和轮询协程；
- 403/404 pending 只表示继续等待；其他 HTTP 状态转换为稳定错误枚举；
- user code、device auth ID、authorization code 和 PKCE 材料只保存在内存中；
- 只有完整 token 集合通过校验后才覆盖已有凭据，失败登录不会破坏已有可用会话。

## 7. 凭据安全

新增 `CodexCredentialStore`，使用 Android 平台 `AndroidKeyStore` 生成不可导出的 AES-256/GCM 密钥：

- Key alias：`eta_codex_oauth_v1`；
- 每次写入生成随机 12-byte IV；
- AAD 绑定应用包名、provider ID 和存储格式版本；
- 应用私有 SharedPreferences 只保存 Base64 编码的 `version + IV + ciphertext`；
- 解密或认证标签校验失败时删除损坏密文并要求重新登录；
- 日志只能输出错误类别、HTTP 状态和阶段，不输出响应体、token、JWT claim、user code 或账号 ID。

Root/LSPosed 环境无法提供绝对的进程机密性，因此 UI 必须提示：设备被恶意 Root 模块控制时，任何登录态都可能被运行时窃取。该风险不能通过本应用加密完全消除。

## 8. Token 生命周期

`CodexOAuthManager` 提供以下边界：

```kotlin
internal interface CodexCredentialProvider {
    fun requireValidCredential(providerId: String): CodexOAuthCredential
}

internal data class CodexOAuthCredential(
    val accessToken: String,
    val refreshToken: String,
    val idToken: String,
    val accountId: String?,
    val expiresAtEpochMillis: Long,
)
```

- access token 在到期前 60 秒即视为需要刷新；
- 同一 provider 的并发请求只允许一次 refresh，其他调用等待同一结果；
- refresh 固定向 `https://auth.openai.com/oauth/token` 发送 JSON `client_id`、`grant_type=refresh_token` 和当前 `refresh_token`；
- refresh 响应中的 access token、refresh token 和 ID token 都允许缺省，只轮换服务端实际返回的字段，其余字段保留当前值；成功后原子保存合并后的完整凭据；
- refresh 返回无效授权时清除凭据并产生 `LoginRequired`；
- 短暂网络失败不清除 refresh token，允许下一次请求重试；
- 用户退出登录时先清除持久化密文，再更新 UI 状态。

## 9. Codex Responses 传输

`ProviderClientFactory` 只在 `authMode == CODEX_OAUTH` 时选择 `CodexResponsesProvider`；`authMode` 为空时继续执行现有 API Key 分流，其他分支保持不变。

Codex Provider 的固定行为：

- 请求地址固定为 `https://chatgpt.com/backend-api/codex/responses`；
- 强制 HTTPS、`stream=true`、`store=false`；
- `Content-Type` 使用精确的 `application/json`；
- 发送 bearer access token、Codex 客户端版本、`originator` 和稳定的 Codex User-Agent；不发送已经过时的 `OpenAI-Beta: responses=experimental`；
- 有账号 ID 时发送 `Chatgpt-Account-Id`；
- 不合并用户自定义 Authorization、Host、Originator、Account ID 或 Base URL；
- 使用 Eta 当前选中的模型字符串；
- 保留完整 `input` 历史、函数工具定义、`function_call` 和 `function_call_output`；
- 请求 reasoning 时包含可跨轮返回的加密 reasoning 内容；
- SSE 仍转换为 Eta 现有 `ProviderEvent`，Agent Loop 无需知道认证差异。

Codex 后端不是文档化的通用 OpenAI Platform API。实际实现按职责拆分：`CodexResponsesProvider` 固定传输端点、认证与 Header，`ResponsesRequestBuilder` 强制 Codex 特殊 body 字段，`ResponsesSseParser` 共享无认证、无端点知识的 SSE 解析。MockWebServer 契约测试覆盖三者组合后的请求和响应行为；协议失配按稳定、脱敏的 `protocol_failure` 类别上报，不把原始响应体或凭据写入异常正文，同时不影响 API Key Provider。

发布候选以官方 Codex CLI `rust-v0.147.0` 为已验证协议基线：Responses 请求不伪造官方不存在的 `version` Header，模型目录请求使用同一基线的 `client_version=0.147.0`，并精确支持 `ultra` 推理档位。后续出包必须重新核对当时最新稳定 CLI，不能仅修改版本字符串。

## 10. UI 设计

内置 OpenAI Provider 详情页增加“认证方式”选择：

- 默认空模式：继续显示并执行当前 API Key 能力；
- `CODEX_OAUTH`：隐藏 API Key 输入框和可编辑 Base URL，显示登录状态卡片。

设备码卡片状态：

- 未登录：显示“使用 Codex 设备码登录”；
- 等待确认：显示验证码，通过按钮打开固定验证页，并可取消；验证码不写入系统剪贴板，避免离开 Eta 进程内存或跨 Binder 传输。
- 已登录：显示“已连接 Codex”，可显示脱敏账号标签和“退出登录”；
- 已失效：显示原因类别和“重新登录”。

切换认证方式只改变当前 Provider 的选中模式，不删除另一种方式的现有 API Key。退出 Codex 登录只删除 OAuth 凭据，不清空 API Key。

## 11. 错误处理

稳定错误类型至少包括：

- `DeviceAuthorizationUnsupported`；
- `DeviceAuthorizationExpired`；
- `DeviceAuthorizationCancelled`；
- `DeviceAuthorizationNetworkFailure`；
- `DeviceAuthorizationProtocolFailure`；
- `LoginRequired`；
- `TokenRefreshRejected`；
- `CodexQuotaExceeded`；
- `CodexRateLimited`；
- `CodexProtocolChanged`。

401 先触发一次受控刷新并重试一次；第二次 401 转为重新登录。429 根据响应信息区分额度窗口与短期速率限制，但不把原始响应体写入日志。其他非 2xx 响应沿用长度受限且脱敏的用户错误展示。

## 12. 测试策略

### JVM 单元测试

- 设备码三个网络阶段的请求、响应分类、pending、取消和总超时；
- 登录期间临时材料不进入持久化；
- AES/GCM round trip、随机 IV、AAD 隔离、密文损坏和注销；
- token 提前刷新、并发 single-flight、刷新拒绝和短暂失败；
- Provider Factory 分流，API Key 默认行为不变；
- Codex 请求 URL、Header、Content-Type、body、完整历史和工具结果；
- 401 单次刷新重试、429 分类、SSE 文本/推理/工具事件。

### Room 迁移测试

- 从数据库 14 升级到 15；
- 所有既有 Provider 的 `auth_mode` 都是空字符串，因而继续走原有 API Key 路径；
- Provider、Model 和选择状态没有丢失。

### Android/UI 测试

- API Key 与 Codex OAuth 界面切换；
- 设备码不进入剪贴板/Binder、浏览器 Intent、取消和旋转恢复；
- 已登录、已失效和退出登录状态；
- UI 语义节点中不暴露 token。

### 手工验收

- 使用专用测试账号完成真实设备码登录；
- 发起普通对话；
- 完成至少一次本地工具调用和下一轮回答；
- 杀死并重启 Eta 后验证登录态刷新；
- 退出登录后验证请求被阻止；
- 确认 OpenAI API Key Provider 仍能在 MockWebServer 下工作，不使用付费真实 API。

## 13. 发布与回退

- 下游持续 fetch `Mangi-11/Eta` 上游更新；没有下游分叉时允许 fast-forward，已经产生下游分叉时使用普通 merge 保留双方历史，不对已发布的下游历史执行 rebase、reset 或强制推送；每次同步后重新验证 OAuth 与原 API Key 路径；
- build、Git tag、GitHub Release 标题和发布资产统一使用 `v<上游版本>.znmlr.<下游序号>`，例如上游 `v2.6.0` 的首个下游候选为 `v2.6.0.znmlr.1`；同一上游版本递增序号，上游版本变化时重置为 `1`；
- 上游版本基线必须来自已验证的 upstream release tag 及其 peeled commit，不能从任意分支名、README 或旧 `versionName` 推断；
- 设置项标记“实验性”，说明它消耗 Codex 共享额度而非 Platform API 额度，并非无限免费；
- 使用编译期功能开关 `BuildConfig.CODEX_OAUTH_ENABLED`，下游构建默认启用，协议失配时可以只关闭新增入口；默认与关闭开关两种 Debug 构建已经通过，但这不替代真实设备验收；
- OAuth Provider 失败不得自动降级到 API Key，以免产生意外 API 账单；
- 数据库迁移只增加有默认值的非敏感字段；开关关闭后的目标行为是隐藏 OAuth 入口并 fail-closed，不自动选择 OAuth、不回退 API Key、不删除既有 `auth_mode` 字段或 OAuth 密文，已有 API Key Provider 仍可正常工作；
- 发布前重新核对 Codex 设备授权端点、client ID、Header 和请求体契约。

## 14. 完成标准

- 用户无需填写 API Key 即可通过设备码登录并完成 Eta 多轮工具调用；
- 浏览器不会回调 Eta，应用没有 OAuth deep link 或本地回调监听器；
- Codex OAuth token 不出现在数据库 Provider 表、RemotePreferences、Binder、日志和测试快照中；
- 现有 API Key、Anthropic 和自定义 Provider 测试全部保持通过；
- 设备码协议与 Codex Responses 协议均有离线契约测试；
- 功能可以独立关闭，关闭后不影响 Eta 原有能力。
