"""Drive the actual launcher task from outside its process. Run through run.sh on a fresh AVD."""
import base64
import json
import re
import shlex
import subprocess
import sys
import time
from pathlib import Path

PKG = "com.yuldash.app"
COMPONENT = PKG + "/.MainActivity"
DIR = "files/taxi-consent-qa"
ADB = ["adb"]
A, B = "QA Stop A", "QA Stop B"
BASHKIR = {
    "Заехать по пути": "Юлда инеп сығыу", "Детали заказа": "Заказ деталдәре",
    "На время": "Ваҡытҡа", "Обратно тоже": "Кире лә", "2 ч": "2 сәғ",
    "Моя позиция": "Минең урын", "Новый заказ": "Яңы заказ", "Убрать остановку": "Туҡталышты алыу",
}


OBSERVER = "com.yuldash.qa.taxidraft/.WindowObserver"
KNOWN_UI = set(BASHKIR) | set(BASHKIR.values()) | {
    A, B, "QA Destination", "QA Origin", "QA Alternate Place", "Изменить", "Үҙгәртеү",
    "Куда едем?", "Ҡайҙа барабыҙ?", "Куда заехать?", "Ҡайҙа инеп сығырға?",
    "Включи геолокацию", "Геолокацияны ҡабыҙ", "Профиль", "Быстрый заказ", "Тиҙ заказ",
    "Рассчитать цену", "Хаҡты иҫәпләү", "Сейчас", "Хәҙер",
}
OBSERVER_LABELS = base64.b64encode(json.dumps(sorted(KNOWN_UI), ensure_ascii=False).encode()).decode()
last_observation = "unread"
scroll_evidence = []


def local_label(text):
    return BASHKIR.get(text, text) if sys.argv[1] == "restore-ba" else text


def adb(*args, check=True, timeout=45):
    return subprocess.run([*ADB, *map(str, args)], capture_output=True, check=check, timeout=timeout).stdout


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


def external_window():
    raw = shell("am", "instrument", "-w", "-r", "-e", "labels", OBSERVER_LABELS, OBSERVER)
    status = re.search(r"^INSTRUMENTATION_RESULT: observation=([a-zA-Z0-9_-]+)", raw, re.M)
    observation = status[1] if status else "instrumentation-did-not-report"
    data = re.search(r"^INSTRUMENTATION_RESULT: window_nodes=([A-Za-z0-9+/=]+)", raw, re.M)
    return observation, json.loads(base64.b64decode(data[1])) if data else []


def tree(native=False):
    # Never let a window read itself launch or foreground the app under test. While the task
    # is stopped/dead the host queries only ActivityManager/PID and the isolated server.
    global last_observation
    live = pid()
    focus = adb("shell", "dumpsys window | grep -m 1 mCurrentFocus=", check=False).decode()
    last_observation = "app-not-foreground"
    if live and PKG in focus:
        try:
            reply = adb("shell", "am", "broadcast", "-n", PKG + "/.TaxiDraftFixtureReceiver",
                        "--es", "fixture", "read-controls", timeout=6).decode()
        except subprocess.TimeoutExpired as expired:
            raise AssertionError("Foreground app did not service the read-only UI probe") from expired
        assert pid() == live, "Window probe replaced the live app process"
        if "result=107" in reply:
            data = adb("exec-out", "run-as", PKG, "cat", DIR + "/controls.json")
            frame = json.loads(data)
            last_observation = frame["observation"]
            nodes = frame["nodes"]
            if nodes and (not native or any(n.get("resource-id") == "android:id/button1" for n in nodes)):
                return nodes
        else:
            last_observation = "probe-did-not-report"
    # Date/time dialogs can own their own native accessibility window.
    status, nodes = external_window()
    last_observation += "/" + status
    return nodes


def bounds(node):
    return [int(n) for n in re.findall(r"-?\d+", node.get("bounds", ""))]


