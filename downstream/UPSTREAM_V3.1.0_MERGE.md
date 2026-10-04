# 上游 v3.1.0 合并记录

> 试用候选记录；签名密码、KeyStore 内容、OAuth token、账号 ID 与设备码不进入本文件。

## 来源与版本

| 项目 | 结果 |
| --- | --- |
| 上游 release tag | `v3.1.0` |
| tag peeled commit | `84d42dc23a328502db9216bfea1fe60590b9e44c` |
| 下游试用目标 | `v3.1.0.znmlr.1` / `2026100201` |
| 当前正式发布 | `v3.0.2.znmlr.1` / `2026090701` |

## 合并边界

- 普通 merge 保留上游 v3.1.0 的语音交互、屏幕上下文、社区 Provider 目录、聊天 Markdown 和上下文压缩更新。
- 保留应用身份 `fuck.andes`、数据库 `fuck_andes.db` 与旧安装升级路径。
- 保留仅下游提供的 Codex 设备码 OAuth、加密凭据、刷新与登出、专用模型目录/Responses 路由、Runtime 凭据隔离、Room `auth_mode` 和 `ultra` 档位。
- 将上游根 README 镜像到 `docs/README.md` / `docs/README_EN.md`，只调整相对链接；根 README 继续说明下游状态。

## 验证门禁

| 检查 | 状态 |
| --- | --- |
| 旧 v3.0.4 候选 6 项合并回归 | 本地定向测试已通过；修复提交 `0f6d17f`。 |
| v3.1.0 合并后 Debug 编译 | 本地 `:app:compileDebugKotlin` 与测试源码编译通过。 |
| OAuth/上下文压缩/迁移/版本/Provider 定向测试 | 本地通过；包含先失败再修复的 Codex 无工具请求与旧 v18 schema 测试。 |
| 完整 JVM 单测 | 本机 Windows 首轮 1280 项中 60 项失败、8 项跳过；其中 Codex 测试夹具已定向修复，其余多涉及缺少 `sh` 与 Windows 文件路径。以最终代码的 Linux CI 为出包门禁。 |
| Android Lint | 本地通过；后续修复由 Linux CI 再验证。 |
| 既有证书签名的 Release APK 与包信息核验 | 待 CI 产出。 |
| 覆盖安装、真实账号及关键功能试用 | 待用户验收。 |

2026-10-04 核对官方 Codex CLI 稳定 release `rust-v0.160.0`，peeled commit `a956835d020762cb2b570053af06f643a11c0ecc`。静态检查未发现设备码、刷新、固定端点、模型目录和 Responses SSE 的强制性不兼容变化；`CODEX_PROTOCOL_COMPAT_VERSION` 保持已完整验证的 `0.147.0`，真实账号与真机行为仍需验收。

本试用候选不创建下游 tag 或 GitHub Release；用户确认安装测试通过后再发布。
