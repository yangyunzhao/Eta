# AGENTS.md

本文件约束 `yangyunzhao/Eta` 下游 Fork 中的自动化开发工作，作用域为整个仓库。

## 仓库与远程

- `origin` 必须指向 `https://github.com/yangyunzhao/Eta.git`，用于下游提交和推送。
- `upstream` 必须指向 `https://github.com/Mangi-11/Eta.git`，只允许 fetch；不得向 upstream 推送。
- 同步上游前先检查工作区、提交图和 ahead/behind，不使用 `git reset --hard`、`git clean -fd` 或强制推送覆盖下游工作。
- 只有用户明确要求提交或推送时才执行；推送后必须核对远端提交。

## 上游同步

- 后续必须持续从 `upstream` 获取上游代码，并合并到我们的下游分支；不得把 Fork 固定在某个上游快照后停止同步。
- 同步前执行 `git fetch upstream --prune --tags`，确认工作区状态、当前分支、共同祖先和 ahead/behind，再把目标上游分支合并到当前下游分支。
- 已发布的下游提交不得通过 rebase 或 reset 改写；存在下游分叉时使用普通 merge 保留双方历史，只有没有下游分叉时才允许 fast-forward。
- 合并冲突必须按文件语义解决，不能整侧覆盖。尤其是上游根 `README.md`、`README_EN.md` 的更新，应合入 `docs/README.md`、`docs/README_EN.md`；根 `README.md` 和 `AGENTS.md` 保持下游入口与规则文件的职责。
- 合并完成后运行与变更范围匹配的自动测试和构建，更新根 README 的下游能力状态，并记录本次采用的上游提交或 release tag。

## 下游版本与发布命名

- 下游 build、Git tag、GitHub Release 标题及发布资产使用统一标识：`v<上游版本>.znmlr.<下游序号>`。
- `znmlr` 是固定的小写下游标签，不得替换、删减或改变大小写。
- 下游序号是从 `1` 开始的正整数。同一上游版本上的后续下游发布依次递增：`.znmlr.1`、`.znmlr.2`、`.znmlr.3`。
- 上游版本发生变化时，下游序号重置为 `1`。例如，上游为 `v2.6.0` 时首个下游版本为 `v2.6.0.znmlr.1`；同基线下一版为 `v2.6.0.znmlr.2`；上游升级到 `v2.7.0` 后首版为 `v2.7.0.znmlr.1`。
- 版本基线必须来自已验证的上游 release tag 及其 peeled commit，不能只根据 `upstream/main`、README 或旧 `versionName` 推断。
- 发布前确认目标 tag 尚不存在、构建产物来自该 tag 对应提交、自动测试和构建通过；已经发布的 tag 不得移动或覆盖。
- release 和 tag 只能发布到 `origin`，不得向 `upstream` 创建分支、tag 或 release。

## 文档边界

- 根目录 `README.md` 是下游项目入口，必须准确区分“已经实现”和“设计/计划中”的能力。
- `docs/README.md` 与 `docs/README_EN.md` 保存上游完整项目说明；移动或同步时必须修复并验证相对链接。
- 根目录 `downstream/` 保存本 Fork 的设计、开发计划、验收和维护记录，不放入 `docs/`。
- 新增功能或状态变化后，同时更新根 README 和对应 downstream 文档，不得提前宣称尚未实现的能力已经可用。

## Codex OAuth 约束

- 现有 API Key、Anthropic、自定义 Provider 和 Agent Runtime 行为不得因 Codex OAuth 改造而改变。
- 只新增 `CODEX_OAUTH`；空认证模式继续表示当前 API Key 路径，不新增 `API_KEY` 类型。
- 登录只允许 Codex 设备码轮询。禁止 OAuth 浏览器回调、Deep Link、WebView 登录和本地 HTTP 回调服务器。
- OAuth 端点和 Codex Responses 端点必须固定为 HTTPS，不受自定义 Base URL 或 cleartext 网络配置影响。
- access token、refresh token、ID token、账号 ID、设备码和 PKCE 材料不得进入 Room Provider 表、RemotePreferences、Binder、日志、异常正文或测试快照。
- OAuth 失败不得自动切换到 API Key；真实 Codex 调用必须集中在最终人工验收并明确消耗共享额度。

## 修改与验证

- 修改前检查 `git status --short --branch` 和相关文件的现有差异，保留用户与其他代理的修改。
- 优先小范围、可回退的改动；多个代理不得同时编辑同一文件。
- 功能开发采用定向测试优先：先验证失败，再实现，再运行相关模块测试。
- 自动测试使用 MockWebServer、测试密钥或本地替身，不调用真实 OpenAI/Codex 或其他付费服务。
- 完成前至少运行与改动匹配的定向测试、`git diff --check` 和必要的完整回归；无法执行的验证必须明确说明。
- 最终报告说明修改内容、验证结果、未验证项和遗留风险。
