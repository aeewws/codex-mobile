# Codex Mobile 状态记录

更新时间：2026-03-09

## 项目定位

这个项目现在的定位已经比较明确：

- `Codex Mobile` 是手机上的主入口
- `Termux` 退到后台，只做真正的 Codex 引擎和救援入口
- 前台 App 以 `codex app-server` 的线程数据为准
- 不追求和 `Termux TUI` 实时镜像

当前后端地址：

- `ws://127.0.0.1:8765`

## 当前底座

- 手机端真实引擎：`Termux` 里的社区版 Codex
- 后端服务：`codex app-server --listen ws://127.0.0.1:8765`
- 手机 UI：Android 原生 `Jetpack Compose`
- root：已接入，用于保活、后端拉起和目录/文件级操作

## 当前已做成的能力

### 1. App 自动拉起后端

- 打开 `Codex Mobile` 时，不再要求先手动打开 `Termux`
- App 会自动检查 `8765` 是否监听
- 未监听时，会自动拉起 `codex app-server`
- 这条链已经实机验证通过

### 2. 聊天主链基本可用

- 聊天页已经收成接近手机 ChatGPT 的结构
- 顶部只保留：
  - 标题
  - 小状态点
  - 当前模型/智力标签
  - 生成中时右上角 `停止`
- 中间只保留消息流
- 底部是紧凑输入框
- 发送后会先做前端本地回显
- 回复开始前会有轻量占位
- `继续 / 重试` 已从主聊天区拿掉

### 3. 后台恢复

- App 留在最近任务里时，会维持前台服务
- 切到后台再回来，会尽量恢复当前线程
- 不再默认跳到历史第一条
- 历史线程只能由用户显式打开

### 4. 命令页结构

当前命令页首页保留：

- `新消息`
- `历史会话`
- `已归档`
- `模型`
- `权限`

说明：

- `归档当前会话` 首页入口已经删掉
- 历史列表里单条仍可归档
- `已归档` 页可查看已归档会话

### 5. 模型 / 权限

- 模型标签当前能显示类似 `gpt-5.4 · 高`
- `Fast` 已接入并修正到当前后端可接受的值
- 权限仍按三档全局模式处理：
  - `默认不同意`
  - `按次同意`
  - `全部放开`

## 归档、恢复、删除的真实语义

这一块已经探明，必须单独记清楚。

### 1. 后端没有 `thread/delete`

当前后端没有真正的 `thread/delete` RPC。直接探测后返回：

- `unknown variant thread/delete`

所以：

- “真删除”不能靠官方后端 API 做
- 只能走本地文件级删除

### 2. `thread/archive` / `thread/unarchive` 不是所有线程都认

对部分线程，后端会直接报：

- `no rollout found for thread id ...`
- `no archived rollout found for thread id ...`

这说明有一部分历史会话更像：

- 本地索引会话
- 或本地文件会话

而不是后端当前能直接识别的 rollout 线程。

### 3. 当前 App 的删除语义

现在的语义已经收成这样：

- `归档`
  - 先尝试走后端 `thread/archive`
  - 如果碰到上面的 rollout 错误，则做本地回退
- `恢复`
  - 先尝试走后端 `thread/unarchive`
  - 如果碰到对应 rollout 错误，则做本地回退
- `已归档 -> 删除`
  - 是物理删除
  - 会按 `threadId` 删除匹配的本地会话文件

当前物理删除覆盖的目录：

- `/data/data/com.termux/files/home/.codex/archived_sessions`
- `/data/data/com.termux/files/home/.codex/sessions`

### 4. 已执行过的物理删除

2026-03-09 已经实际清过一次归档文件：

- `archived_sessions` 文件数：`28 -> 0`
- 目录体积：`2.2M -> 4.0K`

说明：

- 这次删的是物理归档会话文件
- 没有碰当前正在使用的会话

## 图片输入能力探针结论

这是本轮最重要的一个实证结论之一。

### 1. 当前 App 前端还没接图片按钮

目前手机 App 发送链还是纯文本：

- `CodexMobileViewModel.kt` 中仍是 `buildTextInput()`
- 当前发送的是 `type: "text"`

也就是说：

- 前端 UI 还没做“像 GPT 一样点图发送”

### 2. 这台手机后端确实支持图片类输入

我已经对正在跑的 `app-server` 做过真实协议探针，确认支持的输入类型包括：

- `text`
- `image`
- `localImage`
- `skill`
- `mention`

### 3. 图片输入的真实结论

探针结果如下：

