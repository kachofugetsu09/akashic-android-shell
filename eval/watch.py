"""Passively record physical display and fixture state; never send phone actions."""
import argparse
import base64
import json
from pathlib import Path
import re
import signal
import subprocess
import threading
import time


def stamp():
    """Include host suspend in observation gaps."""
    return time.clock_gettime(time.CLOCK_BOOTTIME)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--label", required=True)
    args = parser.parse_args()
    args.output.mkdir(exist_ok=False)
    stop = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    signal.signal(signal.SIGINT, lambda *_: stop.set())
    errors = []
    panels = []
    provider_count = 0
    started = stamp()

    def append(name, data):
        with (args.output / name).open("a") as file:
            file.write(json.dumps(data, ensure_ascii=False) + "\n")

    def adb(*command):
        return subprocess.check_output(["adb", "-s", args.serial, "shell", *command], text=True, timeout=10)

    def panel():
        before = stamp()
        raw = adb("dumpsys", "display")
        lines = [line for line in raw.splitlines() if "DisplayDeviceInfo{" in line and "type INTERNAL" in line]
        if len(lines) != 1:
            raise RuntimeError("physical display is not unique")
        match = re.search(r", state (\w+), committedState (\w+)", lines[0])
        if match is None:
            raise RuntimeError("physical display state unavailable")
        row = {"wall": time.time(), "start": before, "end": stamp(),
               "state": match[1], "committed": match[2]}
        panels.append(row)
        append("panel.jsonl", row)

    def provider():
        nonlocal provider_count
        before = stamp()
        raw = adb("content", "query", "--uri", "content://dev.akashic.phoneeval.state")
        match = re.search(r"state_b64=([A-Za-z0-9+/=]+)", raw)
        if match is None:
            raise RuntimeError("independent provider result unavailable")
        state = json.loads(base64.b64decode(match[1], validate=True))
        append("provider.jsonl", {"wall": time.time(), "start": before, "end": stamp(), "state": state})
        provider_count += 1

    def windows():
        raw = adb("dumpsys", "window", "windows")
        blocks = re.split(r"(?m)^\s*Window #\d+ ", raw)[1:]
        matching = [block for block in blocks if " dev.akashic.phoneeval/" in block.splitlines()[0]]
        displays = []
        for block in matching:
            match = re.search(r"\bmDisplayId=(\d+)\b", block)
            if match is None:
                raise RuntimeError("fixture window lacks a display id")
            displays.append(int(match[1]))
        append("windows.jsonl", {"wall": time.time(), "mono": stamp(), "displays": displays})

    (args.output / "run.json").write_text(json.dumps({"label": args.label,
        "kind": "passive USB observer; adds measurement load, not an unplugged test",
        "clock": "CLOCK_BOOTTIME",
        "wall_start": time.time(), "mono_start": started}, indent=2) + "\n")
    next_provider = next_windows = 0
    try:
        while not stop.is_set():
            tick = stamp()
            try:
                panel()
                if tick >= next_provider:
                    provider()
                    next_provider = stamp() + 1
                if tick >= next_windows:
                    windows()
                    next_windows = stamp() + 10
            except (OSError, subprocess.SubprocessError, RuntimeError, ValueError) as exc:
                row = {"wall": time.time(), "error": f"{type(exc).__name__}: {exc}"}
                errors.append(row)
                append("errors.jsonl", row)
            stop.wait(max(0, .5 - (stamp() - tick)))
        panel()
        provider()
        windows()
    except BaseException as exc:
        errors.append({"wall": time.time(), "error": f"{type(exc).__name__}: {exc}"})
        raise
    finally:
        gaps = [b["start"] - a["start"] for a, b in zip(panels, panels[1:])]
        result = {"elapsed_s": stamp() - started, "samples": len(panels),
            "provider_samples": provider_count, "errors": errors,
            "all_sampled_off": bool(panels) and all(s["state"] == s["committed"] == "OFF" for s in panels),
            "max_sample_start_gap_s": max(gaps) if gaps else None,
            "sampling_cannot_exclude_between_sample_changes": True}
        (args.output / "summary.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
