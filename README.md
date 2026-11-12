# Akashic Android Shell

原生 WebView 壳：连接 Akashic、接收消息通知，并可选地承载后台手机控制（phone-use）。

## 后台手机控制

- 需要通过无线调试启动的 UID 2000 助手。助手创建独立的后台虚拟显示器，
  Agent 的观察和操作只发生在这块显示器上；助手断开或建屏失败时直接报错，不回退到主屏。
- 主屏熄灭时只唤醒虚拟屏自己的电源组，不点亮主屏。
- 节点操作必须携带观察时返回的 `snapshot_ref`；页面变化后旧节点编号会被拒绝。
- `type_text` 覆盖输入框全文，读回一致才算成功；不写用户剪贴板。
- 同一时刻只执行一个请求，其余返回 `PHONE_BUSY`；结果不明的请求不会自动重放。
- `hand_to_user` 把后台任务移到主屏交给用户，`resume_from_user` 只把这一个任务移回去。
- `finish` 释放整块后台显示器。普通虚拟屏与主屏共享音频焦点，`audio_muted` 恒为 false。
- App 进程退出时助手随之退出。首次经无线调试（需 Wi-Fi）启动时会把 adbd 切到本机 `127.0.0.1:5555`，
  之后用流量也能重新拉起助手；手机重启后需连一次 Wi-Fi 恢复。首次连 5555 时手机会询问是否允许调试。

## 版本配套

Shell 0.5.x 需要 phone-use 0.3.0 或更新版本（SSE `connection_id` 与 `snapshot_ref` 协议）。
旧服务端连接时，设置页会显示升级提示。

## 构建

```sh
ANDROID_HOME=/path/to/android-sdk ./gradlew :app:assembleRelease
```

release 变体沿用仓库现有的 debug 签名，便于侧载；调试包名为 `com.akashic.shell.debug`。
