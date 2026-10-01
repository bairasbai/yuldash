"""Per-file audit marks: which in-scope file is read, verified by tests, or excluded.

The registry docs/audit-file-status.json is the only tracked store of per-file marks.
Each mark is stamped with the file's normalized SHA256 at acceptance time, so a later
edit turns the mark stale until the file is checked again. Cards in docs/audit-files/
hold the human-readable evidence; this tool validates their structure, their test
references and the mutation specs in docs/audit-mutations/ that back a "verified" mark.

A mark is evidence bookkeeping, not proof that a file is correct: the proof is the
card, the referenced tests and the recorded deliberate breakages.
"""
import argparse
import fnmatch
import hashlib
import json
import os
import re
import subprocess
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import audit_inventory  # noqa: E402  (shared snapshot rules and hash normalization)

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = "docs/audit-file-status.json"
REPORT = "docs/audit-file-status.md"
CARDS = "docs/audit-files"
MUTATIONS = "docs/audit-mutations"

STATUSES = {
    "not_read": "⬜ не прочитан",
    "read": "🟨 прочитан",
    "verified": "🟩 проверен тестом",
    "bug": "🟥 открытая ошибка",
    "blocked": "⛔ нужен внешний доступ",
    "inactive": "⚪ не исполняется",
}
FINAL = {"verified", "inactive", "blocked"}
NEEDS_REASON = {"inactive", "blocked"}
GROUPS = [
    ("G1", "Деньги", ["B07"]),
    ("G2", "Вход и личные данные", ["B01"]),
    ("G3", "Безопасность, поддержка, администрирование", ["B08"]),
    ("G4", "Попутки, такси, посылки", ["B02", "B03", "B04"]),
    ("G5", "Чат, карта, уведомления", ["B05", "B06"]),
    ("G6", "Общий интерфейс и связующий код", ["B09"]),
    ("G7", "Сборка, миграции, эксплуатация, инструменты", ["B10"]),
]
GROUP_OF_BLOCK = {b: g for g, _, blocks in GROUPS for b in blocks}
SCOPE_TEXT = ("Собственный код Android (android/app/src/main/java) и сервера (backend/app) плюс их "
              "настройки, ресурсы, миграции, скрипты, tools/ и CI. Не входят: webapp/ (PWA), web/ и "
              "promo/ (сайт), mobile/ (старый Flutter), тесты, включая тесты на устройстве "
              "(они — доказательства, а не цель обхода).")
REQUIRED_HEADINGS = [
    "## Назначение",
    "## Функции и разбор",
    "## Связи",
    "## Важные правила и тесты",
    "## Найденные ошибки",
    "## Проверка нарочной поломкой",
    "## Остаток и ограничения",
]
STATUS_RE = re.compile(r"^- Статус:\s*([a-z_]+)\b", re.M)
LEAF_RE = re.compile(r"^- Лист:\s*(leaf-[0-9][0-9.]*[0-9]|leaf-[0-9]+)\s*$", re.M)
REASON_RE = re.compile(r"^Причина:\s*(\S.{9,})$", re.M)
# A test reference: repository path, "::", then a test name (quoted when it has spaces).
REF_RE = re.compile(r'((?:backend|android|tools)/[\w./-]+?\.(?:py|kt))::((?:"[^"]+")|(?:[\w\[\]\-.]+(?:::[\w\[\]\-.]+)*))')
MUTATION_ID_RE = re.compile(r"^\|\s*(M\d+)\s*\|", re.M)


def sha256_of(path):
    data = path.read_bytes()
    return hashlib.sha256(data.replace(b"\r\n", b"\n").replace(b"\r", b"\n")).hexdigest()


def in_scope(path, kind):
    top = path.split("/")[0]
    if kind in ("android_main", "backend_main"):
        return True
    if "/src/androidTest/" in path or "/src/test/" in path:
        # The inventory files on-device tests under configuration; tests are evidence, not walk targets.
        return False
    if kind == "infra_resource_config":
        return top in ("android", "backend", "tools", ".github") or path in (".gitignore", ".gitattributes")
    return False


def scope_snapshot(root):
    audit_inventory.ROOT = root
    snap = audit_inventory.snapshot(root / "docs/audit-code-inventory.json")
    return {f["path"]: f for f in snap["files"] if in_scope(f["path"], f["kind"])}


