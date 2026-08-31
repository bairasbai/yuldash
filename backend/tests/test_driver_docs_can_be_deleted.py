"""Скан прав не должен лежать у нас вечно.

Документы водителя — единственное, что ретеншен не чистит никогда (`cleanup.py` говорит
это прямым текстом). Человек подал заявку в июне, передумал, ездит пассажиром — а фото
его прав хранится бессрочно, и убрать его можно было только вместе со всем аккаунтом.

Здесь проверяется вся кнопка: файлы уходят, а вместе с ними — проверка, бейдж «проверен»
и подтверждение пола. Иначе пассажирка видела бы «водитель проверен», а документа,
по которому проверяли, уже нет.
"""

from sqlmodel import Session, select

from app.db import engine
from app.models import DriverProfile, User, UserRole


def _driver_with_docs(user_factory, name: str, **overrides):
    u = user_factory(name)
    with Session(engine) as s:
        dp = DriverProfile(user_id=u["id"], license_url="/media/docs/lic.jpg",
                           car_photo_url="/media/docs/car.jpg", docs_status="verified",
                           gender_verified=True)
        for k, v in overrides.items():
            setattr(dp, k, v)
        s.add(dp)
        row = s.get(User, u["id"])
        row.verified = True
        s.add(row)
        s.commit()
    return u


def test_documents_are_removed_together_with_the_badge(client, user_factory):
    """Удалили документы — сняли и всё, что на них держалось."""
    u = _driver_with_docs(user_factory, "DocsOwner")

    r = client.post("/me/driver-docs/delete", headers=u["auth"])
    assert r.status_code == 200, r.text

    with Session(engine) as s:
        dp = s.exec(select(DriverProfile).where(DriverProfile.user_id == u["id"])).first()
        assert dp.license_url == "" and dp.car_photo_url == ""
        assert dp.docs_status == "none"
        assert dp.gender_verified is False       # подтверждали по фото прав
        assert s.get(User, u["id"]).verified is False   # бейдж «проверен» тоже снят


def test_on_the_line_deletion_is_refused(client, user_factory):
    """Водитель сейчас работает — не даём вынимать документы из-под живой смены."""
    u = _driver_with_docs(user_factory, "DocsOnline", online=True)

    r = client.post("/me/driver-docs/delete", headers=u["auth"])
    assert r.status_code == 409

    with Session(engine) as s:
        dp = s.exec(select(DriverProfile).where(DriverProfile.user_id == u["id"])).first()
        assert dp.license_url != ""              # ничего не тронули


def test_under_review_deletion_is_refused(client, user_factory):
    """Админ разбирает заявку — вынимать документы посреди разбора нечестно к обоим."""
    u = _driver_with_docs(user_factory, "DocsPending", docs_status="pending")

    r = client.post("/me/driver-docs/delete", headers=u["auth"])
    assert r.status_code == 409

    with Session(engine) as s:
        dp = s.exec(select(DriverProfile).where(DriverProfile.user_id == u["id"])).first()
        assert dp.docs_status == "pending"


def test_nothing_to_delete_is_an_honest_404(client, user_factory):
    """Человек никогда не был водителем — честное «нечего удалять», а не мнимый успех."""
    u = user_factory("NeverDriver")

    assert client.post("/me/driver-docs/delete", headers=u["auth"]).status_code == 404


def test_second_press_says_there_is_nothing_left(client, user_factory):
    """Повторное нажатие не притворяется, что снова что-то удалило."""
    u = _driver_with_docs(user_factory, "DocsTwice")

    assert client.post("/me/driver-docs/delete", headers=u["auth"]).status_code == 200
    assert client.post("/me/driver-docs/delete", headers=u["auth"]).status_code == 404


def test_stranger_cannot_delete_someone_elses_documents(client, user_factory):
    """Чужие документы недостижимы: ручка работает только со своим профилем."""
    owner = _driver_with_docs(user_factory, "DocsVictim")
    stranger = user_factory("DocsStranger", role=UserRole.admin)

    assert client.post("/me/driver-docs/delete", headers=stranger["auth"]).status_code == 404

    with Session(engine) as s:
        dp = s.exec(select(DriverProfile).where(DriverProfile.user_id == owner["id"])).first()
        assert dp.license_url != ""
