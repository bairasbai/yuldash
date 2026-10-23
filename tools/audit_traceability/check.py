"""Локальная строгая приёмка Юлдаша; команды из документов не выполняются."""
import argparse
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent / "vendor"))
from traceability.io import MAX_MANIFEST_BYTES, _constant, _object, read_document  # noqa: E402
from traceability.schema_check import validate_structure  # noqa: E402
from traceability.validator import validate  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = "docs/audit-traceability/rideshare.json"
AUDIT = "W1-E2E-20261018"
REQUIREMENTS = {f"W1-E2E-{i:02}" for i in range(1, 8)}
COMMIT = re.compile(r"(?:[0-9a-f]{40}|[0-9a-f]{64})\Z")
FORBIDDEN = {"mobile", ".git", "local.properties", "google-services.json",
             "keystore.properties", "key.properties", "signing.properties", "buildconfig.java"}
SECRET_SUFFIXES = {".jks", ".keystore", ".pem", ".key", ".p12"}


class InputFailure(ValueError):
    pass


def git(root, *args):
    # No network, pager, fsmonitor, replace objects or pathspec interpretation.
    env = {**os.environ, "GIT_TERMINAL_PROMPT": "0", "GIT_NO_LAZY_FETCH": "1"}
    result = subprocess.run(
        ["git", "--no-lazy-fetch", "--no-replace-objects", "--literal-pathspecs", "-c", "core.fsmonitor=false",
         "-C", str(root), *args], capture_output=True, timeout=30, env=env,
    )
    if result.returncode:
        # Do not echo Git stderr: local config/errors can contain private paths/URLs.
        raise InputFailure(f"Локальная команда Git {args[0]} не выполнена (код {result.returncode})")
    return result.stdout


def safe_path(root, raw):
    """Validate every component before opening; reject symlinks and Windows junctions."""
    if (not isinstance(raw, str) or not raw or raw.startswith("/") or "\\" in raw
            or ":" in raw or any(ord(c) < 32 for c in raw)
            or any(p in {"", ".", ".."} or p.endswith((".", " ")) for p in raw.split("/"))):
        raise InputFailure("Нужен обычный относительный путь с разделителем /")
    path = root
    for part in raw.split("/"):
        lower = part.lower()
        if (lower in FORBIDDEN or lower.startswith(".env")
                or Path(lower).suffix in SECRET_SUFFIXES
                or any(t in lower for t in ("credentials", "service-account", "service_account", "firebase-adminsdk"))):
            raise InputFailure("Запрещён путь к ключам, mobile или служебным данным Git")
        path = path / part
        # lstat examines the link itself, never its target. 0x400 is REPARSE_POINT.
        try:
            info = path.lstat()
        except FileNotFoundError:
            continue
        if getattr(info, "st_file_attributes", 0) & 0x400 or stat.S_ISLNK(info.st_mode):
            raise InputFailure("Ссылки и junction в путях доказательств запрещены")
    if not path.resolve().is_relative_to(root):
        raise InputFailure("Путь вышел за пределы рабочей папки")
    return path


def baseline_document(root, ref):
    if not COMMIT.fullmatch(ref):
        raise InputFailure("База задаётся полным SHA локального проверенного коммита")
    resolved = git(root, "rev-parse", "--verify", f"{ref}^{{commit}}").decode().strip()
    if resolved != ref:
        raise InputFailure("SHA базы должен указывать прямо на коммит")
    spec = f"{ref}:{MANIFEST}"
    entry = git(root, "ls-tree", ref, "--", MANIFEST).split()
    if len(entry) < 3 or entry[0] not in (b"100644", b"100755"):
        raise InputFailure("В выбранной базе нет обычного файла состава приёмки")
    if int(git(root, "cat-file", "-s", spec)) > MAX_MANIFEST_BYTES:
        raise InputFailure("Манифест базы больше 8 МиБ")
    raw = git(root, "show", spec)
    return json.loads(raw.decode("utf-8"), object_pairs_hook=_object, parse_constant=_constant)


def blocker(code, message, path=""):
    return {"code": code, "path": path, "message": message}


def same_source_bytes(current, committed):
    if current == committed:
        return True
    # Git may check out source with CRLF on Windows. No other text normalization.
    try:
        current.decode("utf-8")
        committed.decode("utf-8")
    except UnicodeError:
        return False
    return b"\0" not in current + committed and current.replace(b"\r\n", b"\n") == committed.replace(b"\r\n", b"\n")


def empty_result():
    return {"gate_passed": False, "accepted": False, "computed_state": "invalid",
            "requirements": [], "blockers": [], "limitations": []}


