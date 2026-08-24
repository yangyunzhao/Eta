# Codex OAuth Remote Models Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 使内置 OpenAI Provider 在 `CODEX_OAUTH` 模式下能从 Codex 官方模型目录安全拉取模型，同时保持 API Key、Anthropic 和自定义 Provider 行为不变。

**Architecture:** 新增独立 `CodexModelsClient`，生产路径固定请求 `https://chatgpt.com/backend-api/codex/models?client_version=0.147.0`，通过现有 `CodexCredentialProvider` 获取凭据并复用一次 401 刷新策略。`RemoteModelFetcher` 仅在内置 OpenAI + `CODEX_OAUTH` 时分流到该客户端，其余路径保持现状。

**Tech Stack:** Kotlin、OkHttp、MockWebServer、kotlinx.serialization JSON、Android Keystore 凭据边界、JUnit。

## Global Constraints

- `CODEX_PROTOCOL_COMPAT_VERSION = "0.147.0"` 表示已验证的 Codex CLI 协议基线，不根据 Eta 版本或远程值自动改写。
- 2026-08-24 已与最新稳定 `rust-v0.149.1`（peeled `ff29a44391deccde0aba0f8390337d7f3c319ea4`）比较：普通 OAuth 模型目录 path、`client_version` 和 schema 未变；企业 managed-residency Header 不适用于 Eta 路径，因此保持 `0.147.0` 常量。
- OAuth 模型请求不得使用 `provider.baseUrl`、`provider.apiKey`、`provider.customHeaders` 或跟随重定向。
- token、Account ID 和服务端错误正文不得进入 UI、日志或异常正文。
- 首个 401 只刷新并重试一次；第二个 401 必须使用 rejected-token compare-and-clear，不得误删并发轮换后的新凭据。
- 每个 Step 完成后立即更新本文档；进度与功能一起提交，不创建纯进度提交。

## 状态表

| Step | 状态 | 证据 |
|---|---|---|
| Step 1 | 已完成 | `CodexModelsClientTest` 与 Codex schema 映射测试已先行写入。 |
| Step 2 | 已完成 | 2026-08-14 定向测试 RED：因 `CodexModelsClient`、`CodexModelsException` 和 `parseCodexModels` 尚不存在而编译失败。 |
| Step 3 | 已完成 | 已实现固定 `chatgpt.com/backend-api/codex/models?client_version=0.147.0`、禁重定向、凭据安全 Header、一次 401 刷新、二次 compare-and-clear 及 `models` schema 映射；UI 只传入凭据 Provider。 |
| Step 4 | 已完成 | 2026-08-14 定向 4 组 JVM 回归通过；主代理在 `81fc6d5` 上 fresh `:app:assembleDebug` BUILD SUCCESSFUL（21s）；scoped re-review 结论为 Spec ✅ / APPROVED。 |
| Step 5 | 已完成 | 2026-08-14 通过 `adb install -r` 覆盖安装并强制停止/重启 Eta 后，用户未重新登录即成功拉取 7 个可见模型，切换模型后的真实 Codex 调用正常。 |

---

### Task 1: Codex OAuth 模型目录

**Files:**
- Create: `app/src/main/kotlin/fuck/andes/data/repository/CodexModelsClient.kt`
- Modify: `app/src/main/kotlin/fuck/andes/data/repository/RemoteModelFetcher.kt`
- Modify: `app/src/main/kotlin/fuck/andes/ui/pages/providers/ModelProviderDetailScreen.kt`
- Create/Test: `app/src/test/java/fuck/andes/data/repository/CodexModelsClientTest.kt`
- Modify/Test: `app/src/test/java/fuck/andes/data/repository/RemoteModelFetcherTest.kt`
- Update: `downstream/CODEX_OAUTH_DEVELOPMENT_PLAN.md`
- Update: `downstream/CODEX_OAUTH_MODELS_PLAN.md`

**Interfaces:**
- Consumes: `CodexCredentialProvider.requireValidCredential(providerId)`、`refreshAfterUnauthorized(providerId, rejectedAccessToken)` 和 `invalidateAfterUnauthorized(providerId, rejectedAccessToken)`。
- Produces: `CodexModelsClient.fetch(providerId: String): List<Model>`。
- Produces: `RemoteModelFetcher.fetch(provider, codexCredentialProvider)` 的 OAuth 专用分流；非 OAuth 调用仍可不传凭据依赖。

- [x] **Step 1: 写失败测试**

`CodexModelsClientTest` 用 MockWebServer 断言：路径为 `/models?client_version=0.147.0`；只发送当前 OAuth Bearer 和可选 Account ID；忽略 Provider 的 API Key/Base URL/自定义 Header；首次 401 刷新后只重试一次；二次 401 按 compare-and-clear 结果分类；异常不包含 token、Account ID 或服务端正文。`RemoteModelFetcherTest` 断言 `{ "models": [{"slug":"gpt-test","display_name":"GPT Test","visibility":"list"}] }` 可映射，`visibility="hide"` 被过滤，原 API Key/Anthropic 分流不变。

- [x] **Step 2: 运行 RED**

Run: `./gradlew :app:testDebugUnitTest --tests '*CodexModelsClientTest' --tests '*RemoteModelFetcherTest'`

Expected: 因 `CodexModelsClient` 及 OAuth 分流尚不存在而编译或断言失败，原 JSON 解析测试仍正常。

- [x] **Step 3: 实现最小修复**

`CodexModelsClient` 的生产构造固定 HTTPS host/path/query，禁止 HTTP/HTTPS 重定向；请求 Header 与已验证 Responses 路径使用同一凭据安全校验。将 `{models:[...]}` 中 `slug`、`display_name`、`context_window`、`input_modalities`、`default_reasoning_level` 和 `supported_reasoning_levels[*].effort` 映射为 `Model`，仅保留 `visibility == "list"`。UI 仅向 fetcher 提供 `CodexCredentialProvider`，不读取凭据内容。

- [x] **Step 4: 运行 GREEN、回归与构建**

Run: `./gradlew :app:testDebugUnitTest --tests '*CodexModelsClientTest' --tests '*RemoteModelFetcherTest' --tests '*CodexResponsesProviderTest' --tests '*ProviderClientFactoryTest'`

Run: `./gradlew :app:assembleDebug`

Expected: 定向测试和 Debug APK 均成功，`git diff --check` 通过，独立复审无认证泄漏或旧 Provider 回归。

- [x] **Step 5: 中文提交、覆盖安装与真机验收**

Commit: `fix(provider): 修复 Codex OAuth 模型目录拉取`

Run: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

Expected: 不清数据且保留登录；用户再次点击“从远程自动拉取”后成功显示 Codex 可见模型，不再出现 missing bearer 401。