def touch(node):
    left, top, right, bottom = bounds(node)
    assert left < right and top < bottom, "Empty accessibility bounds"
    shell("input", "tap", (left + right) // 2, (top + bottom) // 2)


def label(value):
    return lambda n: local_label(value) in (n.get("text"), n.get("content-desc"))


def find(predicate, description, scroll=False, native=False, full_row=False):
    earlier = scroll == "earlier"
    swipe_count = 0
    def probe():
        nonlocal earlier, swipe_count
        nodes = tree(native=native)
        for node in nodes:
            if predicate(node) and len(bounds(node)) == 4:
                if full_row:
                    # Compose can expose a title clipped by the fixed action footer while
                    # its switch is still outside the scroll viewport. Keep scrolling until
                    # the entire settings row has room to become actionable.
                    top, bottom = bounds(node)[1], bounds(node)[3]
                    viewports = [bounds(v) for v in nodes if v.get("scrollable") == "true"
                                 and len(bounds(v)) == 4 and bounds(v)[1] <= top < bounds(v)[3]]
                    if not viewports or any(bottom + (v[3] - v[1]) // 5 >= v[3] for v in viewports):
                        continue
                return [node]
        if scroll:
            candidates = [n for n in nodes if n.get("scrollable") == "true" and len(bounds(n)) == 4
                          and bounds(n)[3] - bounds(n)[1] > 120]
            if candidates:
                container = max(candidates, key=lambda n: bounds(n)[3] - bounds(n)[1])
                left, top, right, bottom = bounds(container)
                offset, maximum = container.get("scroll-offset"), container.get("scroll-maximum")
                # The route header is fixed and stays identical as the interior cards scroll.
                # Only reverse at an observed boundary of the actual vertical scroll container.
                if offset is not None and maximum is not None and maximum > 0:
                    if earlier and offset <= 0:
                        earlier = False
                    elif not earlier and offset >= maximum:
                        earlier = True
                x = left + (right - left) * (3 if swipe_count % 2 else 2) // 5
                pad = (bottom - top) // 7
                start, end = (top + pad, bottom - pad) if earlier else (bottom - pad, top + pad)
                scroll_evidence.append({"offset": offset, "maximum": maximum, "from_y": start, "to_y": end})
                del scroll_evidence[:-25]
                shell("input", "swipe", x, start, x, end, 350)
                swipe_count += 1
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
    args = ["am", "start", "-W", "-f", "0x30200000" if uid is not None else "0x10200000", "-a", "android.intent.action.MAIN",
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
                   "--es", "fixture", action, "--es", "language", "Ba" if sys.argv[1] == "restore-ba" else "Ru")
    assert "result=107" in result, "Fixture authentication operation did not complete"


def pickup_row(node):
    return node.get("text") in {"Моя позиция", "Минең урын", "Включи геолокацию", "Геолокацияны ҡабыҙ"}


def open_stop_picker(next_title):
    # Some Android 11 accessibility snapshots omit an icon-only merged child. The entire
    # insert row is clickable and sits halfway from pickup to the next route row (see OrderInsertStopRow).
    def open_visible_control():
        nodes = tree()
        # Prefer the actual clickable Compose parent, not its non-clickable plus-icon
        # semantics child. An icon tap did not open search on the PR 289 baseline.
        button = next((n for n in nodes if label("Заехать по пути")(n)
                       and n.get("clickable") == "true"), None)
        if button is not None:
            touch(button)
            return True
        pickup = next((n for n in nodes if pickup_row(n)), None)
        following = next((n for n in nodes if label(next_title)(n)), None)
        if pickup is None or following is None:
            return False
        left, top, right, bottom = bounds(pickup)
        _, next_top, _, next_bottom = bounds(following)
        if next_top <= bottom:
            return False
        shell("input", "tap", (left + right) // 2, (top + bottom + next_top + next_bottom) // 4)
        return True
    until(open_visible_control, "visible insert control between pickup and next stop")


def open_order_details():
    nodes = tree()
    button = next((n for n in nodes if label("Детали заказа")(n)), None)
    if button is not None:
        touch(button)
        return
    # The real sheet also accepts an upward swipe on its always-visible route header.
    pickup = find(pickup_row, "draggable pickup header")
    left, top, right, bottom = bounds(pickup)
    x, y = (left + right) // 2, (top + bottom) // 2
    shell("input", "swipe", x, y, x, max(0, y - 200), 350)


def add_stops():
    for query, title in (("QA%salpha", A), ("QA%sbravo", B)):
        open_stop_picker("QA Destination" if title == A else A)
        touch(find(lambda n: n.get("class") == "android.widget.EditText", "stop search editor"))
        shell("input", "text", query)
        click(title)


def choose_return_wait():
    # The visible title and switch are separate accessibility nodes in SettingSwitchRow.
    # Select the switch to the right of this title, in the same row, before scrolling on.
    find(label("Обратно тоже"), "round trip settings", scroll="earlier", full_row=True)
    nodes = tree()
    header = next(n for n in nodes if label("Обратно тоже")(n))
    left, top, right, bottom = bounds(header)
    switches = []
    for node in nodes:
        if node.get("checkable") == "true" and len(bounds(node)) == 4:
            x1, y1, x2, y2 = bounds(node)
            if x1 >= right and abs((y1 + y2 - top - bottom) / 2) <= 160:
                switches.append((abs(y1 + y2 - top - bottom), node))
    assert switches, "Return-trip switch not present beside its title"
    touch(min(switches, key=lambda pair: pair[0])[1])
    click("2 ч", scroll=True)


def choose_route(scheduled=True):
    click("QA Destination", scroll=True)
    add_stops()
    if scheduled:
        open_order_details()
        click("На время", scroll=True)
        for _ in range(2):
            button = find(lambda n: n.get("resource-id") == "android:id/button1", "native picker OK", native=True)
            touch(button)
            time.sleep(0.4)
        # The limited NOW pilot requires explicit consent before fetching a fare. Scheduling
        # first loads intercity terms through the normal estimate and unlocks the return switch.
        choose_return_wait()


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
    return find(lambda n: (n.get("text", "").startswith("Заказать на ") or n.get("text", "").endswith("-ға заказ итеү")), "scheduled order control").get("text")


def clean_form(place):
    find(label(place), "saved place belonging to current passenger", scroll=True)
    nodes = tree()
    assert nodes and not any(n.get("text") in (A, B) or label("Убрать остановку")(n)
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


def park_task():
    previous = pid()
    assert previous
    shell("input", "keyevent", "KEYCODE_HOME")
    def stopped_record():
        record = task_record(saved=True)
        return record if record and re.search(r'\b(?:mState|state)=STOPPED\b', record[3]) else None
    token, task, size, _ = until(stopped_record, "stopped task with saved Android Bundle")
    return previous, token, task, size


def kill_background(parked=None):
    previous, token, task, size = parked or park_task()
    current = task_record(saved=True)
    assert current and current[:3] == (token, task, size) and pid() == previous
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


def submitted_revision_persisted():
    # Inspect only the acknowledgement key on-device. No preference values leave the AVD.
    key = "taxi_draft_completed_revision"
    uuid = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    pattern = f'<string name="{key}">{uuid}</string>'
    result = subprocess.run([*ADB, "shell", "run-as", PKG, "grep", "-Eq", shlex.quote(pattern),
                             "shared_prefs/yuldash_prefs.xml"], capture_output=True, timeout=10)
    assert result.returncode in (0, 1), "Could not check the draft completion revision"
    return result.returncode == 0


def check_motion_policy(scenario, results):
    # Test the unmodified route-loss control with normal animation timings. The candidate must
    # handle reduced motion and remain responsive when it opens the real consent footer.
    value = "1" if scenario == "baseline" else "0"
    scales = {}
    for name in ("animator_duration_scale", "window_animation_scale", "transition_animation_scale"):
        shell("settings", "put", "global", name, value)
        actual = shell("settings", "get", "global", name)
        assert float(actual) == float(value), "Unexpected system animation policy"
        scales[name] = actual
    (results / (scenario + "-system-animation.json")).write_text(json.dumps(scales, indent=2))


def run(scenario, session, state, results):
    assert shell("getprop", "ro.kernel.qemu") == "1"
    assert pid() is None, "Motion policy must be set before cold login"
    check_motion_policy(scenario, results)
    # Keep input/accessibility coordinates aligned with the actual AVD framebuffer.
    shell("wm", "size", "reset")
    shell("wm", "density", "reset")
    shell("settings", "put", "system", "accelerometer_rotation", 0)
    shell("settings", "put", "system", "user_rotation", 0)
    user = json.loads(session.read_text())
    identity("primary", session)
    seeded_pid = pid()
    start(user["uid"])
    assert pid() == seeded_pid, "Initial UI must use the process that logged in"
    find(label("Моя позиция"), "initial last-known synthetic pickup")
    choose_route()
    ordered_route()
    expected_cta = submit_label()
    no_writes(state, 0)
    if scenario == "baseline":
        baseline_record = task_record()[:2]
        screenshot(results, "baseline-before-rotation")
        rotate()
        assert pid() == seeded_pid and task_record()[:2] == baseline_record
        find(label("QA Destination"), "destination on the restored route")
        assert submit_label() == expected_cta
        nodes = tree()
        assert any(label("QA Destination")(n) for n in nodes)
        assert any(n.get("text") == "Изменить" for n in nodes)
        assert not any(n.get("text") in (A, B) or label("Убрать остановку")(n) for n in nodes)
        no_writes(state, 0)
        screenshot(results, "baseline-lost-stops")
        (results / "baseline-control.json").write_text(json.dumps({
            "source": "6548bf38f66ef9902cf0ad000e51c5ada85af8d9",
            "before_stops": [A, B], "after_stops": [], "destination_retained": True,
            "scheduled_time_retained": True, "same_pid": seeded_pid, "same_task": baseline_record[1],
            "orders": 0, "schedule": 0, "quote": 0, "confirm": 0, "legacy": 0,
        }, indent=2))
        return
    if scenario.startswith("restore"):
        screenshot(results, scenario + "-before")
        rotate()
        ordered_route()
        assert pid() == seeded_pid and submit_label() == expected_cta
        no_writes(state, 0)
        screenshot(results, scenario + "-rotated")
    parked = None
    if scenario == "delayed":
        assert not submitted_revision_persisted()
        click(expected_cta)
        def server_committed():
            snapshot = json.loads(state.read_text())
            return snapshot["orders"] == 1 and snapshot["schedule"] == 1 and not snapshot["released"]
        until(server_committed, "backend committed, response still pending")
        parked = park_task()  # Android has saved the original, unacknowledged form.
        state.with_suffix(".release").touch()
        until(lambda: json.loads(state.read_text())["released"], "backend released success")
        until(submitted_revision_persisted, "stopped process acknowledged the successful order")
    if scenario.startswith("restore"):
        until(lambda: no_writes(state, 0)["draft_route_estimates"] > 0, "initial scheduled round-trip estimate")
    old_pid, record, task, bundle = kill_background(parked)
    estimates_before_resume = json.loads(state.read_text())["draft_route_estimates"]
    if scenario in ("alternate", "relogin"):
        identity("logout", session)
        until(lambda: "relogin" in json.loads(session.read_text()), "server acknowledged logout and issued a new login")
        identity(scenario, session)
    start()
    restored_pid = pid()
    assert restored_pid and restored_pid != old_pid
    restored_record = until(task_record, "resumed task")
    assert restored_record[:2] == (record, task)
    if scenario.startswith("restore"):
        ordered_route()
        assert submit_label() == expected_cta
        estimates_after_resume = until(
            lambda: (count if (count := no_writes(state, 0)["draft_route_estimates"]) > estimates_before_resume else 0),
            "fresh authenticated estimate for the restored scheduled round trip",
        )
        screenshot(results, scenario + "-restored")
        click(expected_cta)
        find(label("Новый заказ"), "scheduled receipt")
        result = no_writes(state, 1)
        assert result["order_status"] == "scheduled" and result["route"]["stops"] == [A, B]
        assert result["route"] == result["scheduled_route"] and result["route"]["km"] == 180
        assert result["route"]["points"] == [[52.64, 58.41, A], [52.68, 58.54, B]]
        assert result["route"]["minutes"] == 90 and result["route"]["fare"] > 0
        assert result["confirmed_round_trip"] is True and result["confirmed_return_wait_min"] == 120
        order_evidence = {key: result[key] for key in ("orders", "schedule", "confirm", "activate", "legacy", "route",
                                         "scheduled_route", "confirmed_round_trip", "confirmed_return_wait_min")}
        order_evidence["route_estimates_before_resume"] = estimates_before_resume
        order_evidence["route_estimates_after_resume"] = estimates_after_resume
        (results / (scenario + "-order.json")).write_text(json.dumps(order_evidence, indent=2))
        screenshot(results, scenario + "-submitted")
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
    elif scenario == "delayed":
        # Android just returned the pre-acknowledgement Bundle. Opening taxi again must not
        # rehydrate those stops or the expired submission capability.
        clean_form("QA Destination")
        result = no_writes(state, 1)
        assert result["route"]["points"] == [[52.64, 58.41, A], [52.68, 58.54, B]]
        assert result["confirmed_round_trip"] is True and result["confirmed_return_wait_min"] == 120
        screenshot(results, "draft-late-acknowledgement")
    else:
        place = "QA Alternate Place" if scenario == "alternate" else "QA Destination"
        # The navigation owner may safely return to Home on an account switch. Reopen taxi
        # through its normal authenticated entry only if the parked task left the taxi screen.
        def parked_destination():
            nodes = tree()
            assert not any(n.get("text") in (A, B) or label("Убрать остановку")(n)
                           for n in nodes), "Parked task disclosed the former route"
            if any(label(place)(n) for n in nodes):
                return "taxi"
            if any(label("Профиль")(n) for n in nodes) and not any(n.get("class") == "android.widget.EditText" for n in nodes):
                return "home"
            return None
        if until(parked_destination, "restored account's taxi form or home") == "home":
            start(json.loads(session.read_text())[scenario]["uid"])
        clean_form(place)
        no_writes(state, 0)
    (results / f"draft-{scenario}-proof.json").write_text(json.dumps({
        "scenario": scenario, "before_pid": old_pid, "after_pid": restored_pid, "same_task": task,
        "same_activity_record": True, "saved_bundle_bytes": bundle, "submitted": scenario.startswith("restore") or scenario == "delayed",
        "acknowledged_after_task_saved": scenario == "delayed",
    }, indent=2))


def save_visible_controls(folder, scenario):
    # Retain geometry and known fixture labels only; arbitrary editable text stays on-device.
    rows = []
    for node in tree():
        labels = [node.get(k) for k in ("text", "content-desc") if node.get(k) in KNOWN_UI]
        if labels or any(node.get(k) == "true" for k in ("clickable", "scrollable", "checkable")):
            rows.append({"labels": labels, "class": node.get("class"), "bounds": bounds(node),
                         "clickable": node.get("clickable"), "scrollable": node.get("scrollable")})
    (folder / f"draft-{scenario}-controls.json").write_text(json.dumps({
        "display": shell("wm", "size"), "density": shell("wm", "density"), "observer": last_observation,
        "visible": rows[:160], "recent_scroll_actions": scroll_evidence,
    }, ensure_ascii=False, indent=2))


def stack_snapshot(folder, scenario, package):
    """Only named Java stack methods, wait relationships and process metadata may leave the AVD."""
    def shell(command):
        try:
            return subprocess.run(["adb", "shell", command], capture_output=True, timeout=10).stdout.decode().strip()
        except (OSError, subprocess.TimeoutExpired):
            return ""

    identifier = shell("pidof " + package)
    live = re.fullmatch(r"[0-9]+", identifier) is not None
    prefix = "/data/anr/"
    # An ANR trace may already capture the failure earlier than our subsequent SIGQUIT.
    if live:
        shell("run-as " + package + " kill -3 " + identifier)
        time.sleep(2)
    files = shell("ls -t /data/anr/").splitlines()
    traces = []
    for name in files[:8]:
        if not re.fullmatch(r"(?:anr_[A-Za-z0-9_.-]+|traces\.txt)", name):
            continue
        # Filter in the emulator before exporting anything: never capture logcat, raw stacks,
        # Intent extras, URLs, free-form thread names or the contents of app preferences.
        cmd = ("sed -n '/^Cmd line: com\\.yuldash\\.app$/,/^----- end/p' " + prefix + name
               + " | sed -nE 's/^\x22main\x22 .*tid=([0-9]+).*/T main \\1/p; "
                 "s/^\x22[^\x22]+\x22 .*tid=([0-9]+).*/T thread \\1/p; "
                 "s/^[ ]+at ([A-Za-z0-9_.$<>/-]+)\\(.*/F \\1/p; "
                 "s/.*held by thread ([0-9]+).*/L \\1/p'")
        stack = {}
        active = main = None
        for line in shell(cmd).splitlines():
            if line.startswith("T "):
                _, kind, number = line.split()
                active = stack.setdefault(number, {"frames": [], "lock_owners": []})
                if kind == "main":
                    main = number
            elif active is not None and line.startswith("F ") and len(active["frames"]) < 44:
                active["frames"].append(line[2:])
            elif active is not None and line.startswith("L "):
                active["lock_owners"].append(line[2:])
        if main is not None:
            keep = {main, *stack[main]["lock_owners"]}
            traces.append({"trace": name, "main_tid": main, "threads": {k: stack[k] for k in keep if k in stack}})
        if len(traces) >= 3:
            break
    (Path(folder) / ("draft-" + scenario + "-main-thread.json")).write_text(json.dumps({
        "pid": int(identifier) if live else None, "traces": traces,
        "anr_window": "Application Not Responding" in shell("dumpsys window | grep -m 1 mCurrentFocus="),
        "probe_write_epoch": shell("run-as " + package + " stat -c %Y files/taxi-consent-qa/controls.json"),
    }, indent=2))


if __name__ == "__main__":
    scenario, session, state, folder = sys.argv[1:]
    try:
        run(scenario, Path(session), Path(state), Path(folder))
    except Exception:
        stack_snapshot(folder, scenario, PKG)
        screenshot(Path(folder), "draft-" + scenario + "-failure")
        try:
            save_visible_controls(Path(folder), scenario)
        except (AssertionError, subprocess.SubprocessError):
            pass  # Keep the original cause and the earlier thread state.
        raise
