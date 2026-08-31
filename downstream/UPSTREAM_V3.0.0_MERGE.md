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
| Release APK | `release/Eta-v3.0.0.znmlr.1-release.apk`，`fuck.andes`，`3.0.0.znmlr.1` / `2026083101`，APK Signature Scheme v2，证书 SHA-256 `44:4D:EB:65:E1:19:AE:74:38:6D:76:D2:16:FC:EE:70:62:B8:A9:0C:68:AF:28:DE:29:53:BE:24:D7:1D:C7:91`，文件 SHA-256 `AC45CC8F97EA3B05F20F2F0E7E9DF175D24AA95DA60E11C6E68EBCD11C4D2301`。 |
| 真机覆盖安装与基础自测 | 通过：维护者于 2026-08-31 从已安装 `v2.6.5.znmlr.1` 的设备完成覆盖安装并反馈无问题。 |
| 完整 JVM 回归 | `836` 项完成，`25` 项失败，`7` 项跳过；失败均为 Windows/Robolectric 下的 POSIX shell、Linux 子进程、文件/SQLite sidecar 前提，未发现 OAuth/Room/Responses 定向回归失败。 |

## 发布前状态与未完成验证

- 本地已从当前 Windows 用户的 DPAPI 保护恢复材料加载签名信息，并验证新旧 APK 的证书指纹一致；恢复材料不得复制、提交或打印。
- 用户已手动覆盖安装 `v3.0.0.znmlr.1` 并反馈无问题；这不替代 GitHub Actions、tag 或 GitHub Release 验证，当前仍不能称为正式发布。
- 25 个完整 JVM 失败集中于 POSIX `sh`、Android/Linux 子进程、临时文件预览与 SQLite sidecar 行为；仍需在 Linux/Android 适配环境复跑。
- 不执行真实 Codex 账号调用；设备码登录、刷新和真实 SSE 需由最终人工验收，并明确消耗共享额度。
