"""No browser, network, third-party dependencies, backend import or process launch."""
import os
import unittest
from pathlib import Path
from unittest.mock import patch

import launch
from guards import network_table_is_local, sandbox_verified, worker_environment

YES = "You are adequately sandboxed."
ROWS = [["PID namespaces", "Yes"], ["Network namespaces", "Yes"], ["Seccomp-BPF sandbox", "Yes"]]


class GuardTests(unittest.TestCase):
    def test_chromium_requires_explicit_positive_unique_full_rows(self):
        self.assertTrue(sandbox_verified("Sandbox Status\n" + YES, ROWS))
        for body, rows in (
            ("You are not adequately sandboxed.", ROWS),
            (YES + "\nYou are not adequately sandboxed.", ROWS),
            ("adequately sandboxed", ROWS),
            (YES, ROWS[:-1]),
            (YES, ROWS + [ROWS[0]]),
            (YES, ROWS[:2] + [["Seccomp-BPF sandbox", "No"]]),
        ):
            self.assertFalse(sandbox_verified(body, rows))

    def test_network_tables_reject_nonloopback_even_with_no_default_route(self):
        interfaces = "h\nh\n lo: 0"
        ipv4 = "Iface\nlo 00000000"
        ipv6 = "0000 lo"
        self.assertTrue(network_table_is_local(interfaces, ipv4, ipv6))
        self.assertFalse(network_table_is_local(interfaces + "\n eth0: 0", ipv4, ipv6))
        self.assertFalse(network_table_is_local(interfaces, ipv4 + "\neth0 01000000", ipv6))
        self.assertFalse(network_table_is_local(interfaces, ipv4, ipv6 + "\n0001 eth0"))
        self.assertFalse(network_table_is_local("h\nh\n", ipv4, ipv6))

    def test_production_db_proxy_and_api_credentials_cannot_enter_worker_env(self):
        with patch.dict(os.environ, {"DATABASE_URL": "postgresql://never-use", "HTTP_PROXY": "never-use",
                                    "JWT_SECRET": "never-use", "VITE_API_BASE": "never-use"}, clear=True):
            env = worker_environment(Path("/synthetic"), Path("/backend"), Path("/tools"), Path("/chromium"))
        assert env["DATABASE_URL"] == "sqlite:////synthetic/guard.sqlite3"
        assert env["TMPDIR"] == "/synthetic" and env["ENV"] == "dev"
        assert not ({"HTTP_PROXY", "JWT_SECRET", "VITE_API_BASE"} & env.keys())

    def test_preflight_failure_never_spawns_pytest(self):
        with patch.object(launch, "require_isolation", side_effect=RuntimeError("fixture isolated preflight failed")), \
             patch.object(launch.subprocess, "run") as spawn:
            with self.assertRaises(RuntimeError):
                launch.run()
            spawn.assert_not_called()


if __name__ == "__main__":
    unittest.main()
