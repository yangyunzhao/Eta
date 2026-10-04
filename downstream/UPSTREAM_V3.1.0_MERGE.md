# 上游 v3.1.0 合并记录

> 试用候选记录；签名密码、KeyStore 内容、OAuth token、账号 ID 与设备码不进入本文件。

## 来源与版本

| 项目 | 结果 |
| --- | --- |
| 上游 release tag | `v3.1.0` |
| tag peeled commit | `84d42dc23a328502db9216bfea1fe60590b9e44c` |
| 修复试用目标 | `v3.1.0.znmlr.2` / `2026100202` |
| 故障候选代码提交 | `735c903b982bdcc1fc3afa7f26d0fda2c5a9b1b0` |
| 当前正式发布 | `v3.0.2.znmlr.1` / `2026090701` |

## 合并边界

- 普通 merge 保留上游 v3.1.0 的语音交互、屏幕上下文、社区 Provider 目录、聊天 Markdown 和上下文压缩更新。
- 保留应用身份 `fuck.andes`、数据库 `fuck_andes.db` 与旧安装升级路径。
- 保留仅下游提供的 Codex 设备码 OAuth、加密凭据、刷新与登出、专用模型目录/Responses 路由、Runtime 凭据隔离、Room `auth_mode` 和 `ultra` 档位。
- 将上游根 README 镜像到 `docs/README.md` / `docs/README_EN.md`，只调整相对链接；根 README 继续说明下游状态。

## 验证门禁

| 检查 | 状态与适用候选 |
| --- | --- |
| 旧 v3.0.4 候选 6 项合并回归 | 本地定向测试已通过；修复提交 `0f6d17f`。 |
| v3.1.0 合并后 Debug 编译 | 故障 `.znmlr.1` 本地 `:app:compileDebugKotlin` 与测试源码编译通过。 |
| OAuth/上下文压缩/迁移/版本/Provider 定向测试 | 故障 `.znmlr.1` 本地通过；修复 `.znmlr.2` 的旧下游 v19→v21 迁移与版本测试本地通过。 |
| 完整 JVM 单测 | 故障 `.znmlr.1` 的 [Linux CI run 37165165365](https://github.com/yangyunzhao/Eta/actions/runs/37165165365) 通过，但漏测旧下游 v19；修复 `.znmlr.2` 的 [CI run 37168306862](https://github.com/yangyunzhao/Eta/actions/runs/37168306862) 已通过新增迁移测试与完整套件。 |
| Android Lint | 故障 `.znmlr.1` 与修复 `.znmlr.2` 的 CI 均通过。 |
| 既有证书签名的 Release APK | 故障 `.znmlr.1` 覆盖旧数据库后不可用。修复 `.znmlr.2` 的 CI 已签名、组装、验证并上传；本地文件 `release/Eta-v3.1.0.znmlr.2-release.apk`，SHA-256 `918E20F66CB6BA2212D48631C63F0B2437F0EE3CB7532F8A848686F7FE21A2A9`。 |
| 包信息与签名连续性 | 修复 `.znmlr.2` 本机 `apksigner` 验证通过：包名 `fuck.andes`，`versionName 3.1.0.znmlr.2`，`versionCode 2026100202`；证书 SHA-256 `444deb65e119ae74386d76d216fcee7062b8a90c68af28de2953be24d71dc791`，与已发布 v3.0.2 和故障 `.znmlr.1` 一致。 |
| 覆盖安装、真实账号及关键功能试用 | `.znmlr.1` 覆盖安装后启动闪退；`.znmlr.2` 待手机重新连接后验证，未清除应用数据。 |

## v19 升级闪退与修复

2026-10-04 手机 `3.1.0.znmlr.1` 的 AndroidRuntime 崩溃日志确认：`SQLiteException: no such column: context_snapshot_json`，发生在 Room 19→20 升级的历史数据查询。已发布 v3.0.2 下游数据库为 v19，但三张 runtime 表没有上游 v19 已有的 `context_snapshot_json` 与 `operation`；因此旧下游安装不会重跑 18→19，直接进入 19→20 后失败。这是下游合并遗漏，不能归因于上游 v3.1.0。

修复在 19→20 查询前按列存在性补齐三表共六列，不清空、重建或覆盖原表。新增回归测试按已发布下游 v19 的列结构构造测试数据库并升级至 v21，核对旧运行记录、归档、进行中任务、会话及 `CODEX_OAUTH` Provider 数据均保留；测试已先复现同一缺列异常，再通过。该测试不是手机私有数据库的复制品。修复候选的完整 CI 与签名 APK 已验证；手机覆盖安装仍待执行。

2026-10-04 核对官方 Codex CLI 稳定 release `rust-v0.160.0`，peeled commit `a956835d020762cb2b570053af06f643a11c0ecc`。静态检查未发现设备码、刷新、固定端点、模型目录和 Responses SSE 的强制性不兼容变化；`CODEX_PROTOCOL_COMPAT_VERSION` 保持已完整验证的 `0.147.0`，真实账号与真机行为仍需验收。

本试用候选不创建下游 tag 或 GitHub Release；用户确认安装测试通过后再发布。
