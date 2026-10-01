"""Deterministic source inventory; hashes are change evidence, not audit coverage."""
import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BLOCKS = {
    "B01": "Вход, аккаунт и личные данные",
    "B02": "Попутки, бронирование и офлайн-паспорт",
    "B03": "Такси и допуск водителя",
    "B04": "Посылки и курьер",
    "B05": "Чат и повторная доставка сообщений",
    "B06": "Карты, местоположение и уведомления",
    "B07": "Деньги, тарифы и вознаграждения",
    "B08": "Безопасность, поддержка и администрирование",
    "B09": "Общий интерфейс и связующий код",
    "B10": "Сборка, миграции, эксплуатация и инструменты",
    "B11": "Публичный сайт и рекламные материалы с кодом",
}
# Conservative shared dependencies, not a claim to be a complete import graph.
DEPENDS = {b: ["B01", "B09", "B10"] for b in BLOCKS}
DEPENDS.update({"B01": ["B09", "B10"], "B09": ["B10"], "B10": [],
                "B11": ["B10"]})
for b in ("B02", "B03", "B04"):
    DEPENDS[b] += ["B05", "B06", "B07", "B08"]
TEXT_EXT = {".kt", ".kts", ".java", ".py", ".ts", ".tsx", ".js", ".jsx",
            ".mjs", ".cjs", ".css", ".scss", ".html", ".xml", ".json",
            ".yaml", ".yml", ".toml", ".ini", ".cfg", ".conf", ".properties",
            ".sql", ".sh", ".ps1", ".bat", ".cmd", ".txt", ".svg", ".webmanifest",
            # Own R8 rules, migration templates and deployment units/examples.
            # Secret/local-setting exclusions are evaluated before this allowlist.
            ".pro", ".mako", ".service", ".timer", ".example"}


def exclusion(path):
    parts = path.lower().split("/")
    name = parts[-1]
    if parts[0] == "mobile":
        return "legacy_flutter_do_not_modify"
    if parts[0] == "experiments":
        return "experimental_not_current_product"
    if parts[0] in {"docs", "test-results", ".claude", ".codex", "yookassa-anketa"}:
        return "documentation_artifacts_or_private_material"
    if any(p in {"node_modules", ".venv", "venv", "__pycache__", "build", "dist", ".next", ".gradle", "coverage"} for p in parts):
        return "dependencies_or_generated"
    if (name.startswith(".env") or name in {"local.properties", "keystore.properties", "key.properties", "signing.properties", "google-services.json"}
            or any(token in name for token in ("service-account", "service_account", "credentials", "firebase-adminsdk"))
            or Path(name).suffix in {".jks", ".keystore", ".pem", ".key", ".p12"}):
        return "secret_or_local_settings_not_read"
    if Path(name).suffix in {".md", ".rst"}:
        return "documentation"
    if Path(name).suffix not in TEXT_EXT and name not in {"dockerfile", "gradlew", ".gitignore", ".dockerignore", ".gitattributes", ".coveragerc", "nginx.conf"}:
        return "binary_asset_or_non_source"
    return None


def category(path):
    p = path.lower()
    if p.startswith("android/app/src/main/java/") and Path(p).suffix in {".kt", ".java"}:
        return "android_main"
    if p.startswith("backend/app/") and p.endswith(".py"):
        return "backend_main"
    if p.startswith("webapp/src/") and Path(p).suffix in {".ts", ".tsx", ".js", ".css"}:
        return "pwa_main"
    if "/test" in p or "test_" in Path(p).name or ".test." in p or ".spec." in p:
        return "test_support"
    if p.startswith(("web/", "promo/")):
        return "public_site_promo"
    return "infra_resource_config"


def block(path, kind):
    name = Path(path).name.lower()
    if name == "trippass.kt":
        # This mixed file also owns Outbox; storage changes can affect B05 chat.
        return "B01"
    if path.startswith(("web/", "promo/")):
        return "B11"
    if kind == "infra_resource_config":
        return "B10"
    if name.startswith("admin") or any(x in name for x in ("safety", "sos", "incident", "support", "trust", "fraud", "collusion", "sybil", "bodyguard", "flood", "winter", "fairness", "review", "rating", "report", "blocklist")):
        return "B08"
    if any(x in name for x in ("auth", "login", "session", "security", "secure", "sensitive", "privacy", "mydata", "consent", "account", "offline", "outbox", "personaldat")):
        # Shared offline migration/outbox ownership is B01; message semantics remain B05.
        return "B01"
    if any(x in name for x in ("chat", "message", "voice", "quickrepl")):
        return "B05"
    if any(x in name for x in ("payment", "payonline", "paytrip", "paymethod", "paydone", "sbp", "wallet", "ledger", "settlement", "debt", "earning", "income", "commission", "price", "pricing", "coupon", "promo", "boost", "compensation", "referral")):
        return "B07"
    if any(x in name for x in ("parcel", "courier")):
        return "B04"
    if any(x in name for x in ("geo", "location", "map", "fcm", "push", "notif", "place", "livepos", "weather", "triplink")):
        return "B06"
    if any(x in name for x in ("taxi", "instant", "pretrip", "carphoto", "carclass", "car_class", "driver_check", "driveroffer", "driveractive", "workday", "workzone", "doc_check")):
        return "B03"
    if any(x in name for x in ("ride", "booking", "trippass", "tripreceipt", "tripdetail", "activetrip", "request", "passenger", "driver", "bargain", "pickup", "automatch", "waiting", "routewatch", "route_watch", "waitlist", "scheduled", "medical", "family", "cancel_reason")):
        return "B02"
    return "B09"


