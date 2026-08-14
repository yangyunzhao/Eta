# Codex OAuth Device Login Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Eta 内置 OpenAI Provider 增加只使用设备码的 `CODEX_OAUTH` 认证，并复用现有 Responses 多轮工具循环。

**Architecture:** 保留现有 API Key Provider 及其空认证模式语义，只新增 `CODEX_OAUTH` 标记，并实现独立的设备码协议、Keystore 凭据仓库、Token 生命周期管理和 `CodexResponsesProvider`。运行时配置只传 `authMode + providerId`，OAuth token 仅在 Eta Runtime 进程内解密和使用。

**Tech Stack:** Kotlin、Jetpack Compose、Room 14→15、AndroidKeyStore AES/GCM、OkHttp、kotlinx.serialization、MockWebServer、JUnit/Robolectric。

## Global Constraints

- 只新增 `CODEX_OAUTH`；`authMode` 为空时继续执行现有 API Key 功能，不新增 `API_KEY` 类型，不重写或删除原能力。
- 登录必须采用设备码轮询；禁止 deep link、浏览器回调、WebView 和本地 HTTP 回调服务。
- OAuth token 不得进入 Room Provider 表、RemotePreferences、Binder Bundle、日志或异常正文。
- OAuth 请求固定使用 HTTPS Codex 端点，不接受自定义 Base URL 或认证 Header。
- OAuth 失败不得自动回退到 API Key。
- 真实 Codex 登录只用于最终人工验收；自动测试不得调用付费或真实 AI 服务。

## 执行状态总表

> 状态只使用“已完成 / 部分完成 / 进行中 / 未开始 / 待人工”。本表与下文复选框同步维护；只有当一个 Step 的全部要求均有证据时，才能将对应复选框改为 `[x]`。

