# Eta 下游开发版

这是 [Mangi-11/Eta](https://github.com/Mangi-11/Eta) 的个人下游 Fork，由 [yangyunzhao](https://github.com/yangyunzhao) 基于上游持续开发。

本仓库保留 Eta 原有的 Android 系统级 AI Agent、BYOK Provider、多轮工具调用、GUI Agent、设备直达、内置浏览器及 Root/Linux 能力。完整的上游项目介绍、安装要求、功能说明和许可证信息请阅读：

- [上游中文 README](docs/README.md)
- [Upstream README in English](docs/README_EN.md)

## 下游新增与规划

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| Codex OAuth 设备码登录 | 已发布 `v2.6.2.znmlr.1`；本地候选 `v2.6.5.znmlr.1` | 认证、加密凭据、刷新、安全 IPC、Codex Responses、固定模型目录、设备码设置页、编译期开关和下游版本规则已经整合；v2.6.5 同步新增 MCP、运行恢复与跨协议流式块支持，仍待 tag/Release |

使用流程是：在内置 OpenAI Provider 选择 `CODEX_OAUTH`，由 Eta 展示设备码并打开固定 OpenAI 验证页，用户授权后应用通过轮询完成登录。整个流程不使用浏览器回调、Deep Link、WebView 或本地 HTTP 回调服务器。当前已验证的可靠路径是让 Eta 保持前台，并在电脑或另一台设备打开 `https://auth.openai.com/codex/device` 输入验证码；部分手机在切到同机浏览器后会回收 Eta 进程，导致本轮内存登录会话丢失。使用者无需再填写 OpenAI Platform API Key，但请求会消耗登录账号的 Codex 共享额度；共享额度不是无限免费，具体可用量受账号与服务策略约束。

当前发布版本为 [`v2.6.2.znmlr.1`](https://github.com/yangyunzhao/Eta/releases/tag/v2.6.2.znmlr.1)（`versionCode 26201`），基于上游 [`v2.6.2`](https://github.com/Mangi-11/Eta/releases/tag/v2.6.2)（`bd4a14ceeda81b9063b9fde91b14e47f3851929f`）。本地 `main` 已进一步合入上游 `v2.6.5`（`bf0c6fee5968b1f1f31ec4dece1201082d17226c`），下一下游候选基线为 `2.6.5.znmlr.1` / `26501`，尚未创建 tag 或 GitHub Release。2026-08-29 已按最新官方 Codex CLI `rust-v0.150.1`（peeled `90854393966b21e9ebfd21b122334eb09a20c93d`）重新核对设备码、刷新、Responses 与模型目录契约：相对 0.149.1 无必须迁移的变化，因此 `CODEX_PROTOCOL_COMPAT_VERSION` 保持代表已完整验证的 `0.147.0` 基线。v2.6.2 的 main 与 tag CI 均已通过，最终 CI Release APK 的 SHA-256 为 `2BB9C10E8A3A484511433814FCB7B5C5BFE344E93D951022455E292EE6B314E1`。用户没有也不需要 API Key，因此该人工网络验收不适用，原路径只采用自动回归且不发起 Platform API 请求。AndroidKeyStore instrumentation 因手机拒绝 USB 安装测试 APK而尚未实际执行，注销后的敏感日志计数也尚未完成，均作为本版本的已知验证缺口保留。

本地候选 `v2.6.5.znmlr.1` 已通过 Kotlin 编译、MCP/恢复链/OAuth 定向回归、Lint、Debug 和签名 Release 构建；本地签名 APK 为 `release/Eta-v2.6.5.znmlr.1-release.apk`，SHA-256 为 `11B0FBBFAE6F6B70BB3DCD1BB65D9BA73B7F79825D2DC6E45EE9129645FD2545`。它尚未推送、创建 tag 或 GitHub Release，详细证据见下方候选核对记录。

仓库已经加入下游 CI/发布防护：在 `main`、`v*.znmlr.*` tag 和手动触发时运行，构建前执行 unit test 与 lint，并精确核对 tag、APK 和版本 metadata，发布资产使用版本化名称。该流程不会自动创建 tag、GitHub Release 或执行 push；`v2.6.2.znmlr.1` 的 main 与 tag 工作流均已通过。详细发布步骤见 `.github/RELEASING.md`。

相关文档：

- [下游二次开发文档索引](downstream/README.md)
- [Codex OAuth 设计](downstream/CODEX_OAUTH_DESIGN.md)
- [Codex OAuth 开发计划](downstream/CODEX_OAUTH_DEVELOPMENT_PLAN.md)
- [v2.6.0.znmlr.1 发布核对记录](downstream/RELEASE_V2.6.0_ZNMLR_1_CHECKLIST.md)
- [v2.6.2.znmlr.1 发布核对记录](downstream/RELEASE_V2.6.2_ZNMLR_1_CHECKLIST.md)
- [v2.6.5.znmlr.1 候选核对记录](downstream/RELEASE_V2.6.5_ZNMLR_1_CHECKLIST.md)

## 与上游的关系

- `origin`：`https://github.com/yangyunzhao/Eta.git`，用于下游提交和推送。
- `upstream`：`https://github.com/Mangi-11/Eta.git`，只用于获取上游更新。
- 后续会持续获取上游更新；没有下游分叉时允许 fast-forward，已经产生下游分叉时使用普通 merge 保留双方历史，不改写已发布提交；最近一次本地同步为上游 `v2.6.5` / `bf0c6fe`，同步后需重新运行相关测试。
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
- 当前已发布 `v2.6.2.znmlr.1`，对应 `versionName 2.6.2.znmlr.1` 和 `versionCode 26201`；`v2.6.0.znmlr.1` 是上一上游基线的历史首发版本。

## 当前开发原则

- 原有 API Key、Anthropic 和自定义 Provider 能力保持不变。
- Codex OAuth 是新增的可选能力，失败时不得自动回退到 API Key，避免产生意外 API 账单。
- Codex OAuth 只访问固定的 OpenAI 设备授权 HTTPS 端点和 Codex Responses HTTPS 端点；自定义 Base URL、认证 Header 和明文 HTTP 配置不能覆盖它们。
- Codex OAuth 的远端模型列表只访问固定的 `https://chatgpt.com/backend-api/codex/models`，不会把 OAuth token 发送到 Provider 自定义 URL。
- access token、refresh token、ID token、账号 ID、设备码和 PKCE 材料不得进入 Room Provider 表、RemotePreferences、Binder、transcript、日志、异常正文或测试快照。
- 凭据使用 AndroidKeyStore 保护，但 Eta 面向 Root / LSPosed 设备：恶意 Root 模块仍可能从运行中的进程窃取登录态，本地加密无法消除这一风险；不再使用时应在 Provider 页面退出登录。
- 编译期 `CODEX_OAUTH_ENABLED` 已实现且下游构建默认启用；关闭时隐藏 OAuth 入口并 fail-closed，不删除 OAuth 数据库字段，也不改变原有 API Key Provider。
- 自动测试不得调用真实或付费 AI 服务；真实 Codex 账号与共享额度只用于最终人工验收。
