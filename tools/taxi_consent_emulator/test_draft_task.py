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


if __name__ == "__main__":
    unittest.main()