| Task / Step | 状态 | 证据或待办 |
| --- | --- | --- |
| Task 1 / Step 1 | 已完成 | 已补充 14→15 迁移和旧 Provider 保留 API Key 的失败测试。 |
| Task 1 / Step 2 | 已完成 | RED 证据为 `no such column: auth_mode`，已记录于 Task 1 报告。 |
| Task 1 / Step 3 | 已完成 | `CODEX_OAUTH`、`authMode`、Room 15 和 `MIGRATION_14_15` 已整合。 |
| Task 1 / Step 4 | 已完成 | 迁移与 Repository 定向测试通过，独立审查 APPROVED。 |
| Task 1 / Step 5 | 已完成 | Provider 认证类型与测试隔离检查点已提交并整合，独立复审 APPROVED。 |
| Task 2 / Step 1 | 已完成 | MockWebServer / fake Call 测试已覆盖三阶段、异常输入和取消。 |
| Task 2 / Step 2 | 已完成 | 缺失协议类型时的 RED 编译失败已记录。 |
| Task 2 / Step 3 | 已完成 | 三个单次 HTTP 操作、固定 HTTPS 端点和取消边界已实现。 |
| Task 2 / Step 4 | 已完成 | `CodexDeviceAuthClientTest` 12/12 通过，独立复审 APPROVED。 |
| Task 2 / Step 5 | 已完成 | 设备码协议与协议加固检查点已提交并整合，独立复审 APPROVED。 |
| Task 3 / Step 1 | 已完成 | JVM 测试已覆盖 round trip、随机密文、AAD 隔离、篡改清理和脱敏。 |
| Task 3 / Step 2 | 已完成 | 仓库类型尚不存在时的 RED 编译失败已记录。 |
| Task 3 / Step 3 | 已完成 | AndroidKeyStore AES/GCM、随机 IV、128-bit tag 和 provider AAD 已实现。 |
| Task 3 / Step 4 | 已完成 | 真实 AndroidKeyStore instrumentation 测试源码已增加，已编译并打包测试 APK。 |
| Task 3 / Step 5 | 部分完成 | JVM 定向测试 8/8 通过；无连接设备，`connectedDebugAndroidTest` 未运行，真实 AndroidKeyStore 行为尚待验证。 |
| Task 3 / Step 6 | 已完成 | 加密凭据与清理加固检查点已提交并整合，独立安全复审 APPROVED。 |
| Task 4 / Step 1 | 已完成 | 已新增状态机、15 分钟超时、取消、刷新窗口、并发 single-flight、401、rotation、失败分类和脱敏测试。 |
| Task 4 / Step 2 | 已完成 | Manager 与 refresh 契约尚不存在，定向测试在编译阶段按预期失败，RED 证据已记录。 |
| Task 4 / Step 3 | 已完成 | 已统一登录 ownership、凭据保存和状态写入的线性化临界区，并实现状态机、single-flight 与 compare-and-clear。 |
| Task 4 / Step 4 | 已完成 | Fresh 联合定向 47/47；独立二审 APPROVED；全量 610 项仍仅同一 8 个基线失败。 |
| Task 4 / Step 5 | 已完成 | 独立二审 APPROVED，登录生命周期代码已用中文提交为独立检查点。 |
| Task 5 / Step 1 | 已完成 | 已增加 Runtime 配置验证与 Wire 脱敏失败测试；Factory 分流已调整至 Task 6。 |
| Task 5 / Step 2 | 已完成 | RED 证据包括缺少 `authMode` 以及 Wire 误传 OAuth API Key。 |
| Task 5 / Step 3 | 已完成 | `ModelConfig` 条件验证、Runtime 投影和 Binder 二次清空已实现。 |
| Task 5 / Step 4 | 已完成 | Runtime/Wire 定向测试 26/26 通过，旧 API Key 定向回归通过。 |
| Task 5 / Step 5 | 已完成 | Runtime 安全传输检查点已提交并整合，独立审查 APPROVED。 |
| Task 6 / Step 1 | 已完成 | 已新增 Factory 分流、固定 URL/headers/body、历史与工具输入的失败测试。 |
| Task 6 / Step 2 | 已完成 | 已新增 SSE、401 单次重试/二次清理、429、协议脱敏、重定向和取消测试。 |
| Task 6 / Step 3 | 已完成 | 定向测试仅因 Codex Provider、稳定错误类型和显式凭据参数尚不存在而按预期 RED。 |
| Task 6 / Step 4 | 已完成 | 已加固账号 Header、流内取消、二次 401 CAS、固定生产 trust domain 和 429 安全分类。 |
| Task 6 / Step 5 | 已完成 | 强制 fresh Codex 10、API Key Responses 6、Factory 4，共 20/20 通过；独立复审 APPROVED。 |
| Task 6 / Step 6 | 已完成 | 独立复审 APPROVED，Codex Responses 与 Factory 接入已用中文提交为独立检查点。 |
| Task 7 / Step 1 | 已完成 | 已新增 Compose/Robolectric 测试，覆盖认证字段、登录状态、UI 动作、脱敏和固定浏览器 Intent。 |
| Task 7 / Step 2 | 已完成 | 定向测试仅因 launcher 与认证 UI 尚不存在而按预期 RED，Compose 测试依赖已正常解析。 |
| Task 7 / Step 3 | 已完成 | 已实现 Provider 级状态刷新、认证模式规范化和不改写持久配置的 OAuth Responses 运行时派生。 |
| Task 7 / Step 4 | 已完成 | 强制 fresh UI/Manager/Runtime/Repository/API Key 六类 58/58 通过；独立复审 APPROVED。 |
| Task 7 / Step 5 | 已完成 | 独立复审 APPROVED，设备码登录 UI 已用中文提交为独立检查点。 |
| Task 8 / Step 1 | 已完成 | 默认/关闭生产链各 23/23、扩展 85/85、默认构建通过；独立复审 APPROVED。 |
| Task 8 / Step 2 | 未开始 | 待功能完成后按实际行为更新用户文档。 |
| Task 8 / Step 3 | 未开始 | 待运行格式检查和完整单元测试。 |
| Task 8 / Step 4 | 未开始 | 待构建 Debug APK。 |
| Task 8 / Step 5 | 未开始 | 待提交文档和自动回归检查点。 |
| Task 9 / Step 1 | 待人工 | 待 Tasks 1–8 门禁通过后，由人工安装 APK 检查认证界面。 |
| Task 9 / Step 2 | 待人工 | 需在系统浏览器中完成一次真实设备码授权。 |
| Task 9 / Step 3 | 待人工 | 需明确同意后消耗最少量 Codex 共享额度验证多轮 Agent。 |
| Task 9 / Step 4 | 待人工 | 需在真实设备上验证进程重启后的凭据恢复。 |
| Task 9 / Step 5 | 待人工 | 需人工确认原 API Key 界面和配置未受影响。 |
| Task 9 / Step 6 | 待人工 | 需注销并以只计数、不输出匹配行的方式检查敏感日志。 |
| Task 9 / Step 7 | 待人工 | 需记录脱敏验收结果，任一关键项失败则阻止发布。 |

