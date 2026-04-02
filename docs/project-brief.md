# Codex Mobile 项目说明

这是一份公开可见的项目说明页，用来统一回答三个问题：这个项目是什么、为什么值得做、以及对外应该怎么描述。

需要英文版时，请看 [project-brief.en.md](./project-brief.en.md)。

## 一句话介绍

Codex Mobile 是一个围绕真实本地 Codex 运行时构建的 Android 移动端外壳。

## 这个项目是什么

Codex Mobile 的目标，是把运行在 Termux 里的本地 Codex 后端，做成一个更像产品、而不是终端工具的 Android 体验。

它不是：

- 一个假的聊天壳
- 一个把终端截图塞进 App 的包装层
- 一个没有运行时假设的通用 AI 客户端

它是：

- 一个围绕真实本地 Codex 运行时构建的移动端 UI
- 一个把 Android 产品交互和 Termux 后端执行接起来的桥接层
- 一个让本地 AI coding workflow 在真实手机上变得可用的产品化尝试

## 为什么这个方向有价值

大多数本地 coding agent 工作流都是终端优先、桌面优先的。它们很强，但放到真实手机上之后，可用性会立刻掉很多。

Codex Mobile 试图解决的是另一层问题：

- 手机上的线程恢复和重连
- 更像产品的历史会话与生命周期管理
- 在触摸界面里暴露模型、智力、权限等控制项
- Android 上后台、保活、易断连等运行时约束

## 当前公开信号

- 公开 GitHub 仓库
- MIT 许可证
- PR 与 `main` 上的 CI
- 已发布 release
- `SECURITY.md`、`CODEOWNERS`、Dependabot 等维护者文件
- setup 和 roadmap 文档

## 当前已经公开出来的能力

- 基于 Jetpack Compose 的 Android App
- 连接到 Termux 本地 Codex 运行时的桥接层
- 历史会话、归档、恢复、重命名、删除流程
- 模型切换、智力切换、权限模式和 Fast 模式
- 支持部分文档附件的内容提取
- 面向长线程和移动端不稳定场景的恢复处理

## 这个项目为什么不只是一个前端壳

- 接的是真实本地运行时，不是 mocked interface
- Android 的后台、重连、恢复，本来就是产品问题的一部分
- 后端生命周期属于 UX 的一部分，不只是安装说明
- 它要同时处理产品交互和 rooted device / local runtime 的约束

## 可直接复制的对外文案

### 短版

Codex Mobile 是一个围绕真实本地 Codex 运行时构建的 Android 移动端外壳，重点解决线程恢复、会话生命周期和触摸优先交互。

### 中版

Codex Mobile 是一个 Android 产品层，底层连接的是运行在 Termux 里的真实本地 Codex 运行时。它不是把终端直接搬上手机，而是补上聊天、历史、重连、模型控制和运行时生命周期管理这些手机端真正需要的交互。

### 长版

Codex Mobile 是一个 Android 应用，目标是把运行在 Termux 里的本地 Codex 后端做成可在真实手机上使用的产品。这个项目关注的不只是界面，而是 coding agent 工作流离开桌面后真正会遇到的问题，例如线程恢复、重连、本地后端生命周期、移动端历史管理，以及如何在不暴露原始终端 UX 的情况下提供运行时控制。它不是假的聊天壳，而是一层围绕真实本地 coding runtime 的移动端产品外壳。

## 对外定位建议

如果你要对评审、用户、维护者描述这个项目，最稳的说法是：

1. 它是一个围绕真实本地运行时构建的 mobile-first shell。
2. 它做的是把本地 AI coding workflow 产品化到 Android。
3. 真正难的不是“做个界面”，而是可靠性、生命周期、恢复和移动端交互。

## 最近这段时间的进展

最近一轮仓库工作主要强化了公开维护信号：

- 首页 README 切到中文优先
- 文档入口调整为中文在前、英文在后
- CI 工作流更完整
- 发了正式 release
- 加了安全说明和维护者文件
- 源码路径和真实包名对齐

## 相关链接

- 仓库主页：[aeewws/codex-mobile](https://github.com/aeewws/codex-mobile)
- Release：[v0.3.0](https://github.com/aeewws/codex-mobile/releases/tag/v0.3.0)
- 环境与安装：[docs/setup.md](./setup.md)
- 路线图：[docs/roadmap.md](./roadmap.md)
