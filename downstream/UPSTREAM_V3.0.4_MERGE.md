# 上游 v3.0.4 合并记录

> 本文件记录下游候选合并与验证证据。不得写入 OAuth token、设备码、账号 ID、签名密码或 KeyStore 内容。

## 来源与维护判断

| 项目 | 结果 |
| --- | --- |
| 上游 release tag | `v3.0.4` |
| 上游提交 | `bc952e27169bf83dbef86380a54fc5b159e5609d` |
| 下游候选版本 | `v3.0.4.znmlr.1` / `2026091202` |
| 当前正式发布 | `v3.0.2.znmlr.1` / `2026090701` |
| 维护判断 | 继续维护：上游不存在 `CODEX_OAUTH`、设备码、加密凭据、刷新、Codex 专用 Responses/模型目录、Runtime 隔离和 `ultra` 推理档位。 |

## 合并决策

- 普通 merge 合入上游的角色扮演、上下文压缩、对话搜索/导出、流式体验与 Runtime/持久化改进。
- 保留下游 `fuck.andes` applicationId、Provider authority、`fuck_andes.db`、Room `auth_mode` 与旧安装迁移链。
- 保留 Codex OAuth 设备码、AndroidKeyStore 凭据、固定 HTTPS、禁重定向、401 刷新、专用模型目录、Runtime 隔离和 Responses 流。
- Room 在下游兼容 schema 上合入上游 v19→20 上下文快照/分块文本和 v20→21 角色字段/表的升级。

## 本地自动验证

| 门禁 | 结果 |
| --- | --- |
| Kotlin Debug 编译 | 通过：`:app:compileDebugKotlin`。 |
| OAuth/上下文压缩/角色/Runtime/DB/模型目录定向回归 | 通过：使用 MockWebServer 或本地替身，不调用真实服务。 |
| Android Lint | 通过：`:app:lint`。 |
| Release 构建 | 待本候选 merge commit 上的签名 CI 执行。 |

## 已知范围

- 为保留下游设备码 OAuth 设置页，尚未接入上游 v3.0.4 的 Provider 自定义请求头编辑 UI；其他角色、压缩和 Runtime 改动已合入。
- 未执行完整 JVM 回归、Android instrumentation 或真实 Codex 账号调用。
- 未创建 tag 或 GitHub Release；不得表述为正式发布。
