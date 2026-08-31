# 上游 v3.0.0 合并记录

> 本文件记录下游合并与本地构建证据。不得写入 OAuth token、设备码、账号 ID、签名密码或 KeyStore 内容。

## 来源与版本

| 项目 | 结果 |
| --- | --- |
| 上游 release tag | `v3.0.0` |
| 上游提交 | `0a90eac28a10e34d7b63d5b240afaa2621000282` |
| 下游本地版本 | `v3.0.0.znmlr.1` / `2026083101` |
| 当前正式发布 | `v2.6.5.znmlr.1` / `26501` |
| 最新 Codex CLI 稳定版（核对时间：2026-08-31） | `rust-v0.151.0`，非 draft、非 prerelease |
| Codex CLI tag object / peeled commit | `d8673cb68e349c208659b986697773d3145dbb14` / `78c290807ce710180111df227df3b7a4fe845452` |
| Eta 协议兼容基线 | `0.147.0`；设备码、refresh、模型目录、固定端点和核心 SSE 终态未发现必须迁移的不兼容，不机械升级。 |

## 合并决策

- 使用普通 merge 保留上游与下游历史。
- 吸收上游数据备份、MCP 请求头映射、工具调用放宽、Linux 终端/PTY/多会话/守护任务、共享目录、Kimi Code/Web 与 rootfs 浏览能力。
- 保留下游 `fuck.andes` applicationId、Provider authority 与 `fuck_andes.db`，让已安装 `v2.6.5.znmlr.1` 的用户继续位于同一 Android 沙箱和 Room 数据库。
- 保留 Codex OAuth 的设备码、AndroidKeyStore 凭据、固定 HTTPS、禁重定向、401 刷新、模型目录、Runtime 隔离和专用 Responses 路径。

## 本地验证

| 门禁 | 结果 |
| --- | --- |
| Kotlin Debug 编译 | 通过：`:app:compileDebugKotlin`。 |
| OAuth/Room/Responses/Runtime/MCP 与版本策略定向回归 | 通过：19 组 JVM 测试，均使用 MockWebServer 或本地替身。 |
| Android Lint | 通过：`:app:lint`。 |
| Release 编译 | 通过：`:app:assembleRelease`。 |
| Release APK | `release/Eta-v3.0.0.znmlr.1-release-unsigned.apk`，`fuck.andes`，`3.0.0.znmlr.1` / `2026083101`，SHA-256 `4CE71327DEFE66F54A86839524FA6F5A4CB07B7FF9E6C3E7BE104A5D829241AD`。 |
| 完整 JVM 回归 | `836` 项完成，`25` 项失败，`7` 项跳过；失败均为 Windows/Robolectric 下的 POSIX shell、Linux 子进程、文件/SQLite sidecar 前提，未发现 OAuth/Room/Responses 定向回归失败。 |

## 未完成的发布验证

- 当前环境未设置 Release keystore 的四项环境变量，APK 未签名，不能分发、tag 或创建 GitHub Release。
- 25 个完整 JVM 失败集中于 POSIX `sh`、Android/Linux 子进程、临时文件预览与 SQLite sidecar 行为；仍需在 Linux/Android 适配环境复跑。
- 不执行真实 Codex 账号调用；设备码登录、刷新和真实 SSE 需由最终人工验收，并明确消耗共享额度。
