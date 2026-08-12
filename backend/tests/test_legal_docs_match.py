# -*- coding: utf-8 -*-
"""Политика и оферта в приложении обязаны совпадать с теми, что на сайте.

Зачем сторож. Юридические тексты живут в ДВУХ местах: `web/components/legal-content.ts`
(сайт yulbash.ru, туда же ведёт ссылка из Android-приложения) и `webapp/src/legal.ts`
(PWA-фронт, показывает текст у себя, чтобы работать офлайн). Второй файл в шапке честно
называет себя «зеркалом» первого — но зеркалом он был ровно один день.

Что нашли 2026-08-08: копия в PWA отстала на полтора месяца. Политика была вдвое короче
и не упоминала половину того, что сервис реально собирает (документы таксиста и курьера,
переписку, посылки, контакты доверенных близких, лист ожидания, токены пуш-уведомлений),
а в оферте не хватало СЕМИ разделов, включая ответственность и правила поведения. Человек,
открывший PWA, соглашался бы с документом, который занижает сбор его данных, — это прямо
про ст. 9 152-ФЗ (согласие должно быть информированным).

Копию починили руками. Но копия, которую никто не сверяет, разъедется снова в первый же
день — поэтому сверку делает не человек, а этот тест: заголовки разделов и сами абзацы
обоих документов должны совпадать буква в букву. Расхождение = красный тест с указанием,
где именно тексты разошлись.

Тест НАМЕРЕННО живёт в бэкенд-наборе: он единственный, что гоняется на каждом PR и умеет
читать любой файл репозитория. Проверяемый код на Python не похож — это нормально.
"""
import pathlib
import re

import pytest

_ROOT = pathlib.Path(__file__).resolve().parents[2]
_SITE = _ROOT / "web" / "components" / "legal-content.ts"
_PWA = _ROOT / "webapp" / "src" / "legal.ts"


def _doc(src: str, name: str) -> str:
    """Исходник объекта документа (`export const PRIVACY … };`) как текст."""
    i = src.index(f"export const {name}")
    return src[i:src.index("\n};", i)]


def _headings(block: str) -> list:
    return re.findall(r'h: "([^"]+)"', block)


def _paragraphs(block: str) -> list:
    """Все строковые абзацы документа: и вводные, и внутри разделов.

    Берём строки целиком (`"…"` на своей строке) — так сравнение не зависит от того,
    как именно разложены переносы и отступы в двух разных проектах.
    """
    out = []
    for line in block.splitlines():
        s = line.strip()
        if s.startswith('"') and s.endswith(('",', '"')):
            out.append(s.rstrip(",").strip('"'))
    return out


@pytest.mark.parametrize("name", ["PRIVACY", "TERMS"])
def test_pwa_legal_text_matches_the_site(name):
    """Разделы и абзацы копии в PWA совпадают с сайтом слово в слово."""
    if not _SITE.exists() or not _PWA.exists():
        pytest.skip("нет одного из файлов — тест для полного репозитория")

    site = _doc(_SITE.read_text(encoding="utf-8"), name)
    pwa = _doc(_PWA.read_text(encoding="utf-8"), name)

    site_h, pwa_h = _headings(site), _headings(pwa)
    assert site_h == pwa_h, (
        f"{name}: разделы разошлись.\n"
        f"  только на сайте: {[h for h in site_h if h not in pwa_h]}\n"
        f"  только в PWA:    {[h for h in pwa_h if h not in site_h]}\n"
        "Копия в webapp/src/legal.ts обязана повторять web/components/legal-content.ts."
    )

    # Заголовки документа (`titleRu`/`titleBa` против `title: {ru, ba}`) намеренно не
    # сравниваем: у двух проектов разная форма типа. Сверяем то, с чем человек соглашается.
    site_p = [p for p in _paragraphs(site) if p not in site_h]
    pwa_p = [p for p in _paragraphs(pwa) if p not in pwa_h]
    missing = [p for p in site_p if p not in pwa_p]
    extra = [p for p in pwa_p if p not in site_p]
    assert not missing and not extra, (
        f"{name}: текст разошёлся.\n"
        + ("  ЕСТЬ НА САЙТЕ, НЕТ В PWA (человек не увидит, на что соглашается):\n"
           + "".join(f"    • {p[:160]}…\n" for p in missing) if missing else "")
        + ("  ЕСТЬ В PWA, НЕТ НА САЙТЕ (обещание, которого нет в актуальном документе):\n"
           + "".join(f"    • {p[:160]}…\n" for p in extra) if extra else "")
    )


def test_pwa_and_site_agree_on_the_operator():
    """Кто оператор персональных данных и берёт ли сервис комиссию — одинаково в обоих местах.

    Абзац об операторе меняется чаще всего (появится ИП — поменяется снова), и именно он
    отвечает на вопрос «кому я доверяю свои данные и кто отвечает по 152-ФЗ»."""
    if not _SITE.exists() or not _PWA.exists():
        pytest.skip("нет одного из файлов — тест для полного репозитория")

    def operator(src: str) -> str:
        i = src.index("const OPERATOR")
        return re.sub(r"\s+", " ", src[i:src.index(";", i)])

    assert operator(_SITE.read_text(encoding="utf-8")) == operator(_PWA.read_text(encoding="utf-8")), (
        "Абзац об операторе на сайте и в PWA разный. Он говорит человеку, кто отвечает "
        "за его данные и сколько берёт сервис, — расхождение недопустимо."
    )