- `input_image`：不支持
- `image_url`：不支持
- `image + data:base64`：协议层能收，但后端返回 `Invalid image`
- `localImage + /sdcard/...`：协议层能收，但运行时读图权限不够
- `localImage + /data/local/tmp/valid_probe.png`：成功

最后一条已经跑通完整闭环，后端真实回复：

- `OK`

### 4. 未来正确的发图实现方式

如果后面要做图片发送，正确路线不是直接把相册 URI 发给后端，而是：

1. 在 App 里选图
2. 压缩/转存到后端可读的本地路径
3. 用 `type: "localImage"` + `path` 发送

建议路径：

- `/data/local/tmp/...`

### 5. 存储清理原则

如果后面做图片发送，缓存策略应该是：

- 归档不删图
- 真删除对话时，一起删对应图片缓存

## 工作目录能力

这块目前还没有做。

已经讨论过的方向是：

- 在命令页增加 `工作目录`
- 目录浏览器按 root 真实目录来做
- 语义是“新会话默认工作目录”

但当前状态是：

- **暂缓**
- 还没有实现

## 当前代码关注点

### UI / 状态

- `app/src/main/java/com/example/myapplication/ui/app/CodexMobileScreen.kt`
- `app/src/main/java/com/example/myapplication/ui/app/CodexMobileModels.kt`
- `app/src/main/java/com/example/myapplication/ui/app/CodexMobileViewModel.kt`

### RPC / 后端连接

- `app/src/main/java/com/example/myapplication/ui/app/CodexRpcClient.kt`

### 运行时 / root / 保活

- `app/src/main/java/com/example/myapplication/runtime/CodexRuntimeController.kt`
- `app/src/main/java/com/example/myapplication/runtime/RootShell.kt`
- `app/src/main/java/com/example/myapplication/service/BackendForegroundService.kt`

## 当前已确认可用的行为

- `Codex Mobile` 可自动拉起 `app-server`
- `127.0.0.1:8765` 已实机确认可监听
- App 能连上本机后端
- 真实消息能在 App 中发送并收到回复
- 切后台再回来，当前线程可以保留
- 模型标签能显示 `gpt-5.4 · 高`
- `Fast` 已走真实后端参数，不再是假开关

## 当前已知问题

### 1. 聊天交互仍在打磨

虽然主链已通，但当前仍不算完全产品完成态，主要风险还在：

- 某些边界交互仍可能钝
- 长线程体验还可以继续压
- UI 文案和对齐仍有继续打磨空间

### 2. Termux TUI 仍不适合长线程触摸滚动

这个问题没有继续在 TUI 上修，因为产品方向已经改成：

- App 是主入口
- `Termux` 只做后台底座

### 3. 旧 `codex login` 残留进程问题仍记录在案

手机里一度存在旧登录残留进程，但当前策略仍然是：

- 不碰 `~/.codex/auth.json`
- 不误杀 `app-server`
- 优先保证当前 App 主链稳定

## 手机内存结论

手机物理内存约 `11.4 GB`，对当前方案是够的。

结论：

- `12 GB RAM` 不是当前主瓶颈
- 当前主问题更偏：
  - 后台策略
  - 连接状态机
  - UI / 后端同步逻辑

## 回滚方式

你不熟 Git，所以继续保留最简单的回滚策略。

### 方案 A：按源码快照回滚

- 使用电脑侧保存的轻量源码 zip
- 解压覆盖工程目录即可回到对应阶段

### 方案 B：按关键文件回退

如果只是某次改坏了聊天或连接逻辑，优先回退这些文件：

- `CodexMobileViewModel.kt`
- `CodexMobileScreen.kt`
- `CodexRpcClient.kt`
- `CodexMobileModels.kt`
- `CodexRuntimeController.kt`

## 后续建议

下一轮更适合继续压这些点：

1. 聊天交互细节和排版
2. 图片发送 UI 接入
3. 工作目录能力（如果恢复这个需求）

当前不建议：

- 再回头折腾 `Termux TUI`
- 把产品层 UI 直接写进 npm 包
- 在未确认协议前先硬做图片按钮

## Termux 升级脚本

已新增一个手机端升级脚本，目标是把这几件事收成一条命令：

- 挂代理
- 停掉旧的 `codex` / `app-server`
- 升级社区版 `@mmmbuto/codex-cli-termux`
- 自动修复 Android/Termux 入口兼容
- 自检 `codex --version`

脚本源文件：

- `tools/termux-codex-update.sh`

手机端命令：

- `codex-update`
- 可选带版本：`codex-update 0.112.0-termux`

注意：

- 这个命令应当在普通 `Termux` 用户下运行，不要先 `su`
- 它会在升级前短暂停掉 `app-server`，升级后如果原本在运行，会自动拉回
