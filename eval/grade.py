"""Grade remote RPC rounds against separately collected device state."""
import argparse
import json
import math
from pathlib import Path
from PIL import Image


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--driver", type=Path, required=True)
    parser.add_argument("--watch", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--coordination", type=Path, required=True)
    parser.add_argument("--minimum-duration", type=float, default=0)
    parser.add_argument("--max-sample-gap", type=float, default=2)
    args = parser.parse_args()
    if not math.isfinite(args.minimum_duration) or args.minimum_duration < 0:
        parser.error("minimum duration must be finite and nonnegative")
    if not math.isfinite(args.max_sample_gap) or args.max_sample_gap <= 0:
        parser.error("maximum sample gap must be finite and positive")

    def read_lines(path):
        return [json.loads(line) for line in path.read_text().splitlines() if line]

    driver = json.loads((args.driver / "summary.json").read_text())
    watch = json.loads((args.watch / "summary.json").read_text())
    watch_run = json.loads((args.watch / "run.json").read_text())
    rounds = read_lines(args.driver / "rounds.jsonl")
    providers = read_lines(args.watch / "provider.jsonl")
    windows = read_lines(args.watch / "windows.jsonl")
    panels = read_lines(args.watch / "panel.jsonl")
    calls = read_lines(args.driver / "calls.jsonl")
    coordination = json.loads(args.coordination.read_text())
    failures = []
    if watch_run.get("clock") != "CLOCK_BOOTTIME" or coordination.get("clock") != "CLOCK_BOOTTIME":
        failures.append("observer and coordination must use CLOCK_BOOTTIME")
    planned = driver["planned"]
    goals = [row["goal"] for row in rounds]
    if not driver["driver_complete"] or len(rounds) != planned or not all(row["ok"] for row in rounds):
        failures.append("driver did not complete every planned round")
    if len(set(goals)) != len(goals):
        failures.append("goals were reused")
    if driver["elapsed_s"] < args.minimum_duration:
        failures.append("driver duration is shorter than required")
    if watch["errors"] or watch["samples"] < 2 or not watch["all_sampled_off"]:
        failures.append("physical display sampling failed or was not always OFF/OFF")
    if len(panels) != watch["samples"] or not all(row["state"] == row["committed"] == "OFF" for row in panels):
        failures.append("raw physical samples disagree with the OFF/OFF summary")
    gaps = [b["start"] - a["start"] for a, b in zip(panels, panels[1:])]
    if not gaps or min(gaps) < 0 or max(gaps) > args.max_sample_gap:
        failures.append("physical sampling gap exceeds the declared limit")
    if not providers or not windows:
        failures.append("independent provider or WMS observations missing")
    start = coordination["start_requested_mono"]
    end = coordination["finished_observed_mono"]
    if not all(math.isfinite(value) for value in (start, end)) or start >= end:
        failures.append("invalid local coordination markers")
    if not panels or not providers or panels[0]["end"] > start or providers[0]["end"] > start or panels[-1]["start"] < end + 2 or providers[-1]["start"] < end + 2:
        failures.append("independent observations do not cover driver start through the two-second tail")

    # The fixed driver writes each call before completing its round. A partial
    # artifact copy must not turn missing screenshots or errors into a pass.
    expected_calls = []
    for index, row in enumerate(rounds):
        expected_calls.append(("observe", None, None, False))
        if index % 2 == 0:
            expected_calls.append(("action", "clear_text", None, False))
        expected_calls.append(("action", "type_text", row["goal"], index % 10 == 0))
        expected_calls.append(("action", "tap", None, False))
    actual_calls = [(call["op"], call["payload"].get("action"),
                     call["payload"].get("text"), bool(call["payload"].get("include_screenshot")))
                    for call in calls]
    if actual_calls != expected_calls:
        failures.append("RPC evidence is incomplete or differs from the fixed round sequence")
    if any("error" in call or "response" not in call for call in calls):
        failures.append("RPC evidence contains a failed or unanswered call")

    screenshots = 0
    for call in calls:
        if "response" not in call:
            continue
        response = call["response"]
        screen = response if call["op"] == "observe" else response["screen"]
        png = screen.get("screenshot_b64")
        if call["payload"].get("include_screenshot") and not png:
            failures.append("requested screenshot missing from evidence")
        if png:
            path = args.driver / png["path"]
            try:
                if not path.resolve().is_relative_to(args.driver.resolve()):
                    raise ValueError("screenshot path escaped the run")
                with Image.open(path) as image:
                    image.load()
                    if image.format != "PNG" or image.size != (screen["width"], screen["height"]):
                        raise ValueError("PNG format or coordinate size mismatch")
                screenshots += 1
            except (OSError, ValueError) as exc:
                failures.append(f"screenshot cannot be accepted: {type(exc).__name__}: {exc}")

    matches = []
    if providers:
        baseline = providers[0]["state"]["count"]
        if providers[0]["state"]["submitted"] in goals:
            failures.append("observer did not establish a pre-submit baseline")
        counts = [row["state"]["count"] for row in providers]
        if any(b < a or b - a > 1 for a, b in zip(counts, counts[1:])):
            failures.append("submission counter reset or jumped between observations")
        if any(count < baseline or count > baseline + planned for count in counts):
            failures.append("submission count exceeds the planned range")
        for index, row in enumerate(rounds):
            expected = baseline + index + 1
            seen = any(sample["state"]["submitted"] == row["goal"] and sample["state"]["count"] == expected
                       for sample in providers)
            matches.append({"index": index, "expected_count": expected, "observed": seen})
        if len(matches) != planned or not all(row["observed"] for row in matches):
            failures.append("not every fresh goal was independently observed at its exact count")
        if not goals or providers[-1]["state"]["count"] != baseline + planned or providers[-1]["state"]["submitted"] != goals[-1]:
            failures.append("final independent state does not match the planned outcome")

    display_ids = {row["display_id"] for row in rounds if row.get("ok")}
    if not display_ids or any(display <= 0 for display in display_ids):
        failures.append("driver lacks an independent positive display identity")
    if any(len(row["displays"]) != 1 or row["displays"][0] not in display_ids for row in windows):
        failures.append("WMS did not keep the fixture exclusively on a reported owned display")
    result = {"passed": not failures, "planned": planned,
        "independently_observed_goals": sum(row["observed"] for row in matches),
        "matches": matches, "failures": failures, "driver_elapsed_s": driver["elapsed_s"],
        "physical_samples": watch["samples"], "all_sampled_off": watch["all_sampled_off"],
        "decoded_screenshots": screenshots,
        "maximum_allowed_sample_gap_s": args.max_sample_gap,
        "max_sample_start_gap_s": watch["max_sample_start_gap_s"],
        "limits": ["passive USB sampling adds load; not a physical unplug or computer-poweroff test",
                   "sampling cannot exclude between-sample changes; finite fixture tasks do not prove every app",
                   "PNG decoding checks image validity and coordinate size, not visual task completion"]}
    with args.output.open("x") as file:
        json.dump(result, file, ensure_ascii=False, indent=2)
        file.write("\n")
    print(json.dumps({key: result[key] for key in ("passed", "planned", "independently_observed_goals", "failures")}, ensure_ascii=False))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
