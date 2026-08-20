"""Кнопка «защитить от оценки-мести» работала только в попутке — и оставляла клевету на витрине.

«Щит рейтинга» — это когда админ разобрал спор, признал одну сторону виноватой и снял с честного
человека оценку, поставленную в отместку. Три находки роя об одном: кнопка обещала больше, чем
делала (аудит 2026-08-08, волна 146).

**Щит не работал в такси и доставке.** Спор в Юлдаше живёт на трёх видах поездок — попутка,
такси, посылка. Щит искал оценки только по попутке. В такси и доставке админ нажимал «защитить»,
получал «готово» — и единица-месть так и висела на честном человеке. Никто ему об этом не говорил.

**Звёзды снимались, слова оставались.** Щит убирал оценку из среднего, но текст на публичной
странице водителя не трогал. Получалось: «оценок нет» — и прямо под этим «этот водитель ворует
и хамит», любому, даже без входа в приложение. Разбор признал отзыв клеветой; значит клеветы
на витрине быть не должно.

**Витрина отдавала поимённый список пассажиров.** Оценка в коде объявлена анонимной, но
опубликованный текст нёс полное имя автора из профиля — и посторонний по одной ссылке собирал
список тех, кто с этим водителем ездит. В районе, где все друг друга знают, это готовый ответ
на вопрос «с кем она ездит».

Полностью прятать автора не стали: отзыв без человека читается как накрутка, а доверие в Юлдаше
и держится на том, что за словами стоит сосед. Инициал — середина: сосед узнаётся, список
посторонним не собирается.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import Booking, Incident, InstantOrder, ParcelDelivery, Rating, UserRole
from app.safety_logic import _exclude_linked_ratings
from app.services import short_name

from test_api import _ride


def _поездка(session, поле: str, автор: int, кому: int) -> int:
    """Настоящая поездка нужного вида — оценку не привязать к выдуманному номеру."""
    if поле == "order_id":
        o = InstantOrder(passenger_id=автор, driver_id=кому, status="done")
        session.add(o)
    elif поле == "parcel_id":
        o = ParcelDelivery(sender_id=автор, courier_id=кому, status="delivered")
        session.add(o)
    else:
        from app.models import Ride
        поездка = session.exec(
            select(Ride).where(Ride.driver_id == кому).order_by(Ride.id.desc())).first()
        assert поездка is not None, "у водителя нет поездки — не к чему привязать бронь"
        o = Booking(ride_id=поездка.id, passenger_id=автор, seats=1)
        session.add(o)
    session.commit()
    session.refresh(o)
    return o.id


def _оценка(session, *, поле: str, значение: int, автор: int, кому: int,
            текст: str = "ворует и хамит") -> int:
    """Оценка-месть с текстом: одна звезда и слова, которые прочитает весь район."""
    r = Rating(rater_id=автор, ratee_id=кому, stars=1, text=текст, text_published=True,
               **{поле: значение})
    session.add(r)
    session.commit()
    session.refresh(r)
    return r.id


@pytest.mark.parametrize("вид,поле", [
    ("попутка", "booking_id"),
    ("такси", "order_id"),
    ("доставка", "parcel_id"),
])
def test_щит_снимает_месть_во_всех_видах_поездок(client, user_factory, вид: str, поле: str):
    """Главное: кнопка «защитить» должна работать везде, где живёт спор."""
    честный = user_factory(f"Щит{вид}Честный", role=UserRole.driver)
    обидчик = user_factory(f"Щит{вид}Обидчик")
    if поле == "booking_id":
        _ride(client, честный, comment=f"щит {вид}")

    with Session(engine) as s:
        pid = _поездка(s, поле, обидчик["id"], честный["id"])
        rid = _оценка(s, поле=поле, значение=pid, автор=обидчик["id"], кому=честный["id"])
        inc = Incident(reporter_id=обидчик["id"], respondent_id=честный["id"],
                       type="rude", status="resolved", **{поле: pid})
        s.add(inc)
        s.commit()
        s.refresh(inc)
        _exclude_linked_ratings(s, inc)
        s.commit()
        оценка = s.get(Rating, rid)

    assert оценка.excluded is True, (
        f"в разделе «{вид}» админ нажал «защитить», получил «готово», а единица-месть осталась "
        "висеть на честном человеке — и никто ему об этом не сказал"
    )


@pytest.mark.parametrize("поле", ["booking_id", "order_id", "parcel_id"])
def test_щит_убирает_и_текст_клеветы(client, user_factory, поле: str):
    """Иначе получается «оценок нет» и прямо под этим — «ворует и хамит»."""
    честный = user_factory(f"Клевета{поле}", role=UserRole.driver)
    обидчик = user_factory(f"Клеветник{поле}")
    if поле == "booking_id":
        _ride(client, честный, comment="щит клевета")

    with Session(engine) as s:
        pid = _поездка(s, поле, обидчик["id"], честный["id"])
        rid = _оценка(s, поле=поле, значение=pid, автор=обидчик["id"], кому=честный["id"])
        inc = Incident(reporter_id=обидчик["id"], respondent_id=честный["id"],
                       type="rude", status="resolved", **{поле: pid})
        s.add(inc)
        s.commit()
        s.refresh(inc)
        _exclude_linked_ratings(s, inc)
        s.commit()
        оценка = s.get(Rating, rid)

    assert оценка.text_published is False, (
        "разбор признал отзыв клеветой, а слова остались на публичной странице водителя — "
        "их читает любой, даже без входа в приложение"
    )


def test_снятый_отзыв_исчезает_с_публичной_страницы(client, user_factory):
    """Проверяем не поле в базе, а то, что видит человек, открывший страницу водителя."""
    честный = user_factory("ВитринаЧестный", role=UserRole.driver)
    обидчик = user_factory("ВитринаОбидчик")
    _ride(client, честный, comment="чтобы профиль существовал")
    with Session(engine) as s:
        pid = _поездка(s, "booking_id", обидчик["id"], честный["id"])
        rid = _оценка(s, поле="booking_id", значение=pid, автор=обидчик["id"],
                      кому=честный["id"], текст="этот водитель ворует и хамит")

    до = client.get(f"/drivers/{честный['id']}/public")
    with Session(engine) as s:
        оценка = s.get(Rating, rid)
        оценка.excluded = True
        s.add(оценка)
        s.commit()
    после = client.get(f"/drivers/{честный['id']}/public")

    assert "ворует" in до.text, "проверять нечего — отзыв не попал на витрину и до снятия"
    assert "ворует" not in после.text, (
        "снятая разбором клевета всё ещё висит на публичной странице водителя"
    )


def test_витрина_не_отдаёт_список_пассажиров(client, user_factory):
    """Посторонний без входа не должен собирать «кто с этим водителем ездит»."""
    водитель = user_factory("СписокВодитель", role=UserRole.driver)
    пассажирка = user_factory("Гульнара Ахметова")
    _ride(client, водитель, comment="для профиля")
    with Session(engine) as s:
        pid = _поездка(s, "booking_id", пассажирка["id"], водитель["id"])
        _оценка(s, поле="booking_id", значение=pid, автор=пассажирка["id"],
                кому=водитель["id"], текст="спасибо, довёз вовремя")

    гостю = client.get(f"/drivers/{водитель['id']}/public")

    assert гостю.status_code == 200, гостю.text
    assert "Ахметова" not in гостю.text, (
        f"фамилия автора отзыва видна постороннему без входа: {гостю.text[:200]}. "
        "По одной ссылке собирается список тех, кто с этим водителем ездит"
    )
    assert "Гульнара" in гостю.text, (
        "автор скрыт полностью — отзыв стал читаться как накрутка, а доверие держится на том, "
        "что за словами стоит сосед"
    )


def test_щит_не_трогает_оценки_посторонних(client, user_factory):
    """Обратная сторона: разбор про двоих, а не про всю поездку.

    Если щит начнёт снимать все оценки по этой поездке, пострадают попутчики, которые никого
    не оговаривали, — а их честные слова исчезнут вместе с клеветой.
    """
    водитель = user_factory("ЩитВодитель", role=UserRole.driver)
    обидчик = user_factory("ЩитОбидчик")
    посторонний = user_factory("ЩитПопутчик")
    _ride(client, водитель, comment="щит посторонние")

    with Session(engine) as s:
        pid = _поездка(s, "booking_id", обидчик["id"], водитель["id"])
        чужая = _оценка(s, поле="booking_id", значение=pid, автор=посторонний["id"],
                        кому=водитель["id"], текст="хороший водитель, довёз вовремя")
        _оценка(s, поле="booking_id", значение=pid, автор=обидчик["id"], кому=водитель["id"])
        inc = Incident(reporter_id=обидчик["id"], respondent_id=водитель["id"],
                       type="rude", status="resolved", booking_id=pid)
        s.add(inc)
        s.commit()
        s.refresh(inc)
        _exclude_linked_ratings(s, inc)
        s.commit()
        осталась = s.get(Rating, чужая)

    assert осталась.excluded is False, (
        "щит снял оценку человека, который к спору отношения не имеет: его честные слова "
        "исчезли за компанию с клеветой"
    )
    assert осталась.text_published is True, "у постороннего сняли текст вместе со звёздами"


def test_имя_с_инициалом_читается_как_человек():
    """Обратная сторона: сосед должен узнаваться."""
    assert short_name("Гульнара Ахметова") == "Гульнара А."
    assert short_name("Рустам") == "Рустам", "одно имя — не за что прятать"
    assert short_name("") == "Аноним", "без имени показываем нейтральное, а не пустоту"


def test_щит_ищет_оценки_по_всем_видам_поездок():
    """Сторож: спор живёт на трёх идентификаторах, и щит обязан знать про все три.

    Дыра была именно в этом: код смотрел на одно поле из трёх, а рядом в модели лежали
    остальные. Появится четвёртый вид поездки — этот тест напомнит.
    """
    from pathlib import Path

    src = (Path(__file__).resolve().parents[1] / "app" / "safety_logic.py").read_text(
        encoding="utf-8")
    блок = src[src.index("def _exclude_linked_ratings"):]
    блок = блок[: блок.index("\ndef ")]

    for поле in ("booking_id", "order_id", "parcel_id"):
        assert поле in блок, (
            f"щит не знает про {поле}: в этом разделе кнопка «защитить» будет отвечать "
            "«готово» и не делать ничего"
        )
