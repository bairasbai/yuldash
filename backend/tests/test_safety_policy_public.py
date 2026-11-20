from app.config import settings
from app.routers import bookings
from app.visibility import PROBLEM_REPORT_WINDOW


def test_public_policy_exposes_live_booking_windows_without_an_account(client, monkeypatch):
    monkeypatch.setattr(settings, "safety_late_cancel_before_depart_min", 37)
    response = client.get("/safety/policy")

    assert response.status_code == 200
    assert response.json() == {
        "strikes_to_limit": settings.safety_strikes_to_limit,
        "strikes_to_suspend": settings.safety_strikes_to_suspend,
        "suspend_1_days": settings.safety_suspend_1_days,
        "suspend_2_days": settings.safety_suspend_2_days,
        "suspend_3_days": settings.safety_suspend_3_days,
        "strike_decay_days": settings.safety_strike_decay_days,
        "late_cancel_before_min": 37,
        "no_show_wait_min": int(bookings.NO_SHOW_WAIT.total_seconds() // 60),
        "boarding_undo_min": int(bookings.BOARDING_UNDO_WINDOW.total_seconds() // 60),
        "problem_report_hours": int(PROBLEM_REPORT_WINDOW.total_seconds() // 3600),
    }
