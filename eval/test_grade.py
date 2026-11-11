"""用不完整或错误的独立证据验证 grader 不会假通过。"""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from PIL import Image


class GradeTest(unittest.TestCase):
    def check_case(self, case, expected):
        """先建立一致的单轮证据，再只破坏待验证的边界。"""
        with tempfile.TemporaryDirectory(prefix="phone-grade-test-") as folder:
            root = Path(folder)
            driver, watch = root / "driver", root / "watch"
            driver.mkdir()
            watch.mkdir()
            def save(path, value):
                path.write_text(json.dumps(value))
            def lines(path, values):
                path.write_text("".join(json.dumps(value) + "\n" for value in values))

            # 1. 一次提交由独立 provider 确认，采样覆盖执行前和两秒尾部。
            goal = "fresh-test-goal"
            screen = {"width": 2, "height": 2}
            calls = [
                {"op": "observe", "payload": {"include_screenshot": False}, "response": screen},
                {"op": "action", "payload": {"action": "clear_text", "include_screenshot": False}, "response": {"screen": screen}},
                {"op": "action", "payload": {"action": "type_text", "text": goal, "include_screenshot": True}, "response": {"screen": {**screen, "screenshot_b64": {"path": "capture.png"}}}},
                {"op": "action", "payload": {"action": "tap", "include_screenshot": False}, "response": {"screen": screen}},
            ]
            panels = [{"start": tick / 2, "end": tick / 2 + .1, "state": "OFF", "committed": "OFF"} for tick in range(16, 46)]
            providers = [{"start": 8, "end": 8.1, "state": {"count": 0, "submitted": "old"}},
                         {"start": 12, "end": 12.1, "state": {"count": 1, "submitted": goal}},
                         {"start": 22.6, "end": 22.7, "state": {"count": 1, "submitted": goal}}]
            windows = [{"mono": 8, "displays": [2]}, {"mono": 22.7, "displays": [2]}]
            coordination = {"clock": "CLOCK_BOOTTIME", "start_requested_mono": 10, "finished_observed_mono": 20}
            Image.new("RGB", (2, 2), "green").save(driver / "capture.png")

            # 2. 每个负控只改变一种实际可缺失或错误的证据。
            if case == "empty_calls":
                calls = []
            elif case == "late_query_tail":
                panels = [row for row in panels if row["start"] <= 21.5]
                panels[-1]["end"] = 22.2
            elif case == "suspend_gap":
                for row in panels:
                    if row["start"] >= 18:
                        row["start"] += 10
                        row["end"] += 10
            elif case == "wrong_clock":
                coordination["clock"] = "CLOCK_MONOTONIC"
            elif case == "truncated_png":
                (driver / "capture.png").write_bytes(b"\x89PNG\r\n\x1a\n")
            elif case == "missing_window":
                windows = []
            elif case == "extra_submit":
                providers[-1]["state"]["count"] = 2
            elif case != "valid":
                raise AssertionError(case)
            save(driver / "summary.json", {"driver_complete": True, "planned": 1, "elapsed_s": 10})
            lines(driver / "rounds.jsonl", [{"index": 0, "goal": goal, "ok": True, "display_id": 2}])
            lines(driver / "calls.jsonl", calls)
            save(watch / "run.json", {"clock": "CLOCK_BOOTTIME"})
            save(watch / "summary.json", {"samples": len(panels), "errors": [], "all_sampled_off": True,
                 "max_sample_start_gap_s": max(b["start"] - a["start"] for a, b in zip(panels, panels[1:]))})
            lines(watch / "panel.jsonl", panels)
            lines(watch / "provider.jsonl", providers)
            lines(watch / "windows.jsonl", windows)
            save(root / "coord.json", coordination)

            # 3. 调用真实 CLI，既检查退出码，也核对失败来自目标边界。
            completed = subprocess.run([sys.executable, str(Path(__file__).with_name("grade.py")),
                "--driver", str(driver), "--watch", str(watch), "--coordination", str(root / "coord.json"),
                "--output", str(root / "result.json")], capture_output=True, text=True)
            self.assertEqual(completed.returncode, 0 if expected is None else 1, completed.stderr)
            result = json.loads((root / "result.json").read_text())
            self.assertEqual(result["passed"], expected is None)
            if expected is not None:
                self.assertTrue(any(expected in failure for failure in result["failures"]), result)

    def test_independent_evidence(self):
        for case, expected in (
            ("valid", None),
            ("empty_calls", "RPC evidence is incomplete"),
            ("late_query_tail", "two-second tail"),
            ("suspend_gap", "sampling gap"),
            ("wrong_clock", "CLOCK_BOOTTIME"),
            ("truncated_png", "screenshot cannot be accepted"),
            ("missing_window", "WMS observations missing"),
            ("extra_submit", "submission count exceeds"),
        ):
            with self.subTest(case=case):
                self.check_case(case, expected)


if __name__ == "__main__":
    unittest.main()
