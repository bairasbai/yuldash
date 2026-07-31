#!/usr/bin/env bash
# Настоящая компиляция слоя данных Android БЕЗ Android SDK.
#
# Зачем: в веб-сессиях Claude Code нет SDK, а dl.google.com закрыт egress-политикой —
# значит androidx и Compose недоступны, полная сборка невозможна. НО Maven Central открыт,
# а data/*.kt от Compose не зависит. Подставляем android.jar из Robolectric + четыре крошечные
# заглушки — и получаем полноценную проверку типов самого большого и опасного файла проекта
# (ApiClient.kt: ~5000 строк, 350+ функций, 130+ DTO).
#
# Так 2026-07-27 нашлась ошибка, которую не увидели ни статический анализатор, ни пять
# читающих агентов: «*/» посреди KDoc обрывал комментарий и ронял разбор файла.
#
# Использование:  bash tools/compile-data-layer.sh [рабочая_папка]
# Требуется: JDK 17+, curl, unzip, ~1 ГБ диска. Скачивает ~270 МБ при первом запуске.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/android/app/src/main/java/com/yuldash/app"
W="${1:-${TMPDIR:-/tmp}/yuldash-ktbuild}"
KOTLIN_VERSION="2.3.0"          # держать в соответствии с android/build.gradle.kts
ANDROID_ALL="16-robolectric-13921718"
M="https://repo.maven.apache.org/maven2"

mkdir -p "$W/libs" "$W/stubs"
cd "$W"

if [ ! -x kotlinc/bin/kotlinc ]; then
  echo "→ качаю kotlinc $KOTLIN_VERSION"
  curl -sSL -o kotlinc.zip \
    "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VERSION/kotlin-compiler-$KOTLIN_VERSION.zip"
  unzip -q -o kotlinc.zip && chmod +x kotlinc/bin/*
fi

get() { [ -f "libs/$2" ] || { echo "→ качаю $2"; curl -sS -o "libs/$2" "$M/$1"; }; }
get "org/robolectric/android-all/$ANDROID_ALL/android-all-$ANDROID_ALL.jar" android-all.jar
get "org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.10.2/kotlinx-coroutines-core-jvm-1.10.2.jar" coroutines.jar
get "org/jetbrains/kotlinx/kotlinx-coroutines-android/1.10.2/kotlinx-coroutines-android-1.10.2.jar" coroutines-android.jar
get "com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar" okhttp.jar
get "com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar" okio.jar

cp "$ROOT/tools/stubs/"*.kt stubs/

echo "→ компилирую слой данных"
JAVA_TOOL_OPTIONS="" ./kotlinc/bin/kotlinc \
  -cp "$(ls libs/*.jar | tr '\n' ':')" -jvm-target 17 -nowarn -d out \
  "$SRC/data/ApiClient.kt" "$SRC/data/TripPass.kt" "$SRC/data/Analytics.kt" \
  "$SRC/data/NetworkMonitor.kt" "$SRC/data/GeocoderClient.kt" "$SRC/data/ChatSocket.kt" \
  "$SRC/data/LocationSocket.kt" "$SRC/data/MapFeedSocket.kt" \
  "$SRC/data/InstantLocationSocket.kt" "$SRC/data/TripLocationBus.kt" \
  stubs/*.kt

echo "✓ слой данных компилируется ($(find out -name '*.class' | wc -l) классов)"

# Экраны собрать нечем (нужен Compose с dl.google.com), но РАЗОБРАТЬ их компилятор может:
# ошибки вида «unresolved reference» ожидаемы, а вот синтаксические — нет.
echo "→ синтаксическая проверка всех экранов"
JAVA_TOOL_OPTIONS="" ./kotlinc/bin/kotlinc \
  -cp "$(ls libs/*.jar | tr '\n' ':')" -jvm-target 17 -nowarn -d out-syntax \
  $(find "$SRC" -name "*.kt") stubs/*.kt 2>&1 | grep -v "^Picked up" > syntax.log || true
BAD=$(grep -ci "syntax error\|Expecting\|Unexpected token" syntax.log || true)
if [ "$BAD" -gt 0 ]; then
  echo "✗ ошибки разбора:"; grep -i "syntax error\|Expecting\|Unexpected token" syntax.log | head -20; exit 1
fi
echo "✓ ошибок разбора нет во всех $(find "$SRC" -name '*.kt' | wc -l) файлах"
