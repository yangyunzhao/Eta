# v3.1.0.znmlr.2

GitHub Release：<https://github.com/yangyunzhao/Eta/releases/tag/v3.1.0.znmlr.2>。

基于上游 [Eta v3.1.0](https://github.com/Mangi-11/Eta/releases/tag/v3.1.0) 的下游发布。合入语音交互、屏幕上下文、社区模型提供商目录、聊天渲染和上下文压缩改进；继续提供本 Fork 的 Codex OAuth 设备码登录、加密凭据、固定 Codex Responses/模型目录和 `ultra` 推理档位。

修复从已发布下游 v3.0.2（Room v19）覆盖安装时，旧数据库缺少运行上下文列导致启动闪退的问题。迁移会补齐缺失列并保留原有运行记录、会话和 Provider 配置。安装时请直接覆盖现有 Eta，不要卸载或清除应用数据。升级后需在模型提供商设置中手动填写所选模型的上下文窗口大小。

包名：`fuck.andes`；版本码：`2026100202`。Release APK 沿用此前下游签名证书，证书 SHA-256：`444deb65e119ae74386d76d216fcee7062b8a90c68af28de2953be24d71dc791`。

正式 tag 提交：`f935f6c3f97ca83b56c38d128bbbe65b6a40a467`。[tag CI run 37216798359](https://github.com/yangyunzhao/Eta/actions/runs/37216798359) 已通过完整单元测试、Android Lint、签名构建和 APK 校验。附件 `Eta-v3.1.0.znmlr.2-release.apk` 的 SHA-256：`2365F2885DF297A551B1268AE2B0B56AAE4B50848604EA1D27B06751D71D4451`。

已完成真机覆盖安装及基本使用测试。Codex 设备码登录需要手机网络能够访问固定的 `auth.openai.com` 认证域名；当前移动网络若返回 HTTP 403，请切换可用网络后重试。AndroidKeyStore instrumentation 与真实账号更全面的边界测试仍未执行。
