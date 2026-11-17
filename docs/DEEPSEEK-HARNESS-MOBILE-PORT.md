# DeepSeek Harness → Android 移植说明

- 上游：https://github.com/deepseek-ai/deepseek-harness
- 许可：MIT（见 DEEPSEEK-HARNESS-LICENSE.txt）
- 本地参考克隆：`/tmp/deepseek-harness`，仅用于核对交互和工程结构，没有把 263MB 的 Node/Electron 源码塞进 APK。

## 为什么不能直接 `npm run build` 出 APK

DeepSeek Harness 是 Electron/Node 桌面应用，Android 没有 Electron 运行时，也没有桌面 Node 主进程。直接打包会出现“能安装但启动黑屏”，所以本项目采用：

- Android 原生 Activity + 本地 `file:///android_asset/app.html` 内嵌工作台（不打开外部浏览器，不依赖电脑）。
- 原生 Java 桥负责 GGUF、Termux、无障碍、通知、悬浮窗、文件/权限。
- 保留原来的 34 技能、多 MCP、QQ OneBot、PPT 需求确认等能力。

## 本版改动

- 新品牌 DeepSeek Mobile，版本 2.0.0。
- 聊天只渲染最近 160 条；工作日志合并到下一帧渲染；持久化 220ms 合并，减少长任务卡顿。
- 设置页只在打开时重绘，避免每步工具调用都重建 34 技能列表。
- 悬浮窗可拖动，松手停在新位置。
- `onTaskRemoved` 请求保留前台服务；通知栏继续显示任务状态。
- WebView 开启缓存/硬件层/离屏预栅格。
- 无登录页；本地 GGUF 由用户选择，云端 API 由用户填写。

## 未移植

- Electron 主进程、桌面插件市场的 Node 运行时、桌面端专用 API。
- 无障碍读屏只读当前可见界面，不绕过验证码/支付。
- 后台被系统强杀后，WebView 里的 QQ WebSocket 仍可能断开；要 24h 在线需要外部 OneBot 网关常驻。
