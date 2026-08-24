# v2.6.0.znmlr.1 发布核对记录

> 本文件只记录脱敏后的发布证据。不得写入设备码、OAuth token、Account ID、签名密码、KeyStore 内容或服务端原始响应。

## 版本与来源

| 项目 | 结果 |
| --- | --- |
| 下游版本 | `v2.6.0.znmlr.1` / `versionCode 26001` |
| 功能代码基线 | `4247d79`；发布 tag `v2.6.0.znmlr.1` 最终指向 `16453f3c594c8f5d34d0bbba3aba0922ef190e39`，不移动历史 tag。 |
| 上游 release | `v2.6.0` |
| 上游 peeled commit | `4d02c2ca952830bc5a020612174f6035206879af` |
| 上游基线关系 | `v2.6.0^{}` 是候选提交祖先 |
| 最新 Codex CLI 稳定版（核对时间：2026-08-14） | `rust-v0.147.0`，非 draft、非 prerelease |
| Codex CLI tag object | `3ed6f04f6bf8b7c46299d1cb1ff99c74ce21a51d` |
| Codex CLI peeled commit | `be6e8eac029b183056b7e4402879f15d2c85f61b` |
| Eta Codex 协议兼容基线 | `0.147.0`，与最新稳定版一致 |

## 签名

| 项目 | 结果 |
| --- | --- |
| KeyStore | 保存在仓库外，并以当前 Windows 用户的 DPAPI 加密文件保存恢复材料 |
| GitHub Actions Secrets | 四项 Release 签名 Secret 已配置 |
| Key alias | `eta-znmlr-release` |
| 证书 SHA-256 | `44:4D:EB:65:E1:19:AE:74:38:6D:76:D2:16:FC:EE:70:62:B8:A9:0C:68:AF:28:DE:29:53:BE:24:D7:1D:C7:91` |

## 自动验证

| 门禁 | 状态 | 证据 |
| --- | --- | --- |
| Codex 模型目录定向回归 | 已完成 | `CodexModelsClientTest`、`RemoteModelFetcherTest`、`CodexResponsesProviderTest`、`ProviderClientFactoryTest` 与 Debug 构建在整合提交上通过。 |
| 完整 JVM 回归 | 部分完成 | 历史 v2.6.0 候选的 Windows 隔离快照共 669 项，仍为既有 5 类 8 项 Windows/Robolectric/POSIX 基线失败；协议相关 7 类定向测试 81/81 通过，未把旧快照表述为完整回归。 |
| Android Lint | 已完成 | 0 error、100 warnings。 |
| AndroidKeyStore instrumentation | 待完成 | 两次均在安装测试 APK 时被设备以 `INSTALL_FAILED_USER_RESTRICTED` 拒绝，0 项测试实际执行；不得记为通过。 |
| GitHub Actions main/tag 构建 | 已完成 | main run `31821661448` 与 tag run `31822538400` 均通过完整单元测试、Lint、签名恢复、Debug/Release 构建、APK 验证和上传。 |
| 本地 Release APK 签名、版本与证书指纹 | 已完成 | 主应用 `fuck.andes`，应用名 Eta，Launcher 存在，无 `debuggable`/`testOnly`；`2.6.0.znmlr.1` / `26001`；APK Signature Scheme v2 验证通过，证书指纹与本表一致。 |

## 真机人工验收

| 项目 | 状态 | 证据 |
| --- | --- | --- |
| 设备码授权 | 已完成（有限制） | Eta 保持前台、由 PC 打开固定验证页时授权成功；同机浏览器路径存在进程会话丢失限制。 |
| 普通问答 | 已完成 | 最小真实请求返回正确答案。 |
| 本地只读工具与后续回答 | 已完成 | 读取设备时间并完成工具结果后的后续回答。 |
| 进程重启凭据恢复 | 已完成 | 覆盖安装、强制停止并重启后未重新登录，仍能拉取 7 个模型并调用。 |
| Codex 模型目录 | 已完成 | 拉取 7 个可见模型，切换模型后调用正常。 |
| API Key 界面与配置保留 | 已完成（不适用） | 用户没有也不需要 API Key；人工 Platform API 验收不执行，原 UI/Factory 路径以自动回归为准。 |
| 注销与敏感 logcat 计数 | 待完成 | 注销会清除当前 Codex 登录，已取得用户许可，尚未执行。 |

## 发布状态

本地签名候选 APK 为 `Eta-v2.6.0.znmlr.1-release.apk`，大小 `5,499,876` 字节，SHA-256 为 `BADB88886369328A65D72D1294A2B9B166612738829C02053D99F4D31689B7EF`。最终 CI Release APK 大小为 `5,497,736` 字节，SHA-256 为 `0BD87A1AEB4D3F693EDEC206EB5812402122354D409FFCDD21BFEBEBEDBE0F5F`，签名证书与本表一致。tag `v2.6.0.znmlr.1` 精确指向 `16453f3c594c8f5d34d0bbba3aba0922ef190e39`，GitHub Release 已发布：https://github.com/yangyunzhao/Eta/releases/tag/v2.6.0.znmlr.1 。AndroidKeyStore instrumentation 与注销敏感日志计数未实际执行，必须作为本版本已知验证缺口保留；用户没有 API Key，因此对应人工网络验收不适用。
