"""Offline safeguards for TF10 host observations; not emulator acceptance."""
from collections import deque
import itertools
import json
import subprocess
import unittest
from unittest.mock import patch

import draft_task as task


def rectangle(label, bounds="[1,1][100,101]", *, widget="android.view.View", click=False):
    return {"text": label, "class": widget, "bounds": bounds, "enabled": "true",
            "clickable": str(click).lower()}


PICKUP = rectangle("Моя позиция")
DESTINATION = rectangle("QA Destination")
PLUS = rectangle("Заехать по пути", "[8,8][30,30]")
INSERT = rectangle("Заехать по пути", "[1,20][100,55]", click=True)
MOVED = {**INSERT, "bounds": "[1,15][100,50]"}
ROUTE = [PICKUP, DESTINATION, PLUS, INSERT]
EDIT = rectangle("", widget="android.widget.EditText")
EDITOR = [rectangle("Куда заехать?"), EDIT]
BA_EDITOR = [rectangle("Ҡайҙа инеп сығырға?"), EDIT]


class StopEditorTests(unittest.TestCase):
    def setUp(self):
        self.enterContext(patch.object(task, "language", "Ru", create=True))
        self.enterContext(patch.object(task.time, "sleep"))

    def simulate(self, frames, ticks=()):
        """Repeat the last frame; each sample is read from the production selection code."""
        tail = deque(frames)
        source = lambda: tail.popleft() if len(tail) > 1 else tail[0]
        clock = itertools.chain(ticks, itertools.count(8, 0.5))
        self.enterContext(patch.object(task.time, "monotonic", side_effect=lambda: next(clock)))
        self.enterContext(patch.object(task, "tree", side_effect=source))
        return self.enterContext(patch.object(task, "touch"))

    def test_stable_clickable_row_then_real_stop_editor(self):
        tap = self.simulate([[PICKUP, DESTINATION, PLUS, MOVED], ROUTE, ROUTE, EDITOR],
                            ticks=(0, 0.1, 0.2, 0.3, 1, 1.1))
        self.assertEqual(task.open_stop_picker("QA Destination"), EDIT)
        tap.assert_called_once_with(INSERT)

    def test_recovered_geometry_after_unfocused_frame_never_retries(self):
        tap = self.simulate([ROUTE, ROUTE, ROUTE, [], ROUTE, ROUTE, EDITOR],
                            ticks=(0, 0.1, 0.2, 1, 1.1, 6, 6.1, 6.2, 6.3, 6.4, 7, 7.1))
        self.assertEqual(task.open_stop_picker("QA Destination"), EDIT)
        tap.assert_called_once_with(INSERT)

    def test_unchanged_route_without_editor_fails_after_one_tap(self):
        tap = self.simulate([ROUTE, ROUTE, ROUTE], ticks=(0, 0.1, 0.2, 1, 1.1))
        with self.assertRaisesRegex(AssertionError, "stop search editor"):
            task.open_stop_picker("QA Destination")
        tap.assert_called_once_with(INSERT)

    def test_transient_unobserved_frame_disallows_retry(self):
        tap = self.simulate([ROUTE, ROUTE, [], ROUTE, EDITOR],
                            ticks=(0, 0.1, 0.2, 1, 1.1, 6, 6.1, 6.2, 6.3))
        self.assertEqual(task.open_stop_picker("QA Destination"), EDIT)
        tap.assert_called_once_with(INSERT)

    def test_moved_row_disallows_second_tap_even_after_stabilization(self):
        moved_route = [PICKUP, DESTINATION, PLUS, MOVED]
        tap = self.simulate([ROUTE, ROUTE, ROUTE, ROUTE, moved_route, moved_route, EDITOR],
                            ticks=(0, 0.1, 0.2, 1, 1.1, 1.2, 6, 6.1, 6.2, 6.3, 7, 7.1))
        self.assertEqual(task.open_stop_picker("QA Destination"), EDIT)
        tap.assert_called_once_with(INSERT)

    def test_editor_needs_localized_stop_prompt_not_any_text_input(self):
        self.assertIsNone(task.stop_editor([EDIT]))
        self.assertIsNone(task.stop_editor(BA_EDITOR))
        with patch.object(task, "language", "Ba"):
            self.assertEqual(task.stop_editor(BA_EDITOR), EDIT)
            self.assertIsNone(task.stop_editor(EDITOR))

    def test_icon_alone_or_wrong_route_never_tapped(self):
        self.assertIsNone(task.insert_target([PICKUP, DESTINATION, PLUS], "QA Destination"))
        self.assertIsNone(task.insert_target(ROUTE, "QA Stop A"))


