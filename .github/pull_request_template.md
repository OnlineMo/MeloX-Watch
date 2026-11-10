## 目的

关联 Issue：Closes #

说明问题、触发方式及修改后的行为。

## 修改范围

- 主要改动：
- 上游复用来源（仓库、提交、路径；没有则填无）：
- 兼容性或已知限制：

## 验证

- 云端 Actions run：
- 被验证的 source SHA：
- APK artifact / SHA-256（文档变更填不适用）：
- 本地验证设备 / 固件 / 结果（未测须明确写未测）：

## 提交检查

- [ ] 目标分支为 `contribution`；合入 `main` 仅限所有者的 promotion PR。
- [ ] 未修改版本号；未混入签名、令牌、真实邮箱或研究快照。
- [ ] 未修改 `.github/workflows/` 或 `.github/actions/`；需要改 Actions 时另提 Issue。
- [ ] 提交不含 AI 协作者、Co-authored-by 尾注或 AI 自动署名。
- [ ] Android 编译、Gradle 单测和 lint 使用云端，未在本地编译。
- [ ] 已说明失败场景及验证的实际范围，没有把跳过当成通过。
