# -*- coding: utf-8 -*-
"""Контраст токенов Canon* по WCAG — числами, а не на глаз.

В CanonTokens.kt рядом с цветами стоят пометки «≥4.5:1». Это утверждения; здесь они
проверяются расчётом. Порог: обычный текст ≥4.5, крупный/жирный и иконки ≥3.0.

  python3 tools/contrast.py
"""
import re
import sys
from pathlib import Path

TOKENS = Path(__file__).resolve().parent.parent / "android/app/src/main/java/com/yuldash/app/CanonTokens.kt"


def parse() -> dict:
    """{имя: (светлый_argb, тёмный_argb)}. Плоский val → один цвет на обе темы."""
    src = TOKENS.read_text(encoding="utf-8")
    out = {}
    for m in re.finditer(r"internal val (Canon\w+): Color @Composable get\(\) = if \(appIsDark\(\)\) "
                         r"Color\(0x([0-9A-Fa-f]{8})\) else Color\(0x([0-9A-Fa-f]{8})\)", src):
        out[m.group(1)] = (int(m.group(3), 16), int(m.group(2), 16))
    for m in re.finditer(r"internal val (Canon\w+): Color = Color\(0x([0-9A-Fa-f]{8})\)", src):
        v = int(m.group(2), 16)
        out[m.group(1)] = (v, v)
    return out


def rgba(argb: int):
    return ((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, ((argb >> 24) & 255) / 255.0)


def over(fg: int, bg: int) -> tuple:
    """Наложить цвет с альфой на фон (иначе контраст полупрозрачных считается неверно)."""
    r, g, b, a = rgba(fg)
    br, bg_, bb, _ = rgba(bg)
    return (r * a + br * (1 - a), g * a + bg_ * (1 - a), b * a + bb * (1 - a))


def lum(c) -> float:
    def ch(v):
        v /= 255.0
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4
    return 0.2126 * ch(c[0]) + 0.7152 * ch(c[1]) + 0.0722 * ch(c[2])


def ratio(fg: int, bg: int) -> float:
    a, b = lum(over(fg, bg)), lum(over(bg, 0xFFFFFFFF))
    hi, lo = max(a, b), min(a, b)
    return (hi + 0.05) / (lo + 0.05)


WHITE = 0xFFFFFFFF
# (что, на чём, порог, зачем)
PAIRS = [
    ("CanonCourier", "CanonCourierBg", 4.5, "текст курьера на своей подложке"),
    ("CanonCourier", "CanonSurface", 4.5, "акцент курьера на карточке"),
    ("CanonTaxiText", "CanonTaxiBg", 4.5, "ГОСНОМЕР машины в бейдже — пассажир сверяет авто"),
    ("CanonText", "CanonBg", 4.5, "основной текст на фоне"),
    ("CanonText", "CanonSurface", 4.5, "основной текст на карточке"),
    ("CanonMuted", "CanonBg", 4.5, "приглушённый текст на фоне"),
    ("CanonMuted", "CanonSurface", 4.5, "приглушённый текст на карточке"),
    ("CanonMutedStrong", "CanonSurface", 4.5, "неактивные подписи навигации"),
    ("CanonGreen", "CanonBg", 4.5, "зелёный текст на фоне"),
    ("CanonGreen", "CanonSurface", 4.5, "зелёный текст на карточке"),
    ("CanonGreen2", "CanonSurface", 4.5, "акцентный текст/сумма на карточке"),
    ("CanonGreen2", "CanonMint", 4.5, "зелёный текст на мятной плашке"),
    ("CanonWarn", "CanonWarnBg", 4.5, "предупреждение на своей подложке"),
    ("CanonWarn", "CanonSurface", 4.5, "предупреждение на карточке"),
    ("CanonRed", "CanonSurface", 4.5, "ошибка на карточке"),
    ("CanonRed", "CanonDangerBg", 4.5, "ошибка на danger-подложке"),
    ("CanonWoman", "CanonWomanBg", 4.5, "«за рулём женщина»"),
    ("CanonTaxiText", "CanonTaxiBg", 4.5, "госномер и текст на подложке такси"),
    ("CanonTaxiInk", "CanonTaxi", 4.5, "текст/иконка на жёлтой кнопке такси"),
    ("CanonMuted", "CanonMint", 4.5, "подпись на мятной плашке"),
    ("CanonStar", "CanonSurface", 3.0, "звезда рейтинга на карточке"),
    ("CanonStar", "CanonBg", 3.0, "звезда рейтинга на фоне"),
    ("CanonMuted", "CanonSurface", 3.0, "пустая звезда в выборе оценки"),
]
# белый текст на цветных кнопках — порог 3.0 (крупный жирный) и 4.5 (обычный)
# Надпись кнопки — 16sp FontWeight.Black (UiKit.AppButton): крупный жирный текст, порог 3:1.
# Эту же планку заявляет комментарий к CanonGreen2 в CanonTokens.kt.
BUTTONS = [("CanonGreen2", 3.0, "белый текст на зелёной кнопке"),
           ("CanonRed", 3.0, "белый текст на красной кнопке")]
INK = [("CanonGoldInk", "CanonGold", 4.5, "тёмный текст на золотой кнопке")]


def main() -> int:
    t = parse()
    bad = []
    print(f"{'пара':52} {'светлая':>9} {'тёмная':>9}  порог")
    print("-" * 84)
    rows = [(f, b, thr, why) for f, b, thr, why in PAIRS] + [(f, b, thr, why) for f, b, thr, why in INK]
    for fg, bg, thr, why in rows:
        if fg not in t or bg not in t:
            print(f"  ! нет токена: {fg} / {bg}")
            continue
        light = ratio(t[fg][0], t[bg][0])
        dark = ratio(t[fg][1], t[bg][1])
        mark = "" if min(light, dark) >= thr else "  ✗"
        print(f"{fg + ' на ' + bg:52} {light:9.2f} {dark:9.2f}  {thr}{mark}")
        if min(light, dark) < thr:
            bad.append((f"{fg} на {bg}", why, round(light, 2), round(dark, 2), thr))
    for bgname, thr, why in BUTTONS:
        light = ratio(WHITE, t[bgname][0])
        dark = ratio(WHITE, t[bgname][1])
        mark = "" if min(light, dark) >= thr else "  ✗"
        print(f"{'белый на ' + bgname:52} {light:9.2f} {dark:9.2f}  {thr}{mark}")
        if min(light, dark) < thr:
            bad.append((f"белый на {bgname}", why, round(light, 2), round(dark, 2), thr))
    print()
    if bad:
        print(f"НЕ ПРОХОДЯТ ({len(bad)}):")
        for name, why, l, d, thr in bad:
            print(f"  · {name} — {why}: светлая {l}, тёмная {d} (нужно ≥{thr})")
        return 1
    print("✓ все проверенные пары проходят WCAG")
    return 0


if __name__ == "__main__":
    sys.exit(main())
