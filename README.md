# Eta 下游开发版

这是 [Mangi-11/Eta](https://github.com/Mangi-11/Eta) 的个人下游 Fork，由 [yangyunzhao](https://github.com/yangyunzhao) 基于上游持续开发。

本仓库保留 Eta 原有的 Android 系统级 AI Agent、BYOK Provider、多轮工具调用、GUI Agent、设备直达、内置浏览器及 Root/Linux 能力。完整的上游项目介绍、安装要求、功能说明和许可证信息请阅读：

- [上游中文 README](docs/README.md)
- [Upstream README in English](docs/README_EN.md)

## 下游新增与规划

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| Codex OAuth 设备码登录 | 设计与开发计划已完成，功能代码尚未实现 | 在不影响现有 API Key 能力的前提下，只新增 `CODEX_OAUTH` 认证分支；登录必须使用设备码轮询，不使用浏览器回调、Deep Link 或本地回调服务 |

相关文档：

- [下游二次开发文档索引](downstream/README.md)
- [Codex OAuth 设计](downstream/CODEX_OAUTH_DESIGN.md)
- [Codex OAuth 开发计划](downstream/CODEX_OAUTH_DEVELOPMENT_PLAN.md)

## 与上游的关系

- `origin`：`https://github.com/yangyunzhao/Eta.git`，用于下游提交和推送。
- `upstream`：`https://github.com/Mangi-11/Eta.git`，只用于获取上游更新。
- 后续会持续获取并合并上游更新，同时保留下游提交历史；同步上游后需重新运行相关测试。
- 上游 README 的更新合入 `docs/README.md` 和 `docs/README_EN.md`，根 README 继续作为下游入口。
- 上游许可证继续适用于本 Fork；使用、修改和分发前请阅读 [PolyForm Noncommercial License 1.0.0](LICENSE)。

## 下游版本命名

下游 build、Git tag、GitHub Release 标题和发布资产统一使用：

```text
v<上游版本>.znmlr.<下游序号>
```

- 上游 `v2.6.0` 的首个下游版本是 `v2.6.0.znmlr.1`。
- 同一上游基线继续发布时递增为 `v2.6.0.znmlr.2`、`v2.6.0.znmlr.3`。
- 上游升级后序号重置；例如升级到 `v2.7.0` 后从 `v2.7.0.znmlr.1` 开始。
- `znmlr` 是固定的下游标签，release 和 tag 只发布到个人仓库 `origin`。

## 当前开发原则

- 原有 API Key、Anthropic 和自定义 Provider 能力保持不变。
- Codex OAuth 是新增的可选能力，失败时不得自动回退到 API Key，避免产生意外 API 账单。
- Codex OAuth 凭据不得进入 Room Provider 表、RemotePreferences、Binder、日志或测试快照。
- 自动测试不得调用真实或付费 AI 服务；真实 Codex 账号与共享额度只用于最终人工验收。
