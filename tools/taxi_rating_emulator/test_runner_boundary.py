"""Run the actual shell entry with controlled adb/git executables; never start a guest."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from instrumentation_summary import EXPECTED
from test_instrumentation_summary import complete, event

SCRIPTS = Path(__file__).resolve().parent
CANDIDATE = SCRIPTS.parents[1]
SHA = "46b3c6dba454f99ffd738befedea7b05b9ab3dc9"

FAKE_ADB = """#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
a = sys.argv[1:]
with open(os.environ["ADB_CALLS"], "a") as log:
    log.write(json.dumps(a) + "\\n")
scenario = os.environ.get("SCENARIO")
if a == ["reverse", "--list"]:
    if scenario == "reverse_list_unavailable":
        raise SystemExit(1)
    if scenario == "reverse_list_nonempty":
        print("host tcp:8000 tcp:8000")
    raise SystemExit(0)
if scenario == "getprop_failed_with_expected_stdout" and a == ["shell", "getprop", "ro.kernel.qemu"]:
    print(1)
    raise SystemExit(1)
if scenario == "firewall_denied" and a[:4] == ["shell", "ip6tables", "-C", "OUTPUT"]:
    raise SystemExit(1)
if a == ["shell", "pm", "list", "packages"] and os.environ.get("SCENARIO") == "existing":
    print("package:com.yuldash.app.test")
elif a == ["shell", "pm", "list", "instrumentation"]:
    print("instrumentation:com.yuldash.app.test/androidx.test.runner.AndroidJUnitRunner (target=com.yuldash.app)")
elif a[:2] == ["shell", "getprop"]:
    print("30" if a[-1] == "ro.build.version.sdk" else "1")
elif a == ["shell", "id", "-u"]:
    print(0)
elif a[:5] == ["shell", "pm", "list", "packages", "-U"]:
    print("package:" + a[-1] + " uid:" + ("10102" if a[-1].endswith(".test") else "10101"))
elif a == ["shell", "settings", "get", "global", "airplane_mode_on"]:
    print(1)
elif a[:3] == ["shell", "am", "instrument"]:
    print(Path(os.environ["ADB_EVENTS"]).read_text(), end="")
"""


class RunnerBoundaryTest(unittest.TestCase):
    def run_fake(self, scenario):
        with tempfile.TemporaryDirectory(prefix="tf11-host-control-", dir=CANDIDATE) as temp:
            tmp = Path(temp)
            (tmp / "qa" / "tools").mkdir(parents=True)
            (tmp / "qa" / "tools" / "taxi_rating_emulator").symlink_to(SCRIPTS, target_is_directory=True)
            (tmp / "subject").mkdir()
            tools = tmp / "bin"
            tools.mkdir()
            adb = tools / "adb"
            adb.write_text(FAKE_ADB)
            git = tools / "git"
            git.write_text(f"#!/bin/sh\nprintf '%s\\n' '{SHA}'\n")
            for path in (adb, git):
                path.chmod(0o700)
            evidence = tmp / "taxi-tf11-summary.json"
            evidence.write_text('{"status":"not_completed","expected":10}')
            calls = tmp / "calls.jsonl"
            raw_events = tmp / "events"
            raw_events.write_bytes(complete("".join(event(case, 1) + event(case, 0) for case in sorted(EXPECTED))))
            env = {"PATH": str(tools) + os.pathsep + os.environ.get("PATH", ""), "RUNNER_TEMP": temp,
                   "TF11_SUBJECT": SHA, "ADB_CALLS": str(calls), "ADB_EVENTS": str(raw_events),
                   "SCENARIO": scenario, "PYTHONDONTWRITEBYTECODE": "1"}
            r = subprocess.run(["bash", str(SCRIPTS / "run.sh")], cwd=temp, env=env,
                               capture_output=True, timeout=15)
            return r, [json.loads(line) for line in calls.read_text().splitlines()], json.loads(evidence.read_text())

    def test_device_egress_both_families_and_both_uids_precede_exact_instrument(self):
        proc, cmds, evidence = self.run_fake("all_pass")
        self.assertEqual(proc.returncode, 0, proc.stderr.decode())
        installs = [i for i, c in enumerate(cmds) if c[:2] == ["install", "-t"]]
        self.assertEqual(len(installs), 2)
        runs = [i for i, c in enumerate(cmds) if c[:3] == ["shell", "am", "instrument"]]
        self.assertEqual(len(runs), 1)
        for uid in ("10101", "10102"):
            for firewall in ("iptables", "ip6tables"):
                check = ["shell", firewall, "-C", "OUTPUT", "-m", "owner", "--uid-owner", uid, "-j", "REJECT"]
                self.assertLess(max(installs), cmds.index(check))
                self.assertLess(cmds.index(check), runs[0])
        self.assertEqual(cmds[runs[0]][-2],
                         "com.yuldash.app.TaxiDriverCompletedInstrumentedTest,com.yuldash.app.TaxiPostTripInstrumentedTest")
        self.assertEqual((evidence["status"], evidence["passed"]), ("passed", 10))
        self.assertFalse(any(c[:3] == ["shell", "am", "start"] for c in cmds))
        self.assertFalse(any(c[0] == "reverse" and c[1:] != ["--list"] for c in cmds))

    def test_reverse_list_failure_or_existing_mapping_stops_before_install(self):
        for scenario in ("reverse_list_unavailable", "reverse_list_nonempty"):
            with self.subTest(scenario=scenario):
                proc, cmds, evidence = self.run_fake(scenario)
                self.assertNotEqual(proc.returncode, 0)
                self.assertIn(["reverse", "--list"], cmds)
                self.assertNotIn("install", [c[0] for c in cmds])
                self.assertNotIn(["shell", "am", "instrument"], [c[:3] for c in cmds])
                self.assertEqual(evidence["status"], "not_completed")

    def test_nonzero_identity_probe_with_expected_stdout_still_fails(self):
        proc, cmds, evidence = self.run_fake("getprop_failed_with_expected_stdout")
        self.assertNotEqual(proc.returncode, 0)
        self.assertEqual(cmds, [["shell", "getprop", "ro.kernel.qemu"]])
        self.assertEqual(evidence["status"], "not_completed")

    def test_failed_firewall_never_launches_instrumentation(self):
        proc, cmds, evidence = self.run_fake("firewall_denied")
        self.assertNotEqual(proc.returncode, 0)
        self.assertNotIn(["shell", "am", "instrument"], [c[:3] for c in cmds])
        self.assertEqual(evidence["status"], "not_completed")

    def test_previously_installed_instrumentation_package_stops_before_install(self):
        proc, cmds, evidence = self.run_fake("existing")
        self.assertNotEqual(proc.returncode, 0)
        self.assertNotIn("install", [c[0] for c in cmds])
        self.assertEqual(evidence["status"], "not_completed")


if __name__ == "__main__":
    unittest.main()
