# 真实设备回归

这是确定性设备测试，不是模型任务成功率。它经过真实 `PhoneBridge.rpc`、SSE、
手机执行器和真实输入控件；用独立 ContentProvider 检查内容及提交计数。
fixture 是独立包 `dev.akashic.phoneeval`，不打包进 Shell，也不访问生产数据。
runner 要求 Shell 0.5.0 的 `snapshot_ref` 协议：每个节点动作原样回传所属观察的引用，
缺失时直接失败，不自动用最新引用替换。正式工具链配套使用 phone-use 0.3.0。

```text
eval/run.py ── HTTP ── 真实 PhoneBridge ── SSE ── Shell 虚拟屏
     │                                             │
     ├── ADB 只读主屏状态                           ▼
     └── ADB 只读 ContentProvider ◀── fixture 持久状态
```

## 准备

```sh
ANDROID_HOME=/path/to/sdk ./gradlew :eval-fixture:assembleDebug
adb -s DEVICE install -r eval-fixture/build/outputs/apk/debug/eval-fixture-debug.apk
```

使用已有 Akashic Python 环境，包含插件需要的 FastAPI、uvicorn 等依赖。
`CORE` 指向与插件匹配的真实 Core 源码，`PLUGIN` 指向固定提交的 phone-use 源码。
不要指向线上 cache 或 workspace。记录这两个提交和安装的 APK SHA-256。

```sh
PYTHONPATH="$CORE" "$CORE/.venv/bin/python" eval/serve.py \
  --plugin "$PLUGIN" --bind LAN_HOST_IP --peer PHONE_IP \
  --token-file /tmp/phone-eval-token --port 18769
```

在测试 Shell 中使用 `http://LAN_HOST_IP:18769`，打开首页以取得隔离 cookie，
再开启后台控制。配置探测 `/api/shell/state` 返回 `chatReady:false`，只表示这是
可连接的手机测试入口，不伪称已提供聊天服务；该元数据仍受 IP 范围限制。
cookie 明确保留 24 小时，过期后需重新打开测试首页，不能把鉴权失败当成设备断网。
服务不加载线上账户或生产 workspace。它只允许指定手机 IP，
控制入口需要电脑本机 IP 和临时 token，WebSocket 被拒绝；不要公开这个测试服务。
从 `/control/status` 读取 device_id，经 `/control/rpc` 用 `action/launch_app` 在
虚拟屏启动 fixture。所有控制请求使用 `Authorization: Bearer <token>`。
不把 token 放入版本库、命令输出或测试报告。

## 执行与判定

主屏按测试条件自行处于熄屏状态后运行；runner 不按电源键、不改休眠或音量设置。
同一时间只能有一个设备操作者。先运行少量轮次确认环境，再独占设备跑性能样本。

```sh
python eval/run.py --rpc-url http://LAN_HOST_IP:18769/control/rpc \
  --token-file /tmp/phone-eval-token --serial DEVICE --device-id DEVICE_ID \
  --output /tmp/phone-eval-new-run --rounds 10 --seed 1 --screen off \
  --label 'Shell SHA / plugin SHA / Pixel build / Wi-Fi / USB connected'
```

每轮先清空并核对独立存储，再输入新中文、emoji 和随机标记，最后检查提交内容及
全程计数恰好逐次加一，结束后再观察两秒迟到提交。任何结果未知或错误都会停止继续写入，失败和未执行轮次保留在
计划总分母。半小时独立服务器测试使用下方分离 runner；本机 runner 不等于
Doze、断 USB、断网或整夜测试。`--screen record` 只记录主屏状态，不要求熄屏。

`summary.json` 分开给出任务结果、主屏采样错误及最大采样间隔。每轮写前与提交后还用 WMS 核对 fixture 窗口归属。有限采样不能
证明间隔中绝无闪屏。只有至少 100 个成功轮次才输出探索性 p95；这是成功轮次
延迟，失败数量必须一起看。ADB 采样会影响性能，比较时固定其频率并停止并发构建。
`calls.jsonl`、`rounds.jsonl`、`panel.jsonl`、`windows.jsonl`、`tail.jsonl` 保留原始结果；它们可能含设备信息，
不要提交真实 traces。新目录和随机 nonce 避免混淆旧结果或把残留答案当成功。

