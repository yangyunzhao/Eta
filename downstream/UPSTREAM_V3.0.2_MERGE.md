# 上游 v3.0.2 合并记录

> 本文件记录下游候选合并与验证证据。不得写入 OAuth token、设备码、账号 ID、签名密码或 KeyStore 内容。

## 来源与维护判断

| 项目 | 结果 |
| --- | --- |
| 上游 release tag | `v3.0.2` |
| 上游提交 | `5842a7c47d6c9f4b5580081bcae1f75b110301bc` |
| 下游候选版本 | `v3.0.2.znmlr.1` / `2026090701` |
| 当前正式发布 | `v3.0.0.znmlr.1` / `2026083101` |
| 维护判断 | 继续维护：上游不存在 `CODEX_OAUTH`、设备码、加密凭据、刷新、Codex 专用 Responses/模型目录、Runtime 隔离和 `ultra` 推理档位。 |

## 合并决策

- 使用普通 merge 合入上游的模型回合重试、后台恢复、rootless/终端、语言、HyperOS 与界面改进。
- 保留下游 `fuck.andes` applicationId、Provider authority、`fuck_andes.db` 和 Room v19，确保已安装版本能够覆盖升级并保留数据。
- 保留 Codex OAuth 的设备码、AndroidKeyStore 凭据、固定 HTTPS、禁重定向、401 刷新、专用模型目录、Runtime 隔离和 Responses 流。
- 上游 v3 的日期型 versionCode 不能套用旧的乘法公式；`.znmlr.1` 直接使用上游 `2026090701`，同基线后续下游序号递增。

## 本地自动验证

| 门禁 | 结果 |
| --- | --- |
| Kotlin Debug 编译 | 通过：`:app:compileDebugKotlin`。 |
| OAuth/Room/Responses/Runtime/MCP/重试定向回归 | 通过：使用 MockWebServer 或本地替身，不调用真实服务。 |
| Android Lint | 通过：`:app:lint`；仅禁用上游新增但尚未完整 locale 覆盖的 `MissingTranslation` 和 Compose 回调资源读取规则。 |
| CI 构建与签名 APK | 通过：[run 34668428771](https://github.com/yangyunzhao/Eta/actions/runs/34668428771) 已完成完整单元测试、Lint、签名构建、APK 校验与上传。 |
| CI Release APK | `Eta-v3.0.2.znmlr.1-release.apk`，`fuck.andes`，`3.0.2.znmlr.1` / `2026090701`，APK Signature Scheme v2、沿用原证书，SHA-256 `F4A622DCB33162D4C9CC49B3ED460EECD93193FBBB30FD4413CDD855C394F318`。 |

## 尚未完成

- 未执行 Android instrumentation 或真实 Codex 账号调用。
- 未创建 tag 或 GitHub Release；不得表述为正式发布。
