# Codex Responses Debug Logging and Protocol Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 通过 Debug-only 脱敏协议日志定位真实 `protocol_failure`，修复 Codex Responses 请求并在真机上完成一次成功回答。

**Architecture:** 新建可注入开关与 sink 的 `CodexProtocolDebugLogger`，集中完成认证字段脱敏、关联 ID、预算和分块；Provider 报告请求/HTTP 阶段，SSE parser 通过可选 observer 报告合法事件或固定解析枚举。先用 MockWebServer 验证可观测性，再经用户批准安装 APK、捕获一次真实请求，依据固定阶段证据只修复一个根因。

**Tech Stack:** Kotlin、Android `Log`、org.json、OkHttp/MockWebServer、JUnit、Gradle、ADB/logcat。

## Global Constraints

- 详细协议日志只允许出现在 Debug 构建，固定 Tag 为 `EtaCodexProtocol`；Release 不输出。
- 不记录任何 Header 原值、credential、token、账号 ID、设备码、PKCE、Cookie 或未经脱敏的异常 message。
- 合法请求/SSE JSON 可详细记录，但必须先递归脱敏；非法 SSE 只记序号、长度和固定失败枚举。
- 单个 chunk 最多 3,000 字符、单个 JSON 最多 64 KiB、每次请求最多 512 KiB。
- 不改变 API Key、Anthropic、自定义 Provider 行为；不自动清数据、退出登录或重复真实请求。
- 每个 Step 完成后更新本文件状态；进度只 amend 到对应功能提交，不创建纯进度提交。

## 状态表

| Step | 状态 | 证据 |
|---|---|---|
| Task 1 / Step 1–2 | 已完成 | 初始与复审修复测试均先行编写；指定 Gradle 命令先后因 Logger 缺失、`attempt` 接口缺失按预期 RED。 |
| Task 1 / Step 3–5 | 已完成 | Logger GREEN：关闭开关 sink 为 0，64 KiB 安全截断及可重组 chunk 元数据已覆盖；与进度一并 amend `feat(debug): 增加 Codex 协议脱敏日志`。 |
| Task 2 / Step 1–2 | 已完成 | MockWebServer 覆盖非 SSE、非法 JSON、顶层 error、response.failed 与 completed；新增 `debugLogger` 注入参数缺失时按预期 RED。 |
| Task 2 / Step 3–5 | 已完成 | Provider/SSE 已接入固定阶段和脱敏合法帧；Codex/OpenAI 定向测试已 GREEN，联合回归通过后 amend 到同一功能提交。 |
| Task 3 / Step 1–3 | 待开始 | APK 构建、安装和一次真实日志捕获尚未执行。 |
| Task 4 / Step 1–5 | 待开始 | 根因测试、修复、复审和真机成功尚未执行。 |

---

### Task 1: Debug 脱敏日志组件

**Files:**
- Create: `app/src/main/kotlin/fuck/andes/agent/model/CodexProtocolDebugLogger.kt`
- Create: `app/src/test/java/fuck/andes/agent/model/CodexProtocolDebugLoggerTest.kt`

**Interfaces:**
- Produces: `CodexProtocolDebugLogger(enabled: Boolean, sink: (String, String) -> Unit)`
- Produces: `beginRequest(model: String, request: JSONObject): RequestTrace`
- Produces: `RequestTrace.log(stage: String, fields: JSONObject = JSONObject())`
- Produces: `RequestTrace.logJson(stage: String, payload: JSONObject)`
- Produces: `RequestTrace.logException(stage: String, throwable: Throwable)`，只序列化类名与 stack elements。

- [x] **Step 1: 写脱敏、分块和预算失败测试**

测试构造嵌套 JSON，包含 `access_token`、`idToken`、`account-id`、`device_code`、`pkceVerifier`、`headers`、`encrypted_content`、Bearer 与 JWT 字符串，同时包含普通 prompt/output；断言敏感值均为 `[REDACTED]`、普通字段保留、源 JSON 不变、每块不超过 3,000 字符、总量超过 512 KiB 时出现一次 `log_budget_exhausted`。

- [x] **Step 2: 运行 RED**

Run: `./gradlew :app:testDebugUnitTest --tests '*CodexProtocolDebugLoggerTest'`

Expected: 测试编译因 `CodexProtocolDebugLogger` 不存在而失败。

- [x] **Step 3: 实现最小 Logger**

使用 `AtomicLong` 分配 request ID；key 规范化为小写并移除 `_`/`-` 后做敏感子串匹配；字符串再做 Bearer/JWT 正则替换；以深拷贝递归脱敏后分块。默认实例由 `BuildConfig.DEBUG` 控制，并使用 `Log.d("EtaCodexProtocol", line)`；测试注入 sink。

- [x] **Step 4: 运行 GREEN 与 Release 门禁测试**

Run: `./gradlew :app:testDebugUnitTest --tests '*CodexProtocolDebugLoggerTest'`

Expected: 全部通过；`enabled=false` 时 sink 调用次数为 0。

- [x] **Step 5: 提交 Task 1**

提交范围仅 Logger、测试和本计划进度，中文提交说明：`feat(debug): 增加 Codex 协议脱敏日志`。

### Task 2: Provider 与 SSE 阶段接线