> 注：完整 JVM 基线目前有 8 个非 OAuth 失败；其中 6 个已确认为 Windows/Robolectric 与 Android/POSIX 环境差异，另 2 个为既存失败但仍待单独诊断。这些失败不计为 Codex OAuth 新功能通过，也不得用于跳过 Task 8 的整合门禁。

---

## 文件结构

### 新建

- `app/src/main/kotlin/fuck/andes/data/auth/CodexDeviceAuthModels.kt`：设备码协议模型和稳定错误类型。
- `app/src/main/kotlin/fuck/andes/data/auth/CodexDeviceAuthClient.kt`：三个设备码 HTTP 操作，不负责循环和持久化。
- `app/src/main/kotlin/fuck/andes/data/auth/CodexCredentialStore.kt`：凭据接口及 AndroidKeyStore AES/GCM 实现。
- `app/src/main/kotlin/fuck/andes/data/auth/CodexOAuthManager.kt`：登录协调、刷新 single-flight、注销和状态流。
- `app/src/main/kotlin/fuck/andes/agent/model/CodexResponsesProvider.kt`：固定 Codex 后端的 Responses Client。
- `app/src/main/kotlin/fuck/andes/ui/pages/providers/CodexDeviceLoginContent.kt`：设备码登录状态 UI。
- 对应的 JVM、迁移和 UI 测试文件。

### 修改

- `app/src/main/kotlin/fuck/andes/data/model/Provider.kt`
- `app/src/main/kotlin/fuck/andes/data/db/ProviderEntities.kt`
- `app/src/main/kotlin/fuck/andes/data/db/FuckAndesDatabase.kt`
- `app/src/main/kotlin/fuck/andes/data/repository/RuntimeConfigRepository.kt`
- `app/src/main/kotlin/fuck/andes/agent/model/AgentModelClient.kt`
- `app/src/main/kotlin/fuck/andes/agent/model/ProviderClientFactory.kt`
- `app/src/main/kotlin/fuck/andes/agent/model/ResponsesRequestBuilder.kt`
- `app/src/main/kotlin/fuck/andes/ui/pages/providers/ModelProviderDetailScreen.kt`
- `app/src/main/kotlin/fuck/andes/FuckAndesApp.kt`
- `app/build.gradle.kts`

---

### Task 1: Provider 认证标识与 Room 迁移

**Files:**
- Modify: `app/src/main/kotlin/fuck/andes/data/model/Provider.kt`
- Modify: `app/src/main/kotlin/fuck/andes/data/db/ProviderEntities.kt`
- Modify: `app/src/main/kotlin/fuck/andes/data/db/FuckAndesDatabase.kt`
- Modify: `app/src/test/java/fuck/andes/data/db/FuckAndesDatabaseMigrationTest.kt`
- Modify: `app/src/test/java/fuck/andes/data/repository/ProviderRepositoryTest.kt`

**Interfaces:**
- Produces: `ProviderAuthModes.CODEX_OAUTH`、`ProviderSetting.authMode: String = ""`。
- Produces: Room 版本 15 和 `MIGRATION_14_15`。

- [x] **Step 1: 写失败测试，证明旧数据库迁移后保持 API Key 行为**

```kotlin
assertEquals(15, FuckAndesDatabase::class.java.getAnnotation(Database::class.java).version)
assertEquals("", migratedProvider.getString(authModeColumn))
assertEquals("sk-existing", migratedProvider.getString(apiKeyColumn))
```

- [x] **Step 2: 运行迁移与 Repository 测试并确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests '*FuckAndesDatabaseMigrationTest' --tests '*ProviderRepositoryTest'`

Expected: FAIL，原因是数据库仍为 14 或不存在 `auth_mode`。

- [x] **Step 3: 增加认证常量、字段和迁移**

```kotlin
internal object ProviderAuthModes {
    const val CODEX_OAUTH = "codex_oauth"
}
```

`ProviderEntity.authMode` 映射到 `auth_mode`，默认空字符串；`MIGRATION_14_15` 执行：

```sql
ALTER TABLE model_providers
ADD COLUMN auth_mode TEXT NOT NULL DEFAULT ''
```

- [x] **Step 4: 运行测试并确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests '*FuckAndesDatabaseMigrationTest' --tests '*ProviderRepositoryTest'`

Expected: PASS；既有 Provider 的 API Key 和模型未变化。

- [x] **Step 5: 提交独立检查点**

