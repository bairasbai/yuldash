"""Подмена чужого оплаченного объявления не оставляла следа (волна 166).

Соседняя кнопка «снять с эфира» пишет в журнал, кто и когда её нажал. А ручка правки — та,
что переписывает у чужого оплаченного объявления **заголовок, текст, ссылку перехода, картинку,
тариф и срок**, — молчала.

Разница видна на живой ситуации. Кафе платит за рекламу, в ленте района висит «Обеды
по-домашнему». Кто-то с админским доступом меняет её на «Займы под 0%, пиши в личку» и подменяет
ссылку. Реклама продолжает идти — от имени кафе, за деньги кафе. Потом приходит разбираться
владелец, а ответить нечего: в журнале пусто, в общем логе только «POST /admin/ads/7 → 200»,
без имени.

Пока админ один, это незаметно. Но модерация водителей ручная, помощник рано или поздно
появится — и вся прошлая история окажется без авторства задним числом. Именно поэтому в проекте
и заведён журнал админских действий (волна 2026-08-06).

Сторож ниже проверяет весь класс, а не одну ручку: каждая пишущая админская дверь обязана
оставлять след. Раньше такой проверки не было, поэтому дыра и прожила незамеченной.

**Проверено пробой и оказалось чистым:** токен разжалованного админа админские двери не
открывает — роль читается из базы на каждом запросе, а не из токена.
"""
from __future__ import annotations

import logging
import re
from pathlib import Path

import pytest
from sqlmodel import Session

from app.db import engine
from app.models import Ad, User, UserRole

КОРЕНЬ = Path(__file__).resolve().parents[1] / "app" / "routers"


@pytest.fixture
def оплаченное_объявление(client, user_factory):
    админ = user_factory("СледАдмин", role=UserRole.admin)
    владелец = user_factory("СледВладелец")
    with Session(engine) as s:
        ad = Ad(owner_id=владелец["id"], title="Кафе Айгуль", text="Обеды по-домашнему",
                status="active", plan="standard", priority=1)
        s.add(ad)
        s.commit()
        s.refresh(ad)
        ad_id = ad.id
    return админ, владелец, ad_id


def _подмена(client, админ, ad_id: int):
    return client.post(f"/admin/ads/{ad_id}", headers=админ["auth"], json={
        "partner_name": "Другой", "partner_contact": "", "title": "БЫСТРЫЕ ДЕНЬГИ",
        "text": "Займы под 0%, пиши в личку", "button": "Хочу",
        "erid": "", "placements": "feed", "cities": "Баймак",
        "target": "https://example.com", "image_url": "", "priority": 9,
        "plan": "premium",
    })


def test_подмена_объявления_оставляет_след(client, оплаченное_объявление, caplog):
    """Главное: у любого изменения чужой рекламы должен быть автор."""
    админ, _, ad_id = оплаченное_объявление

    with caplog.at_level(logging.INFO, logger="yuldash"):
        ответ = _подмена(client, админ, ad_id)

    assert ответ.status_code == 200, f"правка не прошла: {ответ.text[:150]}"
    следы = [r.message for r in caplog.records if "[ADMIN]" in r.message]
    assert any("ads" in с for с in следы), (
        f"текст, ссылку и тариф чужого оплаченного объявления переписали без следа: {следы}. "
        "Придёт владелец разбираться — ответить будет нечем"
    )


def test_в_следе_есть_кто_именно(client, оплаченное_объявление, caplog):
    """След без имени бесполезен: вопрос «кто это сделал» должен получить ответ."""
    админ, _, ad_id = оплаченное_объявление

    with caplog.at_level(logging.INFO, logger="yuldash"):
        _подмена(client, админ, ad_id)

    следы = [r.message for r in caplog.records if "[ADMIN]" in r.message and "ads" in r.message]
    assert следы and f"admin={админ['id']}" in следы[0], (
        f"в следе не видно, кто именно правил: {следы}"
    )


def test_в_следе_нет_содержимого_рекламы(client, оплаченное_объявление, caplog):
    """Обратная сторона: журнал остаётся безопасным — идентификаторы, не тексты (§8)."""
    админ, _, ad_id = оплаченное_объявление

    with caplog.at_level(logging.INFO, logger="yuldash"):
        _подмена(client, админ, ad_id)

    следы = " ".join(r.message for r in caplog.records if "[ADMIN]" in r.message)
    assert "Займы" not in следы and "example.com" not in следы, (
        f"в журнал уехало содержимое объявления: {следы[:200]}"
    )


def _молчуны_в(текст: str, имя_файла: str = "образец.py") -> list:
    """Пишущие админские ручки без следа. Ищем по признаку, а не по списку известных имён."""
    найдено = []
    for кусок in re.split(r"\n@router\.", текст):
        первая = кусок.split("\n", 1)[0]
        if not re.match(r'(post|delete|patch|put)\("/admin/', первая):
            continue
        if "admin_action(" in кусок:
            continue
        имя = re.search(r"def (\w+)", кусок)
        найдено.append(f"{имя_файла}:{имя.group(1) if имя else '?'}")
    return найдено


def test_каждая_пишущая_админская_дверь_оставляет_след():
    """Сторож на класс: одна забытая дверь — и история без авторства задним числом.

    Любая ручка, которая меняет данные и живёт по адресу `/admin/…`, обязана звать журнал.
    Именно отсутствие такой проверки позволило подмене объявления прожить незамеченной.
    """
    молчуны = []
    for файл in sorted(КОРЕНЬ.glob("*.py")):
        молчуны += _молчуны_в(файл.read_text(encoding="utf-8"), файл.name)

    assert not молчуны, (
        f"пишущие админские ручки без следа: {молчуны}. Пока админ один, это незаметно, "
        "но помощник рано или поздно появится — и вся прошлая история окажется без авторства"
    )


def test_сторож_действительно_видит_молчуна():
    """Проверка самого сторожа: слепой сторож зелёный ровно так же, как исправный.

    Даём ему заведомо дырявый кусок кода и заведомо честный. Без этой проверки поломка
    сторожа выглядит как «всё хорошо» — он просто перестаёт находить что бы то ни было.
    """
    дырявый = '''
@router.post("/admin/things/{tid}")
def admin_update_thing(tid: int, user: User = Depends(current_user)):
    thing.title = "новое"
    session.commit()
    return thing
'''
    честный = '''
@router.post("/admin/things/{tid}")
def admin_update_thing(tid: int, user: User = Depends(current_user)):
    thing.title = "новое"
    session.commit()
    admin_action(user.id, "thing.update", tid=tid)
    return thing
'''
    читающий = '''
@router.get("/admin/things")
def admin_list_things(user: User = Depends(current_user)):
    return session.exec(select(Thing)).all()
'''

    assert _молчуны_в(дырявый), "сторож не видит пишущую админскую ручку без следа"
    assert not _молчуны_в(честный), "сторож ругается на ручку, которая след оставляет"
    assert not _молчуны_в(читающий), "сторож требует след от обычного чтения списка"


def test_разжалованный_админ_дверей_не_открывает(client, user_factory):
    """Проверено пробой: роль читается из базы, а не из токена."""
    бывший = user_factory("БывшийАдмин", role=UserRole.admin)
    with Session(engine) as s:
        u = s.get(User, бывший["id"])
        u.role = UserRole.passenger
        s.add(u)
        s.commit()

    ответ = client.get("/admin/payments/pending", headers=бывший["auth"])

    assert ответ.status_code == 403, (
        f"старый токен админа продолжает работать после разжалования (ответ {ответ.status_code})"
    )
