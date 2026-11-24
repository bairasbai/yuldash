"""Sanitized public QA annotation for one exact, nondefault draft Android gate."""
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

PRODUCT = "a11244a0ddfddaa8804bb03c209ab135a16b9a8d"
TASKS = {
    b":app:testDebugUnitTest": "unit-test-task",
    b":app:jacocoTestReport": "coverage-report-task",
    b":app:jacocoCoverageVerification": "coverage-verification-task",
    b":app:compileDebugUnitTestKotlin": "unit-test-compilation-task",
    b":app:compileDebugUnitTestJavaWithJavac": "unit-test-compilation-task",
}
GRADLE = ("./gradlew", ":app:jacocoCoverageVerification", "--no-daemon", "--stacktrace")
IDENT = r"[A-Za-z][A-Za-z0-9_]{0,79}"
CLASS = re.compile(rf"com\.yuldash(?:\.{IDENT}){{1,6}}\Z", re.ASCII)
METHOD = re.compile(rf"{IDENT}\Z", re.ASCII)
PACKAGE = re.compile(r"^\s*package\s+(com\.yuldash[.A-Za-z0-9_]*)\s*;?\s*$", re.M)
DECL = re.compile(rf"\b(?:fun|void)\s+({IDENT})\s*\(")
LIMIT_LINE = 4096
LIMIT_XML = 1024 * 1024
LIMIT_FILES = 1024
LIMIT_TOTAL_XML = 16 * LIMIT_XML
LIMIT_NAMES = 8


def task_marker(line):
    line = line.rstrip(b"\r\n")
    # Exact allowlisted spelling only. Mixed Gradle/test stdout can spoof even an
    # exact match, so this is a hint, never authoritative task evidence.
    for task, phase in TASKS.items():
        if line in (b"> Task " + task + b" FAILED", b"Execution failed for task '" + task + b"'."):
            return phase
    return None


def observe_stream(stream):
    phases = set()
    while line := stream.readline(LIMIT_LINE + 1):
        if len(line) > LIMIT_LINE:
            while line and not line.endswith(b"\n"):
                line = stream.readline(LIMIT_LINE + 1)
            continue
        if phase := task_marker(line):
            phases.add(phase)
    return phases


def name_is_declared(root, classname, method):
    if len(classname) + len(method) > 180 or not (CLASS.fullmatch(classname) and METHOD.fullmatch(method)):
        return False
    pkg, stem = classname.rsplit(".", 1)
    for extension in ("kt", "java"):
        source = root / "app" / "src" / "test" / "java" / Path(*pkg.split(".")) / f"{stem}.{extension}"
        try:
            if source.resolve() != source:
                continue
            with source.open("r", encoding="utf-8") as f:
                content = f.read(LIMIT_XML + 1)
        except (OSError, UnicodeError):
            continue
        package = PACKAGE.search(content) if len(content) <= LIMIT_XML else None
        if package and package[1] == pkg and method in DECL.findall(content):
            return True
    return False


def find_failures(root, results):
    names, coverage, seen_bytes = set(), "scanned", 0
    if not results.is_dir() or results.resolve() != results:
        return names, "unavailable"
    found = False
    for number, item in enumerate(results.glob("TEST-*.xml"), start=1):
        found = True
        if number > LIMIT_FILES or seen_bytes >= LIMIT_TOTAL_XML:
            coverage = "partial"
            break
        try:
            if item.resolve() != item or not item.is_file():
                coverage = "partial"
                continue
            with item.open("rb") as f:
                payload = f.read(min(LIMIT_XML, LIMIT_TOTAL_XML - seen_bytes) + 1)
            seen_bytes += len(payload)
            if (len(payload) > LIMIT_XML or seen_bytes > LIMIT_TOTAL_XML or
                    b"\0" in payload or b"<!DOCTYPE" in payload.upper() or b"<!ENTITY" in payload.upper()):
                coverage = "partial"
                continue
            document = ET.fromstring(payload)
            if document.tag not in {"testsuite", "testsuites"}:
                coverage = "partial"
                continue
        except (OSError, ET.ParseError):
            coverage = "partial"
            continue
        for case in document.iter("testcase"):
            if case.find("failure") is None and case.find("error") is None:
                continue
            classname, method = case.get("classname", ""), case.get("name", "")
            if len(names) < LIMIT_NAMES and name_is_declared(root, classname, method):
                names.add(f"{classname}.{method}")
    return names, coverage if found else "unavailable"


def annotate(status, rc, hints, names=(), reports="unavailable"):
    junit = "source-matched-failure-entry" if names else ("not-needed" if rc == 0 else "unknown")
    message = (f"TF09 Android new probe; product={PRODUCT}; status={status}; exit={rc}; "
               f"stream_hints_unverified={','.join(sorted(hints)) or 'none'}; "
               f"junit_evidence={junit}; xml_scan={reports}; "
               f"identifiers={','.join(sorted(names)) or 'none'}")
    kind = "notice" if rc == 0 else "error"
    print(f"::{kind} title=TF09 Android diagnostic::{message}", flush=True)

def main():
    root = Path.cwd()
    results = root / "app/build/test-results/testDebugUnitTest"
    if (os.environ.get("TF09_QA_VERIFIED") != PRODUCT or root.name != "android" or
            results.exists() or results.is_symlink()):
        annotate("preflight-refused", 125, ())
        return 125
    try:
        # Exact ordinary CI gate, no substitution or relaxed coverage thresholds.
        os.chmod("./gradlew", Path("./gradlew").stat().st_mode | 0o111)
        with subprocess.Popen(GRADLE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT) as process:
            hints = observe_stream(process.stdout)
            rc = process.wait()
    except OSError:
        annotate("launch-unavailable", 125, ())
        return 125

    rc = rc if rc >= 0 else 128 - rc
    if rc == 0:
        annotate("passed", rc, ())
    else:
        names, reports = find_failures(root, results)
        annotate("failed", rc, hints, names, reports)
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
