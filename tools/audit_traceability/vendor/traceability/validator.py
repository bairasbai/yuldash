"""Fail-closed graph, artifact-integrity and strict acceptance policy checks.

This validates declarations and bytes, not the truth of an execution or the
identity/independence of the human behind an ID. See README before real use.
"""

import hashlib
from collections import Counter
from datetime import datetime
from pathlib import Path

from . import __version__
from .schema_check import validate_structure


GROUPS = (
    "people", "artifacts", "evidence", "features", "requirements",
    "implementations", "tests", "acceptances", "skip_decisions",
)
LIMITATIONS = [
    "JSON declarations and SHA256 do not prove that a test ran, passed, or used the declared commit.",
    "Review real execution artifacts, target checkout/build provenance, environment, and failures independently.",
    "Person IDs and roles do not authenticate humans or prove real-world reviewer independence.",
    "Commits are compared as full identifiers; this offline tool does not verify Git history or build provenance.",
    "Static/public evidence does not prove runtime behavior. Inferred/unknown evidence cannot satisfy observed support.",
]


def validate(document, artifact_root, *, baseline=None):
    """Return a JSON-serializable result. Never execute commands or fetch URLs."""
    blockers = validate_structure(document)
    result = {
        "tool_version": __version__, "schema_version": "1.0", "valid": False,
        "gate": "strict_acceptance", "gate_passed": False,
        "computed_state": "invalid", "implemented": False, "accepted": False,
        "blockers": blockers, "requirements": [], "features": [], "skips": [],
        "limitations": list(LIMITATIONS),
    }
    if blockers:
        return result

    root = Path(artifact_root).resolve()
    target = document["audit"]["target_commit"]
    result.update({"audit_id": document["audit"]["id"], "scope": document["audit"]["scope"],
                   "target_commit": target})
    if document["audit"]["scope"] == "demo":
        result["limitations"].insert(0, "SYNTHETIC DEMO ONLY: no finding about Yuldash or any real APK audit.")

    def issue(code, path, message, entity_id=None):
        item = {"code": code, "path": path, "message": message}
        if entity_id:
            item["entity_id"] = entity_id
        blockers.append(item)

    indexes = {group: {} for group in GROUPS}
    seen = {document["audit"]["id"]}
    duplicates = set()
    for group in GROUPS:
        for i, row in enumerate(document[group]):
            ident = row["id"]
            if ident in seen:
                duplicates.add(ident)
                issue("DUPLICATE_ID", f"/{group}/{i}/id", f"ID {ident} is not globally unique", ident)
            seen.add(ident)
            indexes[group].setdefault(ident, row)

    if baseline is not None:
        _check_baseline(document, baseline, issue)

    def references(row, field, group, path):
        values = row[field]
        if isinstance(values, str):
            values = [values]
        okay = True
        for ref in values:
            if ref not in indexes[group]:
                issue("DANGLING_REFERENCE", path + "/" + field,
                      f"{ref} is not an ID in {group}", row.get("id"))
                okay = False
            elif ref in duplicates:
                okay = False
        return okay

    ref_fields = {
        "audit": {"author_ids": "people"},
        "evidence": {"artifact_ids": "artifacts"},
        "features": {"requirement_ids": "requirements"},
        "requirements": {"feature_id": "features", "evidence_ids": "evidence",
                         "implementation_ids": "implementations", "test_ids": "tests",
                         "acceptance_ids": "acceptances", "skip_decision_id": "skip_decisions"},
        "implementations": {"requirement_ids": "requirements", "author_ids": "people", "artifact_ids": "artifacts"},
        "tests": {"requirement_ids": "requirements", "implementation_ids": "implementations",
                  "executor_id": "people", "artifact_ids": "artifacts"},
        "acceptances": {"requirement_ids": "requirements", "implementation_ids": "implementations",
                        "test_ids": "tests", "reviewer_id": "people",
                        "reviewed_artifact_ids": "artifacts", "artifact_ids": "artifacts"},
        "skip_decisions": {"requirement_ids": "requirements", "decider_id": "people", "artifact_ids": "artifacts"},
    }
    bad_refs = set(duplicates)
    for group, fields in ref_fields.items():
        rows = [document["audit"]] if group == "audit" else document[group]
        for i, row in enumerate(rows):
            path = "/audit" if group == "audit" else f"/{group}/{i}"
            for field, destination in fields.items():
                if field in row and not references(row, field, destination, path):
                    bad_refs.add(row["id"])

    # Require the two ends of each link to agree. No forward-only coverage claims.
    for i, feature in enumerate(document["features"]):
        for rid in feature["requirement_ids"]:
            req = indexes["requirements"].get(rid)
            if req and req["feature_id"] != feature["id"]:
                issue("LINK_MISMATCH", f"/features/{i}/requirement_ids", "Feature/requirement link disagrees", feature["id"])
                bad_refs.update((feature["id"], rid))
    for i, req in enumerate(document["requirements"]):
        feature = indexes["features"].get(req["feature_id"])
        if feature and req["id"] not in feature["requirement_ids"]:
            issue("LINK_MISMATCH", f"/requirements/{i}/feature_id", "Feature does not list this requirement", req["id"])
            bad_refs.add(req["id"])
    for group, field in (("implementations", "implementation_ids"), ("tests", "test_ids"), ("acceptances", "acceptance_ids")):
        for i, row in enumerate(document[group]):
            for rid in row["requirement_ids"]:
                req = indexes["requirements"].get(rid)
                if req and row["id"] not in req[field]:
                    issue("LINK_MISMATCH", f"/{group}/{i}/requirement_ids", f"Requirement {rid} does not list {row['id']}", row["id"])
                    bad_refs.update((row["id"], rid))
        for i, req in enumerate(document["requirements"]):
            for ident in req[field]:
                row = indexes[group].get(ident)
                if row and req["id"] not in row["requirement_ids"]:
                    issue("LINK_MISMATCH", f"/requirements/{i}/{field}", f"{ident} does not list this requirement", req["id"])
                    bad_refs.update((ident, req["id"]))

    good_artifacts = set()
    for i, row in enumerate(document["artifacts"]):
        path = f"/artifacts/{i}"
        try:
            raw_path = row["path"]
            if (raw_path.startswith("/") or "\\" in raw_path or ":" in raw_path
                    or any(part in ("", ".", "..") for part in raw_path.split("/"))):
                raise ValueError("Artifact must have a normalized relative POSIX path without traversal")
            artifact = (root / raw_path).resolve()
            if not artifact.is_relative_to(root):
                raise ValueError("Artifact resolves outside the manifest directory")
            if not artifact.is_file():
                issue("ARTIFACT_MISSING", path + "/path", f"Regular file not found: {raw_path}", row["id"])
                continue
            digest = hashlib.sha256()
            with artifact.open("rb") as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(chunk)
            if digest.hexdigest() != row["sha256"]:
                issue("HASH_MISMATCH", path + "/sha256", f"SHA256 mismatch for {raw_path}", row["id"])
            elif row["id"] not in duplicates:
                good_artifacts.add(row["id"])
        except ValueError as exc:
            issue("ARTIFACT_PATH", path + "/path", str(exc), row["id"])
        except (OSError, RuntimeError) as exc:
            issue("ARTIFACT_IO", path + "/path", f"Cannot read {row['path']}: {exc}", row["id"])

    def artifacts_ok(row, field, allowed_kinds, path):
        okay = True
        for ident in row[field]:
            artifact = indexes["artifacts"].get(ident)
            if ident not in good_artifacts:
                okay = False
            if artifact and artifact["kind"] not in allowed_kinds:
                issue("ARTIFACT_KIND", path + "/" + field,
                      f"{ident} must have kind in {sorted(allowed_kinds)}", row["id"])
                okay = False
        return okay

    def has_role(ident, role, path, entity):
        person = indexes["people"].get(ident)
        if not person or ident in duplicates:
            return False
        if role not in person["roles"]:
            issue("PERSON_ROLE", path, f"{ident} lacks role {role}", entity)
            return False
        return True

    for author in document["audit"]["author_ids"]:
        has_role(author, "audit_author", "/audit/author_ids", document["audit"]["id"])

    observed_sources = set()
    for i, row in enumerate(document["evidence"]):
        kinds = {"source", "execution"} if row["kind"] == "runtime" else {"source"}
        okay = artifacts_ok(row, "artifact_ids", kinds, f"/evidence/{i}")
        if okay and row["certainty"] == "observed" and row["id"] not in bad_refs:
            observed_sources.add(row["id"])

    good_impl = set()
    for i, row in enumerate(document["implementations"]):
        path = f"/implementations/{i}"
        start = len(blockers)
        if row["state"] != "implemented":
            issue("IMPLEMENTATION_NOT_IMPLEMENTED", path + "/state", "Proposed/unknown is not implemented", row["id"])
        if row["commit"] != target:
            issue("COMMIT_MISMATCH", path + "/commit", "Implementation commit differs from audit target", row["id"])
        okay = artifacts_ok(row, "artifact_ids", {"implementation"}, path)
        for author in row["author_ids"]:
            okay = has_role(author, "implementation", path + "/author_ids", row["id"]) and okay
        if len(blockers) == start and okay and row["id"] not in bad_refs:
            good_impl.add(row["id"])

    def timestamp(value, path, entity):
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
            if parsed.tzinfo is None:
                raise ValueError("Timezone required")
            return parsed
        except ValueError:
            issue("TIMESTAMP", path, "Expected a real RFC3339 timestamp with timezone", entity)
            return None

    good_tests = set()
    test_finished = {}
    for i, row in enumerate(document["tests"]):
        path = f"/tests/{i}"
        start = len(blockers)
        if row["state"] != "executed":
            issue("TEST_NOT_EXECUTED", path + "/state", "Only an executed test can satisfy the gate", row["id"])
        if row["result"] != "passed":
            issue("TEST_NOT_PASSED", path + "/result", "Failed/unknown test cannot satisfy the gate", row["id"])
        if row["certainty"] != "observed":
            issue("TEST_NOT_OBSERVED", path + "/certainty", "Execution must be recorded as observed", row["id"])
        if row["target_commit"] != target:
            issue("COMMIT_MISMATCH", path + "/target_commit", "Test did not target the audit commit", row["id"])
        if not set(row["implementation_ids"]).issubset(good_impl):
            issue("TEST_IMPLEMENTATION_INVALID", path + "/implementation_ids", "Test references an invalid/unimplemented change", row["id"])
        for rid in row["requirement_ids"]:
            if not any(rid in indexes["implementations"].get(ident, {}).get("requirement_ids", []) for ident in row["implementation_ids"]):
                issue("TEST_IMPLEMENTATION_UNRELATED", path + "/implementation_ids", f"No listed implementation covers {rid}", row["id"])
        for ident in row["implementation_ids"]:
            impl = indexes["implementations"].get(ident)
            if impl and not set(impl["requirement_ids"]) & set(row["requirement_ids"]):
                issue("TEST_IMPLEMENTATION_UNRELATED", path + "/implementation_ids", f"{ident} covers none of the test requirements", row["id"])
        began = timestamp(row["started_at"], path + "/started_at", row["id"])
        ended = timestamp(row["finished_at"], path + "/finished_at", row["id"])
        if began and ended and ended < began:
            issue("TIMESTAMP_ORDER", path, "Test finished before it started", row["id"])
        if ended:
            test_finished[row["id"]] = ended
        okay = artifacts_ok(row, "artifact_ids", {"execution"}, path)
        okay = has_role(row["executor_id"], "test", path + "/executor_id", row["id"]) and okay
        if len(blockers) == start and okay and row["id"] not in bad_refs:
            good_tests.add(row["id"])

    # Deliberately conservative: acceptance/skip decision makers must be separate
    # from EVERY declared audit author, implementer and test executor in this audit.
    authors_and_executors = set(document["audit"]["author_ids"])
    for impl in document["implementations"]:
        authors_and_executors.update(impl["author_ids"])
    authors_and_executors.update(row["executor_id"] for row in document["tests"])

    good_acceptances = set()
    for i, row in enumerate(document["acceptances"]):
        path = f"/acceptances/{i}"
        start = len(blockers)
        if row["decision"] != "accepted":
            issue("ACCEPTANCE_NOT_ACCEPTED", path + "/decision", "Pending/rejected is not acceptance", row["id"])
        if row["certainty"] != "observed":
            issue("ACCEPTANCE_NOT_OBSERVED", path + "/certainty", "Acceptance must be an observed review", row["id"])
        if row["target_commit"] != target:
            issue("COMMIT_MISMATCH", path + "/target_commit", "Acceptance targets a different commit", row["id"])
        if row["reviewer_id"] in authors_and_executors:
            issue("REVIEWER_NOT_INDEPENDENT", path + "/reviewer_id", "Reviewer is an audit author, implementer or test executor", row["id"])
        if not set(row["implementation_ids"]).issubset(good_impl) or not set(row["test_ids"]).issubset(good_tests):
            issue("ACCEPTANCE_CHAIN_INVALID", path, "Acceptance references an invalid implementation or test", row["id"])
        reviewed = timestamp(row["reviewed_at"], path + "/reviewed_at", row["id"])
        for tid in row["test_ids"]:
            if reviewed and tid in test_finished and reviewed < test_finished[tid]:
                issue("ACCEPTANCE_BEFORE_EXECUTION", path + "/reviewed_at", f"Review predates completion of {tid}", row["id"])
        required_artifacts = set()
        for rid in row["requirement_ids"]:
            req = indexes["requirements"].get(rid)
            if req:
                if not set(req["implementation_ids"]).issubset(row["implementation_ids"]) or not set(req["test_ids"]).issubset(row["test_ids"]):
                    issue("ACCEPTANCE_COVERAGE", path, f"Review must cover the whole implementation/test chain for {rid}", row["id"])
                for eid in req["evidence_ids"]:
                    required_artifacts.update(indexes["evidence"].get(eid, {}).get("artifact_ids", []))
        for group, field in (("implementations", "implementation_ids"), ("tests", "test_ids")):
            for ident in row[field]:
                related = indexes[group].get(ident)
                if related:
                    if not set(related["requirement_ids"]) & set(row["requirement_ids"]):
                        issue("ACCEPTANCE_UNRELATED", path + "/" + field, f"{ident} covers none of the reviewed requirements", row["id"])
                    required_artifacts.update(related["artifact_ids"])
        if not required_artifacts.issubset(row["reviewed_artifact_ids"]):
            issue("EXECUTION_REVIEW_INCOMPLETE", path + "/reviewed_artifact_ids",
                  f"Reviewer must inspect source, change and execution artifacts: missing {sorted(required_artifacts - set(row['reviewed_artifact_ids']))}", row["id"])
        okay = artifacts_ok(row, "artifact_ids", {"acceptance"}, path)
        okay = artifacts_ok(row, "reviewed_artifact_ids", {"source", "implementation", "execution"}, path) and okay
        okay = has_role(row["reviewer_id"], "acceptance", path + "/reviewer_id", row["id"]) and okay
        if len(blockers) == start and okay and row["id"] not in bad_refs:
            good_acceptances.add(row["id"])

    good_skips = set()
    for i, row in enumerate(document["skip_decisions"]):
        path = f"/skip_decisions/{i}"
        start = len(blockers)
        if row["decision"] != "approved":
            issue("SKIP_NOT_APPROVED", path + "/decision", "Skip must have an explicit approved decision", row["id"])
        if row["decider_id"] in authors_and_executors:
            issue("SKIP_DECIDER_NOT_INDEPENDENT", path + "/decider_id", "Skip decision maker is not independent", row["id"])
        for rid in row["requirement_ids"]:
            req = indexes["requirements"].get(rid)
            if req and (req["disposition"] != "skipped" or req.get("skip_decision_id") != row["id"]):
                issue("SKIP_LINK_MISMATCH", path + "/requirement_ids", f"{rid} does not declare this skip", row["id"])
        timestamp(row["decided_at"], path + "/decided_at", row["id"])
        okay = artifacts_ok(row, "artifact_ids", {"decision"}, path)
        okay = has_role(row["decider_id"], "acceptance", path + "/decider_id", row["id"]) and okay
        if len(blockers) == start and okay and row["id"] not in bad_refs:
            good_skips.add(row["id"])

    for i, req in enumerate(document["requirements"]):
        rid, path = req["id"], f"/requirements/{i}"
        source_ids = set(req["evidence_ids"]) & observed_sources
        if not req["evidence_ids"]:
            issue("SOURCE_MISSING", path + "/evidence_ids", "Requirement needs local source evidence", rid)
        elif not source_ids:
            issue("SOURCE_NOT_OBSERVED", path + "/evidence_ids", "Inferred/unknown or invalid sources cannot satisfy observed support", rid)
        mandatory = req["level"] == "must" or req["priority"] == "P0"
        skip_approved = False
        if req["disposition"] == "skipped":
            sid = req.get("skip_decision_id")
            decision = indexes["skip_decisions"].get(sid)
            skip_approved = sid in good_skips and decision is not None and rid in decision["requirement_ids"]
            if not skip_approved:
                issue("SKIP_DECISION_MISSING", path, "Skip requires a linked approved independent decision, reason and artifact", rid)
            if mandatory:
                issue("SKIPPED_BLOCKER", path, "A must or P0 remains blocking even with an approved skip", rid)
            result["skips"].append({"requirement_id": rid, "mandatory": mandatory,
                                   "approved": skip_approved, "decision_id": sid,
                                   "reason": decision["reason"] if decision else None,
                                   "blocks_gate": mandatory or not skip_approved})
        elif "skip_decision_id" in req:
            issue("SKIP_ON_ACTIVE_REQUIREMENT", path + "/skip_decision_id", "Active requirement cannot carry a skip decision", rid)

        impl_ok = bool(req["implementation_ids"]) and set(req["implementation_ids"]).issubset(good_impl)
        test_ok = bool(req["test_ids"]) and set(req["test_ids"]).issubset(good_tests)
        tested_impl = set()
        for tid in req["test_ids"]:
            if tid in good_tests:
                tested_impl.update(indexes["tests"][tid]["implementation_ids"])
        test_ok = test_ok and set(req["implementation_ids"]).issubset(tested_impl)
        accepted_ok = bool(req["acceptance_ids"]) and set(req["acceptance_ids"]).issubset(good_acceptances)
        implemented = impl_ok and bool(source_ids) and rid not in bad_refs
        accepted = (implemented and test_ok and accepted_ok and req["disposition"] == "active")
        waived_optional = skip_approved and not mandatory and req["disposition"] == "skipped"
        if not waived_optional:
            if not impl_ok:
                issue("IMPLEMENTATION_INCOMPLETE", path + "/implementation_ids", "Requirement has no complete implementation at target", rid)
            if not test_ok:
                issue("EXECUTION_INCOMPLETE", path + "/test_ids", "Requirement needs passed executed tests covering every implementation at target", rid)
            if not accepted_ok:
                issue("ACCEPTANCE_INCOMPLETE", path + "/acceptance_ids", "Requirement needs a complete independent review at target", rid)

        related = {rid, req["feature_id"], *req["implementation_ids"], *req["test_ids"], *req["acceptance_ids"], *req["evidence_ids"]}
        for group, ids in (("evidence", req["evidence_ids"]), ("implementations", req["implementation_ids"]), ("tests", req["test_ids"]), ("acceptances", req["acceptance_ids"])):
            for ident in ids:
                related.update(indexes[group].get(ident, {}).get("artifact_ids", []))
        codes = sorted({item["code"] for item in blockers if item.get("entity_id") in related})
        if codes:
            accepted = False
        evidence_rows = [indexes["evidence"][eid] for eid in req["evidence_ids"] if eid in indexes["evidence"]]
        result["requirements"].append({
            "id": rid, "feature_id": req["feature_id"], "level": req["level"], "priority": req["priority"],
            "mandatory": mandatory, "disposition": req["disposition"], "implemented": implemented,
            "accepted": accepted, "executed_tests_complete": test_ok,
            "skip_approved": skip_approved, "blocker_codes": codes,
            "observed_support_ids": sorted(source_ids),
            "evidence_certainty": dict(Counter(row["certainty"] for row in evidence_rows)),
            "evidence_kinds": dict(Counter(row["kind"] for row in evidence_rows)),
        })

    for feature in document["features"]:
        rows = [row for row in result["requirements"] if row["feature_id"] == feature["id"]]
        result["features"].append({"id": feature["id"], "classification": feature["classification"],
                                   "implemented": bool(rows) and all(row["implemented"] for row in rows),
                                   "accepted": bool(rows) and all(row["accepted"] for row in rows)})
    rows = result["requirements"]
    accepted_count = sum(row["accepted"] for row in rows)
    by_class = {}
    for classification in ("business_feature", "robustness", "helper"):
        feature_ids = {row["id"] for row in document["features"] if row["classification"] == classification}
        subset = [row for row in rows if row["feature_id"] in feature_ids]
        by_class[classification] = {"total": len(subset), "accepted": sum(row["accepted"] for row in subset)}
    result["diagnostics"] = {
        "total_requirements": len(rows), "accepted_requirements": accepted_count,
        "accepted_percent": round(100 * accepted_count / len(rows), 2),
        "reported_score_ignored": document["audit"].get("reported_score"),
        "score_controls_gate": False, "by_classification": by_class,
        "mandatory_open_ids": [row["id"] for row in rows if row["mandatory"] and not row["accepted"]],
    }
    implemented = all(row["implemented"] for row in rows)
    all_accepted = all(row["accepted"] for row in rows)
    claimed = document["audit"]["claimed_state"]
    if claimed == "implemented" and not implemented:
        issue("CLAIM_OVERSTATED", "/audit/claimed_state", "Claimed implemented but at least one requirement is not implemented")
    if claimed == "accepted" and not all_accepted:
        issue("CLAIM_OVERSTATED", "/audit/claimed_state", "Claimed accepted but at least one requirement is not accepted")
    if claimed == "accepted_with_skips" and (not result["skips"] or not all(row["accepted"] or (row["skip_approved"] and not row["mandatory"]) for row in rows)):
        issue("CLAIM_OVERSTATED", "/audit/claimed_state", "Claimed accepted_with_skips without complete acceptance/approved optional skips")
    result["valid"] = result["gate_passed"] = not blockers
    result["implemented"] = implemented
    result["accepted"] = not blockers and all_accepted
    result["computed_state"] = ("accepted" if all_accepted else "accepted_with_skips") if not blockers else ("implemented" if implemented else "blocked")
    return result


