"""Кого пускать в выдачу — ОДНО место на все двери (поездки и заявки).

Зачем отдельный модуль. Правило «этого водителя человеку не показывать» родилось в ленте
`GET /rides` и там же и осталось: четыре фильтра лежали приватными функциями внутри роутера
попуток. Пока дверь была одна, это работало. Потом дверей стало больше, и каждая новая
открывалась без фильтров — не по злому умыслу, а потому что правило было не видно со стороны.

Что нашла проверка (аудит 2026-08-08, волна 71). Пассажирка заблокировала водителя — в ленте
он исчез, обещание кнопки выполнено. Но ровно та же поездка приходила к ней двумя другими
путями: подбор под её заявку (`GET /match/rides`) и витрина поездок к клинике
(`GET /medical-partners/{id}/rides`). Второй путь тяжелее первого: человек едет в больницу,
он уязвим, и именно там ему предлагают сесть в машину к тому, от кого он прятался.

Поэтому фильтры переехали сюда, а роутеры зовут `visible_rides` — одну функцию. Новая ручка,
которая отдаёт поездки, теперь либо зовёт её, либо явно объясняет, почему нет.

Порядок фильтров важен: сначала убираем поездки целиком (блокировка, пауза, «только для
своих»), потом у оставшихся стираем медицинскую связку для анонима. Обратный порядок сделал
бы лишнюю работу над строками, которые всё равно уйдут.

Заявки пассажиров (волна 72) живут по тому же правилу и берут его отсюда же —
`hidden_author_ids`. Витрин у заявок две, и расходились они ровно так же: лента водителя
про паузу знала, а «заявки рядом» — нет.
"""
from __future__ import annotations

from typing import Optional

from sqlmodel import Session

from .models import RideCategory, User
from .safety_logic import suspended_user_ids
from .services import blocked_user_ids
from .trust_service import INSIDER_LEVEL, trust_level


def _f(r, key, default=None):
    """Поле у RideOut (свежий ответ) или у dict (значение из кеша) — одинаково."""
    return r[key] if isinstance(r, dict) else getattr(r, key, default)


def hide_blocked(items, user: Optional[User], session: Session):
    """Прячем из выдачи поездки заблокированных водителей (в обе стороны). Аноним → без фильтра.
    items — список RideOut (свежие) или dict (из кеша/near); оба содержат driver_id."""
    if user is None:
        return items
    blocked = blocked_user_ids(session, user.id)
    if not blocked:
        return items
    return [r for r in items if _f(r, "driver_id") not in blocked]


def hide_suspended(items, user: Optional[User], session: Session):
    """Прячем поездки водителей, которые сейчас на паузе за нарушения.

    Не про безопасность — про честность выдачи. Везти такой водитель уже не может: подтвердить
    бронь и принять цену ему закрыто (аудит 2026-08-08, волна 9). Но поездка продолжала висеть
    в ленте, пассажир её бронировал и ждал подтверждения, которого не будет. Время человека
    тратилось зря, а водитель выглядел как «не отвечает».

    СВОЮ поездку водитель видит всегда — иначе он решит, что объявление пропало, и опубликует
    заново. Тот же приём, что в `hide_trusted_only`.

    Цена запроса: ОДИН select на всю страницу (приостановленных единицы), а не проверка на
    каждого водителя — это горячая ручка, N запросов тут недопустимы. Пустой набор → выходим
    сразу, обычный случай не платит ничего.
    """
    ids = suspended_user_ids(session)
    if not ids:
        return items
    uid = user.id if user is not None else None
    return [r for r in items if _f(r, "driver_id") not in ids or _f(r, "driver_id") == uid]


def hide_trusted_only(items, user: Optional[User], session: Session):
    """Прячем поездки «только для своих» (only_trusted) от всех, кто НЕ L3.
    Аноним и L0–L2 их не видят; свой водитель видит СВОЮ поездку всегда.
    items — RideOut (свежие) или dict (кеш/near); оба содержат only_trusted + driver_id."""
    # Вычисляем уровень зрителя один раз (не в цикле).
    level = trust_level(session, user) if user is not None else 0
    if level >= INSIDER_LEVEL:
        return items
    uid = user.id if user is not None else None
    return [r for r in items if not _f(r, "only_trusted") or _f(r, "driver_id") == uid]


def hide_health_hint(items, user: Optional[User]):
    """Анониму не показываем, что поездка едет в конкретную клинику.

    «Кто и когда едет в такую-то больницу» — вывод о здоровье, а не просто маршрут. Ручка
    `/medical-partners/{id}/rides` это уже понимает и требует входа. Но та же связка спокойно
    уезжала во вторую дверь: `GET /rides` без токена отдавал `partner_id` и `category=hospital`
    вместе с именем водителя и временем выезда (аудит 2026-08-08, волна 22). Барьер входа
    отсекает поисковики и массовый сбор — ровно то, ради чего он поставлен в medical.py.

    Саму поездку не прячем: человеку без входа она видна как обычная «Баймак → Уфа»,
    и по ссылке карточка открывается. Убираем только связку с клиникой. Свою поездку
    водитель видит целиком — как и в соседних фильтрах.
    """
    if user is not None:
        return items
    out = []
    for r in items:
        hospital = _f(r, "category") in (RideCategory.hospital, "hospital")
        if not hospital and not _f(r, "partner_id"):
            out.append(r)
            continue
        patch = {"partner_id": None, "category": RideCategory.regular}
        if isinstance(r, dict):
            r = {**r, **{"partner_id": None, "category": RideCategory.regular.value}}
        else:
            r = r.model_copy(update=patch)
        out.append(r)
    return out


def visible_rides(items, user: Optional[User], session: Session):
    """Все четыре фильтра разом — то, что человек имеет право увидеть.

    Любая ручка, отдающая список поездок, зовёт ЭТО, а не отдельные фильтры: набор правил
    со временем растёт, и собирать цепочку заново в каждом роутере — способ снова забыть один
    из них (так и вышло с подбором под заявку и витриной клиники, волна 71).
    """
    out = hide_blocked(items, user, session)
    out = hide_suspended(out, user, session)
    out = hide_trusted_only(out, user, session)
    return hide_health_hint(out, user)


def hidden_author_ids(session: Session, user: Optional[User]) -> set[int]:
    """Чьи объявления этому человеку показывать нельзя — ОДНО множество на все витрины.

    Два повода, и оба уже приняты в проекте:

    * **блокировка** (в обе стороны) — обещание кнопки «заблокировать»;
    * **пауза «Справедливости» (§2)** — принять отклик такому человеку закрыто (волна 9),
      значит объявление живое только на вид: водитель поторгуется впустую, а пассажир
      будет ждать ответа, которого не будет.

    Зачем множеством, а не проверкой по одному: витрины отдают до 200 строк за раз, и
    запрос на каждого автора превратил бы горячую ленту в сотни обращений к базе.

    Аноним → пустое множество: блокировок у него нет, а паузу без входа мы и раньше
    не скрывали (объявление не опасно само по себе, оно просто не доведёт до сделки).
    """
    if user is None:
        return set()
    return set(blocked_user_ids(session, user.id)) | set(suspended_user_ids(session))