```bash
git add app/src/main/kotlin/fuck/andes/data/model/Provider.kt app/src/main/kotlin/fuck/andes/data/db/ProviderEntities.kt app/src/main/kotlin/fuck/andes/data/db/FuckAndesDatabase.kt app/src/test/java/fuck/andes/data/db/FuckAndesDatabaseMigrationTest.kt app/src/test/java/fuck/andes/data/repository/ProviderRepositoryTest.kt
git commit -m "feat(provider): 新增 Codex OAuth 认证类型"
```

### Task 2: 设备码协议 Client

**Files:**
- Create: `app/src/main/kotlin/fuck/andes/data/auth/CodexDeviceAuthModels.kt`
- Create: `app/src/main/kotlin/fuck/andes/data/auth/CodexDeviceAuthClient.kt`
- Create: `app/src/test/java/fuck/andes/data/auth/CodexDeviceAuthClientTest.kt`

**Interfaces:**
- Produces: `CodexDeviceAuthProtocol.requestAuthorization()`、`pollOnce()`、`exchangeToken()`。
- Produces: `CodexDeviceAuthorization`、`CodexAuthorizationCode`、`CodexTokenSet` 和不含响应正文的错误枚举。

- [x] **Step 1: 使用 MockWebServer 写三个阶段的失败测试**

覆盖：初始 JSON、403/404 pending、授权成功、token exchange、缺字段、非 JSON、取消 Call。断言初始请求只发送公开 client ID：

```kotlin
assertEquals("app_EMoamEEZ73f0CkXaXp7hrann", requestJson.getString("client_id"))
assertEquals("/api/accounts/deviceauth/usercode", request.path)
```

- [x] **Step 2: 运行定向测试并确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests '*CodexDeviceAuthClientTest'`

Expected: FAIL，设备码类型和 Client 尚不存在。

- [x] **Step 3: 实现单次操作边界**

```kotlin
internal interface CodexDeviceAuthProtocol {
    suspend fun requestAuthorization(): CodexDeviceAuthorizationResult
    suspend fun pollOnce(auth: CodexDeviceAuthorization): CodexDevicePollResult
    suspend fun exchangeToken(code: CodexAuthorizationCode): CodexTokenResult
}
```

生产端点固定为 `auth.openai.com`；每个方法只执行一次 HTTP 操作，循环、延迟、浏览器和存储均不放入 Client。

- [x] **Step 4: 运行协议测试并确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests '*CodexDeviceAuthClientTest'`

Expected: PASS；测试网络请求全部指向 MockWebServer。

- [x] **Step 5: 提交独立检查点**

```bash
git add app/src/main/kotlin/fuck/andes/data/auth/CodexDeviceAuthModels.kt app/src/main/kotlin/fuck/andes/data/auth/CodexDeviceAuthClient.kt app/src/test/java/fuck/andes/data/auth/CodexDeviceAuthClientTest.kt
git commit -m "feat(auth): 新增 Codex 设备码授权协议"
```

### Task 3: Keystore 加密凭据仓库

**Files:**
- Create: `app/src/main/kotlin/fuck/andes/data/auth/CodexCredentialStore.kt`
- Create: `app/src/test/java/fuck/andes/data/auth/CodexCredentialStoreTest.kt`
- Create: `app/src/androidTest/java/fuck/andes/data/auth/CodexCredentialStoreInstrumentedTest.kt`

**Interfaces:**
- Produces: `CodexOAuthCredential`。
- Produces: `CodexCredentialStore.load(providerId)`、`save(providerId, credential)`、`clear(providerId)`。

- [x] **Step 1: 写凭据 round trip 和安全属性测试**

断言相同明文连续保存产生不同密文、错误 provider ID 无法解密、篡改密文后返回空并清理、序列化和 `toString()` 不出现 token。

- [x] **Step 2: 运行测试并确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests '*CodexCredentialStoreTest'`

Expected: FAIL，凭据仓库尚不存在。

- [x] **Step 3: 实现 AndroidKeyStore AES/GCM 存储**

```kotlin
internal interface CodexCredentialStore {
    fun load(providerId: String): CodexOAuthCredential?
    fun save(providerId: String, credential: CodexOAuthCredential)
    fun clear(providerId: String)
}
```

使用 alias `eta_codex_oauth_v1`、随机 12-byte IV、128-bit GCM tag，并用 `packageName|providerId|v1` 作为 AAD。测试使用注入式 `SecretKeyProvider`，不依赖真实设备 KeyStore。

- [x] **Step 4: 增加 AndroidKeyStore 自动化设备测试**

在 instrumentation test 中使用真实 `AndroidKeyStore` 完成保存、进程内重新加载、篡改密文 fail-closed 和注销清理；测试只使用固定的合成 token，禁止真实 OAuth 凭据。

- [ ] **Step 5: 运行凭据测试并确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests '*CodexCredentialStoreTest'`