先用断开的测试 bridge 验证错误不能通过，再跑真实手机；也应校准“未清空、未提交、
重复提交”的独立状态判定。runner 不自动重试未知写操作，不卸载 fixture 或回滚数据。
测试结束由唯一设备 owner 恢复之前的连接与临时设置，停止隔离服务，保留必要证据。

## 服务器自主执行与独立采样

长测将执行与判定拆开，任务进程运行在服务器，笔记本只通过 USB 读取状态。
这能验证任务没有笔记本 RPC 依赖；保留 USB 观测仍不等于实物拔线或电脑关机。
先用 2 轮校准，再固定提交、APK、脚本和参数跑 61 轮、间隔 30 秒。

```text
服务器 drive.py ── 本机 HTTP ── Bridge ── 手机后台屏
                                      │
笔记本 watch.py ── 只读 USB ───────────┘
        │
        └── grade.py：独立计数、主屏 OFF、WMS、PNG、完整调用记录
```

1. 启动 `watch.py`，等待首次主屏 OFF/OFF、provider 基线与 WMS 归属读取完成。
   它不发送输入、不改系统电源状态。观察时钟使用 Linux `CLOCK_BOOTTIME`，
   因此宿主休眠会留下真实采样空档，不会因 `CLOCK_MONOTONIC` 暂停而被隐藏。
2. 在观察电脑上，以 `time.clock_gettime(time.CLOCK_BOOTTIME)` 记下
   `start_requested_mono`，然后启动服务器独立进程。容器可使用 `docker exec -d`，
   将 stdout/stderr 留在已存在的日志目录；不要预建 driver 的 `--output` 目录。
3. 确认服务器 `summary.json` 已完成后，用同一电脑、同一时钟记录
   `finished_observed_mono`。继续采样至少两秒，再正常 SIGTERM 停止 watcher。
   等待 watcher 最终读取与 `summary.json` 写完，不能直接杀死后推断成功。
4. 将远端输出完整取回，创建含上述两个时间及 `"clock":"CLOCK_BOOTTIME"`
   的 coordination JSON。`grade.py` 返回 0 才是通过；driver 完成不等于验收通过。

```sh
# 观察电脑：独立只读采样
python eval/watch.py --serial DEVICE --output /tmp/new-watch --label 'fixed versions and USB state'

# 服务器：独立任务进程；token 在本机文件中，不传到命令输出
python eval/drive.py --url http://SERVER_LAN_IP:PORT/control/rpc \
  --token-file /path/to/token --device-id DEVICE_ID --output /path/to/new-driver \
  --rounds 61 --interval 30 --label 'fixed versions and Wi-Fi state'

# 观察电脑：需 Pillow；校准时 minimum-duration 用 0
python eval/grade.py --driver /path/to/copied-driver --watch /tmp/new-watch \
  --coordination /path/to/coordination.json --output /tmp/new-result.json \
  --minimum-duration 1800 --max-sample-gap 2
```

可先运行 `python eval/test_grade.py`：一个有效控制及七个故意破坏证据的负控，
覆盖缺调用记录、慢查询假尾部、休眠长度空档、错误时钟、损坏 PNG、缺 WMS 与额外提交。
这些是主机判定器回归，不是真机通过率。

61 轮应有 214 次调用、7 张请求截图、61 个不同目标，独立提交计数恰好逐次加一。
判定还要求日志序列完整、无错误回包、PNG 能完整解码且尺寸对应逻辑坐标、
fixture 的每次 WMS 采样只出现在正数后台 display。主屏样本须全部 OFF/OFF，
相邻采样开始时间最多相隔两秒，首次基线读取须在启动前完成，最后读取须在两秒尾部后开始。
慢查询的返回耗时不能补足尾部覆盖。任一失败停止后续写操作，不自动重放未知结果。
有限采样和 fixture 任务仍不能证明所有应用、两次采样之间绝不闪屏或音频焦点隔离。

## 后续 agent eval

模型层另选查找应用、滚动定位、中文编辑、深链接、恢复旧节点等任务；固定模型、
提示词、工具 schema 和预算，记录工具次数、无效调用、token 和最终独立状态。
把调优集、验证集和最终未使用的 holdout 分开，不向执行模型提供 grader 状态。
目前这个 runner 没有运行模型，不能用它的分数替代上述评估。
方法参考 [eval design and hillclimbing](https://claude.dev/blog/automating-eval-design-and-hillclimbing/)。