def card_path(path):
    return f"{CARDS}/{path}.md"


def load_registry(root):
    target = root / REGISTRY
    if not target.exists():
        return {"files": []}
    return json.loads(target.read_text(encoding="utf-8"))


def save_registry(root, registry):
    registry["files"].sort(key=lambda e: e["path"])
    (root / REGISTRY).parent.mkdir(parents=True, exist_ok=True)
    (root / REGISTRY).write_text(json.dumps(registry, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def new_registry(entries):
    return {
        "schema": 1,
        "scope": SCOPE_TEXT,
        "hash_algorithm": "SHA256 after CRLF/CR to LF normalization (same as audit-code-inventory.json)",
        "statuses": STATUSES,
        "final_statuses": sorted(FINAL),
        "stamp_rule": "sha256 is the file hash at acceptance; a different current hash makes the mark stale",
        "files": entries,
    }


def section(text, heading):
    start = text.find("\n" + heading + "\n")
    if start < 0:
        return None
    start += len(heading) + 2
    end = text.find("\n## ", start)
    return text[start:] if end < 0 else text[start:end]


def table_rows(block):
    rows = [line for line in (block or "").splitlines() if line.strip().startswith("|")]
    return [r for r in rows[1:] if not re.match(r"^\|[\s:|-]+\|$", r.strip())]


def resolve_ref(root, rel, name):
    target = root / rel
    if not target.is_file():
        return f"нет файла теста {rel}"
    text = target.read_text(encoding="utf-8", errors="replace")
    for part in name.split("::"):
        part = part.strip('"')
        part = re.sub(r"\[.*\]$", "", part)
        if not part:
            return f"пустое имя теста в {rel}"
        if rel.endswith(".py"):
            if not re.search(rf"^\s*(?:async\s+)?(?:def|class)\s+{re.escape(part)}\b", text, re.M):
                return f"в {rel} нет функции/класса {part}"
        elif part not in text:
            return f"в {rel} нет теста {part}"
    return None


def load_mutations(root, leaf):
    spec = root / MUTATIONS / f"{leaf}.json"
    if not spec.exists():
        return None
    data = json.loads(spec.read_text(encoding="utf-8"))
    return {m["id"]: m for m in data.get("mutations", [])}


def validate_card(root, path, leaf_hint=None):
    """Return (status, leaf, tests, mutation_ids, reason, problems) for one source file's card."""
    problems = []
    card = root / card_path(path)
    if not card.exists():
        return None, None, [], [], None, [f"нет карточки {card_path(path)}"]
    text = card.read_text(encoding="utf-8").replace("\r\n", "\n")
    first = text.splitlines()[0] if text else ""
    if path not in first:
        problems.append("первая строка карточки не содержит путь файла")
    status_m = STATUS_RE.search(text)
    status = status_m.group(1) if status_m else None
    if status not in STATUSES or status == "not_read":
        problems.append(f"строка «- Статус:» отсутствует или неверна ({status})")
    leaf_m = LEAF_RE.search(text)
    leaf = leaf_m.group(1) if leaf_m else None
    if not leaf:
        problems.append("нет строки «- Лист: leaf-…»")
    elif leaf_hint and leaf != leaf_hint:
        problems.append(f"карточка относится к {leaf}, а не к {leaf_hint}")
    for heading in REQUIRED_HEADINGS:
        if ("\n" + heading + "\n") not in text:
            problems.append(f"нет раздела «{heading}»")
    analysis = table_rows(section(text, "## Функции и разбор"))
    if not analysis:
        problems.append("таблица «Функции и разбор» пуста")
    tests, mutation_ids, reason = [], [], None
    rules = table_rows(section(text, "## Важные правила и тесты"))
    if status == "verified":
        if not rules:
            problems.append("для 🟩 нужна хотя бы одна строка правил с тестом")
        for row in rules:
            refs = REF_RE.findall(row)
            if not refs:
                problems.append(f"правило без ссылки на тест: {row[:80]}")
            for rel, name in refs:
                err = resolve_ref(root, rel, name)
                if err:
                    problems.append(err)
                tests.append(f"{rel}::{name}")
        mutation_ids = MUTATION_ID_RE.findall(section(text, "## Проверка нарочной поломкой") or "")
        if not mutation_ids:
            problems.append("для 🟩 нужна хотя бы одна нарочная поломка (строка | M1 | …)")
        specs = load_mutations(root, leaf) if leaf else None
        if mutation_ids and specs is None:
            problems.append(f"нет файла {MUTATIONS}/{leaf}.json")
        for mid in mutation_ids:
            if specs is not None and (mid not in specs or specs[mid].get("file") != path):
                problems.append(f"поломка {mid} не описана для этого файла в {MUTATIONS}/{leaf}.json")
    if status in NEEDS_REASON:
        found = REASON_RE.search(section(text, "## Остаток и ограничения") or "")
        if not found:
            problems.append("для ⚪/⛔ нужна строка «Причина: …» (не короче 10 знаков) в «Остаток и ограничения»")
        else:
            reason = found.group(1).strip()
    return status, leaf, sorted(set(tests)), mutation_ids, reason, problems


def cmd_init(root, args):
    current = scope_snapshot(root)
    registry = load_registry(root)
    old = {e["path"]: e for e in registry.get("files", [])}
    entries = []
    for path, f in sorted(current.items()):
        entry = old.get(path, {"path": path, "status": "not_read", "sha256": None, "card": None,
                               "leaf": None, "tests": [], "mutations": [], "reason": None, "stamped_at": None})
        entry.update({"kind": f["kind"], "block": f["block"], "group": GROUP_OF_BLOCK[f["block"]], "lines": f["lines"]})
        entries.append(entry)
    removed = sorted(set(old) - set(current))
    added = sorted(set(current) - set(old))
    save_registry(root, new_registry(entries))
    print(json.dumps({"files": len(entries), "added": added, "removed": removed}, ensure_ascii=False, indent=2))


def cmd_check_cards(root, args):
    bad = 0
    for path in args.files:
        status, leaf, tests, muts, reason, problems = validate_card(root, path, args.leaf)
        if args.require_status and status not in args.require_status:
            problems.append(f"статус {status}, требуется один из {sorted(args.require_status)}")
        if problems:
            bad += 1
            print(f"✗ {path}")
            for p in problems:
                print(f"    - {p}")
        else:
            print(f"✓ {path}: {status}, тестов {len(tests)}, поломок {len(muts)}")
    if bad:
        print(f"CARDS INVALID {bad}/{len(args.files)}")
        raise SystemExit(1)
    print(f"CARDS OK {len(args.files)}")


def cmd_apply(root, args):
    registry = load_registry(root)
    index = {e["path"]: e for e in registry.get("files", [])}
    stamped = []
    for path in args.files:
        if path not in index:
            raise SystemExit(f"{path} нет в реестре; сначала init")
        status, leaf, tests, muts, reason, problems = validate_card(root, path, args.leaf)
        if problems:
            raise SystemExit(f"{path}: карточка не принята: " + "; ".join(problems))
        entry = index[path]
        entry.update({"status": status, "sha256": sha256_of(root / path), "card": card_path(path), "leaf": leaf,
                      "tests": tests, "mutations": muts, "reason": reason,
                      "stamped_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat()})
        stamped.append(f"{path}={status}")
    save_registry(root, registry)
    print("\n".join(stamped))
    print(f"APPLIED {len(stamped)}")


def measure(root):
    registry = load_registry(root)
    current = scope_snapshot(root)
    entries = registry.get("files", [])
    known = {e["path"] for e in entries}
    drift = {"added": sorted(set(current) - known), "removed": sorted(known - set(current))}
    stale, missing_reason = [], []
    for e in entries:
        if e["path"] in current and e["status"] != "not_read" and e.get("sha256") != current[e["path"]]["sha256"]:
            stale.append(e["path"])
        if e["status"] in NEEDS_REASON and not (e.get("reason") or "").strip():
            missing_reason.append(e["path"])
    return entries, drift, stale, missing_reason


def summary(entries, stale):
    counts = Counter(e["status"] for e in entries)
    by_group = {}
    for gid, title, _ in GROUPS:
        group = [e for e in entries if e.get("group") == gid]
        by_group[gid] = {"title": title, "total": len(group),
                         **{s: sum(1 for e in group if e["status"] == s) for s in STATUSES}}
    return {"total": len(entries), "by_status": {s: counts.get(s, 0) for s in STATUSES},
            "stale": len(stale), "by_group": by_group}


def cmd_progress(root, args):
    entries, drift, stale, missing_reason = measure(root)
    if args.group:
        entries = [e for e in entries if e.get("group") == args.group]
        paths = {e["path"] for e in entries}
        stale = [p for p in stale if p in paths]
        missing_reason = [p for p in missing_reason if p in paths]
        current = scope_snapshot(root)
        drift = {"added": [p for p in drift["added"] if GROUP_OF_BLOCK[current[p]["block"]] == args.group],
                 "removed": [p for p in drift["removed"] if p in paths]}
    data = summary(entries, stale)
    data.update({"drift": drift, "stale_files": stale[:50], "missing_reason": missing_reason})
    print(json.dumps(data, ensure_ascii=False, indent=2))
    if args.require_final:
        not_final = [e["path"] for e in entries if e["status"] not in FINAL]
        if not entries or not_final or stale or drift["added"] or drift["removed"] or missing_reason:
            print(f"FILE WALK INCOMPLETE: не финальных {len(not_final)}, устаревших {len(stale)}, "
                  f"новых {len(drift['added'])}, исчезнувших {len(drift['removed'])}, без причины {len(missing_reason)}")
            raise SystemExit(1)
        print(f"FILE WALK COMPLETE {len(entries)}")


def cmd_check(root, args):
    entries, drift, stale, missing_reason = measure(root)
    print(json.dumps({"stale": stale, "drift": drift, "missing_reason": missing_reason}, ensure_ascii=False, indent=2))
    if args.strict and (stale or drift["added"] or drift["removed"] or missing_reason):
        raise SystemExit(1)
    print("REGISTRY CONSISTENT" if not (stale or drift["added"] or drift["removed"] or missing_reason) else "REGISTRY HAS DRIFT")


def bar(done, total, width=20):
    filled = round(width * done / total) if total else 0
    return "█" * filled + "░" * (width - filled)


def cmd_report(root, args):
    entries, drift, stale, missing_reason = measure(root)
    data = summary(entries, stale)
    stale_set = set(stale)
    final = sum(data["by_status"][s] for s in FINAL)
    lines = [
        "# Пофайловый обход: отметки по каждому файлу",
        "",
        "Документ создаётся командой `python tools/audit_file_status.py report` из реестра "
        "[audit-file-status.json](audit-file-status.json). Руками не править.",
        "",
        f"Охват: {SCOPE_TEXT}",
        "",
        "Легенда: " + " · ".join(STATUSES.values()) + " · 🔄 файл изменился после отметки — перепроверить.",
        "",
        f"**Готово (🟩/⚪/⛔): {final} из {data['total']}** {bar(final, data['total'])}",
        "",
        "| Статус | Файлов |",
        "|---|---|",
    ]
    lines += [f"| {STATUSES[s]} | {data['by_status'][s]} |" for s in STATUSES]
    lines += [f"| 🔄 устарела отметка | {len(stale)} |", ""]
    for gid, title, _ in GROUPS:
        group = sorted((e for e in entries if e.get("group") == gid), key=lambda e: e["path"])
        done = sum(1 for e in group if e["status"] in FINAL)
        lines += [f"## {gid}. {title} — {done} из {len(group)} {bar(done, len(group))}", "",
                  "| Файл | Строк | Статус | Карточка | Лист |", "|---|---|---|---|---|"]
        for e in group:
            mark = STATUSES[e["status"]] + (" 🔄" if e["path"] in stale_set else "")
            card = f"[карточка]({e['card'][len('docs/'):]})" if e.get("card") else "—"
            lines.append(f"| `{e['path']}` | {e.get('lines', '')} | {mark} | {card} | {e.get('leaf') or '—'} |")
        lines.append("")
    (root / REPORT).write_text("\n".join(lines), encoding="utf-8")
    print(f"REPORT WRITTEN {REPORT}: готово {final} из {data['total']}")


def glob_to_regex(pattern):
    out, i = "", 0
    while i < len(pattern):
        if pattern.startswith("**/", i):
            out += r"(?:.*/)?"
            i += 3
        elif pattern.startswith("**", i):
            out += r".*"
            i += 2
        elif pattern[i] == "*":
            out += r"[^/]*"
            i += 1
        elif pattern[i] == "?":
            out += r"[^/]"
            i += 1
        else:
            out += re.escape(pattern[i])
            i += 1
    return re.compile(r"^" + out + r"$")


def cmd_owns_guard(root, args):
    globs = [g.strip() for g in args.owns.split(",") if g.strip()]
    patterns = [glob_to_regex(g) for g in globs]
    committed = subprocess.run(["git", "diff", "--name-only", f"{args.base}...{args.head or 'HEAD'}"], cwd=root,
                               capture_output=True, text=True, check=True).stdout.split()
    worktree = []
    if not args.head:  # a named head is a finished branch; the local working tree is someone else's
        porcelain = subprocess.run(["git", "status", "--porcelain", "-uall"], cwd=root,
                                   capture_output=True, text=True, check=True).stdout.splitlines()
        worktree = [line[3:].split(" -> ")[-1].strip('"') for line in porcelain if line.strip()]
    ignored = [glob_to_regex(g) for g in (args.ignore or "").split(",") if g.strip()]
    changed = sorted({p for p in committed + worktree if not any(r.match(p) for r in ignored)})
    outside = [p for p in changed if not any(r.match(p) for r in patterns)]
    for p in outside:
        print(f"✗ вне зоны листа: {p}")
    if outside:
        print(f"OWNS VIOLATED {len(outside)}")
        raise SystemExit(1)
    print(f"OWNS RESPECTED {len(changed)} changed path(s)")


def kotlin_class(rel):
    for marker in ("/src/test/java/", "/src/androidTest/java/"):
        if marker in rel:
            return rel.split(marker, 1)[1][:-len(".kt")].replace("/", ".")
    return None


def cmd_run_tests(root, args):
    """Run every test the given cards reference; prints LEAF TESTS GREEN only when all pass."""
    import audit_mutation
    refs, statuses = set(), []
    for path in args.files:
        status, _, tests, _, _, problems = validate_card(root, path, args.leaf)
        if problems:
            print(f"✗ {path}: " + "; ".join(problems))
            print("LEAF TESTS RED (карточки неверны)")
            raise SystemExit(1)
        statuses.append(status)
        refs.update(tests)
    paths = sorted({r.split("::", 1)[0] for r in refs})
    instrumented = [p for p in paths if "/src/androidTest/" in p]
    if instrumented:
        print("✗ тесты на устройстве нельзя перепроверить на компьютере: " + ", ".join(instrumented))
        print("LEAF TESTS RED (нужен эмулятор)")
        raise SystemExit(1)
    if not refs:
        if statuses and all(s in NEEDS_REASON for s in statuses):
            print("LEAF TESTS GREEN 0 (все файлы листа ⚪/⛔, тестов не требуется)")
            return
        print("LEAF TESTS RED (в карточках нет ссылок на тесты)")
        raise SystemExit(1)
    python = os.environ.get("AUDIT_PYTHON") or (str(audit_mutation.DEFAULT_PYTHON)
                                               if audit_mutation.DEFAULT_PYTHON.exists() else sys.executable)
    env = os.environ.copy()
    if env.get("DATABASE_URL", "").startswith("postgres"):
        env.pop("DATABASE_URL")
    runs = []
    backend = sorted({r[len("backend/"):] for r in refs if r.startswith("backend/")})
    tools = sorted({r for r in refs if r.startswith("tools/")})
    kotlin = sorted({kotlin_class(p) for p in paths if p.endswith(".kt")})
    if backend:
        cmd = [python, "-m", "pytest", "-q", "-p", "no:cacheprovider", *backend]
        if args.db == "postgres":
            with audit_mutation.FreshPostgres(args.leaf or "leaf") as url:
                runs.append(("backend/postgres", subprocess.run(cmd, cwd=root / "backend", env={**env, "DATABASE_URL": url})))
        else:
            runs.append(("backend", subprocess.run(cmd, cwd=root / "backend", env=env)))
    if tools:
        runs.append(("tools", subprocess.run([python, "-m", "pytest", "-q", "-p", "no:cacheprovider", *tools],
                                             cwd=root, env=env)))
    if kotlin:
        held = audit_mutation.acquire("gradle", f"run-tests {args.leaf}", 7200)
        try:
            env.setdefault("JAVA_HOME", audit_mutation.DEFAULT_JAVA_HOME)
            runs.append(("android", subprocess.run(audit_mutation.gradle_command(kotlin), cwd=root / "android",
                                                   env=env, shell=(os.name == "nt"))))
        finally:
            audit_mutation.release(held)
    failed = [name for name, proc in runs if proc.returncode != 0]
    if failed:
        print(f"LEAF TESTS RED: {', '.join(failed)}")
        raise SystemExit(1)
    print(f"LEAF TESTS GREEN {len(refs)} ref(s): backend {len(backend)}, tools {len(tools)}, android classes {len(kotlin)}")


def cmd_list(root, args):
    entries = load_registry(root).get("files", [])
    for e in entries:
        if (not args.group or e.get("group") == args.group) and (not args.status or e["status"] == args.status) \
                and (not args.block or e.get("block") == args.block) and (not args.glob or fnmatch.fnmatch(e["path"], args.glob)):
            print(f"{e['path']}\t{e.get('lines')}\t{e['status']}")


def main():
    global ROOT
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8")  # Russian output must not depend on the console code page
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=ROOT, help="Repository root (default: this checkout)")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("init", help="Create/refresh the registry from the current in-scope files; keeps marks")
    p = sub.add_parser("check-cards", help="Validate cards of the given files; prints CARDS OK on success")
    p.add_argument("--files", nargs="+")
    p.add_argument("--files-from", type=Path, help="Text file with one repository path per line")
    p.add_argument("--leaf")
    p.add_argument("--require-status", nargs="+", choices=sorted(STATUSES))
    p = sub.add_parser("apply", help="Stamp the card status and current hash of the given files into the registry")
    p.add_argument("--files", nargs="+")
    p.add_argument("--files-from", type=Path, help="Text file with one repository path per line")
    p.add_argument("--leaf", required=True)
    p = sub.add_parser("progress", help="Print measured counts; --require-final prints FILE WALK COMPLETE only when done")
    p.add_argument("--require-final", action="store_true")
    p.add_argument("--group", choices=[g for g, _, _ in GROUPS])
    p = sub.add_parser("run-tests", help="Run all tests referenced by the given cards; prints LEAF TESTS GREEN")
    p.add_argument("--files", nargs="+")
    p.add_argument("--files-from", type=Path, help="Text file with one repository path per line")
    p.add_argument("--leaf")
    p.add_argument("--db", choices=["sqlite", "postgres"], default="sqlite")
    p = sub.add_parser("check", help="Report stale marks and scope drift; --strict exits 1 on any")
    p.add_argument("--strict", action="store_true")
    sub.add_parser("report", help=f"Write {REPORT}")
    p = sub.add_parser("owns-guard", help="Fail when changes since --base touch paths outside --owns globs")
    p.add_argument("--base", required=True)
    p.add_argument("--head", help="Check a finished branch instead of HEAD plus the working tree")
    p.add_argument("--owns", help="Comma-separated repository-relative globs")
    p.add_argument("--owns-from", type=Path, help="Text file with one glob per line")
    p.add_argument("--ignore", help="Comma-separated globs that are never counted (untracked local files)")
    p = sub.add_parser("list", help="List registry entries with optional filters")
    p.add_argument("--group")
    p.add_argument("--block")
    p.add_argument("--status", choices=sorted(STATUSES))
    p.add_argument("--glob")
    args = parser.parse_args()
    root = args.root.resolve()
    ROOT = root
    if hasattr(args, "files_from"):
        listed = [] if args.files_from is None else [
            line.strip() for line in (root / args.files_from).read_text(encoding="utf-8").splitlines() if line.strip()]
        args.files = (args.files or []) + listed
        if not args.files:
            parser.error("нужны --files или --files-from")
    if hasattr(args, "owns_from"):
        listed = [] if args.owns_from is None else [
            line.strip() for line in (root / args.owns_from).read_text(encoding="utf-8").splitlines() if line.strip()]
        args.owns = ",".join(filter(None, [args.owns or "", *listed]))
        if not args.owns:
            parser.error("нужны --owns или --owns-from")
    {"init": cmd_init, "check-cards": cmd_check_cards, "apply": cmd_apply, "progress": cmd_progress,
     "check": cmd_check, "report": cmd_report, "owns-guard": cmd_owns_guard, "list": cmd_list,
     "run-tests": cmd_run_tests}[args.command](root, args)


if __name__ == "__main__":
    main()
