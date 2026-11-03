"""Local feedback exports for Yuldash/Tili. Ordinary commands never import an AI SDK."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile

PROJECTS = ("yuldash", "tili")
CATEGORIES = ("bug", "usability", "feature", "praise", "other")
SENTIMENTS = ("negative", "neutral", "positive")
MAX_BYTES = 4 * 1024 * 1024
PATTERNS = {
    "email": re.compile(r"\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b", re.I),
    "url": re.compile(r"(?:https?://|www\.)[^\s<>]+", re.I),
    "phone": re.compile(r"(?<!\w)(?:\+?\d[\s().-]*){10,15}(?!\w)"),
    "coordinates": re.compile(r"(?<!\d)[+-]?\d{1,2}\.\d{4,}\s*[,;]\s*[+-]?\d{1,3}\.\d{4,}(?!\d)"),
    "token": re.compile(r"\b(?:sk-[A-Za-z0-9_-]{16,}|eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)\b"),
}


class InputError(ValueError):
    """An error safe to display: never includes supplied feedback or credentials."""


def read_json(path: Path):
    if path.stat().st_size > MAX_BYTES:
        raise InputError("Файл больше 4 МиБ / File exceeds 4 MiB")
    try:
        def unique_keys(pairs):
            result = {}
            for key, value in pairs:
                if key in result:
                    raise InputError("Повторяющееся поле JSON / Duplicate JSON field")
                result[key] = value
            return result
        return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_keys)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise InputError("Нужен JSON UTF-8 / UTF-8 JSON required") from error


def records(value) -> list[dict]:
    if not isinstance(value, dict) or set(value) != {"schemaVersion", "records"} or type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1:
        raise InputError("Неверный формат экспорта / Invalid export format")
    rows = value["records"]
    if not isinstance(rows, list) or len(rows) > 1000:
        raise InputError("Допустимо до 1000 записей / Maximum 1000 records")
    seen = set()
    for row in rows:
        if not isinstance(row, dict) or set(row) != {"id", "project", "text"}:
            raise InputError("Неверные поля записи / Invalid record fields")
        if not isinstance(row["id"], str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", row["id"]) or row["id"] in seen:
            raise InputError("Неверный или повторный ID / Invalid or duplicate ID")
        seen.add(row["id"])
        if row["project"] not in PROJECTS or not isinstance(row["text"], str) or not row["text"].strip() or len(row["text"]) > 4000 or any(ord(char) < 32 and char not in "\n\t\r" for char in row["text"]):
            raise InputError("Неверный проект или текст / Invalid project or text")
    return rows


def privacy_findings(rows: list[dict]) -> list[dict]:
    return [{"id": row["id"], "kinds": [kind for kind, pattern in PATTERNS.items() if pattern.search(row["text"])]} for row in rows if any(pattern.search(row["text"]) for pattern in PATTERNS.values())]


def redact(rows: list[dict]) -> list[dict]:
    output = []
    for row in rows:
        text = row["text"]
        for kind, pattern in PATTERNS.items():
            text = pattern.sub(f"[{kind.upper()}]", text)
        output.append({**row, "text": text})
    return output


def validate_labels(rows: list[dict], value) -> list[dict]:
    if not isinstance(value, list) or len(value) != len(rows):
        raise InputError("Неполный ответ классификации / Incomplete classification")
    source = {row["id"]: row for row in rows}
    labels = {}
    for row in value:
        if not isinstance(row, dict) or set(row) != {"id", "project", "category", "sentiment"} or not isinstance(row.get("id"), str) or row["id"] not in source or row["id"] in labels:
            raise InputError("Подменённый или повторный ID ответа / Invalid result ID")
        if row["project"] != source[row["id"]]["project"] or row["category"] not in CATEGORIES or row["sentiment"] not in SENTIMENTS:
            raise InputError("Неверная классификация / Invalid classification")
        labels[row["id"]] = row
    return [labels[row["id"]] for row in rows]


def validate_sdk_results(rows: list[dict], value) -> list[dict]:
    if not isinstance(value, list):
        raise InputError("Неверный ответ SDK / Invalid SDK result")
    source = {row["id"]: row for row in rows}
    labels = []
    for result in value:
        if not isinstance(result, dict) or set(result) != {"id", "project", "text", "category", "sentiment"}:
            raise InputError("Неверные поля ответа SDK / Invalid SDK fields")
        original = source.get(result["id"]) if isinstance(result["id"], str) else None
        if original is None or any(result[key] != original[key] for key in ("id", "project", "text")):
            raise InputError("SDK изменил исходную запись / SDK altered a source record")
        labels.append({key: result[key] for key in ("id", "project", "category", "sentiment")})
    return validate_labels(rows, labels)


def summarize(rows: list[dict], labels: list[dict] | None = None) -> dict:
    classified = validate_labels(rows, labels) if labels is not None else None
    output = {"schemaVersion": 1, "mode": "classified" if classified is not None else "counts_only", "total": len(rows), "projects": {}}
    for project in PROJECTS:
        total = sum(row["project"] == project for row in rows)
        entry = {"total": total}
        if classified is not None:
            counts = {category: sum(row["project"] == project and row["category"] == category for row in classified) for category in CATEGORIES}
            entry.update(categories=counts, categoryPercent={key: round(count / total * 100, 1) if total else 0 for key, count in counts.items()}, sentiments={sentiment: sum(row["project"] == project and row["sentiment"] == sentiment for row in classified) for sentiment in SENTIMENTS})
        output["projects"][project] = entry
    if classified is not None:
        counts = {category: sum(row["category"] == category for row in classified) for category in CATEGORIES}
        output["overall"] = {"categories": counts, "categoryPercent": {key: round(count / len(rows) * 100, 1) if rows else 0 for key, count in counts.items()}, "sentiments": {sentiment: sum(row["sentiment"] == sentiment for row in classified) for sentiment in SENTIMENTS}}
    return output


def classify(rows: list[dict], *, model: str, allowed_host: str, timeout: int = 120) -> list[dict]:
    if not rows:
        return []
    if privacy_findings(rows):
        raise InputError("Найдены возможные личные данные. Обработку моделью отменили / Possible private data; model processing refused")
    if not re.fullmatch(r"[a-zA-Z0-9_-]+/[a-zA-Z0-9_.:/-]{1,180}", model) or not re.fullmatch(r"(?=.{1,253}$)[a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?", allowed_host) or "." not in allowed_host:
        raise InputError("Неверная модель или адрес / Invalid model or host")
    if not 1 <= timeout <= 120:
        raise InputError("Предел времени: 1–120 секунд / Timeout must be 1–120 seconds")
    with tempfile.TemporaryDirectory(prefix="feedback-docetl-") as owned:
        environment = os.environ.copy()
        environment.update(PYTHON_DOTENV_DISABLED="1", LITELLM_LOCAL_MODEL_COST_MAP="True", DOCETL_HOME_DIR=owned, XDG_CACHE_HOME=owned, HF_HOME=owned, MPLCONFIGDIR=owned, TIKTOKEN_CACHE_DIR=owned, NUMBA_CACHE_DIR=owned, TEMP=owned, TMP=owned, TMPDIR=owned)
        worker = Path(__file__).with_name("feedback_docetl.py")
        command = [sys.executable, "-X", "utf8", "-B", "-I", str(worker), "--model", model, "--allowed-host", allowed_host]
        options = {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == "nt" else {"start_new_session": True}
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8", env=environment, cwd=owned, **options)
        try:
            stdout, _ = process.communicate(json.dumps(rows, ensure_ascii=False), timeout=timeout)
        except subprocess.TimeoutExpired as error:
            raise InputError("Обработка остановлена по времени / Processing timeout") from error
        finally:
            if process.poll() is None:
                if os.name == "nt":
                    try:
                        subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], capture_output=True, check=False, timeout=5)
                    except subprocess.TimeoutExpired:
                        pass
                else:
                    os.killpg(process.pid, signal.SIGKILL)
                if process.poll() is None:
                    process.kill()
                try:
                    process.communicate(timeout=5)
                except subprocess.TimeoutExpired as error:
                    raise InputError("Не подтверждена остановка worker / Worker shutdown not confirmed") from error
        if process.returncode:
            raise InputError("DocETL не завершил обработку; проверь отдельное окружение и настройки провайдера / DocETL worker failed")
        try:
            return validate_labels(rows, json.loads(stdout))
        except json.JSONDecodeError as error:
            raise InputError("Повреждённый ответ процесса / Invalid worker result") from error


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Локальная сводка отзывов / Local feedback summaries")
    parser.add_argument("command", choices=("validate", "redact", "summarize", "classify"))
    parser.add_argument("input", type=Path)
    parser.add_argument("--labels", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--send-model", action="store_true")
    parser.add_argument("--privacy-reviewed", action="store_true")
    parser.add_argument("--model")
    parser.add_argument("--allowed-host")
    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args(argv)
    output = None
    output_identity = None
    saved = False
    try:
        rows = records(read_json(args.input))
        if args.output and args.output.exists():
            raise InputError("Выходной файл уже существует / Output file already exists")
        if args.output:
            # Reserve before any model call; even an invalid/unwritable destination fails first.
            output = args.output.open("x", encoding="utf-8")
            stat = os.fstat(output.fileno())
            output_identity = (stat.st_dev, stat.st_ino)
        if args.command == "classify":
            if not (args.send_model and args.privacy_reviewed and args.model and args.allowed_host and args.output):
                raise InputError("Нужны --send-model --privacy-reviewed --model --allowed-host --output / Explicit model and privacy opt-in required")
            value = classify(rows, model=args.model, allowed_host=args.allowed_host, timeout=args.timeout)
        elif args.command == "redact":
            if not args.output:
                raise InputError("Для обезличенной копии нужен --output / Redacted copy requires --output")
            value = {"schemaVersion": 1, "records": redact(rows)}
        elif args.command == "validate":
            value = {**summarize(rows), "privacyFindings": privacy_findings(rows), "manualPrivacyReviewRequired": True}
        else:
            value = summarize(rows, read_json(args.labels) if args.labels else None)
        encoded = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
        if output:
            output.write(encoded)
            output.flush()
            os.fsync(output.fileno())
            output.close()
            saved = True
        else:
            print(encoded, end="")
        return 0
    except KeyboardInterrupt:
        print("Обработка отменена / Processing cancelled", file=sys.stderr)
        return 130
    except (InputError, OSError) as error:
        print(str(error) if isinstance(error, InputError) else "Не удалось прочитать или сохранить файл / File operation failed", file=sys.stderr)
        return 2
    finally:
        if output:
            try:
                output.close()
            except OSError:
                pass
        if output_identity and not saved:
            try:
                stat = args.output.stat()
                if (stat.st_dev, stat.st_ino) == output_identity:
                    args.output.unlink()
            except OSError:
                pass


if __name__ == "__main__":
    raise SystemExit(main())
