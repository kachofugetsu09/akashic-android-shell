# Akashic Android Shell

原生 WebView 壳负责连接 Akashic、消息通知和可选的后台手机控制。

## 后台显示器隔离

后台手机控制要求已连接 UID 2000 助手，且系统实际授予虚拟显示器以下能力：

- `TRUSTED`、`OWN_DISPLAY_GROUP`、`ALWAYS_UNLOCKED`
- `OWN_FOCUS`、`STEAL_TOP_FOCUS_DISABLED`
- 本地显示器输入法（`DISPLAY_IME_POLICY_LOCAL`）

系统可能移除请求的标志，因此创建后会核对实际 `Display.flags` 和输入法策略。
缺少能力、助手断开或创建失败时，后台工具会明确报错，不会自动切到主屏。
WebView 和通知功能不依赖后台显示器支持。

虚拟屏休眠后仅通过 `wakeUpWithDisplayId` 恢复其独立电源组；不注入
`KEYCODE_WAKEUP`，因为 Android 会把该按键路由到主屏。
电源接口不可用时保留当前任务并报错，不自动重建以掩盖失败。
后台 `press_key` 拒绝 `POWER`、`SLEEP`、`WAKEUP` 和 `SOFT_SLEEP` 数字键码；
不认识的非数字名称也会报错，不转换为返回键。
用户显式请求的 `hand_to_user` 仍可通知用户接管，不依赖助手在线。

## 构建

```sh
ANDROID_HOME=/path/to/android-sdk ./gradlew :app:assembleDebug
```

默认调试包名为 `com.akashic.shell.debug`。测试机可使用
`-PtestApplicationIdSuffix=` 构建同包名调试包；不同签名之间不能直接覆盖安装。
安装或替换 APK 不等于后台控制、熄屏或网络重连已验收。

## 手机连接协议（0.4.0）

Shell 0.4.0 与 phone-use 0.2.0 必须配套升级。
SSE hello 声明 `supports_connection_id: true`；服务端的首条 `welcome`
返回 `device_id` 与不可为空的 `connection_id`。
每条请求和异步状态上报固定使用产生它的连接标识，重连后不能借用新连接标识回传旧结果。
服务端拒绝旧标识时，端侧不改写标识重发；旧结果不会变成新连接的成功结果。
ACK 未被服务端接受时不开始操作。已经开始的外部效果仍可能完成，连接失效不等于回滚。

旧服务端没有合法 welcome 时端侧会断开，并在设置中显示升级说明；不静默兼容。
APK 的 release 构建变体仍遵循本仓库已有的本机 debug 签名演示约定。
本机构建和验收不等于 GitHub release 或线上部署，换签名可能需要卸载重装。

## RPC 执行和停止

端侧同一时刻只运行一个 RPC，包括观察、动作和收尾。上一个请求在服务端超时后，
其已开始的设备效果仍可能继续；新请求返回 `PHONE_BUSY`，不会排队或稍后自动执行。
调用方应等待后重新观察，而不是把超时理解成设备已停止。

每次启动桥接循环使用独立身份。停止会使旧循环失效，旧连接迟到的 welcome、
清理和错误不能覆盖新循环；主线程不等待旧网络读取退出。
用户主动停止和已开始的外部效果并不构成事务回滚。
