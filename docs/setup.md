# Codex Mobile 环境与安装说明

这份文档是中文优先入口。英文版请看 [setup.en.md](./setup.en.md)。

Codex Mobile 当前面向 rooted Android + 本地 Termux Codex 运行时这一类使用方式。

## 你需要准备什么

- Android Studio 和 Android SDK
- 一台 Android 9+ 设备
- 手机上安装好的 Termux
- Termux 里能正常工作的 Codex CLI 包
- 已经在 Termux 内完成本地登录
- 如果你想走当前这套后端生命周期方案，还需要 root 权限

## 这个仓库不包含什么

这个仓库公开的是 Android App 工程本身，不包含：

- 你的 Termux 登录态
- 你的本地 Codex 会话历史
- 你的代理配置
- 一整套完整导出的手机运行环境

## 高层启动流程

1. 克隆仓库并用 Android Studio 打开。
2. 同步 Gradle，并安装缺失的 Android SDK 组件。
3. 先把手机侧的 Termux 运行环境准备好。
4. 先确认 Codex 能在 Termux 内独立正常运行，再测试 Android App。
5. 用 Android Studio、`./gradlew assembleLegacyDebug` 或 `./gradlew assembleOssDebug` 构建并安装应用。
6. 打开 App，重点验证后端发现、重连行为和线程加载是否正常。

## Termux 运行时说明

仓库里带了一个辅助脚本 [`tools/termux-codex-update.sh`](../tools/termux-codex-update.sh)，用来更新社区版 Termux Codex 包，并在需要时重启本地 app-server。

当前 App 预期本地 websocket 端点是 `ws://127.0.0.1:8765`。

## 当前约束

- rooted Android 仍然是当前产品设计的一部分
- 后端行为会受到 Termux 内 Codex 包版本影响
- 实际部署仍然包含电源管理、root 工具链、代理等设备侧决策
- App 界面与仓库说明目前都以中文优先

## 排查清单

- 确认 Termux 已安装并且能正常启动
- 确认测试保活能力时，App 能拿到 root
- 确认 Termux 里已经有 Codex 登录态
- 确认本地 app-server 能监听 `8765`
- 提 issue 之前尽量先准备截图或日志
