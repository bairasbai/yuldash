"""«Магнитогорск» в башкирской подсказке был «Магнит» (приёмка 18.10).

Черновой башкирский из сида обрезал название города до «Магнит» — в фильтрах «Откуда/Куда» и
подсказках маршрута Сибай–Магнитогорск человек видел магазин, а не город. По-башкирски город
так и пишется — «Магнитогорск»; пустое башкирское имя клиент показывает русским. Сид сам по себе
заполняет только ПУСТОЕ имя, поэтому уже записанный «Магнит» снимается отдельным правилом —
точечно: правку админа (любое другое значение) не трогаем.
"""
from sqlmodel import Session, select

from app import geo
from app.db import engine
from app.models import Settlement


def _magnitogorsk(s: Session) -> Settlement:
    return s.exec(select(Settlement).where(Settlement.name_ru == "Магнитогорск")).one()


def test_seed_drops_the_truncated_draft_name(client):
    with Session(engine) as s:
        row = _magnitogorsk(s)
        row.name_ba = "Магнит"
        s.add(row)
        s.commit()
        geo.seed_settlements(s)
        assert not (_magnitogorsk(s).name_ba or "").strip()


def test_seed_keeps_an_admin_edit(client):
    with Session(engine) as s:
        row = _magnitogorsk(s)
        row.name_ba = "Магнитогорск"
        s.add(row)
        s.commit()
        geo.seed_settlements(s)
        assert _magnitogorsk(s).name_ba == "Магнитогорск"
        row = _magnitogorsk(s)
        row.name_ba = None
        s.add(row)
        s.commit()


def test_seed_table_no_longer_carries_the_truncation():
    assert all(ba != "Магнит" for _ru, ba, *_rest in geo.SETTLEMENTS_SEED)
