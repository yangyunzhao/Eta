# Codex Responses Debug 脱敏日志设计（v2.6.0 历史调试记录）

## 目标

本文件记录 `2.6.0.znmlr.1` 阶段的历史调试工作：为 Debug 构建补充足够详细的 Codex Responses 协议日志，用一次真实请求区分请求构造、HTTP、Content-Type、SSE 解析和服务端终态失败。Release 构建不得输出这些详细日志；它不是当前版本配置说明。

## 日志范围

Debug logcat 使用固定 Tag `EtaCodexProtocol`，记录：

- 请求开始、尝试次数、模型 ID、请求 JSON 字节数、input/tools 数量；
- 经脱敏后的完整请求 JSON；
- HTTP 状态码、Content-Type、是否进入 SSE 解析；
- 每个合法 SSE data frame 的序号、事件类型和经脱敏后的 JSON；非法 frame 只记录序号、UTF-8 字节数和固定解析失败枚举，绝不记录原文；
- data frame 总数、终态类型、解析完成或稳定失败原因；
- 异常类和本地 `StackTraceElement`，但不把 `Throwable` 传给 Log，也不记录 exception message、cause 或 suppressed message。

日志只写 logcat，不写 Room、DataStore、文件、Binder 或运行归档。

每次请求分配进程内递增的关联 ID；所有行都携带关联 ID、attempt 和 chunk 序号，便于并发重组。单个 logcat chunk 最多 3,000 字符，单个 JSON 最多输出 64 KiB，单次请求最多输出 512 KiB；超过预算时只写固定的 `log_budget_exhausted` 事件。

## 强制脱敏

无论 Debug/Release，也无论测试输入是否敏感，下列内容都不得进入日志：

- Authorization、Cookie 和所有请求/响应 Header 原值；
- access token、refresh token、ID token、账号 ID；
- device auth ID、user code、PKCE verifier/challenge；
- key 名经小写化并移除 `_`、`-` 后，只要包含 `token`、`auth`、`account`、`devicecode`、`usercode`、`pkce`、`secret`、`cookie`、`header` 或 `encryptedcontent`，其 JSON 值就统一替换为 `[REDACTED]`；
- 形似 JWT 或 Bearer credential 的字符串。

JSON 脱敏先递归复制再输出，不修改实际请求或响应对象。日志按固定长度分块，避免 logcat 截断；每块只包含已脱敏字符串。

## 代码边界

- 新增独立的 Debug 协议日志组件，集中负责 BuildConfig 门禁、递归脱敏、JWT/Bearer 替换、关联 ID、预算和分块输出；`enabled` 与日志 sink 可注入测试。
- `CodexResponsesProvider` 继续按现有逻辑读取 credential 构造认证 Header，但诊断组件不得接收 credential、Headers 或 Header 值；日志调用只接收无凭据的请求和阶段元数据。
- `ResponsesSseParser` 通过可选诊断接口报告 frame/event/terminal；默认 API Key Provider 不启用详细 frame 日志，行为保持不变。
- 生产请求端点、Header、body、认证、重试和异常分类不在本次日志改动中改变。

## 测试

采用 TDD：

1. 先写失败测试，证明嵌套认证字段、Bearer/JWT、账号 ID 和 encrypted reasoning 会统一替换为 `[REDACTED]`，普通请求/SSE 字段保留，且原 JSON 不被修改。
2. 验证 Release/关闭诊断时不生成详细日志。
3. 用 MockWebServer 覆盖 HTTP 200 非 SSE、非法 SSE、`response.failed` 和正常完成，断言阶段日志能区分它们；非法 frame 原文不得出现。
4. 回归 Codex Responses、API Key Responses、Factory 和 Runtime 测试；运行 `git diff --check` 与 Debug 构建。

## 真机验证门禁

本地测试和 APK 构建完成后暂停。只有用户明确同意，才通过 ADB 覆盖安装 Debug APK；只有再次明确同意，才清空当前 logcat，并由用户发送一次固定的纯文本最小请求（禁止图片和工具）后读取脱敏日志。一次成功或失败即停止，不得自动退出登录、清除应用数据或重复消耗共享额度。