def _check_baseline(current, baseline, issue):
    errors = validate_structure(baseline)
    if errors:
        for error in errors:
            issue("BASELINE_SCHEMA", "/baseline" + error["path"], error["message"])
        return
    seen = {baseline["audit"]["id"]}
    for group in GROUPS:
        for row in baseline[group]:
            if row["id"] in seen:
                issue("BASELINE_DUPLICATE_ID", "/baseline/" + group, f"Duplicate baseline ID: {row['id']}")
            seen.add(row["id"])
    if baseline["audit"]["id"] != current["audit"]["id"] or baseline["audit"]["scope"] != current["audit"]["scope"]:
        issue("BASELINE_SCOPE", "/audit", "Baseline must belong to the same audit ID and scope")
    features = {row["id"]: row for row in current["features"]}
    for old in baseline["features"]:
        new = features.get(old["id"])
        if not new:
            issue("FEATURE_ID_REMOVED", "/features", f"Stable feature ID disappeared: {old['id']}")
        elif new["classification"] != old["classification"]:
            issue("FEATURE_CLASS_CHANGED", "/features", f"Feature category changed: {old['id']}")
    requirements = {row["id"]: row for row in current["requirements"]}
    for old in baseline["requirements"]:
        new = requirements.get(old["id"])
        if not new:
            issue("REQUIREMENT_ID_REMOVED", "/requirements", f"Stable requirement ID disappeared: {old['id']}")
        else:
            for field in ("feature_id", "text", "level", "priority"):
                if old[field] != new[field]:
                    issue("REQUIREMENT_IDENTITY_CHANGED", "/requirements", f"{old['id']} changed immutable field {field}")
