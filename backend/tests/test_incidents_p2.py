"""P2/P3 харднинг «Справедливости» из аудита: анти-харассмент + URL-allowlist доказательств."""
from sqlmodel import Session

from app.db import engine
from app.models import Incident, UserRole

from test_incidents import _book, _publish  # общие хелперы


def test_incident_requires_shared_trip(client, user_factory):
    """Обычная (не-severe) жалоба без общей поездки → 400 (нельзя валить инцидентами кого попало)."""
    a = user_factory("HarA")
    b = user_factory("HarB")
    r = client.post("/incidents", headers=a["auth"], json={"respondent_id": b["id"], "type": "rude"})
    assert r.status_code == 400, r.text


def test_incident_drops_external_evidence_url(client, user_factory):
    """Внешний URL в доказательствах отбрасывается (деанон), свой /media/ — сохраняется."""
    drv = user_factory("UrlDrv", role=UserRole.driver)
    pax = user_factory("UrlPax")
    ride = _publish(client, drv)
    booking = _book(client, pax, ride["id"])
    inc = client.post("/incidents", headers=pax["auth"], json={
        "respondent_id": drv["id"], "type": "rude", "booking_id": booking["id"],
        "evidence_urls": ["http://evil.example/track.png", "/media/chat/ok.jpg"],
    }).json()
    with Session(engine) as s:
        stored = s.get(Incident, inc["id"]).evidence_urls
    assert "evil.example" not in stored, "внешний URL не должен сохраняться"
    assert "/media/chat/ok.jpg" in stored, "свой media-URL должен сохраниться"