Expected: PASS，测试输出和失败信息不包含测试 token。

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=fuck.andes.data.auth.CodexCredentialStoreInstrumentedTest`

Expected: PASS；真实 AndroidKeyStore round trip、密文损坏和注销清理均通过。

- [x] **Step 6: 提交独立检查点**

```bash
git add app/src/main/kotlin/fuck/andes/data/auth/CodexCredentialStore.kt app/src/test/java/fuck/andes/data/auth/CodexCredentialStoreTest.kt app/src/androidTest/java/fuck/andes/data/auth/CodexCredentialStoreInstrumentedTest.kt
git commit -m "feat(auth): 加密存储 Codex OAuth 凭据"
```

### Task 4: 登录协调与 Token 刷新

**Files:**
- Create: `app/src/main/kotlin/fuck/andes/data/auth/CodexOAuthManager.kt`
- Create: `app/src/test/java/fuck/andes/data/auth/CodexOAuthManagerTest.kt`
- Modify: `app/src/main/kotlin/fuck/andes/FuckAndesApp.kt`

**Interfaces:**
- Produces: `CodexLoginState: StateFlow<CodexLoginState>`。
- Produces: `beginDeviceLogin(providerId)`、`cancelLogin()`、`requireValidCredential(providerId)`、`logout(providerId)`。
- Consumes: Task 2 的协议 Client 和 Task 3 的凭据仓库。

- [x] **Step 1: 写状态机和并发刷新失败测试**

覆盖 `Idle -> AwaitingUser -> Authorized`、15 分钟超时、取消、失败不覆盖旧凭据、到期前 60 秒刷新、十个并发请求只刷新一次、invalid grant 清理、网络失败保留凭据。

- [x] **Step 2: 运行测试并确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests '*CodexOAuthManagerTest'`

Expected: FAIL，Manager 尚不存在。

- [x] **Step 3: 实现状态机和 single-flight**

```kotlin
internal sealed interface CodexLoginState {
    data object Idle : CodexLoginState
    data class AwaitingUser(val userCode: String, val verificationUrl: String) : CodexLoginState
    data class Authorized(val accountLabel: String?) : CodexLoginState
    data class Failed(val reason: CodexAuthFailure) : CodexLoginState
}
```

登录使用可取消协程；刷新使用每个 provider 独立的锁。`FuckAndesApp.onCreate()` 初始化生产 store 和 manager，token 不写入 RemotePreferences。

- [x] **Step 4: 运行测试并确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests '*CodexOAuthManagerTest'`

Expected: PASS；并发刷新计数严格为 1。

- [x] **Step 5: 提交独立检查点**

```bash
git add app/src/main/kotlin/fuck/andes/data/auth/CodexOAuthManager.kt app/src/test/java/fuck/andes/data/auth/CodexOAuthManagerTest.kt app/src/main/kotlin/fuck/andes/FuckAndesApp.kt
git commit -m "feat(auth): 管理 Codex 设备码登录生命周期"
```

### Task 5: Runtime 配置与安全传输

**Files:**
- Modify: `app/src/main/kotlin/fuck/andes/data/repository/RuntimeConfigRepository.kt`
- Modify: `app/src/main/kotlin/fuck/andes/agent/model/AgentModelClient.kt`
- Modify: `app/src/main/kotlin/fuck/andes/agent/runtime/AgentRuntimeWire.kt`
- Modify: `app/src/test/java/fuck/andes/data/repository/RuntimeConfigRepositoryTest.kt`
- Modify: `app/src/test/java/fuck/andes/agent/runtime/AgentRuntimeWireTest.kt`

**Interfaces:**
- Produces: `ModelConfig.authMode`，默认空字符串；只有新能力使用 `codex_oauth`。
- Consumes: `providerId` 作为 OAuth credential lookup key。

- [x] **Step 1: 写配置验证和 Runtime Wire 安全传输失败测试**

```kotlin
assertDoesNotThrow { codexConfig.copy(apiKey = "").validateForTest() }
```

同时断言 `AgentRuntimeWire` round trip 只携带 `authMode`，序列化内容不包含 OAuth token 字段。

- [x] **Step 2: 运行定向测试并确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests '*RuntimeConfigRepositoryTest' --tests '*AgentRuntimeWireTest'`

