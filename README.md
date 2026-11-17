# DeepSeek Mobile 2.0.0

手机独立版 Android 工作台，不依赖电脑、无登录页。

- APK：`dist/deepseek-mobile-2.0.0-android.apk`
- 签名：ETC Code Signing
- 本地 GGUF：设置 → 本地 GGUF → 选择模型；支持自动加载
- 云端 API：设置 → 模型服务 → 填写 OpenAI 兼容地址/Key
- 终端：设置 → 终端；内置 32 位 Termux
- 无障碍：设置 → 能力 → 开启屏幕权限 → 读一次屏幕
- 内置 UI：`android-standalone/app/src/main/assets/app.html`
- 构建：`bash scripts/build-standalone-apk.sh`
