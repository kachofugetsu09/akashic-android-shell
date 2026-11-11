# 无声 AudioFocus 对照 fixture

此模块独立构建为 `dev.akashic.audiofocus.a` / `.b`，不打包进正式 Shell。
只调用真实 AudioManager 请求/放弃焦点及记录回调；没有 AudioTrack、MediaPlayer、音频文件、网络或账号。
所以它检验焦点归属与回调，不证明实际媒体暂停、duck、音量、路由或完整静音隔离。
不调用全局静音，不改其他 App 的 AppOps，也不会自动重新请求焦点。

Android 16（min/target SDK36）。[官方要求](https://developer.android.com/media/optimize/audio-focus)：
target35+ 请求焦点必须是 top app 或正在运行 FGS，否则可返回 FAILED。
为了让 A 在 B 启动后仍符合条件，使用独立 mediaPlayback FGS；
[此类型](https://developer.android.com/develop/background-work/services/fgs/service-types#media)
声明 FOREGROUND_SERVICE / FOREGROUND_SERVICE_MEDIA_PLAYBACK，没有运行时媒体权限前提。
常驻通知仅 MIN、无声音/振动、onlyAlertOnce，无点击启动入口，不申请通知运行时权限。
若已有测试 channel 被改成有声音、振动或 DEFAULT/HIGH，会明确失败，不覆盖设置或假称成功。

```text
A（受控 VD_A）GAIN ──► A 真实 request_result
                         │
B（普通 VD_B）TRANSIENT ─┼─► B request_result + A LOSS_TRANSIENT 回调
B 显式 abandon ─────────┴─► A GAIN 回调（若系统实际返回）
```

## 构建与记录

```sh
ANDROID_HOME=/path/to/sdk ./gradlew :audio-focus-fixture:assembleADebug :audio-focus-fixture:assembleBDebug
```

APK 位于 `audio-focus-fixture/build/outputs/apk/{a,b}/debug/`。
Activity、Service 和只读 Provider 都要求 DUMP 权限，供 UID2000 测试操作。
每包独立 SQLite 追加事件，含自增序号、wall_ms、elapsed_ms、实际 UID/PID、display_id、
Context device_id、run_id、独立 request_id、事件、真实整数返回值及细节。
回调携带原请求的 display/request 身份，不改写为最新值。listener.toString() 固定包含包名和本次请求 UUID，
避免两个冷进程的对象地址相同导致 AudioManager 的焦点 clientId 碰撞；request_begin 也记录此值。Provider 不接受修改或清空。
SQLite 正常提交不等于掉电/强杀持久化验证。

`request_result`：GRANTED=1、FAILED=0、DELAYED=2；本 fixture 不接受延迟焦点。
`callback`：GAIN=1、LOSS=-1、LOSS_TRANSIENT=-2、LOSS_TRANSIENT_CAN_DUCK=-3。
首次 GRANTED 由请求返回确认，不能要求一定同时收到 GAIN 回调。
`abandon_result` 单独记录；`request_begin` 没有对应 result 不能算请求成功。
跨包比较使用同一设备的 elapsed_ms；同包 id 保留真实记录顺序。

## 由唯一设备 owner 执行

以下只是命令模板，构建者不安装或执行。设备 owner 先确认是受控窗口、没有真实媒体/通话需保留焦点；
GAIN 请求本身就可能抢全局焦点，不能靠“没有播放器”宣称对其他 App 无影响。
由 owner 安装两 APK、准备两个有效且不同的普通 VD，并记录其 WMS 归属和主屏被动状态。

```sh
# VD_A / VD_B 必须由 owner 填成实际正整数；不要省略 --display 或用主屏0。
adb shell am start --display "$VD_A" -n dev.akashic.audiofocus.a/dev.akashic.audiofocus.FocusActivity --es command request --es gain gain --es run_id cross-display-1
adb shell content query --uri content://dev.akashic.audiofocus.a.events/events
# 确认 A 本次请求 GRANTED 后，B 才请求临时焦点。
adb shell am start --display "$VD_B" -n dev.akashic.audiofocus.b/dev.akashic.audiofocus.FocusActivity --es command request --es gain transient --es run_id cross-display-1
adb shell content query --uri content://dev.akashic.audiofocus.b.events/events
adb shell content query --uri content://dev.akashic.audiofocus.a.events/events
# 显式放弃并结束 B 的 host，检查 A 是否实际收到 GAIN。
adb shell am startservice -n dev.akashic.audiofocus.b/dev.akashic.audiofocus.FocusService --es command abandon
adb shell content query --uri content://dev.akashic.audiofocus.a.events/events
# 最后释放 A，保留各包日志；stopservice 也是明确的清理入口，onDestroy 放弃原请求。
adb shell am startservice -n dev.akashic.audiofocus.a/dev.akashic.audiofocus.FocusService --es command stop
adb shell am stopservice -n dev.akashic.audiofocus.a/dev.akashic.audiofocus.FocusService
adb shell am stopservice -n dev.akashic.audiofocus.b/dev.akashic.audiofocus.FocusService
```

Activity 从自身 Display 读取 display_id 再传给 Service，随后 finish；Service 再验证 Display 存在，
用该 DisplayContext 的 AudioManager 请求。Activity 在0上会记录拒绝并不启动服务；
这个检查发生在 Activity 创建之后，不能撤销错误命令已经造成的主屏任务切换。
Service 只接受 request/abandon/stop；不是 sticky，不在重建或 Provider 读取时请求焦点。

通过条件：A 请求实际 GRANTED；B 请求实际 GRANTED；A 收到本次 LOSS_TRANSIENT；
B abandon 后 A 实际 GAIN；最后两包都有释放记录且 host 停止。
可另用 B `--es gain gain` 检查永久 LOSS；该情形不能期待 B abandon 后 A 自动恢复。
任一 FAILED、异常、缺回调或未清理都原样报告，不能把命令退出0当作焦点测试通过。
两个 VD 均应记录 device_id；普通 VD 常与默认设备共享音频域，以实际结果为准。
这只能证明两个测试包的跨显示器焦点行为，不等同主屏真实播放器或 VirtualDevice 独立音频验收。

## 有界真机结果

Pixel 7 / Android16 在两个不同普通 VD 上执行，两个包实际都报告 device_id=0：
A 的 GAIN 返回 GRANTED，B 的 TRANSIENT 返回 GRANTED，A 收到 LOSS_TRANSIENT；
B abandon 返回成功后 A 收到 GAIN，两包最终均显式释放且服务停止，独立 audio focus stack 为空。
这证明普通显示器没有隔离这两个包的音频焦点，而不是“产品音频隔离通过”。
没有创建播放器或测真实主屏媒体，也没有改音量、AppOps 或用全局静音掩盖效果。

首轮默认 listener 对象地址在两个冷进程中碰撞，系统生成相同 clientId，结果不适合判定隔离。
修正为包名+请求 UUID 后重新执行得到上述回调链；首轮原始证据保留，没有把无回调当成通过。
设备运行源 head 为 7958e898c6a8bc81457961c61a0014c0377e64bb；本节只补结果边界。