class RotationTests(unittest.TestCase):
    def test_observation_requires_fresh_successful_focused_snapshot(self):
        frame = json.dumps({"observation": "focused-compose", "activity-generation": 3})
        with patch.object(task, "rotation_query", side_effect=["42", task.PKG, "result=107", frame, "42"]):
            self.assertEqual(task.rotation_generation(42), 3)
        for payload in ({"observation": "no-root", "activity-generation": 3},
                        {"observation": "focused-compose", "activity-generation": True}):
            with patch.object(task, "rotation_query",
                              side_effect=["42", task.PKG, "result=107", json.dumps(payload), "42"]):
                self.assertIsNone(task.rotation_generation(42))
        with patch.object(task, "rotation_query", side_effect=["42", task.PKG, "result=107", frame, "99"]):
            with self.assertRaisesRegex(AssertionError, "replaced the app process"):
                task.rotation_generation(42)

    def test_adb_timeout_is_failure_with_stage_not_success(self):
        with patch.object(task, "adb", side_effect=subprocess.TimeoutExpired(["adb"], 6)) as adb:
            with self.assertRaisesRegex(AssertionError, "ADB rotation probe unavailable: display"):
                task.rotation_query("display", "shell", "dumpsys", "input")
        adb.assert_called_once_with("shell", "dumpsys", "input", timeout=6, check=True)

    def test_orientation_alone_is_not_recreation(self):
        display = ["SurfaceOrientation: 0", "", "SurfaceOrientation: 1"]
        stages = []

        def wait(probe, description):
            stages.append(description)
            value = probe()
            if not value:
                raise AssertionError(description)
            return value

        with patch.object(task, "pid", return_value=42), patch.object(task, "task_record", return_value=("same", 4)), \
                patch.object(task, "rotation_generation", side_effect=[1, 1]), \
                patch.object(task, "rotation_query", side_effect=display), patch.object(task, "until", side_effect=wait):
            with self.assertRaisesRegex(AssertionError, "different focused MainActivity"):
                task.rotate()
        self.assertEqual(stages[-1], "different focused MainActivity instance after rotation")

    def test_observed_instances_in_both_orientations_and_same_task(self):
        with patch.object(task, "pid", return_value=42), patch.object(task, "task_record", return_value=("same", 4)), \
                patch.object(task, "rotation_generation", side_effect=[1, 2, 3]), \
                patch.object(task, "rotation_query", side_effect=["SurfaceOrientation: 0", "", "SurfaceOrientation: 1", "", "SurfaceOrientation: 0"]):
            task.rotate()

        with patch.object(task, "pid", return_value=42), \
                patch.object(task, "task_record", side_effect=[("same", 4), ("other", 5)]), \
                patch.object(task, "rotation_generation", side_effect=[1, 2]), \
                patch.object(task, "rotation_query", side_effect=["SurfaceOrientation: 0", "", "SurfaceOrientation: 1"]):
            with self.assertRaisesRegex(AssertionError, "replaced the original Android task"):
                task.rotate()


