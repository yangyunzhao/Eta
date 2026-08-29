# v2.6.5.znmlr.1 候选核对记录

> 本文件记录本地候选验证，不代表已发布。不得写入设备码、OAuth token、Account ID、签名密码、KeyStore 内容或服务端原始响应。

## 版本与来源

| 项目 | 结果 |
| --- | --- |
| 下游候选版本 | `v2.6.5.znmlr.1` / `versionCode 26501` |
| 本地 merge 提交 | `9fe46cd07290db6fca0fba553e2cf0def77d2e66` |
| 上游 release | `v2.6.5` |
| 上游 peeled commit | `bf0c6fee5968b1f1f31ec4dece1201082d17226c` |
| 上游基线关系 | `v2.6.5^{}` 是本地候选提交祖先；未合入后续 `upstream/main` |
| 当前正式发布 | `v2.6.2.znmlr.1` / `26201` |

## 本地自动验证

| 门禁 | 状态 | 证据 |
| --- | --- | --- |
| Kotlin 编译 | 已完成 | `:app:compileDebugKotlin` 成功。 |
| MCP/恢复链/OAuth 定向回归 | 已完成 | MCP 协议/上下文、checkpoint/recovery、OpenAI/Codex Responses、OAuth、模型目录、Runtime Wire 与 Room 迁移通过。 |
| 完整 JVM 回归 | 部分完成 | 764 项，仍有 8 个既有 Windows/Robolectric/POSIX 基线失败，无新增 v2.6.5/OAuth/MCP 失败。 |
| Android Lint | 已完成 | `:app:lintDebug` 成功。 |
| Debug APK | 已完成 | `:app:assembleDebug` 成功，`2.6.5.znmlr.1` / `26501`。 |
| 本地签名 Release APK | 已完成 | applicationId、版本与证书已核验。 |
| GitHub Actions main/tag | 待完成 | 尚未推送本地候选。 |

## 本地签名 Release APK

| 项目 | 结果 |
| --- | --- |
| 文件 | `release/Eta-v2.6.5.znmlr.1-release.apk` |
| applicationId | `fuck.andes` |
| 版本 | `2.6.5.znmlr.1` / `26501` |
| 大小 | `6,088,872` 字节 |
| SHA-256 | `11B0FBBFAE6F6B70BB3DCD1BB65D9BA73B7F79825D2DC6E45EE9129645FD2545` |
| 签名 | APK Signature Scheme v2，证书 SHA-256 `44:4D:EB:65:E1:19:AE:74:38:6D:76:D2:16:FC:EE:70:62:B8:A9:0C:68:AF:28:DE:29:53:BE:24:D7:1D:C7:91` |

## 已知验证缺口

- AndroidKeyStore instrumentation 与注销后的敏感 logcat 匹配计数仍未完成，不能追记为通过。
- 当前仅为本地候选；在 main/tag CI、CI Release APK 核验和远端回执完成前，不推送 `v2.6.5.znmlr.1` tag，也不创建 GitHub Release。
