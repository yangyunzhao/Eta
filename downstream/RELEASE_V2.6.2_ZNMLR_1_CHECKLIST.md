# v2.6.2.znmlr.1 发布核对记录

> 仅记录脱敏发布证据。不得写入设备码、OAuth token、Account ID、签名密码、KeyStore 内容或服务端原始响应。

## 版本与来源

| 项目 | 结果 |
| --- | --- |
| 下游候选版本 | `v2.6.2.znmlr.1` / `versionCode 26201` |
| 功能合并提交 | `266af06aeb9a76119b838506788d6d623963fc6e` |
| 上游 release | `v2.6.2` |
| 上游 peeled commit | `bd4a14ceeda81b9063b9fde91b14e47f3851929f` |
| 上游基线关系 | `v2.6.2^{}` 是候选提交祖先；未合入后续 `upstream/main` |
| 最新 Codex CLI 稳定版（核对时间：2026-08-24） | `rust-v0.149.1`，非 draft、非 prerelease |
| Codex CLI tag object / peeled commit | `980a6d12110b110d29ec13bdcbe14011100b3566` / `ff29a44391deccde0aba0f8390337d7f3c319ea4` |
| Eta 协议兼容基线 | `0.147.0`；不机械升级，已与 `0.149.1` 比对 |

## Codex CLI 0.149.1 差异结论

- 设备码授权、PKCE、轮询和 token endpoint 契约与 `0.147.0` 相同。
- Responses endpoint、通用 Header、请求 body 与 SSE 基础终态文件相同；CLI 新增的条件性 `x-codex-routing-hint` 是其自身路由优化，不是 Eta 直连的必填 Header。
- 普通 ChatGPT OAuth 模型目录的 `/models?client_version=0.147.0` 请求与 schema 未变；企业 managed-residency Header 不适用于普通路径。
- `invalid_grant` 的 HTTP 400 需永久失效并重新登录，现有 `CodexOAuthManager` 已等效映射为 `REAUTHENTICATION_REQUIRED`。
- SSE 新增的 safety-buffering/策略错误分类与未知 delta 降噪不影响 Eta 当前文本、工具调用与终态消费；作为后续观察项。

## 自动与本地构建验证

| 门禁 | 状态 | 证据 |
| --- | --- | --- |
| Kotlin 编译 | 已完成 | `:app:compileDebugKotlin` 成功。 |
| OAuth/Provider/迁移定向回归 | 已完成 | 最终 137 项通过；包含 Room v16 两种 v15 历史、Codex Responses、模型目录、Runtime、Provider 与设备码 UI。 |
| 设备码公开 URL | 已完成 | 先 RED 后 GREEN：显示并复制固定 `https://auth.openai.com/codex/device`，不复制验证码或服务端 URL。 |
| 完整 JVM 回归 | 部分完成 | 714 项、8 个既有 Windows/Robolectric/POSIX 基线失败，无新增 OAuth 失败。 |
| Android Lint | 已完成 | `:app:lintDebug` 成功。 |
| 本地签名 Release APK | 已完成 | applicationId `fuck.andes`，`2.6.2.znmlr.1` / `26201`，APK Signature Scheme v2 验证通过。 |
| GitHub Actions main/tag | 已完成 | main run `32696174121` 与 tag run `32697222715` 均通过完整单元测试、Lint、签名恢复、Debug/Release 构建、APK 校验和上传。 |

## 本地 Release APK

| 项目 | 结果 |
| --- | --- |
| 本地文件 | `release/Eta-v2.6.2.znmlr.1-release.apk` |
| 大小 | `5,967,684` 字节 |
| SHA-256 | `A15CB05A9B043C6D1EA2846CBAA5EB80050CB5678360B46018A9161021601EC3` |
| 签名证书 SHA-256 | `44:4D:EB:65:E1:19:AE:74:38:6D:76:D2:16:FC:EE:70:62:B8:A9:0C:68:AF:28:DE:29:53:BE:24:D7:1D:C7:91` |

## 最终 CI Release 与发布

| 项目 | 结果 |
| --- | --- |
| tag | `v2.6.2.znmlr.1` → `005c8a9b5c565cc4b3300846833647e35efb77ea` |
| CI Release APK 大小 | `5,965,548` 字节 |
| CI Release APK SHA-256 | `2BB9C10E8A3A484511433814FCB7B5C5BFE344E93D951022455E292EE6B314E1` |
| GitHub Release | `https://github.com/yangyunzhao/Eta/releases/tag/v2.6.2.znmlr.1` |

## 人工验收与已知缺口

- 用户已手动安装并报告基础使用未见异常；不以此替代自动验证。
- AndroidKeyStore instrumentation 曾被设备以 `INSTALL_FAILED_USER_RESTRICTED` 阻止，尚未实际执行。
- 注销后的敏感 logcat 匹配计数尚未执行。
- 同机浏览器可能回收 Eta 进程；可靠设备码路径是让 Eta 保持前台、由电脑或另一设备打开固定验证页。

GitHub Actions main/tag、CI Release APK 签名/版本/哈希核验和远端回执均已完成，`v2.6.2.znmlr.1` 已正式发布。AndroidKeyStore instrumentation 与注销敏感日志计数仍为已知验证缺口，不得追记为通过。
