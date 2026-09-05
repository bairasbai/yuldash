#!/usr/bin/env python3
"""Разовый пересчёт `DriverProfile.rating` водительскими оценками (волна 194).

Зачем. До волны 194 это поле переписывалось ОБЩИМ баллом человека — в него шли и оценки,
полученные им как пассажиром такси или попутчиком. По этому же числу matcher решает, кому
предложить заказ (`instant_service._score`: ниже 4.6 → штраф, водитель уходит в конец
очереди офферов). То есть таксист, которого разок отругали как пассажира, тихо терял заказы.

Код починен, но в базе остались СТАРЫЕ значения: они перепишутся сами только когда водителя
в следующий раз оценят за рулём. Тот, кого штраф уже отправил в конец очереди, заказов почти
не получает — и оценить его некому. Замкнутый круг разрывает этот скрипт.

Что делает: для каждого DriverProfile считает `services.driver_rating` (только поездки,
где человек был за рулём — такси и попутка) и пишет результат. Нет ни одной водительской
оценки → нейтральный сид 5.0, как в щите рейтинга. Витрины не касается: публичный балл
человека считается отдельно (`services.user_rating`) и остаётся общим по всем ролям.

Идемпотентен: второй запуск ничего не меняет.

Запуск (из каталога backend/):
    python -m scripts.recompute_driver_ratings            # только показать, что изменится
    python -m scripts.recompute_driver_ratings --apply    # записать в базу
"""
from __future__ import annotations

import argparse

from sqlmodel import Session, select

from app.db import engine
from app.models import DriverProfile
from app.services import driver_rating

SEED = 5.0          # нет водительских оценок → нейтральный сид (как в щите рейтинга)


def main() -> int:
    ap = argparse.ArgumentParser(description="Пересчёт DriverProfile.rating (волна 194)")
    ap.add_argument("--apply", action="store_true", help="записать в базу (без флага — только показать)")
    args = ap.parse_args()

    changed = 0
    with Session(engine) as s:
        profiles = list(s.exec(select(DriverProfile)).all())
        for p in profiles:
            avg, cnt = driver_rating(s, p.user_id)
            new = round(avg, 1) if cnt > 0 else SEED
            if abs(new - p.rating) < 0.05:
                continue
            changed += 1
            print(f"  user={p.user_id}: {p.rating} -> {new}  (водительских оценок: {cnt})")
            if args.apply:
                p.rating = new
                s.add(p)
        if args.apply:
            s.commit()

    print(f"\nПрофилей всего: {len(profiles)}, поменялось: {changed}")
    if not args.apply and changed:
        print("Это была примерка. Записать — тот же запуск с --apply.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
