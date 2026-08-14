# Eta 下游二次开发文档

根目录 `downstream/` 保存 `yangyunzhao/Eta` 相对上游 `Mangi-11/Eta` 的设计、开发计划和维护记录，避免与上游 `docs/` 混放。

## Codex OAuth

- [设计文档](CODEX_OAUTH_DESIGN.md)
- [开发计划](CODEX_OAUTH_DEVELOPMENT_PLAN.md)

当前阶段只完成设计与计划，尚未实现 Codex OAuth 功能。

## 仓库约定

- `origin`：`https://github.com/yangyunzhao/Eta.git`，用于提交和推送下游修改。
- `upstream`：`https://github.com/Mangi-11/Eta.git`，只用于获取上游更新，禁止推送。
- 后续持续 fetch 并 merge 上游更新到下游分支；保留下游历史，不对已发布提交执行 rebase 或 reset。
- 上游根 README 的更新合入 `docs/README.md` 和 `docs/README_EN.md`，不覆盖根目录的下游 README 与 `AGENTS.md`。
- 下游 build、tag、release 和发布资产统一使用 `v<上游版本>.znmlr.<下游序号>`；同一上游版本递增序号，上游版本变化时从 `1` 重新开始。
