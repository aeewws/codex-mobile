# Codex Mobile

Codex Mobile 是一个 Android 应用，目标是把运行在 Termux 里的本地 Codex 后端，做成一个更像产品而不是终端工具的手机端体验。

英文说明见 [README.md](README.md)。

## 为什么做这个项目

Codex 的终端工作流很强，但它并不是一个适合手机触摸使用的产品。这个项目不是给终端套一个外壳，而是想把本地 AI coding workflow 收成一个更像移动产品的体验。

当前方向主要是：

- 聊天优先的移动端交互
- 后台恢复、重连和线程续接
- 本地后端生命周期管理
- 把模型、智力、权限等控制做成手机上能用的界面

## 当前状态

这个项目不是概念演示，而是一个还在持续打磨的活跃原型。

- 基于 Jetpack Compose 的 Android App
- 后端运行在 Termux 里的社区版 Codex CLI
- 依赖 root 做后端拉起和保活加固
- 当前 UI 仍以中文为主
- 目前默认面向 rooted Android 场景

仓库公开的是 App 工程本身，不是一份一键还原某台手机的整机镜像。

## 截图

| 聊天页 | 命令页 |
| --- | --- |
| ![聊天页](docs/images/chat.png) | ![命令页](docs/images/commands.png) |

## 当前能力

- 自动拉起并连接本地 Codex 后端
- 在手机上继续真实 Codex 线程
- 历史会话、归档、恢复、重命名、删除流程
- 模型切换、智力档位切换、权限模式和 Fast 模式
- 支持对部分文档附件做内容提取
- 对长对话和移动端不稳定场景做了恢复优化

## 环境与运行预期

当前预期环境：

- Android 9+ 设备
- 手机上安装 Termux
- Termux 中安装社区版 Codex CLI
- 先在 Termux 内完成本地登录
- 当前设计依赖 root 权限

更详细的说明见 [docs/setup.md](docs/setup.md)。

## 开发

本地常用命令：

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

仓库里也补上了 GitHub Actions，会在 push 和 pull request 时构建 debug APK。

## 仓库边界

这个仓库只公开 App 工程本身，不公开设备私有运行环境。

仓库里不包含：

- Termux 登录态和认证文件
- 本地 Codex 会话历史
- 运行环境备份压缩包
- 设备专用代理或 root 配置
- 私有调试产物

## 当前限制

- UI 目前仍以中文为主
- 后端行为会受到 Termux 内 Codex 包版本影响
- Android 与 root 相关配置仍然是运行时的一部分
- 图片输入能力已经在后端验证过，但前端主界面还没接上
- 长线程和重连稳定性还在持续加固中

## 开源推进方向

近期计划见 [docs/roadmap.md](docs/roadmap.md)。

如果你想参与贡献，可以先看 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 说明

这个项目与 OpenAI、Termux 官方没有隶属关系，当前运行时依赖的是社区封装的 Codex CLI。

## 许可证

本项目使用 [MIT License](LICENSE)。