**Files:**
- Modify: `app/src/main/kotlin/fuck/andes/agent/model/CodexResponsesProvider.kt`
- Modify: `app/src/main/kotlin/fuck/andes/agent/model/ResponsesSseParser.kt`
- Modify: `app/src/test/java/fuck/andes/agent/model/CodexResponsesProviderTest.kt`
- Modify: `app/src/test/java/fuck/andes/agent/model/OpenAiResponsesProviderTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `CodexProtocolDebugLogger` 与 `RequestTrace`。
- Produces: `ResponsesSseParser.Observer`，只接收 frame index、event type、合法脱敏前 JSONObject 引用、terminal type 或固定 parse failure 枚举；默认 `null` 保持 API Key 行为。

- [x] **Step 1: 写 HTTP/SSE 阶段失败测试**

MockWebServer 分别返回 200 非 SSE、非法 JSON SSE、顶层 `error`、`response.failed`、正常 `response.completed`；断言固定 stage 能区分五种情况，非法 frame 原文及认证哨兵不出现，正常 API Key Responses sink 不产生 Codex 日志。

- [x] **Step 2: 运行 RED**

Run: `./gradlew :app:testDebugUnitTest --tests '*CodexResponsesProviderTest' --tests '*OpenAiResponsesProviderTest'`

Expected: 因 observer/trace 接口尚未接线而失败。

- [x] **Step 3: 实现最小阶段接线**

Provider 记录 `request_built`、`http_headers`、`content_type_mismatch`、`sse_start`、`sse_complete`、`request_failed`；不把 credential 或 Headers 传给 logger。Parser 对合法 frame 报 event type 与 JSON，对非法 frame 报 `sse_invalid_json`，对空流、缺终态、顶层 error、failed terminal 分别报固定 stage；异常日志仅使用类名和 stack elements。

- [x] **Step 4: 运行 GREEN 与相关回归**

Run: `./gradlew :app:testDebugUnitTest --tests '*CodexProtocolDebugLoggerTest' --tests '*CodexResponsesProviderTest' --tests '*OpenAiResponsesProviderTest' --tests '*ProviderClientFactoryTest' --tests '*AgentModelClientLoopTest'`

Expected: 全部通过，无真实网络。

- [x] **Step 5: 提交 Task 2**

Task 2 进度 amend 到 Task 1 功能提交，不新增纯接线或进度提交；最终中文说明保持 `feat(debug): 增加 Codex 协议脱敏日志`。

### Task 3: 构建、安装与真实证据捕获

**Files:**
- Update: `downstream/CODEX_RESPONSES_DEBUGGING_PLAN.md`
- Update: `downstream/CODEX_OAUTH_DEVELOPMENT_PLAN.md`

- [ ] **Step 1: 构建并静态检查 APK**

Run: `./gradlew :app:assembleDebug`

Expected: BUILD SUCCESSFUL；APK versionName 为 `2.6.0.znmlr.1`，详细 logger 受 `BuildConfig.DEBUG` 门禁。

- [ ] **Step 2: 经用户已授权后覆盖安装**

Run: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

Expected: `Success`，不清应用数据；安装后登录状态仍可读取。若 Android 因签名不一致要求卸载，立即停止并请求用户决定。

- [ ] **Step 3: 请求人工发送一次固定文本并采集日志**

先清空 logcat 并启动仅 Tag `EtaCodexProtocol` 的监听，然后通知用户发送：`只回答：2+3等于多少？不要调用工具。` 一次。收到成功或失败后立即停止监听；本地只保留脱敏输出，按关联 ID确定根因 stage，不自动重试。

### Task 4: 单根因修复与真机成功验收

**Files:**
- Modify/Test: 由 Task 3 固定 stage 指向的最小 Provider、request builder 或 parser 文件；不得同时尝试多个假说。
- Update: `downstream/CODEX_RESPONSES_DEBUGGING_PLAN.md`
- Update: `downstream/CODEX_OAUTH_DEVELOPMENT_PLAN.md`

- [ ] **Step 1: 写能复现真实证据的 RED 测试**

把 Task 3 观察到的 HTTP/SSE 形态转换成 MockWebServer fixture，断言当前代码产生同一失败；测试不得包含真实 prompt、token、账号 ID 或原始服务端敏感正文。

- [ ] **Step 2: 只实现该根因的最小修复**

若证据是请求字段/Header 指纹差异，只改固定协议字段；若是已知 SSE event/terminal 形态，只扩展 parser；若是服务端明确拒绝，保留稳定脱敏分类并按官方 Codex 0.147.0 已验证协议修正请求。不得顺带重构。

- [ ] **Step 3: 定向 GREEN、回归、构建与独立复审**

Run Task 2 的联合回归、`git diff --check` 和 `./gradlew :app:assembleDebug`；独立 reviewer 检查认证泄漏、API Key 回归和测试是否真实覆盖根因。

- [ ] **Step 4: amend 中文修复提交并覆盖安装**

中文提交说明：`fix(agent): 修复 Codex Responses 真实请求协议`。覆盖安装仍使用 `adb install -r`，不得卸载或清数据。

- [ ] **Step 5: 经通知后完成一次成功请求**

通知用户再次发送同一固定文本一次；Eta 必须返回 `5`，日志终态为 `response.completed`，且认证哨兵扫描为 0。成功后将 Task 9 Step 3 的普通问答子项标记完成；工具多轮仍单独验收。
