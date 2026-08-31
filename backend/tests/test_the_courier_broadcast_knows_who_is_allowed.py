"""Рассылка курьерам знала про наказания, но не про ДОПУСК (волна 222).

Вторая половина того, что волна 221 починила у такси. Волна 61 донесла до рассылки
«новая доставка рядом» все три НАКАЗАНИЯ курьера: паузу «Справедливости», мягкую паузу
по качеству и долг по комиссии. А полный гейт курьера (`routers/courier._guard_courier`)
проверяет ещё две вещи, и рассылка о них не знала:

  * **заявка одобрена** — `CourierApplication.status == "approved"`;
  * **фотоконтроль машины** (`carphoto.blocked(..., COURIER)`, 580-ФЗ) — последняя ступень
    мягкой лестницы: первую неделю просрочки человек только получает напоминания, дальше
    работать нельзя.

Разница с наказаниями та же, что у такси: наказание накладываем мы, а допуск отваливается
САМ. Фотоконтроль просрочивается по календарю; заявку модератор может отозвать в любой
момент. Присутствие на линии (`CourierProfile.online`) живёт своим сроком — человек
остаётся «на линии».

Что происходило. Бабушке из Сибая нужно лекарство. Заявка создаётся, и рассылка шлёт пуш
«Новая доставка рядом 📦 Баймак → Сибай» тому, кому мы сами закрыли работу. Он открывает
«Курьер», жмёт взять — 403 «покажи машину». Для него это выглядит как поломка приложения,
а бабушкина заявка тем временем ждёт того, кто действительно может приехать.

Собственное описание гейта курьера прямо говорит: фотоконтроль «срабатывает только на
последней ступени лестницы: первую неделю просрочки человек получает напоминания
и ПАДАЕТ В ПОДБОРЕ». В подборе он не падал — рассылка про фотоконтроль не спрашивала.
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app import carphoto as cp_mod
from app.config import settings
from app.db import engine
from app.models import (CarPhotoCheck, CourierApplication, CourierProfile, ParcelDelivery,
                        UserRole)
from app.routers.parcels import _notify_couriers_new_parcel
from app.timeutil import utcnow
from conftest import upload_doc


@pytest.fixture
def режим_курьера(monkeypatch):
    monkeypatch.setattr(settings, "courier_enabled", True, raising=False)
    # Зона и география — отдельное правило и отдельная волна; здесь проверяем ТОЛЬКО допуск.
    monkeypatch.setattr("app.geo.zone_allows", lambda *a, **k: True)
    yield


@pytest.fixture
def кого_позвали(monkeypatch):
    """Кому реально ушёл пуш. Ловим уведомление, а не отправку в FCM: без устройства
    push не уходит, и тест был бы зелёным на пустой рассылке."""
    позвали: list = []
    monkeypatch.setattr("app.routers.parcels.push_notification",
                        lambda session, uid, *a, **k: позвали.append(uid))
    return позвали


def _одобренный_курьер(client, user_factory, метка):
    u = user_factory(метка, role=UserRole.passenger)
    селфи = upload_doc(client, u["auth"])
    assert client.post("/courier/apply", headers=u["auth"], json={
        "transport": "car", "full_name": "Курьер Курьеров", "car_plate": "А111АА102",
        "selfie_url": селфи, "rules_accepted": True,
    }).status_code == 200
    with Session(engine) as s:
        row = s.exec(select(CourierApplication).where(
            CourierApplication.user_id == u["id"])).first()
        row.status = "approved"
        row.reviewed_at = utcnow()
        s.add(row)
        s.commit()
    return u


def _на_линии(user_id: int) -> None:
    """Курьер числится на линии — как если бы вышел до того, как допуск отвалился."""
    with Session(engine) as s:
        prof = s.exec(select(CourierProfile).where(CourierProfile.user_id == user_id)).first()
        if prof is None:
            prof = CourierProfile(user_id=user_id)
        prof.online = True
        prof.zone = "region"
        prof.work_regions = True
        prof.work_intercity = True
        s.add(prof)
        s.commit()


def _отозвать_заявку(user_id: int) -> None:
    with Session(engine) as s:
        row = s.exec(select(CourierApplication).where(
            CourierApplication.user_id == user_id)).first()
        row.status = "rejected"
        s.add(row)
        s.commit()


def _не_показал_машину(user_id: int, дней_просрочки: int) -> None:
    with Session(engine) as s:
        s.add(CarPhotoCheck(
            user_id=user_id, mode=cp_mod.COURIER, kind=cp_mod.PERIODIC,
            status=cp_mod.WAITING,
            due_at=utcnow() - timedelta(days=дней_просрочки),
        ))
        s.commit()


def _разослать(sender_id: int) -> None:
    """Бабушке из Сибая нужно лекарство — заявка на курьерскую доставку."""
    with Session(engine) as s:
        p = ParcelDelivery(sender_id=sender_id, from_city="Баймак", to_city="Сибай",
                           size="small", description="лекарство", receiver_name="Гөлнара",
                           receiver_phone="+79995550001", status="created",
                           delivery_type="courier", rules_accepted=True)
        s.add(p)
        s.commit()
        s.refresh(p)
        _notify_couriers_new_parcel(s, p)


def test_кто_не_показал_машину_на_доставку_не_зовём(client, user_factory, режим_курьера,
                                                    кого_позвали, monkeypatch):
    """Главное: рассылка обязана знать то же, что гейт курьера."""
    monkeypatch.setattr(settings, "car_photo_courier_enabled", True, raising=False)
    отправитель = user_factory("ДопускОтправитель", role=UserRole.passenger)
    честный = _одобренный_курьер(client, user_factory, "КурьерЧестный")
    забывший = _одобренный_курьер(client, user_factory, "КурьерБезФото")
    _на_линии(честный["id"])
    _на_линии(забывший["id"])
    _не_показал_машину(забывший["id"], дней_просрочки=int(settings.car_photo_soft_days) + 1)

    _разослать(отправитель["id"])

    assert честный["id"] in кого_позвали, "честного курьера рассылка не позвала"
    assert забывший["id"] not in кого_позвали, (
        "рассылка позвала на доставку курьера, который больше недели не показывает машину: "
        "взять заказ он не сможет, а заявка тем временем ждёт того, кто реально приедет"
    )


def test_курьера_с_отозванной_заявкой_на_доставку_не_зовём(client, user_factory, режим_курьера,
                                                           кого_позвали):
    """Вторая дверь допуска: модератор отозвал одобрение, а человек остался «на линии»."""
    отправитель = user_factory("ОтозваннаяОтправитель", role=UserRole.passenger)
    бывший = _одобренный_курьер(client, user_factory, "КурьерБывший")
    _на_линии(бывший["id"])
    _отозвать_заявку(бывший["id"])

    _разослать(отправитель["id"])

    assert бывший["id"] not in кого_позвали, (
        "рассылка позвала на доставку человека, которому мы курьерить уже не разрешаем"
    )


def test_первые_дни_просрочки_фото_доставку_не_отнимают(client, user_factory, режим_курьера,
                                                        кого_позвали, monkeypatch):
    """Обратная сторона лестницы: первые трое суток — только напоминание, не запрет."""
    monkeypatch.setattr(settings, "car_photo_courier_enabled", True, raising=False)
    отправитель = user_factory("ЛестницаОтправитель", role=UserRole.passenger)
    забыл_на_день = _одобренный_курьер(client, user_factory, "КурьерЗабылНаДень")
    _на_линии(забыл_на_день["id"])
    _не_показал_машину(забыл_на_день["id"], дней_просрочки=1)

    _разослать(отправитель["id"])

    assert забыл_на_день["id"] in кого_позвали, (
        "курьера отрезали от заказов за один день просрочки фото — лестница на то и мягкая"
    )


def test_фотоконтроль_выключен_никого_не_отрезает(client, user_factory, режим_курьера,
                                                  кого_позвали, monkeypatch):
    """Договор про флаг (урок волны 221): выключенная проверка не мешает никому.

    Ровно на этом я оступился волной раньше: пакетный близнец гейта скопировал главную
    проверку и потерял флаг включённости — подбор опустел целиком.
    """
    monkeypatch.setattr(settings, "car_photo_courier_enabled", False, raising=False)
    отправитель = user_factory("ФлагОтправитель", role=UserRole.passenger)
    курьер = _одобренный_курьер(client, user_factory, "КурьерПриВыключенном")
    _на_линии(курьер["id"])
    _не_показал_машину(курьер["id"], дней_просрочки=int(settings.car_photo_soft_days) + 5)

    _разослать(отправитель["id"])

    assert курьер["id"] in кого_позвали, (
        "фотоконтроль курьера выключен настройкой, а человека всё равно отрезали от заказов"
    )


# ------------------------------ договор из разбора мутаций (волна 222) ------------------------------


def test_забывшему_фото_не_говорят_стань_курьером(client, user_factory, режим_курьера,
                                                   monkeypatch):
    """Договор: у каждой причины отказа свой текст, и путать их нельзя.

    Мутация, где гейт отвечал «Сначала стань курьером Юлдаша» на любую причину, прошла все
    тесты насквозь. А человек в этот момент КУРЬЕР — просто не показал машину. Такой ответ
    он прочтёт как «меня выгнали», пойдёт подавать заявку заново и упрётся в то, что она
    уже есть. Настоящее действие — три фото — ему при этом никто не назвал.

    Тот же принцип, что у такси (волна 66): «истекли документы» и «ты не прошёл проверку» —
    разные ситуации, и первый текст объясняет, что делать, а второй обвиняет человека.
    """
    monkeypatch.setattr(settings, "car_photo_courier_enabled", True, raising=False)
    курьер = _одобренный_курьер(client, user_factory, "КурьерЧитаетОтказ")
    _на_линии(курьер["id"])
    _не_показал_машину(курьер["id"], дней_просрочки=int(settings.car_photo_soft_days) + 1)

    ответ = client.get("/courier/available", headers=курьер["auth"])

    assert ответ.status_code == 403, f"гейт пропустил забывшего фото: {ответ.status_code}"
    текст = ответ.json()["detail"]["ru"].lower()
    assert "фото" in текст or "машин" in текст, (
        f"курьеру, забывшему показать машину, ответили не про фото: «{текст}»"
    )
    assert "стань курьером" not in текст, (
        f"курьеру сказали «стань курьером» — он им уже является: «{текст}»"
    )
