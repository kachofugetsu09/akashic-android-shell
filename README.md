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

## 动作的显示器归属（0.4.2）

点击和编辑节点前刷新节点，要求它仍可见且 `window.displayId` 等于本次后台显示器；
消失或已移到用户屏幕的节点明确报错。无障碍服务断开时不保留旧节点引用。
输入框拒绝 `ACTION_SET_TEXT` 时明确报告未支持，不再写入用户的全局剪贴板并假称成功。
这不是对所有自定义编辑器的输入支持；无显示器身份的 Accessibility IME 也不能安全代替。

链接、分享和应用启动统一由 UID 2000 助手带目标 displayId 发起。分享只打开目标界面，
不声称已向外部发送。只有 Android 的成功返回区间 0..99 表示启动请求被接受，
实际落在哪个页面仍需观察验证。可独立的标准 Activity 使用独立任务，已有后台任务按实际归属复用；
检测到会复用其它显示器 singleton 的已知 ideal task 时拒绝，不把用户任务拉回后台。

任务查询不再截断到 50。Android 16 跨显示器只匹配 ideal component/document task，
不会仅凭外屏 affinity candidate 复用，所以保留已验证的 cold singleton 启动。
`TaskInfo` 缺少历史 `affinityIntent`，查询与启动之间也不是系统原子操作；这些仍是隔离限制，
不能据此承诺任意应用、任意用户并发迁移下绝对不抢屏。

后台按键只支持应用文字和导航键；HOME、最近任务、音量、媒体、电源等全局系统控制由用户操作。
未知动作、缺失参数和不支持的按键在创建显示器或输入前明确拒绝，不静默选择默认动作。

## 节点快照协议（0.5.0，需 phone-use 0.3.0）

每个最终 `screen` 携带产生它的 RPC UUID `snapshot_ref`，短 `node_id` 只在该快照内有效。
节点点击、`type_text` 和 `clear_text` 必须原样传回引用；即使省略 `node_id` 使用聚焦输入框，
文字操作也必须提供引用。缺少引用报告 `PHONE_SNAPSHOT_REF_REQUIRED`，不自动补最新值。

新观察或动作结果替换引用与节点映射；连接更换、显示器释放或重建、空树、已开始的 RPC 失败清除节点引用。
旧引用报告 `PHONE_SNAPSHOT_STALE`，在唤醒显示器、读取节点或输入前拒绝。
仍会刷新节点并核对窗口的 displayId；此协议不保证页面在快照后不变，也不为坐标点击提供稳定性保证。
Shell hello 声明 `supports_snapshot_ref=true`；新插件拒绝不支持的旧 Shell。

```text
RPC UUID ──► 最终 screen + 节点映射 ──► snapshot_ref + 短 node_id
                                               │
动作 ◄──────────────────────────────────────────┘
  └─ effectLock 内先核对引用、连接与显示器代际，再读取节点
```

## 当前应用身份

屏幕的 `package_name` / `activity_name` 保持字符串，未知时为空串。
优先使用本次目标显示器的 top Activity；没有该结果时，包名只取本次该显示器的
active/focused 应用窗口；候选包不唯一则未知，Activity 仍未知。不用最近启动包、其它显示器事件或
Shell 自身的默认名称填充“当前前台”。请配套更新 phone-use 的空值展示；旧插件会补造默认包名。
