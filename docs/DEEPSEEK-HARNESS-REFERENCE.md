# DeepSeek Harness 参考说明

- 源码：`https://github.com/deepseek-ai/deepseek-harness`
- 许可证：MIT License，Copyright (c) 2026 DeepSeek
- 本地仅用于阅读工作流和 UI 结构，没有把 263MB 的 Node/Electron 源码打进 Android APK。
- 移植到 `agent_etc` 的部分：工作过程轨迹（计划、思考摘要、工具调用、工具结果、完成/失败）、可折叠过程面板、无登录页的本地 Agent 入口。
- 未移植：DeepSeek 账号登录/API Key 引导、Electron 桌面壳、Cordis 插件运行时、Node 服务端。
- Android 独立版仍使用自带的 WebView + Java 桥 + llama.cpp JNI，不需要电脑、不需要 Node。

MIT 许可证要求保留版权与许可声明；本文件作为第三方参考声明，未复制其源代码文件。
