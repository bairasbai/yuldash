"""Regression tests for driver verification edge cases."""

import base64

import pytest
from fastapi import HTTPException
from sqlmodel import Session

from app.config import settings
from app.db import engine
from app.models import DriverProfile, User, UserRole
from app.routers import drivers


def _jpg():
    return base64.b64encode(b"\xff\xd8\xfffake-jpeg").decode()


def _upload_two_docs(client, auth):
    first = client.post("/upload/photo", headers=auth, json={"photo_b64": _jpg(), "ext": "jpg"})
    second = client.post("/upload/photo", headers=auth, json={"photo_b64": _jpg(), "ext": "jpg"})
    assert first.status_code == 200
    assert second.status_code == 200
    return first.json()["url"], second.json()["url"]


def test_driver_status_defaults_and_online_update(client, user_factory):
    driver = user_factory("DriverDefaults", role=UserRole.driver)
    status = client.get("/driver/status", headers=driver["auth"])
    assert status.status_code == 200
    assert status.json()["docs_status"] == "none"
    assert status.json()["seats"] == 4

    online = client.post("/driver/online", headers=driver["auth"], json={"online": True})
    assert online.status_code == 200
    assert online.json()["online"] is True
    offline = client.post("/driver/online", headers=driver["auth"], json={"online": False})
    assert offline.status_code == 200
    assert offline.json()["online"] is False


def test_driver_doc_helpers_reject_empty_foreign_and_missing_files():
    assert drivers._doc_name_from_url("https://host/secure/docs/file.jpg?x=1#frag") == "file.jpg"
    assert drivers._profile_doc_names(None) == set()
    user = User(id=123, phone="p", role=UserRole.driver)
    profile = DriverProfile(user_id=123, license_url="", car_photo_url="")

    with pytest.raises(HTTPException) as empty:
        drivers._ensure_owned_doc_url("", user, profile)
    assert empty.value.status_code == 400

    with pytest.raises(HTTPException) as foreign:
        drivers._ensure_owned_doc_url("https://host/secure/docs/999_doc.jpg", user, profile)
    assert foreign.value.status_code == 403

    with pytest.raises(HTTPException) as missing:
        drivers._ensure_owned_doc_url("https://host/secure/docs/123_missing.jpg", user, profile)
    assert missing.value.status_code == 404


def test_driver_verify_autocheck_autoapprove_and_autoreject(client, user_factory, monkeypatch):
    old_enabled = settings.driver_autocheck_enabled
    old_autoapprove = settings.driver_autoapprove_enabled
    old_autoreject = settings.driver_autoreject_enabled
    settings.driver_autocheck_enabled = True
    settings.driver_autoapprove_enabled = True
    settings.driver_autoreject_enabled = True
    try:
        approved_driver = user_factory("AutoApproveDriver", role=UserRole.driver)
        license_url, car_url = _upload_two_docs(client, approved_driver["auth"])
        monkeypatch.setattr(
            "app.driver_check.check_driver_docs",
            lambda *_args, **_kwargs: {"result": "pass", "score": 1.0, "data": {"reasons": ["ok"]}},
        )
        approved = client.post(
            "/driver/verify",
            headers=approved_driver["auth"],
            json={"license_url": license_url, "car_photo_url": car_url},
        )
        assert approved.status_code == 200
        assert approved.json()["docs_status"] == "verified"
        assert approved.json()["autocheck_result"] == "pass"
        with Session(engine) as session:
            assert session.get(User, approved_driver["id"]).verified is True

        rejected_driver = user_factory("AutoRejectDriver", role=UserRole.driver)
        license_url, car_url = _upload_two_docs(client, rejected_driver["auth"])
        monkeypatch.setattr(
            "app.driver_check.check_driver_docs",
            lambda *_args, **_kwargs: {"result": "reject", "score": 0.1, "data": {"reasons": ["not_a_license"]}},
        )
        rejected = client.post(
            "/driver/verify",
            headers=rejected_driver["auth"],
            json={"license_url": license_url, "car_photo_url": car_url},
        )
        assert rejected.status_code == 200
        assert rejected.json()["docs_status"] == "rejected"
        assert rejected.json()["autocheck_result"] == "reject"
    finally:
        settings.driver_autocheck_enabled = old_enabled
        settings.driver_autoapprove_enabled = old_autoapprove
        settings.driver_autoreject_enabled = old_autoreject


def test_driver_verify_autocheck_disabled_and_error_path(client, user_factory, monkeypatch):
    old_enabled = settings.driver_autocheck_enabled
    settings.driver_autocheck_enabled = False
    try:
        driver = user_factory("AutocheckDisabledDriver", role=UserRole.driver)
        license_url, car_url = _upload_two_docs(client, driver["auth"])
        disabled = client.post(
            "/driver/verify",
            headers=driver["auth"],
            json={"license_url": license_url, "car_photo_url": car_url},
        )
        assert disabled.status_code == 200
        assert disabled.json()["docs_status"] == "pending"
        assert disabled.json()["autocheck_result"] == ""
    finally:
        settings.driver_autocheck_enabled = old_enabled

    driver = user_factory("AutocheckErrorDriver", role=UserRole.driver)
    license_url, car_url = _upload_two_docs(client, driver["auth"])
    monkeypatch.setattr("app.driver_check.check_driver_docs", lambda *_args, **_kwargs: (_ for _ in ()).throw(RuntimeError("ocr")))
    errored = client.post(
        "/driver/verify",
        headers=driver["auth"],
        json={"license_url": license_url, "car_photo_url": car_url},
    )
    assert errored.status_code == 200
    assert errored.json()["docs_status"] == "pending"
    assert errored.json()["autocheck_result"] == "error"


def test_admin_driver_moderation_reject_and_missing_target(client, user_factory):
    admin = user_factory("DriverAdminReject", role=UserRole.admin)
    driver = user_factory("DriverToReject", role=UserRole.driver)

    missing = client.post("/admin/drivers/99999999/moderate", headers=admin["auth"], json={"approve": False})
    assert missing.status_code == 404

    rejected = client.post(f"/admin/drivers/{driver['id']}/moderate", headers=admin["auth"], json={"approve": False})
    assert rejected.status_code == 200
    assert rejected.json()["verified"] is False
    assert rejected.json()["docs_status"] == "rejected"
