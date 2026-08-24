# Eta 发布流程

## 配置签名 Secrets

发布证书和密码不得提交到 Git。首次使用前，在仓库的
`Settings > Secrets and variables > Actions` 中添加：

- `ETA_RELEASE_KEYSTORE_BASE64`：发布证书的 Base64 文本
- `ETA_RELEASE_STORE_PASSWORD`：KeyStore 密码
- `ETA_RELEASE_KEY_ALIAS`：Key alias
- `ETA_RELEASE_KEY_PASSWORD`：Key 密码

macOS 可以用下面的命令复制证书的 Base64 文本：

```bash
base64 < /path/to/Eta-release.jks | tr -d '\n' | pbcopy
```

也可以使用 GitHub CLI。密码类 Secret 不要直接写在命令参数中，运行命令后按提示输入：

```bash
base64 < /path/to/Eta-release.jks | gh secret set ETA_RELEASE_KEYSTORE_BASE64
gh secret set ETA_RELEASE_STORE_PASSWORD
gh secret set ETA_RELEASE_KEY_ALIAS
gh secret set ETA_RELEASE_KEY_PASSWORD
```

## 构建与发布

以下情况会在同一次工作流中生成 Debug APK 和经过签名验证的 Release APK，
并作为两个可直接下载的 Actions Artifact 保存 14 天：

- 向 `main` 推送提交
- 推送 `v*.znmlr.*` 标签
- 在 GitHub 的 `Actions > Eta Build` 中手动运行

构建 APK 前，工作流会运行 `:app:testDebugUnitTest` 和 `:app:lint`；任一检查失败都会终止构建。
工作流只构建、验证和上传 Artifact，不会创建或修改 GitHub Release，也不会创建、
移动或推送 Git 标签。

## 版本规则

发布版本必须采用 `v<上游版本>.znmlr.<下游序号>`：

- `versionName` 不含开头的 `v`，例如 `2.6.0.znmlr.1`。
- Git 标签、Release 标题和 APK 文件名使用带 `v` 的完整版本。
- `znmlr` 固定为小写。
- 同一上游版本的下游序号从 `1` 开始递增；上游版本变化后重置为 `1`。
- 上游版本基线必须来自已验证的上游 release tag 及其 peeled commit。
- `versionCode` 必须在每次已发布构建之间严格单调递增，不得降低或复用。
  当前构建脚本按 `上游 versionCode × 100 + 下游序号` 计算，因此下游序号必须在
  `1..99` 内；发布前还要确认计算结果大于上一个已发布 APK 的 `versionCode`。

正式发布前，在 `app/build.gradle.kts` 中更新上游版本、上游 `versionCode` 和下游序号，
确认计算出的 `versionName` 后，再创建与 `v${versionName}` 严格一致的标签。
例如发布首个基于上游 `v2.6.0` 的下游版本：

```bash
git tag v2.6.0.znmlr.1
git push origin v2.6.0.znmlr.1
```

标签触发时，工作流会拒绝不符合命名规则的标签，并在构建后核对标签是否严格等于
`v${versionName}`，同时验证 Debug APK、Release APK 与 Gradle 输出元数据中的
`versionName` 完全一致。

标签推送后，等待 `Eta Build` 工作流完成。以 `v2.6.0.znmlr.1` 为例，工作流会生成：

- `Eta-v2.6.0.znmlr.1-debug.apk`
- `Eta-v2.6.0.znmlr.1-release.apk`

两个 Actions Artifact 的名称也包含同一完整版本。然后：

1. 从该次工作流的 `Artifacts` 下载 `Eta-v2.6.0.znmlr.1-release.apk`。
2. 在仓库的 `Releases > Draft a new release` 中选择已有标签。
3. 使用同一标签作为 Release 标题，填写 Release Notes 并上传版本化 APK。
4. 检查版本、说明和附件后，由维护者手动发布。
