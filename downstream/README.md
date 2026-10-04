# Eta 下游二次开发文档

根目录 `downstream/` 保存 `yangyunzhao/Eta` 相对上游 `Mangi-11/Eta` 的设计、开发计划和维护记录，避免与上游 `docs/` 混放。

## Codex OAuth

- [设计文档](CODEX_OAUTH_DESIGN.md)
- [开发计划](CODEX_OAUTH_DEVELOPMENT_PLAN.md)
- [模型目录实施计划](CODEX_OAUTH_MODELS_PLAN.md)
- [v2.6.0.znmlr.1 发布核对记录](RELEASE_V2.6.0_ZNMLR_1_CHECKLIST.md)
- [v2.6.2.znmlr.1 发布核对记录](RELEASE_V2.6.2_ZNMLR_1_CHECKLIST.md)
- [v2.6.5.znmlr.1 发布核对记录](RELEASE_V2.6.5_ZNMLR_1_CHECKLIST.md)
- [上游 v3.0.0 合并记录](UPSTREAM_V3.0.0_MERGE.md)
- [上游 v3.0.2 合并记录](UPSTREAM_V3.0.2_MERGE.md)
- [上游 v3.0.4 合并记录](UPSTREAM_V3.0.4_MERGE.md)
- [上游 v3.1.0 合并记录](UPSTREAM_V3.1.0_MERGE.md)

当前发布版本为 [`v3.0.2.znmlr.1`](https://github.com/yangyunzhao/Eta/releases/tag/v3.0.2.znmlr.1) / `2026090701`，基于上游 `v3.0.2`（`5842a7c47d6c9f4b5580081bcae1f75b110301bc`）。tag CI [34794064664](https://github.com/yangyunzhao/Eta/actions/runs/34794064664) 已通过完整单元测试、Lint、签名构建和 APK 校验；公开 Release APK SHA-256 为 `8DA68A5F1534806FE1700AAB006E1F2B7E96F10B1C99F47B3861918C76B44D0D`。用户已完成同证书覆盖安装及一般功能测试并反馈无问题。

当前修复候选合入上游 `v3.1.0`（`84d42dc23a328502db9216bfea1fe60590b9e44c`），目标版本为 `v3.1.0.znmlr.2` / `2026100202`。此前 `.znmlr.1` 试用包覆盖旧 Fork v19 数据库后启动闪退，不得继续分发；用户验证修复候选后才会创建 tag 与 GitHub Release。证据见 [v3.1.0 合并记录](UPSTREAM_V3.1.0_MERGE.md)。

故障候选代码提交 `735c903b982bdcc1fc3afa7f26d0fda2c5a9b1b0` 的 [CI run 37165165365](https://github.com/yangyunzhao/Eta/actions/runs/37165165365) 虽通过完整单元测试、Lint、签名构建和 APK 校验，但真机启动失败。修复候选已补旧 Fork v19 的迁移测试，CI 签名与用户试装尚待完成。升级后需手动填写当前模型的上下文窗口大小。

Fork 继续保留上游未覆盖的 Codex OAuth 能力：设备码、AndroidKeyStore 加密凭据、刷新/登出、固定 Codex Responses/模型目录、Runtime 隔离、Room `auth_mode` 与 `ultra` 推理档位。`v3.0.0.znmlr.1`、`v2.6.5.znmlr.1`、`v2.6.2.znmlr.1` 与 `v2.6.0.znmlr.1` 均为历史发布记录。

2026-10-04 已按官方 Codex CLI 稳定版 `rust-v0.160.0`（peeled commit `a956835d020762cb2b570053af06f643a11c0ecc`）静态核对设备码、刷新、固定模型目录/Responses 路径与 SSE 终态；`CODEX_PROTOCOL_COMPAT_VERSION` 继续为 `0.147.0`，不机械升级。AndroidKeyStore instrumentation、真实 Codex 账号专项调用与注销后的敏感日志计数仍为已知验证缺口。

下游 CI/发布防护已经实现并通过代码审查：支持 `main`、`v*.znmlr.*` tag 和手动触发，构建前执行 unit test 与 lint，精确校验 tag、APK 和版本 metadata，并生成版本化资产名。它不会自动创建 tag、GitHub Release 或执行 push，且尚不能把上述本地门禁缺口记为通过；操作说明见 `.github/RELEASING.md`。

目标登录流程只使用设备码轮询：Eta 展示验证码，用户在固定 OpenAI 验证页授权后由 Eta 轮询完成登录。它不注册浏览器回调、Deep Link，不嵌入 WebView，也不启动本地 HTTP 回调服务器。当前可靠路径是 Eta 保持前台、在电脑或另一台设备打开固定验证页；同机浏览器可能因系统回收 Eta 进程而丢失本轮内存会话。该模式无需额外填写 OpenAI Platform API Key，但会消耗登录账号的 Codex 共享额度，且共享额度不是无限免费。OAuth 失败时必须 fail-closed，绝不自动回退到 API Key；现有 API Key、Anthropic 和自定义 Provider 行为保持不变。

凭据只在 Eta Runtime 进程内从 AndroidKeyStore 保护的密文中解密，固定 OpenAI/Codex HTTPS 端点不能被自定义 Base URL 或 Header 覆盖。token、账号 ID、设备码和 PKCE 材料不得进入 Room、RemotePreferences、Binder、transcript、日志或异常正文。Root / LSPosed 环境中的恶意模块仍可能读取运行中进程，AndroidKeyStore 无法消除此风险；不再使用时应退出 Codex 登录。

## 仓库约定

- `origin`：`https://github.com/yangyunzhao/Eta.git`，用于提交和推送下游修改。
- `upstream`：`https://github.com/Mangi-11/Eta.git`，只用于获取上游更新，禁止推送。
- 后续持续 fetch 上游更新；没有下游分叉时允许 fast-forward，已经产生下游分叉时使用普通 merge 保留双方历史，不对已发布提交执行 rebase、reset 或强制推送。
- 上游根 README 的更新合入 `docs/README.md` 和 `docs/README_EN.md`，不覆盖根目录的下游 README 与 `AGENTS.md`。
- 下游 build、tag、release 和发布资产统一使用 `v<上游版本>.znmlr.<下游序号>`；同一上游版本递增序号，上游版本变化时从 `1` 重新开始。
- 上游版本基线必须来自已验证的 upstream release tag 及其 peeled commit，不能从任意分支名、README 或旧 `versionName` 推断。