Expected: FAIL，`authMode` 和对应 Runtime Wire schema 尚不存在。

- [x] **Step 3: 实现条件验证和安全传输**

`ModelConfig.validate()` 规则改为：`authMode` 为空时完整保留当前非空 `apiKey` 校验；Codex OAuth 模式要求内置 OpenAI、Responses endpoint 和非空 provider ID，但不要求 API Key。Runtime 投影和 Binder Wire 只传递 `authMode + providerId`，并在 OAuth 模式强制清空 `apiKey`。

- [x] **Step 4: 运行定向测试并确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests '*RuntimeConfigRepositoryTest' --tests '*AgentRuntimeWireTest'`

Expected: PASS；现有 API Key 校验测试仍通过。

- [x] **Step 5: 提交独立检查点**

```bash
git add app/src/main/kotlin/fuck/andes/data/repository/RuntimeConfigRepository.kt app/src/main/kotlin/fuck/andes/agent/model/AgentModelClient.kt app/src/main/kotlin/fuck/andes/agent/runtime/AgentRuntimeWire.kt app/src/test/java/fuck/andes/data/repository/RuntimeConfigRepositoryTest.kt app/src/test/java/fuck/andes/agent/runtime/AgentRuntimeWireTest.kt
git commit -m "feat(agent): 安全传输 Codex OAuth Provider 配置"
```

### Task 6: Codex Responses Provider

**Files:**
- Create: `app/src/main/kotlin/fuck/andes/agent/model/CodexResponsesProvider.kt`
- Modify: `app/src/main/kotlin/fuck/andes/agent/model/ProviderClientFactory.kt`
- Modify: `app/src/main/kotlin/fuck/andes/agent/model/ResponsesRequestBuilder.kt`
- Create: `app/src/test/java/fuck/andes/agent/model/CodexResponsesProviderTest.kt`
- Create: `app/src/test/java/fuck/andes/agent/model/ProviderClientFactoryTest.kt`
- Modify: `app/src/test/java/fuck/andes/agent/model/OpenAiResponsesProviderTest.kt`

**Interfaces:**
- Consumes: `CodexCredentialProvider.requireValidCredential(providerId)`。
- Produces: `AgentProviderClient` ID `codex_oauth_responses`。
- Produces: `ProviderClientFactory` 按已验证的 `authMode` 分流：Codex OAuth 选择新 Provider，空模式仍选择原 API Key Provider。

- [x] **Step 1: 写 Factory 分流与 MockWebServer 契约失败测试**

先用 `ProviderClientFactoryTest` 断言 Codex OAuth 配置选择 `CodexResponsesProvider`，空 `authMode` 的 API Key 配置仍选择 `OpenAiResponsesProvider`，OAuth 失败时不得回退。

断言固定路径 `/backend-api/codex/responses`、bare `application/json`、bearer、`Originator`、Account ID、`stream=true`、`store=false`、完整历史、函数调用和函数结果。断言自定义 Base URL 与 Authorization Header 无法覆盖固定值。

- [x] **Step 2: 写 SSE 与认证错误失败测试**

覆盖文本、reasoning、tool call、usage、401 刷新后仅重试一次、第二次 401 要求登录、429 分类、取消请求。

- [x] **Step 3: 运行定向测试并确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests '*CodexResponsesProviderTest' --tests '*OpenAiResponsesProviderTest' --tests '*ProviderClientFactoryTest'`

Expected: FAIL，Codex Provider 和 Factory 分流尚不存在。

- [x] **Step 4: 实现独立 Provider 并抽取最小共享解析器**

只从 `OpenAiResponsesProvider` 抽取无认证、无端点知识的 SSE 解析函数；API Key 请求构建保持原样。Codex 特殊字段由 `CodexResponsesProvider` 最终写入，用户自定义 body 不能覆盖 `model`、`input`、`tools`、`stream`、`store` 或 reasoning continuity 字段。`ProviderClientFactory` 只根据已验证的 `authMode` 选择 Provider，不将凭据回填到 `ModelConfig`。

