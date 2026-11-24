import json
import unittest

from fixture import A, B, ID, NEW, PATH, QUERY, ROUTE, WAIT, Scene


def call(scene, role, method="GET", path=PATH, command=None):
    return scene.handle(method, path, "Bearer " + scene.sessions[role],
                        b"" if command is None else json.dumps(command).encode())


START = {"action": "start_planned", "route_revision": ROUTE, "wait_revision": None, "waypoint_id": B}
EDIT = {"route_revision": ROUTE, "wait_revision": WAIT,
        "waypoints": [{"kind": "new", **NEW}, {"kind": "keep", "waypoint_id": B}]}
END = {"action": "resume", "wait_id": WAIT}


class JointFixtureTest(unittest.TestCase):
    def test_exact_joint_order_requires_both_role_reads_after_single_drain(self):
        scene = Scene()
        c, card = call(scene, "driver")
        self.assertEqual(c, 200)
        self.assertEqual(card["id"], ID)
        self.assertEqual(card["stops"][0] | {"waypoint_id": B}, card["stops"][1])
        self.assertEqual(call(scene, "driver", "POST", PATH + "/stop/v1", START)[0], 200)
        driver, passenger = (call(scene, role)[1] for role in ("driver", "passenger"))
        self.assertFalse(driver["stop_control"]["admission_enabled"])
        self.assertEqual(driver["stop_control"], passenger["stop_control"])
        self.assertEqual(call(scene, "passenger", path="/geocode?q=" + QUERY)[0], 200)
        status, edited = call(scene, "passenger", "POST", PATH + "/waypoints/v1", EDIT)
        self.assertEqual(status, 200)
        self.assertEqual(passenger["stop_control"]["active_wait"], edited["order"]["stop_control"]["active_wait"])
        self.assertEqual(edited["order"]["stops"][1], driver["stops"][1])
        self.assertEqual(call(scene, "driver", "POST", PATH + "/stop/v1", END)[0], 200)
        self.assertEqual(scene.verdict, "pending")
        self.assertNotIn("stop_control", call(scene, "driver")[1])
        self.assertEqual(scene.verdict, "pending")
        self.assertNotIn("stop_control", call(scene, "passenger")[1])
        self.assertEqual(scene.verdict, "passed")
        self.assertEqual(call(scene, "driver", "POST", PATH + "/stop/v1", END)[0], 409)
        self.assertEqual(scene.verdict, "failed")

    def test_wait_cannot_be_mutated_by_wrong_role_or_legacy_or_without_owner_reads(self):
        for role, path, operation in (
            ("driver", PATH + "/stop", START),
            ("passenger", PATH + "/stop/v1", START),
            ("driver", PATH + "/stop/v1", START | {"waypoint_id": A}),
            ("passenger", PATH + "/waypoints/v1", EDIT),
        ):
            with self.subTest(role=role, path=path):
                scene = Scene()
                if operation is EDIT:
                    self.assertEqual(call(scene, "driver", "POST", PATH + "/stop/v1", START)[0], 200)
                    self.assertEqual(call(scene, "passenger")[0], 200)
                before = scene.phase
                self.assertEqual(call(scene, role, "POST", path, operation)[0], 409)
                self.assertEqual(scene.phase, before)
                self.assertEqual(scene.verdict, "failed")


if __name__ == "__main__":
    unittest.main()
