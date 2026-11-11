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

## 接管只移动明确记录的任务

`hand_to_user` 只建立等待。用户真实点击 PiP 时，在与 RPC 共用的 effectLock 内，
先从助手拥有的后台显示器查询 topTaskId，记下确切 ID，再向助手提交移交主屏请求。
回包丢失或失败仍保留 ID 和接管原因；再次点击不重新选另一个任务。
不再按包名从所有显示器挑任务，也不通过 launcher 创建替代实例。
通知点击仍只打开 Shell；此改动不把通知点击改成自动移交。

`resume_from_user` 没有已建立的接管等待时明确拒绝。尚未真实移交（没有记录 ID）时，
只解除等待，不从主屏找应用。有 ID 时只请求它从主屏返回当前后台显示器；
已在目标显示器可无操作成功，消失、第三屏、权限或调用失败均保留接管状态。
成功文案表示恢复请求已接受，实际画面仍需核实，不声称物理迁移已独立验证。

助手 identity 仍返回 int[]，在 UID/PID 后增加 protocolVersion=1。
旧助手只有两个元素时明确提示重建；不调用旧 slot7 按包名移动方法。
新 topTaskId / moveTaskById 使用未占用的事务13/14，slot7不复用。
仓库内未发现其它 identity() 调用消费者；外部工具若依赖数组长度应同步更新。
该任务 ID 是本次进程内接管状态，未新增跨进程持久恢复协议；系统查询与移动也不是原子比较交换。

```text
等待 ──真实点击──► 查 owned VD 的 taskId ──先记录──► 提交移交
  │                                  │             │
  └─未点击 resume：只清等待             └─失败/未知：保留 ID + reason
                                                 │
                      resume：只按该 ID 从主屏返回 ┘
```

验收由唯一设备 owner 排队：无等待 resume 拒绝；未点击的等待仅解除；
同包不同显示器实例只移动记录的 owned ID；回包丢失后仍使用同 ID；
任务消失/第三屏/旧助手拒绝且原因保留；成功后通过独立 WMS 核对同 taskId 的归属。
不在非受控窗口移交主屏，不把构建通过当作真机通过。

原生隔离 Binder 故障 probe（只有构建命令，不自动安装）：

```sh
ANDROID_HOME=/path/to/sdk ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PtestApplicationIdSuffix=.resumetest -PprobeRunner=com.akashic.shell.TaskTransferProbe
```

设备 owner 可只安装这两份专用 APK 后执行
`am instrument -w com.akashic.shell.resumetest.test/com.akashic.shell.TaskTransferProbe`。
Probe 拒绝不带 `.resumetest` 后缀的目标；使用 Binder test double/故障夹具、实际 Proxy/Parcel 和
真实 Controller 检查旧助手、拒绝、回包丢失、重复点击、第三屏、消失和 effectLock，以及
受控阻塞→释放显示器→迟到成功/异常不复活 PiP。没有创建/移动真实 task，不是真机迁移验收。
失效代际只记录结果未知；不会把已停止的接管重新设为 NEEDS_YOU。

## finish 的实际能力

finish 保持整块 VD 的释放流程，不实现按包保留/关闭，不停止应用进程或音频，
不保证最近任务消失。kept/closed 固定为空数组，不再把请求参数回显为已完成结果。
为兼容旧在途请求，payload 即使携带 keep/close 也不会阻止整屏 release；不新增包级任务 API。
