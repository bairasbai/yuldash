"""PATH-12 (попутки) — админ: очередь → решение → результат владельцу → жалоба/спор → журнал.

Как это видит Александр-админ: водитель прислал права и фото машины — заявка в очереди
проверки. Обычный человек эту очередь не откроет. Админ одобряет — водитель получает бейдж
«Проверен», и пассажиры видят его в ленте. После поездки пассажир жалуется на грубость и
открывает спор; водитель отвечает своей версией; админ разбирает жалобу и спор — обе стороны
получают решение, а в журнале остаётся строка «кто и что сделал» (без телефонов).
"""
import re
from urllib.parse import urlsplit

from .conftest import LIVE_URL


def test_admin_queue_decision_owner_result_complaint_dispute_and_journal(api, kit, admin_journal):
    admin = kit.login_admin()
    driver = kit.login("Ильдар Водитель")
    passenger = kit.login("Гульнара Пассажир")
    if not LIVE_URL:   # в живом режиме админ тот же с прошлого прогона — повышения уже не будет
        assert any(f"admin={admin.id} role.promote" in ln for ln in admin_journal()), admin_journal()

    # 1. Водитель подаёт документы → заявка в очереди.
    assert api.post("/driver/profile", headers=driver.auth, json={
        "car_make": "Lada", "car_model": "Vesta", "car_color": "белая", "car_plate": "А123ВС102",
        "seats": 4}).status_code == 200
    lic, photo = kit.upload_doc(driver), kit.upload_doc(driver)
    sent = api.post("/driver/verify", headers=driver.auth, json={"license_url": lic, "car_photo_url": photo})
    assert sent.status_code == 200 and sent.json()["docs_status"] == "pending", sent.text
    assert api.get("/me", headers=driver.auth).json()["verified"] is False

    # 2. Очередь: обычному человеку закрыта, админу — с этой заявкой.
    assert api.get("/admin/drivers/pending", headers=passenger.auth).status_code == 403
    assert api.post(f"/admin/drivers/{driver.id}/moderate", headers=driver.auth,
                    json={"approve": True}).status_code == 403
    queue = api.get("/admin/drivers/pending", headers=admin.auth)
    assert queue.status_code == 200, queue.text
    assert driver.id in [row["user_id"] for row in queue.json()]
    # Документ из заявки админ открывает, посторонний — нет.
    doc_path = urlsplit(lic).path
    assert doc_path.startswith("/secure/docs/"), lic
    assert api.get(doc_path, headers=admin.auth).status_code == 200
    assert api.get(doc_path, headers=passenger.auth).status_code in (403, 404)

    # 3. Решение → результат владельцу и пассажирам.
    dec = api.post(f"/admin/drivers/{driver.id}/moderate", headers=admin.auth, json={"approve": True})
    assert dec.status_code == 200 and dec.json()["verified"] is True, dec.text
    assert driver.id not in [row["user_id"] for row in api.get("/admin/drivers/pending", headers=admin.auth).json()]
    assert api.get("/me", headers=driver.auth).json()["verified"] is True
    told = [n for n in kit.notifications(driver) if n["title_ru"] == "Документы проверены"]
    assert len(told) == 1, "водитель не узнал о решении по документам (экран обещает «Сообщим о результате»)"
    assert told[0]["title_ba"] and told[0]["body_ba"]
    # Повторное нажатие админа — без второго сообщения.
    assert api.post(f"/admin/drivers/{driver.id}/moderate", headers=admin.auth,
                    json={"approve": True}).status_code == 200
    assert len([n for n in kit.notifications(driver) if n["title_ru"] == "Документы проверены"]) == 1
    ride = kit.publish(driver)
    assert kit.find_ride(ride["id"], who=passenger)["driver_verified"] is True

    # 4. Поездка состоялась → жалоба и спор от пассажира.
    bid = kit.book(passenger, ride["id"])["id"]
    kit.confirm(driver, bid)
    # Посадку отмечает водитель; поездку закрывает его «Завершить» (пассажиру пришлось бы ждать
    # окно «Отменить» — 5 минут, а журней идёт и на живом сервере).
    assert kit.driver_status(driver, bid, "boarded").status_code == 200
    assert kit.driver_status(driver, bid, "done").status_code == 200
    rep = api.post("/reports", headers=passenger.auth,
                   json={"booking_id": bid, "category": "rude", "reason": "Грубил всю дорогу"})
    assert rep.status_code == 200, rep.text
    rep_id = rep.json()["id"]
    assert "reporter_id" not in rep.json()
    # Водитель узнаёт о жалобе, но не узнаёт, кто пожаловался.
    target_note = [n for n in kit.notifications(driver) if "жалоб" in (n["title_ru"] + n["body_ru"]).lower()]
    assert target_note and all("Гульнара" not in (n["title_ru"] + n["body_ru"]) for n in target_note)
    inc = api.post("/incidents", headers=passenger.auth, json={
        "respondent_id": driver.id, "type": "rude", "booking_id": bid,
        "description": "Грубил всю дорогу, кричал"})
    assert inc.status_code == 200, inc.text
    inc_id = inc.json()["id"]
    resp = api.post(f"/incidents/{inc_id}/respond", headers=driver.auth,
                    json={"statement": "Пассажирка опоздала на 20 минут, я просто торопил"})
    assert resp.status_code == 200 and resp.json()["status"] == "under_review", resp.text
    assert kit.notified(passenger, "Ответ по спору", ref_id=inc_id)

    # 5. Админ разбирает: жалобу и спор видит в очереди, решает; посторонний решить не может.
    reports = api.get("/admin/reports", headers=admin.auth)
    assert reports.status_code == 200 and rep_id in [r["id"] for r in reports.json()], reports.text
    assert api.post(f"/admin/reports/{rep_id}/resolve", headers=passenger.auth,
                    json={"resolution": "x"}).status_code == 403
    rr = api.post(f"/admin/reports/{rep_id}/resolve", headers=admin.auth,
                  json={"resolution": "Предупредили водителя"})
    assert rr.status_code == 200 and rr.json()["status"] == "resolved", rr.text
    assert kit.notified(passenger, "Разбор закончен"), "автор жалобы не узнал итог"
    incidents = api.get("/admin/incidents", headers=admin.auth)
    assert incidents.status_code == 200 and inc_id in [i["id"] for i in incidents.json()], incidents.text
    ri = api.post(f"/admin/incidents/{inc_id}/resolve", headers=admin.auth,
                  json={"resolution": "warning", "fault": "respondent", "note": "Предупреждение водителю"})
    assert ri.status_code == 200 and ri.json()["status"] == "resolved", ri.text
    for side in (passenger, driver):
        assert kit.notified(side, "Решение по спору", ref_id=inc_id), f"{side.name} не узнал решение"
        mine = api.get(f"/incidents/{inc_id}", headers=side.auth)
        assert mine.status_code == 200 and mine.json()["status"] == "resolved"
    # Несогласная сторона может обжаловать — спор снова у админа.
    ap = api.post(f"/incidents/{inc_id}/appeal", headers=driver.auth, json={"text": "Не согласен"})
    assert ap.status_code == 200 and ap.json()["status"] == "appealed", ap.text

    # 6. Журнал: каждое решение подписано админом, без телефонов.
    journal = admin_journal()
    for what in (f"driver.moderate target_user={driver.id}", f"report.resolve report_id={rep_id}",
                 f"incident.resolve incident_id={inc_id}"):
        assert any(f"admin={admin.id} {what}" in ln for ln in journal), (what, journal)
    for ln in journal:
        assert not re.search(r"\+?7\d{10}", ln), f"телефон в журнале: {ln}"
