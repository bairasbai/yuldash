"""Drive the actual launcher task from outside its process. Run through run.sh on a fresh AVD."""
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PKG = "com.yuldash.app"
COMPONENT = PKG + "/.MainActivity"
DIR = "files/taxi-consent-qa"
ADB = ["adb"]
A, B = "QA Stop A", "QA Stop B"


def adb(*args, check=True):
    return subprocess.run([*ADB, *map(str, args)], capture_output=True, check=check, timeout=45).stdout


def shell(*args):
    return adb("shell", *args).decode().strip()


def until(probe, description, timeout=40):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        result = probe()
        if result:
            return result
        time.sleep(0.25)
    raise AssertionError("Not observed: " + description)


def tree():
    # Only read a file from a successful dump. Never reuse an older screen.
    path = "/data/local/tmp/taxi-draft-window.xml"
    shell("rm", "-f", path)
    result = adb("shell", "uiautomator", "dump", path, check=False)
    if b"dump" not in result.lower():
        return []
    try:
        return list(ET.fromstring(adb("exec-out", "cat", path, check=False)).iter("node"))
    except ET.ParseError:
        return []


def bounds(node):
    return [int(n) for n in re.findall(r"-?\d+", node.get("bounds", ""))]


def touch(node):
    left, top, right, bottom = bounds(node)
    assert left < right and top < bottom, "Empty accessibility bounds"
    shell("input", "tap", (left + right) // 2, (top + bottom) // 2)


def label(value):
    return lambda n: value in (n.get("text"), n.get("content-desc"))


def find(predicate, description, scroll=False):
    def probe():
        nodes = tree()
        for node in nodes:
            if predicate(node) and len(bounds(node)) == 4:
                return [node]  # a leaf Element by itself is falsey
        if scroll:
            candidates = [bounds(n) for n in nodes if n.get("scrollable") == "true"]
            candidates = [b for b in candidates if len(b) == 4 and b[3] - b[1] > 120]
            if candidates:
                left, top, right, bottom = max(candidates, key=lambda b: b[3] - b[1])
                x = (left + right) // 2
                pad = (bottom - top) // 6
                shell("input", "swipe", x, bottom - pad, x, top + pad, 350)
        return None
    return until(probe, description)[0]


def click(text, scroll=False):
    touch(find(label(text), text, scroll))


def pid():
    text = adb("shell", "pidof", PKG, check=False).strip()
    return int(text) if text.isdigit() else None


def task_record(saved=False):
    dump = shell("dumpsys", "activity", "-a", "activities")
    match = re.search(r'\*\s+Hist\s*#\d+:\s+ActivityRecord\{(\w+)\s+u\d+\s+'
                      r'com\.yuldash\.app/\.MainActivity\s+t(\d+)[^\n]*\}(.*?)'
                      r'(?=\n\s*\* Hist|\n\s*Task\{|\Z)', dump, re.S)
    if not match:
        return None
    record = match.group(3)
    bundle = re.search(r'(?:mHaveState|haveState)=true\s+(?:mIcicle|icicle)=Bundle\[mParcelledData.dataSize=(\d+)\]', record)
    if saved and (not bundle or int(bundle[1]) <= 0):
        return None
    return match[1], int(match[2]), int(bundle[1]) if bundle else 0, record


def start(uid=None):
    args = ["am", "start", "-W", "-f", "0x10200000", "-a", "android.intent.action.MAIN",
            "-c", "android.intent.category.LAUNCHER", "-n", COMPONENT]
    if uid is not None:
        args += ["--es", "type", "instant", "--es", "recipient_user_id", str(uid)]
    result = shell(*args)
    assert "Error" not in result, "Launcher did not open"
    shell("input", "keyevent", "KEYCODE_WAKEUP")


def identity(action, session_path):
    # Feed private fixture bytes directly to app-private storage; never place a bearer in argv or logs.
    with session_path.open("rb") as fp:
        subprocess.run([*ADB, "shell", "-T", f"run-as {PKG} sh -c 'umask 077; cat > {DIR}/session.json'"],
                       stdin=fp, check=True, timeout=15)
    result = shell("am", "broadcast", "--include-stopped-packages", "-n", PKG + "/.TaxiDraftFixtureReceiver",
                   "--es", "fixture", action)
    assert "result=107" in result, "Fixture authentication operation did not complete"


def add_stops():
    for query, title in (("QA%salpha", A), ("QA%sbravo", B)):
        click("Заехать по пути")
        touch(find(lambda n: n.get("class") == "android.widget.EditText", "stop search editor"))
        shell("input", "text", query)
        click(title)


def choose_route(scheduled=True):
    click("QA Destination", scroll=True)
    add_stops()
    if scheduled:
        click("Детали заказа")
        click("На время", scroll=True)
        for _ in range(2):
            button = find(lambda n: n.get("resource-id") == "android:id/button1", "native picker OK")
            touch(button)
            time.sleep(0.4)


def ordered_route():
    def probe():
        nodes = tree()
        texts = [(n.get("text"), bounds(n)) for n in nodes]
        rows = [next((b for text, b in texts if text == name), None) for name in (A, B, "QA Destination")]
        if all(rows) and rows[0][1] < rows[1][1] < rows[2][1]:
            return True
        return False
    until(probe, "both intermediate stops in order above destination")


def submit_label():
    return find(lambda n: n.get("text", "").startswith("Заказать на "), "scheduled order control").get("text")


def clean_form(place):
    find(label(place), "saved place belonging to current passenger", scroll=True)
    nodes = tree()
    assert nodes and not any(n.get("text") in (A, B) or n.get("content-desc") == "Убрать остановку"
                             for n in nodes), "Previous stops on new form"
    assert any(n.get("class") == "android.widget.EditText" for n in nodes), "Destination is still selected"


def lifecycle_events():
    events = adb("logcat", "-b", "events", "-d", "-s", "wm_on_create_called:I", "am_on_create_called:I").decode()
    return sum(1 for line in events.splitlines() if "MainActivity" in line)


def rotate():
    before = lifecycle_events()
    for orientation in (1, 0):
        shell("settings", "put", "system", "user_rotation", orientation)
        until(lambda: re.search(rf'SurfaceOrientation:\s*{orientation}\b', shell("dumpsys", "input")),
              "display rotation " + str(orientation))
        before = until(lambda: (count if (count := lifecycle_events()) > before else None),
                       "MainActivity recreated for rotation")


def kill_background():
    previous = pid()
    assert previous
    shell("input", "keyevent", "KEYCODE_HOME")
    def stopped_record():
        record = task_record(saved=True)
        return record if record and re.search(r'\b(?:mState|state)=STOPPED\b', record[3]) else None
    token, task, size, _ = until(stopped_record, "stopped task with saved Android Bundle")
    shell("am", "kill", "--user", "current", PKG)
    until(lambda: pid() is None, "old process disappeared")
    dead = until(lambda: task_record(saved=True), "retained dead task")
    assert dead[:3] == (token, task, size) and "app=null" in dead[3], "System discarded the original task"
    return previous, token, task, size


def screenshot(folder, name):
    png = adb("exec-out", "screencap", "-p")
    assert png.startswith(b"\x89PNG\r\n\x1a\n")
    (folder / (name + ".png")).write_bytes(png)


def no_writes(state_path, expected):
    state = json.loads(state_path.read_text())
    assert state["schedule"] == state["orders"] == expected, "Unexpected scheduled order"
    assert all(state[k] == 0 for k in ("legacy", "quote", "confirm", "activate", "close")), "Unexpected request"
    return state


def run(scenario, session, state, results):
    assert shell("getprop", "ro.kernel.qemu") == "1"
    shell("wm", "size", "880x2400")
    shell("wm", "density", "320")
    shell("settings", "put", "system", "accelerometer_rotation", 0)
    shell("settings", "put", "system", "user_rotation", 0)
    for kind in ("COARSE", "FINE"):
        shell("pm", "grant", PKG, "android.permission.ACCESS_" + kind + "_LOCATION")
    user = json.loads(session.read_text())
    identity("primary", session)
    seeded_pid = pid()
    start(user["uid"])
    assert pid() == seeded_pid, "Initial UI must use the process that logged in"
    adb("emu", "geo", "fix", "58.317", "52.591")
    find(label("Моя позиция"), "synthetic GPS pickup")
    choose_route()
    ordered_route()
    expected_cta = submit_label()
    no_writes(state, 0)
    if scenario == "restore":
        screenshot(results, "draft-before")
        rotate()
        ordered_route()
        assert pid() == seeded_pid and submit_label() == expected_cta
        no_writes(state, 0)
        screenshot(results, "draft-rotated")
    old_pid, record, task, bundle = kill_background()
    if scenario in ("alternate", "relogin"):
        identity("logout", session)
        until(lambda: "relogin" in json.loads(session.read_text()), "server acknowledged logout and issued a new login")
        identity(scenario, session)
    start()
    restored_pid = pid()
    assert restored_pid and restored_pid != old_pid
    restored_record = until(task_record, "resumed task")
    assert restored_record[:2] == (record, task)
    if scenario == "restore":
        ordered_route()
        assert submit_label() == expected_cta
        no_writes(state, 0)
        screenshot(results, "draft-restored")
        click(expected_cta)
        find(label("Новый заказ"), "scheduled receipt")
        result = no_writes(state, 1)
        assert result["order_status"] == "scheduled" and result["route"]["stops"] == [A, B]
        assert result["route"] == result["scheduled_route"] and result["route"]["km"] == 180
        assert result["route"]["minutes"] == 90 and result["route"]["fare"] > 0
        screenshot(results, "draft-submitted")
        click("Новый заказ")
        clean_form("QA Destination")
        # Protected NOW: the same selections may live on screen but must not return after death.
        choose_route(scheduled=False)
        ordered_route()
        privacy_pid, token, privacy_task, _ = kill_background()
        start()
        clean_form("QA Destination")
        assert pid() != privacy_pid and task_record()[:2] == (token, privacy_task)
        no_writes(state, 1)
    else:
        place = "QA Alternate Place" if scenario == "alternate" else "QA Destination"
        # The navigation owner may safely return to Home on an account switch. Reopen taxi
        # through its normal authenticated entry only if the parked task left the taxi screen.
        nodes = until(tree, "foreground accessibility tree")
        if not any(n.get("text") == place for n in nodes):
            start(json.loads(session.read_text())[scenario]["uid"])
        clean_form(place)
        no_writes(state, 0)
    (results / f"draft-{scenario}-proof.json").write_text(json.dumps({
        "scenario": scenario, "before_pid": old_pid, "after_pid": restored_pid, "same_task": task,
        "same_activity_record": True, "saved_bundle_bytes": bundle, "submitted": scenario == "restore",
    }, indent=2))


if __name__ == "__main__":
    scenario, session, state, folder = sys.argv[1:]
    try:
        run(scenario, Path(session), Path(state), Path(folder))
    except Exception:
        screenshot(Path(folder), "draft-" + scenario + "-failure")
        raise
