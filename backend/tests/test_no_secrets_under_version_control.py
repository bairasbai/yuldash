"""Сторож: ключи и секреты не попадают под контроль версий.

Зачем. Ключ, случайно закоммиченный один раз, остаётся в истории навсегда — удалить файл
следующим коммитом недостаточно, достать его сможет любой, у кого есть доступ к репозиторию.
Именно так уехал ключ Яндекс-карт (он лежал в сборках, которые уже раздали, — из-за этого
в задачах Александру до сих пор висит ротация).

Проверка идёт по файлам, которые Git реально отслеживает, а не по всему диску: `.env`
на машине разработчика — это нормально, `.env` в индексе — нет.

Тест держит три обещания:

* среди отслеживаемых файлов нет тех, которым там не место (`.env`, ключи подписи,
  сервисные аккаунты Firebase);
* в тексте отслеживаемых файлов нет ключей узнаваемых форматов;
* `.gitignore` продолжает закрывать эти пути — если строку случайно удалят, следующий
  коммит утащит секреты молча.
"""
from __future__ import annotations

import re
import subprocess
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]

# Файлы, которых под контролем версий быть не должно.
FORBIDDEN_NAMES = re.compile(
    r"(^|/)(\.env|\.env\..+|local\.properties|google-services\.json|"
    r"firebase-service-account.*\.json|keystore\.properties)$"
)
# Шаблоны остаются в репозитории намеренно: по ним человек заполняет свой файл.
# В самом .gitignore это оговорено отдельной строкой.
TEMPLATE_SUFFIXES = (".example", ".sample", ".template", ".dist")
FORBIDDEN_SUFFIXES = (".jks", ".keystore", ".pem", ".p12")

# Ключи узнаваемых форматов. Ищем только то, что нельзя спутать с обычным текстом.
SECRET_PATTERNS = {
    "ключ Google/Firebase": re.compile(r"AIza[0-9A-Za-z_-]{30,}"),
    "приватный ключ": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "токен Telegram-бота": re.compile(r"\b\d{8,10}:[A-Za-z0-9_-]{30,}\b"),
    "боевой ключ платежей": re.compile(r"\b(?:sk|rk)_live_[0-9a-zA-Z]{16,}\b"),
    "ключ AWS": re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
}

# Где ключеподобные строки — это примеры и объяснения, а не настоящие ключи.
DOC_LIKE = (".md", ".example", ".sample")


@pytest.fixture(scope="module")
def tracked_files() -> list[str]:
    out = subprocess.run(
        ["git", "ls-files"], cwd=REPO, capture_output=True, text=True, encoding="utf-8", check=True,
    )
    return [line.strip() for line in out.stdout.splitlines() if line.strip()]


def test_секретных_файлов_нет_под_контролем_версий(tracked_files):
    guilty = [
        f for f in tracked_files
        if not f.endswith(TEMPLATE_SUFFIXES)
        and (FORBIDDEN_NAMES.search(f) or f.endswith(FORBIDDEN_SUFFIXES))
    ]
    assert not guilty, (
        f"Эти файлы попали под контроль версий: {guilty}. Убери их из индекса и смени ключи — "
        "то, что один раз уехало в историю, оттуда уже не вычистить."
    )


def test_в_отслеживаемых_файлах_нет_ключей(tracked_files):
    found: list[str] = []
    for rel in tracked_files:
        path = REPO / rel
        if path.suffix.lower() in DOC_LIKE or not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for label, pattern in SECRET_PATTERNS.items():
            if pattern.search(text):
                found.append(f"{rel} → {label}")
    assert not found, (
        f"Похоже на настоящие ключи в файлах репозитория: {found}. "
        "Если это пример — вынеси его в файл с расширением .example или .md."
    )


def test_gitignore_продолжает_закрывать_секреты():
    """Строку из .gitignore легко удалить при слиянии веток — тогда секрет уедет молча."""
    # Сравниваем ЦЕЛЫМИ строками: подстрока «.env» находится и внутри «.env.*», поэтому
    # удаление отдельного правила проходило бы незамеченным.
    rules = {
        line.strip().lstrip("/")
        for line in (REPO / ".gitignore").read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.strip().startswith("#")
    }
    must_cover = [".env", "local.properties", "google-services.json", "*.jks", "*.keystore"]

    lost = [rule for rule in must_cover
            if not any(r == rule or r.endswith("/" + rule) for r in rules)]
    assert not lost, (
        f".gitignore больше не закрывает: {lost}. Верни строки — иначе следующий коммит "
        "утащит ключи, и никто этого не заметит."
    )