- [x] **Step 5: 运行定向测试并确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests '*CodexResponsesProviderTest' --tests '*OpenAiResponsesProviderTest' --tests '*ProviderClientFactoryTest'`

Expected: PASS；API Key Responses 请求快照不变，空认证模式分流不变。

- [x] **Step 6: 提交独立检查点**

```bash
git add app/src/main/kotlin/fuck/andes/agent/model/CodexResponsesProvider.kt app/src/main/kotlin/fuck/andes/agent/model/ProviderClientFactory.kt app/src/main/kotlin/fuck/andes/agent/model/ResponsesRequestBuilder.kt app/src/test/java/fuck/andes/agent/model/CodexResponsesProviderTest.kt app/src/test/java/fuck/andes/agent/model/ProviderClientFactoryTest.kt app/src/test/java/fuck/andes/agent/model/OpenAiResponsesProviderTest.kt
git commit -m "feat(provider): 使用 OAuth 调用 Codex Responses"
```

### Task 7: Provider 设置页设备码 UI

**Files:**
- Create: `app/src/main/kotlin/fuck/andes/ui/pages/providers/CodexDeviceLoginContent.kt`
- Create: `app/src/main/kotlin/fuck/andes/ui/pages/providers/CodexVerificationPageLauncher.kt`
- Modify: `app/src/main/kotlin/fuck/andes/ui/pages/providers/ModelProviderDetailScreen.kt`
- Create: `app/src/test/java/fuck/andes/ui/pages/providers/CodexDeviceLoginContentTest.kt`

**Interfaces:**
- Consumes: `CodexOAuthManager.loginState` 和登录、取消、注销方法。
- Produces: 认证方式选择与设备码状态卡片。
- Produces: `CodexVerificationPageLauncher.open(): Boolean`，生产实现只发出固定 HTTPS `ACTION_VIEW` Intent。

- [x] **Step 1: 写 Compose 状态测试**

断言 API Key 模式继续显示原输入框；Codex 模式隐藏 API Key/Base URL 编辑，展示设备码登录。等待状态包含验证码、复制、浏览器和取消按钮；点击浏览器按钮时，注入的 launcher 只收到 `https://auth.openai.com/codex/device`；已登录状态包含退出按钮；语义树不包含 token。

- [x] **Step 2: 运行 UI 定向测试并确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests '*CodexDeviceLoginContentTest'`

Expected: FAIL，设备码 UI 尚不存在。

- [x] **Step 3: 实现状态 UI 和系统浏览器 Intent**

```kotlin
internal fun interface CodexVerificationPageLauncher {
    fun open(): Boolean
}
```

生产实现只用 `Intent.ACTION_VIEW` 打开 `https://auth.openai.com/codex/device`；测试注入 recording launcher，不启动真实浏览器。Manifest 不增加 intent-filter、deep link 或回调 Activity。旋转后从 Manager 的 StateFlow 恢复等待状态。

