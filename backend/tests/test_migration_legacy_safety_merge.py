"""Истории R/S сходятся без изменения прежних revision ID, родителей и данных.

Это сторож графа; реальные исторические схемы проверяются отдельной матрицей двух СУБД.
"""
import ast
from pathlib import Path

from alembic.config import Config
from alembic.script import ScriptDirectory


BACKEND = Path(__file__).resolve().parents[1]
MERGE = "cf_merge_legacy_safety_20261102"


def test_legacy_and_segment_meeting_histories_keep_exact_parents():
    cfg = Config(str(BACKEND / "alembic.ini"))
    cfg.set_main_option("script_location", str(BACKEND / "alembic"))
    scripts = ScriptDirectory.from_config(cfg)
    heads = scripts.get_heads()
    assert len(heads) == 1
    assert MERGE in {revision.revision for revision in scripts.iterate_revisions(heads[0], "base")}
    parents = {
        "cc_booking_decline_reason_20261024": "cb_sos_retry_intent_20261021",
        "cd_boarding_undo_consent_20261024": "cc_booking_decline_reason_20261024",
        "cc_ride_segment_seats_20261031": "cb_sos_retry_intent_20261021",
        "cd_ride_stop_prices_20261031": "cc_ride_segment_seats_20261031",
        "ce_booking_meeting_points_20261101": "cd_ride_stop_prices_20261031",
        MERGE: ("ce_booking_meeting_points_20261101", "cd_boarding_undo_consent_20261024"),
    }
    for revision, parent in parents.items():
        assert scripts.get_revision(revision).down_revision == parent


def test_merge_performs_no_schema_or_data_operations():
    path = BACKEND / "alembic" / "versions" / f"{MERGE}.py"
    tree = ast.parse(path.read_text(encoding="utf-8"))
    functions = {node.name: node for node in tree.body if isinstance(node, ast.FunctionDef)}
    assert set(functions) == {"upgrade", "downgrade"}
    for function in functions.values():
        assert len(function.body) == 1 and isinstance(function.body[0], ast.Pass)
