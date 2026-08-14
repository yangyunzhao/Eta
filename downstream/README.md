# Eta 下游二次开发文档

根目录 `downstream/` 保存 `yangyunzhao/Eta` 相对上游 `Mangi-11/Eta` 的设计、开发计划和维护记录，避免与上游 `docs/` 混放。

## Codex OAuth

- [设计文档](CODEX_OAUTH_DESIGN.md)
- [开发计划](CODEX_OAUTH_DEVELOPMENT_PLAN.md)
- [模型目录实施计划](CODEX_OAUTH_MODELS_PLAN.md)
- [v2.6.0.znmlr.1 发布核对记录](RELEASE_V2.6.0_ZNMLR_1_CHECKLIST.md)

Tasks 1–12 已形成并发布 `v2.6.0.znmlr.1`：认证标识与数据库迁移、设备码协议、AndroidKeyStore 加密凭据、登录与刷新生命周期、Runtime 安全传输、Codex Responses Provider、固定 OAuth 模型目录、设备码设置页、编译期开关和下游版本规则均已落地。真机已经完成登录、最小问答、一次本地只读工具回合、进程重启恢复、7 个模型拉取与切换调用；发布前已核对官方 Codex CLI 最新稳定版 `rust-v0.147.0` 并完成协议兼容修正。main 与 tag GitHub Actions 均通过。用户没有也不需要 API Key，该人工网络验收不适用，原路径以自动回归为准。AndroidKeyStore instrumentation 和注销后的敏感日志计数仍是已知验证缺口。

本地自动门禁的实际结果是：兼容修复前隔离快照的完整 JVM 回归共 669 项，仍复现同一组 8 个 Windows/Robolectric/POSIX 基线失败，lint 为 0 error；当前候选的协议相关 7 类定向回归 81/81 和签名 Release 构建通过，没有新增 OAuth 失败。AndroidKeyStore instrumentation 已在真机发起，但设备两次拒绝 USB 安装测试 APK，因此 0 项测试实际执行，不能标记为通过。

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
