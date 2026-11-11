"""在真实手机上核对持久输入、提交次数和主屏采样；不操纵主屏电源。"""
from __future__ import annotations

import argparse
import base64
import json
import math
from pathlib import Path
import random
import re
import secrets
import statistics
import subprocess
import threading
import time
import urllib.error
import urllib.request


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rpc-url", required=True, help="隔离服务 /control/rpc 的完整 URL")
    parser.add_argument("--token-file", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--device-id", required=True)
    parser.add_argument("--output", type=Path, required=True, help="必须是新目录")
    parser.add_argument("--rounds", type=int, default=10)
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--screen", choices=("off", "record"), default="off")
    parser.add_argument("--sample-interval", type=float, default=0.25)
    parser.add_argument("--interval", type=float, default=0, help="轮次间隔秒数")
    parser.add_argument("--label", required=True, help="候选提交、设备和网络条件")
    args = parser.parse_args()
    if args.rounds < 1 or not all(map(math.isfinite, (args.sample_interval, args.interval))) or args.sample_interval <= 0 or args.interval < 0:
        parser.error("rounds/sample-interval 必须为正，interval 不能为负")
    args.output.mkdir(parents=True, exist_ok=False)
    token = args.token_file.read_text().strip()
    nonce = secrets.token_hex(6)
    randoms = random.Random(args.seed)
    stop = threading.Event()
    monitor_finished = threading.Event()
    samples: list[dict] = []
    results: list[dict] = []
    initial_count: int | None = None
    tail_error: str | None = None
    run_error: str | None = None

    def save(name: str, value: object) -> None:
        (args.output / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")

    def append(name: str, value: object) -> None:
        with (args.output / name).open("a") as file:
            file.write(json.dumps(value, ensure_ascii=False) + "\n")

    def adb(*command: str) -> str:
        return subprocess.check_output(["adb", "-s", args.serial, "shell", *command], text=True, timeout=10)

    def oracle() -> dict:
        raw = adb("content", "query", "--uri", "content://dev.akashic.phoneeval.state")
        match = re.search(r"state_b64=([A-Za-z0-9+/=]+)", raw)
        if not match:
            raise RuntimeError("缺少独立状态列；请安装当前 eval-fixture APK")
        return json.loads(base64.b64decode(match[1], validate=True))

    def panel_sample() -> None:
        started = time.monotonic()
        try:
            raw = adb("dumpsys", "display")
            lines = [line for line in raw.splitlines() if "DisplayDeviceInfo{" in line and "type INTERNAL" in line]
            states = [re.search(r", state (\w+), committedState (\w+)", line) for line in lines]
            if len(states) != 1 or states[0] is None:
                raise RuntimeError("不能唯一识别主屏及其提交状态")
            sample = {"start": started, "end": time.monotonic(), "state": states[0][1], "committed": states[0][2]}
        except (OSError, subprocess.SubprocessError, RuntimeError) as error:
            sample = {"start": started, "end": time.monotonic(), "error": str(error)}
        samples.append(sample)
        append("panel.jsonl", sample)

    def monitor() -> None:
        while not stop.is_set():
            panel_sample()
            stop.wait(args.sample_interval)
        monitor_finished.set()

    def rpc(op: str, payload: dict) -> dict:
        frame = {"device_id": args.device_id, "op": op, "payload": payload, "timeout_ms": 15000}
        request = urllib.request.Request(args.rpc_url, json.dumps(frame).encode(),
            {"Content-Type": "application/json", "Authorization": "Bearer " + token})
        started = time.monotonic()
        record = {"op": op, "payload": payload, "start": started}
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                value = json.load(response)
            record["response"] = value
            return value
        except urllib.error.HTTPError as error:
            record["error"] = error.read().decode()
            raise RuntimeError(f"RPC HTTP {error.code}: {record['error']}") from error
        except (OSError, ValueError) as error:
            record["error"] = str(error)
            raise RuntimeError("RPC 结果未知，不自动重发") from error
        finally:
            record["elapsed_ms"] = (time.monotonic() - started) * 1000
            append("calls.jsonl", record)

    def observe() -> dict:
        screen = rpc("observe", {"include_screenshot": False})
        if screen["package_name"] != "dev.akashic.phoneeval" or screen["display_id"] <= 0:
            raise RuntimeError("当前画面不是独立虚拟屏中的 fixture")
        return screen

    def field(screen: dict) -> int:
        nodes = [n for n in screen["nodes"] if n["editable"]]
        if len(nodes) != 2:
            raise RuntimeError("没有看到 fixture 的两个编辑框")
        return nodes[0]["node_id"]

    def check_window(display_id: int) -> None:
        raw = adb("dumpsys", "window", "windows")
        blocks = re.split(r"(?m)^\s*Window #\d+ ", raw)[1:]
        windows = [re.search(r"\bmDisplayId=(\d+)\b", block) for block in blocks
                   if " dev.akashic.phoneeval/" in block.splitlines()[0]]
        ids = [int(match[1]) if match else None for match in windows]
        append("windows.jsonl", {"time": time.monotonic(), "expected": display_id, "actual": ids})
        if ids != [display_id] or display_id <= 0:
            raise RuntimeError("WMS 未确认 fixture 唯一窗口属于预期虚拟屏")

    save("run.json", {"label": args.label, "seed": args.seed, "nonce": nonce,
        "rounds": args.rounds, "screen": args.screen, "interval": args.interval,
        "sample_interval": args.sample_interval, "device_id": args.device_id,
        "kind": "deterministic-device-eval", "model_used": False})
    # 1. 开始前核对屏幕；不为得到好分数而按电源键或更改休眠设置。
    panel_sample()
    if "error" in samples[0] or (args.screen == "off" and
            (samples[0]["state"], samples[0]["committed"]) != ("OFF", "OFF")):
        save("summary.json", {"passed": False, "setup_error": "主屏采样失败或未熄屏", "planned": args.rounds, "attempted": 0})
        return 1
    thread = threading.Thread(target=monitor, daemon=True)
    thread.start()
    try:
        # 2. 每轮新目标，先清空并独立核对，杜绝旧答案残留导致假成功。
        for index in range(args.rounds):
            started = time.monotonic()
            result = {"index": index, "ok": False}
            try:
                before = oracle()
                if initial_count is None:
                    initial_count = before["count"]
                if before["count"] != initial_count + index:
                    raise RuntimeError("轮次间出现额外提交，不能重新设定计数基线")
                if index and before["submitted"] != results[-1]["goal"]:
                    raise RuntimeError("轮次间提交内容被额外操作修改")
                goal = f"花月-{nonce}-{randoms.randrange(10**9):09d}-😀Ω"
                result["goal"] = goal
                screen = observe()
                check_window(screen["display_id"])
                rpc("action", {"action": "clear_text", "node_id": field(screen)})
                if oracle()["text"] != "":
                    raise RuntimeError("clear_text 返回后独立存储未清空")
                screen = observe()
                rpc("action", {"action": "type_text", "node_id": field(screen), "text": goal})
                if oracle()["text"] != goal:
                    raise RuntimeError("输入与独立存储不一致")
                screen = observe()
                buttons = [n for n in screen["nodes"] if n["text"].casefold() == "submit text"]
                if len(buttons) != 1:
                    raise RuntimeError("无法唯一识别提交按钮")
                rpc("action", {"action": "tap", "node_id": buttons[0]["node_id"]})
                after = oracle()
                check_window(screen["display_id"])
                result.update(before=before, after=after)
                result["ok"] = after["submitted"] == goal and after["count"] == initial_count + index + 1
                if not result["ok"]:
                    raise RuntimeError("提交内容或独立计数不匹配")
            except (RuntimeError, OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
                result["error"] = str(error)
            finally:
                result["elapsed_ms"] = (time.monotonic() - started) * 1000
                results.append(result)
                append("rounds.jsonl", result)
            if not result["ok"]:
                break  # 未知效果后不继续写入；其余计划轮次仍在总分母里。
            if index + 1 < args.rounds:
                time.sleep(max(0, args.interval - (time.monotonic() - started)))
        # 固定两秒尾部观察迟到效果；不能证明窗口以后的行为。
        if len(results) == args.rounds and all(r["ok"] for r in results):
            tail_until = time.monotonic() + 2
            while True:
                current = oracle()
                append("tail.jsonl", {"time": time.monotonic(), "state": current})
                if current["count"] != initial_count + args.rounds or current["submitted"] != results[-1]["goal"]:
                    tail_error = "尾部观察发现额外提交或内容变化"
                    break
                if time.monotonic() >= tail_until:
                    break
                time.sleep(.25)
    except BaseException as error:
        # 诊断边界只记录后重新抛出，不能让尾部读取或取消失败留下 passed=true。
        run_error = f"{type(error).__name__}: {error}"
        raise
    finally:
        stop.set()
        thread.join(timeout=12)
        panel_sample()
        elapsed = sorted(r["elapsed_ms"] for r in results if r["ok"])
        off = all(s.get("state") == "OFF" and s.get("committed") == "OFF" for s in samples)
        success = sum(r["ok"] for r in results)
        # 3. 报告样本间隔及失败分母；采样不能证明间隔内绝无闪屏。
        summary = {"planned": args.rounds, "attempted": len(results), "successful": success,
            "not_run": args.rounds - len(results), "all_sampled_off": off,
            "panel_errors": sum("error" in s for s in samples), "panel_samples": len(samples),
            "max_sample_gap_s": max((b["end"] - a["start"] for a, b in zip(samples, samples[1:])), default=0),
            "successful_round_median_ms": statistics.median(elapsed) if elapsed else None,
            "successful_round_p95_ms": elapsed[math.ceil(len(elapsed) * .95) - 1] if len(elapsed) >= 100 else None,
            "tail_error": tail_error, "initial_count": initial_count, "run_error": run_error, "monitor_finished": monitor_finished.is_set(),
            "passed": success == args.rounds and monitor_finished.is_set() and tail_error is None and run_error is None and not any("error" in s for s in samples) and (args.screen != "off" or off)}
        save("summary.json", summary)
        print(json.dumps(summary, ensure_ascii=False))
    return 0 if summary["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