- [x] **Step 4: 运行 UI 测试并确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests '*CodexDeviceLoginContentTest'`

Expected: PASS；取消会停止轮询，退出只清除 OAuth 凭据。

- [x] **Step 5: 提交独立检查点**

```bash
git add app/src/main/kotlin/fuck/andes/ui/pages/providers/CodexDeviceLoginContent.kt app/src/main/kotlin/fuck/andes/ui/pages/providers/CodexVerificationPageLauncher.kt app/src/main/kotlin/fuck/andes/ui/pages/providers/ModelProviderDetailScreen.kt app/src/test/java/fuck/andes/ui/pages/providers/CodexDeviceLoginContentTest.kt
git commit -m "feat(ui): 新增 Codex 设备码登录界面"
```

### Task 8: 功能开关、文档和自动回归

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `README.md`
- Modify: `docs/README.md`
- Modify: `docs/README_EN.md`
- Modify: `docs/AGENT_RUNTIME.md`
- Modify: `downstream/CODEX_OAUTH_DESIGN.md`
- Modify: `downstream/CODEX_OAUTH_DEVELOPMENT_PLAN.md`

**Interfaces:**
- Produces: `BuildConfig.CODEX_OAUTH_ENABLED`，默认在下游构建启用。

- [x] **Step 1: 增加编译期开关并覆盖关闭行为**

关闭时不显示 OAuth 入口；数据库字段和 API Key Provider 仍正常。测试断言关闭开关不会自动选择 OAuth 或修改既有 Provider。

- [ ] **Step 2: 更新用户文档**

写明设备码步骤、共享额度不是无限免费、实验性协议风险、Root 环境 token 风险、退出方式，以及 OAuth 失败绝不自动产生 API 账单。

- [ ] **Step 3: 运行格式和完整单元测试**

Run: `git diff --check`

Expected: exit 0。

Run: `./gradlew :app:testDebugUnitTest`

Expected: BUILD SUCCESSFUL；无真实网络和付费 API 调用。

- [ ] **Step 4: 构建 Debug APK**

Run: `./gradlew :app:assembleDebug`

Expected: BUILD SUCCESSFUL，生成可安装 Debug APK。

- [ ] **Step 5: 提交文档和自动回归检查点**

```bash
git add app/build.gradle.kts README.md docs/README.md docs/README_EN.md docs/AGENT_RUNTIME.md downstream
git commit -m "docs(auth): 记录 Codex OAuth 设备码登录"
```

### Task 9: 最终人工验收（唯一允许真实 Codex 账号和额度的任务）

**Files:**
- Review: `README.md`
- Review: `downstream/CODEX_OAUTH_DESIGN.md`
- Review: `downstream/CODEX_OAUTH_DEVELOPMENT_PLAN.md`

**Preconditions:**

- Tasks 1–8 的自动测试、AndroidKeyStore instrumentation test 和 Debug APK 构建全部通过。
- 使用专用测试设备和专用 Codex 测试账号。
- 执行人明确同意消耗最少量的 Codex 共享额度。
- 禁止录屏、截图或保存 user code、token、Account ID 和 JWT 内容。

- [ ] **Step 1: 安装候选 APK 并检查认证界面**

选择内置 OpenAI Provider，确认默认空 `authMode` 仍显示现有 API Key 和 Base URL；选择 `CODEX_OAUTH` 后确认 API Key/Base URL 编辑项隐藏，Responses 模式固定。

- [ ] **Step 2: 完成一次设备码浏览器授权**

确认 Eta 展示验证码、复制、取消和打开浏览器按钮；在系统浏览器输入设备码并授权，然后手动回到 Eta。登录必须由 Eta 轮询完成，不出现 Deep Link、WebView、回调 Activity 或本地回调监听器。

- [ ] **Step 3: 以最小额度验证多轮 Agent 能力**

只执行一次简短普通问答，以及一次本地只读工具调用和工具结果后的第二轮回答。不得为了压力、刷新或错误分支测试重复消耗真实额度，这些路径由自动测试覆盖。

- [ ] **Step 4: 验证进程重启后的凭据恢复**

强制停止并重新启动 Eta，确认仍显示已登录，并用一次最短请求验证凭据可用。不等待 access token 自然到期；到期刷新、single-flight 和 401 重试以 Task 4、Task 6 的自动测试为准。

- [ ] **Step 5: 验证原有 API Key 能力未受影响**

切回默认空 `authMode`，确认原 API Key、Base URL、模型和 Endpoint 配置仍在且 UI 行为不变。默认不发起真实 Platform API 请求；请求协议回归以 Task 5、Task 6 自动测试为准。

- [ ] **Step 6: 注销并检查敏感信息边界**

注销 Codex 后确认状态回到未登录，再次选择 OAuth 请求时要求重新登录。清空并重新采集测试期间 logcat，只统计 `access_token`、`refresh_token`、`id_token`、`Authorization: Bearer`、`Chatgpt-Account-Id` 等敏感模式的匹配数量，不打印匹配行；期望数量为 0。

- [ ] **Step 7: 记录验收结果**

只记录候选提交 SHA、设备型号、Android 版本、每个步骤的 PASS/FAIL 和脱敏失败类别。任一认证、工具多轮、重启恢复、注销或敏感日志检查失败，都阻止发布；记录中不得包含验证码、token、账号 ID 或原始响应体。

---

## 实施顺序与并行边界

- Task 1 是数据模型前置任务。
- Task 2 与 Task 3 可并行，文件互不重叠。
- Task 4 依赖 Task 2 和 Task 3。
- Task 5 依赖 Task 1；可与 Task 2、3、4 的开发并行，但合并后再执行集成测试。
- Task 6 依赖 Task 4 和 Task 5。
- Task 7 依赖 Task 1 和 Task 4。
- Task 8 在所有实现任务完成后执行，只包含自动验证和候选构建。
- Task 9 最后执行，是唯一允许真人交互、真实 Codex 账号和共享额度调用的任务。

每个任务先运行定向测试，再合并到主开发分支；最终只以整合后的 `:app:testDebugUnitTest` 和 `:app:assembleDebug` 结果作为完成依据。
