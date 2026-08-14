# Eta 下游开发版

这是 [Mangi-11/Eta](https://github.com/Mangi-11/Eta) 的个人下游 Fork，由 [yangyunzhao](https://github.com/yangyunzhao) 基于上游持续开发。

本仓库保留 Eta 原有的 Android 系统级 AI Agent、BYOK Provider、多轮工具调用、GUI Agent、设备直达、内置浏览器及 Root/Linux 能力。完整的上游项目介绍、安装要求、功能说明和许可证信息请阅读：

- [上游中文 README](docs/README.md)
- [Upstream README in English](docs/README_EN.md)

## 下游新增与规划

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| Codex OAuth 设备码登录 | Tasks 1–8 实现已形成候选，Task 9 待人工 | 认证、加密凭据、刷新、安全 IPC、Codex Responses、设备码设置页、编译期开关和下游版本规则已经整合；自动门禁仍有已知基线失败和设备验证缺口，因此当前不能视为正式发布或已完成真实验收 |

计划中的使用流程是：在内置 OpenAI Provider 选择 `CODEX_OAUTH`，由 Eta 展示设备码并用系统浏览器打开 OpenAI 验证页，用户授权后手动回到 Eta，应用通过轮询完成登录。整个流程不使用浏览器回调、Deep Link、WebView 或本地 HTTP 回调服务器。使用者无需再填写 OpenAI Platform API Key，但请求会消耗登录账号的 Codex 共享额度；共享额度不是无限免费，具体可用量受账号与服务策略约束。

当前候选版本为 `2.6.0.znmlr.1`（`versionCode 26001`），编译期 Codex OAuth 开关默认启用，默认与关闭开关两种 Debug 构建均成功；仓库尚未创建对应 tag、GitHub Release，也没有推送本次候选。定向回归 137/137 通过，lint 为 0 error；完整 JVM 回归 649 项中仍有同一组 8 个 Windows/Robolectric 基线失败，WSL 因缺少 JDK 25 未复核，无连接设备也使真实 AndroidKeyStore instrumentation 尚未运行。因此 Task 8 自动门禁不能记为全绿。计划中的人工步骤仅剩 Task 9：真实登录、进程重启恢复、多轮工具调用及共享额度消耗必须由人工最小化验证；在此之前不建议把开发分支用于主账号或生产任务。

仓库已经加入下游 CI/发布防护：在 `main`、`v*.znmlr.*` tag 和手动触发时运行，构建前执行 unit test 与 lint，并精确核对 tag、APK 和版本 metadata，发布资产使用版本化名称。该流程不会自动创建 tag、GitHub Release 或执行 push；当前候选也尚未以工作流结果证明自动门禁全绿。详细发布步骤见 `.github/RELEASING.md`。

相关文档：

- [下游二次开发文档索引](downstream/README.md)
- [Codex OAuth 设计](downstream/CODEX_OAUTH_DESIGN.md)
- [Codex OAuth 开发计划](downstream/CODEX_OAUTH_DEVELOPMENT_PLAN.md)

## 与上游的关系

- `origin`：`https://github.com/yangyunzhao/Eta.git`，用于下游提交和推送。
- `upstream`：`https://github.com/Mangi-11/Eta.git`，只用于获取上游更新。
- 后续会持续获取上游更新；没有下游分叉时允许 fast-forward，已经产生下游分叉时使用普通 merge 保留双方历史，不改写已发布提交；同步上游后需重新运行相关测试。
- 上游 README 的更新合入 `docs/README.md` 和 `docs/README_EN.md`，根 README 继续作为下游入口。
- 上游许可证继续适用于本 Fork；使用、修改和分发前请阅读 [PolyForm Noncommercial License 1.0.0](LICENSE)。

## 下游版本命名

下游 build、Git tag、GitHub Release 标题和发布资产统一使用：

```text
v<上游版本>.znmlr.<下游序号>
```

- 上游 `v2.6.0` 的首个下游版本是 `v2.6.0.znmlr.1`。
- 同一上游基线继续发布时递增为 `v2.6.0.znmlr.2`、`v2.6.0.znmlr.3`。
- 上游升级后序号重置；例如升级到 `v2.7.0` 后从 `v2.7.0.znmlr.1` 开始。
- `znmlr` 是固定的下游标签，release 和 tag 只发布到个人仓库 `origin`。
- 当前代码候选使用 `versionName 2.6.0.znmlr.1` 和 `versionCode 26001`；这只是构建版本，尚未创建 tag 或 Release。

## 当前开发原则

- 原有 API Key、Anthropic 和自定义 Provider 能力保持不变。
- Codex OAuth 是新增的可选能力，失败时不得自动回退到 API Key，避免产生意外 API 账单。
- Codex OAuth 只访问固定的 OpenAI 设备授权 HTTPS 端点和 Codex Responses HTTPS 端点；自定义 Base URL、认证 Header 和明文 HTTP 配置不能覆盖它们。
- access token、refresh token、ID token、账号 ID、设备码和 PKCE 材料不得进入 Room Provider 表、RemotePreferences、Binder、transcript、日志、异常正文或测试快照。
- 凭据使用 AndroidKeyStore 保护，但 Eta 面向 Root / LSPosed 设备：恶意 Root 模块仍可能从运行中的进程窃取登录态，本地加密无法消除这一风险；不再使用时应在 Provider 页面退出登录。
- 编译期 `CODEX_OAUTH_ENABLED` 已实现且下游构建默认启用；关闭时隐藏 OAuth 入口并 fail-closed，不删除 OAuth 数据库字段，也不改变原有 API Key Provider。
- 自动测试不得调用真实或付费 AI 服务；真实 Codex 账号与共享额度只用于最终人工验收。
