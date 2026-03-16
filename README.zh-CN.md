# Codex Mobile

[![Android CI](https://github.com/aeewws/codex-mobile/actions/workflows/android-ci.yml/badge.svg)](https://github.com/aeewws/codex-mobile/actions/workflows/android-ci.yml)
[![MIT License](https://img.shields.io/github/license/aeewws/codex-mobile)](LICENSE)
![平台](https://img.shields.io/badge/platform-Android%209%2B-3DDC84)
![状态](https://img.shields.io/badge/status-active%20prototype-0A7EA4)

Codex Mobile 是一个 Android 应用，目标是把运行在 Termux 里的本地 Codex 后端，做成一个更像产品而不是终端工具的手机端体验。

它不是一个假的聊天壳，也不是把终端截图塞进 App，而是一个围绕真实本地 Codex 运行时去打磨的移动端外壳。

英文说明见 [README.md](README.md)。

快速入口：[项目说明](docs/project-brief.zh-CN.md) · [Setup](docs/setup.md) · [Roadmap](docs/roadmap.md) · [Contributing](CONTRIBUTING.md) · [Security](SECURITY.md)

## 为什么做这个项目

Codex 的终端工作流很强，但它并不是一个适合手机触摸使用的产品。这个项目不是给终端套一个外壳，而是想把本地 AI coding workflow 收成一个更像移动产品的体验。

当前方向主要是：

- 聊天优先的移动端交互
- 后台恢复、重连和线程续接
- 本地后端生命周期管理
- 把模型、智力、权限等控制做成手机上能用的界面

## 这个项目不一样的地方

- 接的是真实运行在 Termux 里的本地 Codex 后端，不是假聊天壳
- 重点不是“能不能聊天”，而是手机上的线程恢复、重连和长会话可用性
- 后端拉起、保活和运行时硬化本身就是产品设计的一部分
- 项目是在真实日常设备上持续使用和打磨出来的，不是纯演示工程

## 当前状态

这个项目不是概念演示，而是一个还在持续打磨的活跃原型。

- 基于 Jetpack Compose 的 Android App
- 后端运行在 Termux 里的社区版 Codex CLI
- 依赖 root 做后端拉起和保活加固
- 当前 UI 仍以中文为主
- 目前默认面向 rooted Android 场景

仓库公开的是 App 工程本身，不是一份一键还原某台手机的整机镜像。

## 截图

| 添加内容 | 历史会话 | 设置 |
| --- | --- | --- |
| ![添加内容](docs/images/attachments.jpg) | ![历史会话](docs/images/history.jpg) | ![设置](docs/images/settings.jpg) |

## 当前能力

- 自动拉起并连接本地 Codex 后端
- 在手机上继续真实 Codex 线程
- 历史会话、归档、恢复、重命名、删除流程
- 模型切换、智力档位切换、权限模式和 Fast 模式
- 支持对部分文档附件做内容提取
- 对长对话和移动端不稳定场景做了恢复优化

## 仓库维护信号

- Android CI 会在 pull request 和 push 到 `main` 时运行
- 仓库已经补上 issue template 和 PR template
- Dependabot 已配置用于依赖维护
- 也补了基础的安全说明和代码所有权文件

## 环境与运行预期

当前预期环境：

- Android 9+ 设备
- 手机上安装 Termux
- Termux 中安装社区版 Codex CLI
- 先在 Termux 内完成本地登录
- 当前设计依赖 root 权限

更详细的说明见 [docs/setup.md](docs/setup.md)。

更准确地说，这个项目更适合被理解为“围绕本地 coding runtime 的 Android 产品外壳”，而不是一个无需环境假设就能直接跑起来的通用 App。

## 开发

构建渠道：

- `legacyDebug` 保持和你手机当前可用安装线一致，方便覆盖升级
- `ossDebug` 使用公开仓库对应的 `io.github.aeewws.codexmobile` 包名，适合开源分发

本地常用命令：

```bash
./gradlew testLegacyDebugUnitTest testOssDebugUnitTest
./gradlew assembleLegacyDebug
./gradlew assembleOssDebug
```

仓库里也补上了 GitHub Actions，会在 push 和 pull request 时同时构建两条 debug 渠道。

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
