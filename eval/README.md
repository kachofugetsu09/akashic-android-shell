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
再开启后台控制。服务不加载线上账户或生产 workspace。它只允许指定手机 IP，
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
计划总分母。可以用 `--rounds 60 --interval 30` 做约半小时低频任务；它不等于
Doze、断 USB、断网或整夜测试。`--screen record` 只记录主屏状态，不要求熄屏。

`summary.json` 分开给出任务结果、主屏采样错误及最大采样间隔。每轮写前与提交后还用 WMS 核对 fixture 窗口归属。有限采样不能
证明间隔中绝无闪屏。只有至少 100 个成功轮次才输出探索性 p95；这是成功轮次
延迟，失败数量必须一起看。ADB 采样会影响性能，比较时固定其频率并停止并发构建。
`calls.jsonl`、`rounds.jsonl`、`panel.jsonl`、`windows.jsonl`、`tail.jsonl` 保留原始结果；它们可能含设备信息，
不要提交真实 traces。新目录和随机 nonce 避免混淆旧结果或把残留答案当成功。

先用断开的测试 bridge 验证错误不能通过，再跑真实手机；也应校准“未清空、未提交、
重复提交”的独立状态判定。runner 不自动重试未知写操作，不卸载 fixture 或回滚数据。
测试结束由唯一设备 owner 恢复之前的连接与临时设置，停止隔离服务，保留必要证据。

## 后续 agent eval

模型层另选查找应用、滚动定位、中文编辑、深链接、恢复旧节点等任务；固定模型、
提示词、工具 schema 和预算，记录工具次数、无效调用、token 和最终独立状态。
把调优集、验证集和最终未使用的 holdout 分开，不向执行模型提供 grader 状态。
目前这个 runner 没有运行模型，不能用它的分数替代上述评估。
方法参考 [eval design and hillclimbing](https://claude.dev/blog/automating-eval-design-and-hillclimbing/)。
