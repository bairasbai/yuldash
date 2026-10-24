# Шрифты лендинга

Лежат здесь, а не скачиваются с Google Fonts при сборке: `next/font/google` в Turbopack изредка падал —
CSS шрифтов приходил, а шаг подстановки самих файлов шрифтов нет (CI 16.10, 17.10, 24.10.2026: «next/font/google
queries have exactly one entry»; повтор того же коммита был зелёным). Подключение — `next/font/local` в `app/layout.tsx`.

| Файл | Откуда | Что внутри |
|---|---|---|
| `Inter-latin-cyrillic.woff2` | google/fonts, `ofl/inter/Inter[opsz,wght].ttf` (Inter 4.001) | толщина 100–900 (переменный), оптический размер закреплён на 14 — как отдавал Google без `axes` |
| `Montserrat-700-900-latin-cyrillic.woff2` | google/fonts, `ofl/montserrat/Montserrat[wght].ttf` (Montserrat 9.000) | толщина 700–900 (переменный) |

Знаки: латиница, кириллица и расширенная кириллица — те же диапазоны, что у наборов Google `latin`, `cyrillic`,
`cyrillic-ext` (башкирские ҡ ғ ҙ һ ә ө ү ң — U+0460-052F), плюс «₽» (U+20BD). **Набор уже, чем отдавал Google**:
тот по мере надобности докачивал ещё `latin-ext`, греческий и вьетнамский. Текст лендинга их не использует
(сверено по каждой надписи 12 страниц), но знак вне набора нарисуется запасным шрифтом системы — например,
латинская ə (U+0259) вместо кириллической ә (U+04D9). Нужен новый знак — добавь его в `U` ниже и пересобери.

Лицензия — SIL Open Font License 1.1: `OFL-Inter.txt`, `OFL-Montserrat.txt` здесь и запись о лицензии внутри самих
файлов шрифтов (поля name 13 и 14 — их сохраняет `--name-IDs+=13,14`; по условию 2 OFL лицензия может ехать
в машиночитаемых полях файла). Так раздаваемые сайтом шрифты несут и авторов, и лицензию. Зарезервированного имени
шрифта (Reserved Font Name) в обеих лицензиях нет (первая строка файлов), поэтому урезанные файлы называются так же.

Мелочи, на сайт не влияют: внутреннее имя Montserrat осталось от исходного файла («Montserrat Thin»), у Inter
в таблице STAT осталась ось `opsz`.

## Пересобрать (fontTools ≥ 4.67, brotli)

Пересобранные файлы совпадут с этими по содержимому (`ttx`), но не побайтно: внутри отметка времени.

```sh
pip install fonttools brotli
U="U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,U+2000-206F,U+20AC,U+20BD,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD,U+0301,U+0400-045F,U+0490-0491,U+04B0-04B1,U+2116,U+0460-052F,U+1C80-1C8A,U+20B4,U+2DE0-2DFF,U+A640-A69F,U+FE2E-FE2F"
fonttools varLib.instancer "Inter[opsz,wght].ttf" opsz=14 wght=100:900 -o inter.ttf
fonttools varLib.instancer "Montserrat[wght].ttf" wght=700:900 -o montserrat.ttf
pyftsubset inter.ttf --unicodes="$U" --layout-features='*' --name-IDs+=13,14 --flavor=woff2 --output-file=Inter-latin-cyrillic.woff2
pyftsubset montserrat.ttf --unicodes="$U" --layout-features='*' --name-IDs+=13,14 --flavor=woff2 --output-file=Montserrat-700-900-latin-cyrillic.woff2
```
