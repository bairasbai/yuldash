#!/usr/bin/env bash
# Слепок «неразрешённых имён» для экранов на Compose.
#
# Собрать экраны здесь нечем (Compose лежит на закрытом dl.google.com), но РАЗОБРАТЬ их
# компилятор может. Ошибок «unresolved reference» при этом десятки тысяч — все имена Compose.
# Идея: снять эталонный НАБОР таких имён до правок и сравнить после. Новое имя = кандидат
# на ошибку: опечатка, несуществующая функция, поле DTO, которого не завели.
#
# ⚠️ ОГРАНИЧЕНИЕ, проверенное на практике. Детектор НЕ отличает опечатку от валидного API
# Compose, применённого в проекте ВПЕРВЫЕ: и то и другое для него — новое неразрешённое имя.
# Так 2026-07-30 он выдал `scaleOut` — настоящий androidx.compose.animation.scaleOut,
# корректно импортированный, просто раньше не использовавшийся. Поэтому на каждое новое имя
# нужно посмотреть глазами: есть ли импорт, существует ли API, верна ли сигнатура. Если всё
# верно — пересними эталон (`save`). Инструмент сужает поиск, но не выносит вердикт.
#
#   bash tools/ktbaseline.sh save     # снять эталон (до правок)
#   bash tools/ktbaseline.sh check    # сравнить с эталоном (после правок)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/android/app/src/main/java/com/yuldash/app"
W="${KTBUILD_DIR:-${TMPDIR:-/tmp}/yuldash-ktbuild}"
BASE="$ROOT/tools/.unresolved-baseline.txt"
MODE="${1:-check}"

[ -x "$W/kotlinc/bin/kotlinc" ] || { echo "нет окружения — сначала: bash tools/compile-data-layer.sh"; exit 1; }
cp "$ROOT/tools/stubs/"*.kt "$W/stubs/"

cd "$W"
JAVA_TOOL_OPTIONS="" ./kotlinc/bin/kotlinc \
  -cp "$(ls libs/*.jar | tr '\n' ':')" -jvm-target 17 -nowarn -d out-baseline \
  $(find "$SRC" -name "*.kt") stubs/*.kt 2>&1 | grep -v "^Picked up" > baseline-raw.log || true

# Ошибки разбора недопустимы в любом случае — они означают сломанный файл.
if grep -qi "syntax error\|Expecting\|Unexpected token" baseline-raw.log; then
  echo "✗ ОШИБКИ РАЗБОРА (файл сломан):"
  grep -i "syntax error\|Expecting\|Unexpected token" baseline-raw.log | head -20
  exit 1
fi

grep -o "unresolved reference '[^']*'" baseline-raw.log | sed "s/.*'\(.*\)'/\1/" | sort -u > now.txt
echo "неразрешённых имён: $(wc -l < now.txt) (ожидаемо — это Compose и androidx)"

if [ "$MODE" = "save" ]; then
  cp now.txt "$BASE"
  echo "✓ эталон снят: $BASE"
  exit 0
fi

[ -f "$BASE" ] || { echo "эталона нет — сначала: bash tools/ktbaseline.sh save"; exit 1; }
NEW=$(comm -13 "$BASE" now.txt || true)
if [ -n "$NEW" ]; then
  echo "✗ ПОЯВИЛИСЬ НОВЫЕ неразрешённые имена — скорее всего ссылка на то, чего нет:"
  echo "$NEW" | head -30
  echo "--- где именно ---"
  for n in $(echo "$NEW" | head -8); do grep -n "unresolved reference '$n'" baseline-raw.log | head -2; done
  exit 1
fi
echo "✓ новых неразрешённых имён нет"
