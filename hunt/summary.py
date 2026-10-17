"""HUNT ONLY: count results from JUnit XML and print every failure (first lines) into the job log.

usage: python3 hunt/summary.py <id> [class-prefix]
"""
import collections
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

run_id = sys.argv[1]
prefix = sys.argv[2] if len(sys.argv) > 2 else ""
base = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "android", "app", "build", "test-results")
total = 0
failed = []
for f in glob.glob(os.path.join(base, "**", "*.xml"), recursive=True):
    try:
        root = ET.parse(f).getroot()
    except Exception:
        continue
    for tc in root.iter("testcase"):
        cls = tc.get("classname") or ""
        if prefix and not cls.split(".")[-1].startswith(prefix):
            continue
        total += 1
        for kind in ("failure", "error"):
            node = tc.find(kind)
            if node is not None:
                failed.append((cls, tc.get("name"), tc.get("time"), (node.text or node.get("message") or "")))
by_test = collections.Counter(re.sub(r"\d{3}Test$", "Test", c.split(".")[-1]) + "." + n for c, n, _, _ in failed)
print(f"HUNT RESULT id={run_id} prefix={prefix or '*'} tests={total} failed={len(failed)} by_test={dict(by_test)}")
for cls, name, t, text in failed[:40]:
    print(f"----- HUNT FAILURE {cls}.{name} time={t}s")
    for line in text.splitlines()[:45]:
        print("  | " + line[:600])
print(f"HUNT RESULT id={run_id} prefix={prefix or '*'} tests={total} failed={len(failed)} by_test={dict(by_test)}")