class ProtectedNowWitnessTests(unittest.TestCase):
    def setUp(self):
        self.enterContext(patch.object(task, "language", "Ba", create=True))

    def test_routes_are_attributed_by_distinct_label_and_adjacent_edit(self):
        old_chip = rectangle(task.SCHEDULED_DESTINATION, "[10,10][100,30]", widget="android.widget.TextView")
        new_route = rectangle(task.NOW_DESTINATION, "[10,220][100,260]", widget="android.widget.TextView")
        edit = rectangle("Үҙгәртеү", "[140,210][220,270]", widget="android.widget.Button", click=True)
        price = rectangle("Хаҡты иҫәпләү", "[100,300][240,360]", click=True)
        with patch.object(task, "tree", return_value=[old_chip, new_route, edit, price]), \
                patch.object(task, "last_observation", "focused-compose"):
            self.assertEqual(task.protected_now_witness(), {
                "scheduled_destination_selected": False, "now_destination_selected": True,
                "protected_now_cta": True, "editor": False, "stops": False,
            })
        self.assertFalse(task.selected_destination([new_route, {**edit, "clickable": "false"}],
                                                   task.NOW_DESTINATION))

    def test_nonfocused_observer_cannot_claim_selected_or_clean(self):
        with patch.object(task, "tree", return_value=[EDIT]), \
                patch.object(task, "last_observation", "app-not-foreground"):
            self.assertIsNone(task.protected_now_witness())

    def test_fresh_bundle_requires_explicit_resume_empty_state(self):
        self.assertTrue(task.fresh_resumed_record(("token", 9, 0, "mHaveState=false mIcicle=null\nstate=RESUMED")))
        for size, text in (
            (0, "state=RESUMED"),
            (5, "mHaveState=true mIcicle=Bundle[mParcelledData.dataSize=5]\nstate=RESUMED"),
            (0, "mHaveState=false mIcicle=null\nstate=STOPPED"),
        ):
            self.assertFalse(task.fresh_resumed_record(("token", 9, size, text)))

    def test_park_rejects_changed_pid_token_task_or_missing_bundle(self):
        fresh = ("activity", 9, 0, "mHaveState=false mIcicle=null\nstate=RESUMED")
        for parked in ((42, "activity", 9, 0), (43, "activity", 9, 12),
                       (42, "other", 9, 12), (42, "activity", 10, 12)):
            with self.subTest(parked=parked), patch.object(task, "task_record", return_value=fresh), \
                    patch.object(task, "pid", return_value=42), patch.object(task, "park_task", return_value=parked):
                with self.assertRaisesRegex(AssertionError, "fresh Bundle"):
                    task.witness_park()
        with patch.object(task, "task_record", return_value=fresh), patch.object(task, "pid", return_value=42), \
                patch.object(task, "park_task", return_value=(42, "activity", 9, 12)):
            self.assertEqual(task.witness_park(), (42, "activity", 9, 12))

    def test_stale_before_home_does_not_dispatch_home(self):
        stale = ("activity", 9, 93, "mHaveState=true mIcicle=Bundle[mParcelledData.dataSize=93]\nstate=RESUMED")
        def check(probe, _):
            if probe() is None:
                raise AssertionError("before Home has no fresh evidence")
        with patch.object(task, "task_record", return_value=stale), \
                patch.object(task, "pid", return_value=42), patch.object(task, "until", side_effect=check), \
                patch.object(task, "park_task") as park:
            with self.assertRaisesRegex(AssertionError, "no fresh evidence"):
                task.witness_park()
        park.assert_not_called()


    def test_changed_or_lost_pid_cannot_supply_a_fresh_resumed_snapshot(self):
        fresh = ("activity", 9, 0, "mHaveState=false mIcicle=null\nstate=RESUMED")
        for observed in ((42, 99), (42, None), (42, 42, 99), (42, 42, None)):
            with self.subTest(observed=observed), patch.object(task, "pid", side_effect=observed), \
                    patch.object(task, "task_record", return_value=fresh) as read_record, \
                    patch.object(task, "park_task") as park:
                with self.assertRaisesRegex(AssertionError, "Live process changed"):
                    task.witness_park()
                self.assertEqual(read_record.call_count, 1 if len(observed) == 3 else 0)
                park.assert_not_called()


if __name__ == "__main__":
    unittest.main()