def snapshot(baseline):
    candidates = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=ROOT).decode("utf-8").split("\0")
    files, excluded = [], []
    for path in sorted(set(filter(None, candidates))):
        # The generated output must not change its own next snapshot on first write.
        if (ROOT / path).resolve() == baseline.resolve():
            continue
        reason = exclusion(path)
        if reason:
            excluded.append({"path": path, "reason": reason})
            continue
        target = ROOT / path
        if not target.is_file() or target.is_symlink():
            excluded.append({"path": path, "reason": "missing_or_symlink"})
            continue
        data = target.read_bytes()
        kind = category(path)
        # Text-only scope: normalize checkout line endings, preserve every other byte.
        normalized = data.replace(b"\r\n", b"\n").replace(b"\r", b"\n")
        files.append({"path": path, "kind": kind, "block": block(path, kind),
                      "sha256": hashlib.sha256(normalized).hexdigest(),
                      "lines": len(data.splitlines())})
    return {"schema": 1, "hash_algorithm": "SHA256 after CRLF/CR to LF normalization; no other text changes; binary assets excluded",
            "method": "git tracked + nonignored untracked regular files; physical lines include comments/blanks",
            "ignored_files": "Not traversed; dependencies, local secrets and generated outputs outside git are not inventoried. The inventory output itself is excluded to avoid self-reference.",
            "classification": "Ordered filename/path ownership rules, not semantic audit or a complete dependency graph; mixed files have one owner.",
            "blocks": BLOCKS, "depends_on": DEPENDS,
            "counts_by_kind": dict(sorted(Counter(f["kind"] for f in files).items())),
            "counts_by_block": dict(sorted(Counter(f["block"] for f in files).items())),
            "excluded_counts": dict(sorted(Counter(f["reason"] for f in excluded).items())),
            "files": files, "excluded": excluded}


def main():
    global ROOT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="Replace the inventory, without changing audit statuses")
    parser.add_argument("--check", action="store_true", help="Exit 1 on included source drift; never update statuses")
    parser.add_argument("--root", type=Path, default=ROOT, help="Repository root, useful for isolated verification")
    parser.add_argument("--baseline", type=Path, help="Saved inventory; defaults to <root>/docs/audit-code-inventory.json")
    args = parser.parse_args()
    if args.write and args.check:
        parser.error("--write and --check are mutually exclusive")
    ROOT = args.root.resolve()
    args.baseline = args.baseline or ROOT / "docs/audit-code-inventory.json"
    current = snapshot(args.baseline)
    old = json.loads(args.baseline.read_text(encoding="utf-8")) if args.baseline.exists() else {"files": []}
    before = {f["path"]: f for f in old["files"]}
    after = {f["path"]: f for f in current["files"]}
    changed = sorted(p for p in before.keys() & after.keys() if any(before[p].get(k) != after[p].get(k) for k in ("sha256", "block", "kind")))
    added, removed = sorted(after.keys() - before.keys()), sorted(before.keys() - after.keys())
    impacted = {after[p]["block"] for p in changed + added} | {before[p]["block"] for p in removed}
    direct = sorted(impacted)
    while True:
        expanded = impacted | {b for b, dependencies in DEPENDS.items() if set(dependencies) & impacted}
        if expanded == impacted:
            break
        impacted = expanded
    print(json.dumps({"added": added, "changed": changed, "removed": removed, "direct_blocks": direct,
                      "review_dependency_impact": sorted(impacted), "counts_by_kind": current["counts_by_kind"],
                      "counts_by_block": current["counts_by_block"]}, ensure_ascii=False, indent=2))
    if args.write:
        args.baseline.write_text(json.dumps(current, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.check and (added or removed or changed or old.get("hash_algorithm") != current["hash_algorithm"]):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