def check(root, manifest, baseline_ref):
    root = root.resolve()
    if git(root, "rev-parse", "--show-toplevel").decode().strip().replace("\\", "/").lower() != root.as_posix().lower():
        raise InputFailure("Укажи корень отдельного рабочего чекаута Git")
    document = read_document(safe_path(root, manifest))
    result = empty_result()
    structure = validate_structure(document)
    if structure:
        result["blockers"] = structure
        return result

    # Validate ALL paths first: the upstream engine must never open a forbidden file.
    for i, artifact in enumerate(document["artifacts"]):
        try:
            safe_path(root, artifact["path"])
        except (InputFailure, OSError, RuntimeError) as exc:
            result["blockers"].append(blocker("YULDASH_PATH", str(exc), f"/artifacts/{i}/path"))
    if result["blockers"]:
        return result

    baseline = baseline_document(root, baseline_ref)
    result = validate(document, root, baseline=baseline)
    additional = []
    if document["audit"]["id"] != AUDIT or document["audit"]["scope"] != "real":
        additional.append(blocker("YULDASH_SCOPE", "Нужен реальный аудит W1-E2E-20261018; demo не принимается"))
    by_id = {row["id"]: row for row in document["requirements"]}
    if not REQUIREMENTS.issubset(by_id) or any(by_id[r]["level"] != "must" for r in REQUIREMENTS & by_id.keys()):
        additional.append(blocker("YULDASH_REQUIRED_SCOPE", "Все семь W1-E2E-критериев обязательны"))
    head = git(root, "rev-parse", "HEAD").decode().strip()
    if document["audit"]["target_commit"] != head:
        additional.append(blocker("YULDASH_TARGET", "Доказательства относятся к другому коммиту; текущий HEAD не принят"))

    # Check declared implementation files against the CURRENT checkout and HEAD.
    # The core separately checks actual raw-byte SHA256 of every evidence artifact.
    for artifact in document["artifacts"]:
        if artifact["kind"] != "implementation":
            continue
        path = artifact["path"]
        entry = git(root, "ls-tree", "HEAD", "--", path).split()
        if len(entry) < 3 or entry[0] not in (b"100644", b"100755"):
            additional.append(blocker("YULDASH_UNTRACKED_IMPLEMENTATION", "Реализация отсутствует в дереве HEAD", path))
            continue
        # Read the actual blob, bypassing index assume-unchanged/skip-worktree flags.
        oid = entry[2].decode("ascii")
        if int(git(root, "cat-file", "-s", oid)) > MAX_MANIFEST_BYTES:
            additional.append(blocker("YULDASH_IMPLEMENTATION_SIZE", "Файл реализации больше 8 МиБ; нужен меньший исходный артефакт", path))
            continue
        committed = git(root, "cat-file", "blob", oid)
        current_path = safe_path(root, path)
        if current_path.is_file():
            with current_path.open("rb") as stream:
                current = stream.read(MAX_MANIFEST_BYTES + 1)
        else:
            current = b""
        if not same_source_bytes(current, committed):
            additional.append(blocker("YULDASH_DIRTY_IMPLEMENTATION", "Реализация изменена после коммита", path))

    if additional:
        result["blockers"].extend(additional)
        result.update(gate_passed=False, accepted=False, valid=False, computed_state="blocked")
    result["yuldash"] = {"head": head, "baseline_ref": baseline_ref, "baseline_path": MANIFEST,
                         "manifest_path": manifest, "registry": "docs/audit-blocks.md"}
    result["limitations"] = [
        "Проверены декларации и байты. Это не доказательство запуска теста или личности принимающего.",
        "Независимый принимающий проверяет первичные журналы, код завершения, окружение и происхождение сборки.",
        "Проверяются заявленные реализации; полнота зависимостей требует отдельной проверки.",
        "Статусы меняются только вручную в docs/audit-blocks.md. Зелёные self-tests не принимают попутку.",
    ]
    return result


def render(result):
    lines = ["Проверка приёмки попутки: " + ("ПРОЙДЕНА" if result["gate_passed"] else "НЕ ПРОЙДЕНА")]
    if result["gate_passed"] and not result["accepted"]:
        lines.append("Есть согласованные необязательные пропуски; полной приёмки без пропусков нет.")
    if result.get("yuldash"):
        lines.append("Коммит: " + result["yuldash"]["head"])
        lines.append("Доверенная база: " + result["yuldash"]["baseline_ref"])
    rows = result["requirements"]
    lines.append(f"Полных цепочек доказательств: {sum(bool(r['accepted']) for r in rows)} из {len(rows)}. Это не процент готовности приложения.")
    reasons = {"IMPLEMENTATION_INCOMPLETE": "нет связанной реализации",
               "EXECUTION_INCOMPLETE": "нет полного выполненного прогона",
               "ACCEPTANCE_INCOMPLETE": "нет независимой приёмки"}
    for row in rows:
        codes = row.get("blocker_codes", [])
        state = "цепочка полная" if row["accepted"] else "; ".join(reasons.get(c, c) for c in codes) or "не принято"
        lines.append(f"{row['id']}: {state}")
    # Global blockers (and detailed file/link errors) must not disappear behind row counts.
    for item in result["blockers"]:
        if item["code"] not in reasons:
            lines.append(f"Блокирует [{item['code']}] {item.get('path', '')}: {item['message']}")
    lines.extend(result["limitations"])
    return "\n".join(lines)


class Parser(argparse.ArgumentParser):
    def error(self, message):
        result = empty_result()
        result["blockers"] = [blocker("USAGE", message)]
        print(json.dumps(result, ensure_ascii=True))
        self.exit(2)


def main(argv=None):
    parser = Parser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help="Корень проверяемого чекаута")
    parser.add_argument("--manifest", default=MANIFEST, help="Относительный путь манифеста внутри чекаута")
    parser.add_argument("--baseline-ref", required=True, help="Полный SHA отдельно проверенного локального коммита с исходным составом")
    parser.add_argument("--json", action="store_true", help="Машинный отчёт вместо русского текста")
    args = parser.parse_args(argv)
    try:
        result = check(args.root, args.manifest, args.baseline_ref)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as exc:
        result = empty_result()
        result["blockers"] = [blocker("INPUT", str(exc))]
        code = 2
    else:
        code = 0 if result["gate_passed"] else 1
    if args.json:
        print(json.dumps(result, ensure_ascii=True, sort_keys=True, indent=2))
    else:
        print(render(result))
    return code


if __name__ == "__main__":
    sys.exit(main())
