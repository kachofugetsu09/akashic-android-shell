"""Run finite phone tasks on hua-home without a laptop RPC process."""
import argparse
import base64
import json
import math
import os
import signal
from pathlib import Path
import secrets
import time
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True)
    parser.add_argument("--token-file", type=Path, required=True)
    parser.add_argument("--device-id", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--rounds", type=int, default=61)
    parser.add_argument("--interval", type=float, default=30)
    parser.add_argument("--label", required=True)
    args = parser.parse_args()
    if args.rounds < 1 or not math.isfinite(args.interval) or args.interval < 0:
        parser.error("positive rounds and finite nonnegative interval required")
    args.output.mkdir(exist_ok=False)
    token = args.token_file.read_text().strip()
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    nonce = secrets.token_hex(6)
    started = time.monotonic()
    rounds = []
    error = None
    call_count = 0

    def save(name, value):
        (args.output / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")

    def append(name, value):
        with (args.output / name).open("a") as file:
            file.write(json.dumps(value, ensure_ascii=False) + "\n")

    def rpc(op, payload):
        nonlocal call_count
        call_count += 1
        request = urllib.request.Request(args.url,
            json.dumps({"device_id": args.device_id, "op": op, "payload": payload, "timeout_ms": 15000}).encode(),
            {"Content-Type": "application/json", "Authorization": "Bearer " + token})
        before = time.monotonic()
        record = {"op": op, "payload": payload, "wall_start": time.time(), "mono_start": before}
        try:
            with opener.open(request, timeout=20) as response:
                value = json.load(response)
            screen = value if op == "observe" else value["screen"]
            # Keep screenshots as artifacts, not megabytes of repeated JSON logs.
            png = screen.get("screenshot_b64")
            if png:
                raw = base64.b64decode(png, validate=True)
                if not raw.startswith(b"\x89PNG\r\n\x1a\n"):
                    raise RuntimeError("invalid PNG result")
                path = args.output / f"capture-{call_count:04d}.png"
                path.write_bytes(raw)
                screen["screenshot_b64"] = {"path": path.name, "bytes": len(raw)}
            if payload.get("include_screenshot") and not png:
                raise RuntimeError("requested screenshot missing")
            record["response"] = value
            return screen
        except urllib.error.HTTPError as exc:
            record["error"] = {"status": exc.code, "detail": exc.read().decode()}
            raise
        except BaseException as exc:
            record["error"] = f"{type(exc).__name__}: {exc}"
            raise
        finally:
            record["elapsed_ms"] = (time.monotonic() - before) * 1000
            append("calls.jsonl", record)

    def editable(screen):
        if screen["package_name"] != "dev.akashic.phoneeval" or screen["display_id"] <= 0:
            raise RuntimeError("fixture is not on the owned display")
        fields = [node for node in screen["nodes"] if node["editable"]]
        if len(fields) != 2:
            raise RuntimeError("expected normal and paste-only fixture fields")
        return fields[0]["node_id"]

    save("run.json", {"kind": "remote deterministic driver; independent grading required",
        "label": args.label, "pid": os.getpid(), "nonce": nonce, "planned": args.rounds, "interval": args.interval,
        "wall_start": time.time(), "mono_start": started, "model_used": False})
    def interrupted(*_):
        raise InterruptedError("driver stopped; outstanding effects are not retried")

    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        for index in range(args.rounds):
            before = time.monotonic()
            row = {"index": index, "ok": False, "wall_start": time.time()}
            try:
                goal = f"花月远航-{nonce}-{index:03d}-😀Ω"
                row["goal"] = goal
                screen = rpc("observe", {"include_screenshot": False})
                if index % 2 == 0:
                    screen = rpc("action", {"action": "clear_text", "node_id": editable(screen),
                        "snapshot_ref": screen["snapshot_ref"], "include_screenshot": False})
                screen = rpc("action", {"action": "type_text", "node_id": editable(screen),
                    "snapshot_ref": screen["snapshot_ref"], "text": goal, "include_screenshot": index % 10 == 0})
                buttons = [node for node in screen["nodes"] if node["text"].casefold() == "submit text"]
                if len(buttons) != 1:
                    raise RuntimeError("submit button is not unique")
                screen = rpc("action", {"action": "tap", "node_id": buttons[0]["node_id"],
                    "snapshot_ref": screen["snapshot_ref"], "include_screenshot": False})
                row.update(ok=True, display_id=screen["display_id"], snapshot_ref=screen["snapshot_ref"])
            finally:
                row["elapsed_s"] = time.monotonic() - before
                rounds.append(row)
                append("rounds.jsonl", row)
            if index + 1 < args.rounds:
                time.sleep(max(0, args.interval - (time.monotonic() - before)))
    except BaseException as exc:
        error = f"{type(exc).__name__}: {exc}"
        raise
    finally:
        complete = len(rounds) == args.rounds and all(row["ok"] for row in rounds)
        save("summary.json", {"driver_complete": complete and error is None,
            "independent_grading_required": True, "planned": args.rounds, "attempted": len(rounds),
            "successful_rpc_rounds": sum(row["ok"] for row in rounds),
            "not_run": args.rounds - len(rounds), "error": error,
            "elapsed_s": time.monotonic() - started, "wall_end": time.time()})


if __name__ == "__main__":
    main()
