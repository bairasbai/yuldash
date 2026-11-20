import json
from collections.abc import Callable

import httpx
import pytest

from app.rideshare_bank_provider import (
    BankOutcomeUnknown,
    BankRequestRejected,
    BankResource,
    YooKassaSafeDeal,
    prepare_deal,
    prepare_payment,
    prepare_payout,
    prepare_refund,
)


def rub(value: str) -> dict:
    return {"value": value, "currency": "RUB"}


def pretend(request: httpx.Request) -> httpx.Response:
    resource = request.url.path.split("/")[2]
    assert request.url.host == "api.yookassa.ru"
    assert request.headers["Authorization"].startswith("Basic ")
    assert request.method in {"GET", "POST"}
    data = json.loads(request.content) if request.method == "POST" else {}
    observation = {"id": "synthetic-" + resource, "status": "succeeded",
                   "test": True, "metadata": data.get("metadata", {"rideshare_operation": "operation"})}
    if resource == "deals":
        return httpx.Response(200, json={**observation, "type": "safe_deal", "fee_moment": "deal_closed",
                                        "status": "opened", "balance": rub("0"), "payout_balance": rub("0.00")})
    observation.update(amount=data.get("amount", rub("1200.05")), deal={"id": "synthetic-deals"})
    if resource == "payments":
        observation.update(paid=True, payment_method={"type": "bank_card"})
        observation["deal"]["settlements"] = [{"type": "payout", "amount": rub("1200.05")}]
    elif resource == "refunds":
        observation.pop("test")
        observation["payment_id"] = "synthetic-payments"
        observation["deal"]["refund_settlements"] = data.get("deal", {}).get(
            "refund_settlements", [{"type": "payout", "amount": data.get("amount", rub("1200.05"))}],
        )
    return httpx.Response(200, json=observation)


def bank(client: httpx.Client, *, test_mode: bool = True) -> YooKassaSafeDeal:
    return YooKassaSafeDeal(client, shop_id="synthetic-shop", secret_key="synthetic-credential", test_mode=test_mode)


def charge():
    return prepare_payment(
        "operation", "stable-key", deal_id="synthetic-deals", amount_kop=120_005, payout_kop=120_005,
        return_url="https://rideshare.example/checkout/return",
    )


def test_four_operations_share_stable_requests_and_verify_fetched_objects():
    observed: list[httpx.Request] = []

    def transport(request):
        observed.append(request)
        return pretend(request)

    requests = [
        prepare_deal("operation", "key-deal"),
        charge(),
        prepare_refund("operation", "key-refund", payment_id="synthetic-payments", amount_kop=120_005),
        prepare_payout("operation", "key-payout", deal_id="synthetic-deals", amount_kop=120_005,
                       payout_token="synthetic-payout-token"),
    ]
    with httpx.Client(transport=httpx.MockTransport(transport), trust_env=False) as client:
        api = bank(client)
        for operation in requests:
            first = api.submit(operation)
            assert api.submit(operation) == first
            fetched = api.fetch(operation.resource, first.provider_id)
            assert fetched == first
            sent_first, sent_again, read = observed[-3:]
            assert sent_first.content == sent_again.content == operation.body
            assert sent_first.headers["Idempotence-Key"] == sent_again.headers["Idempotence-Key"] == operation.key
            assert read.method == "GET" and "Idempotence-Key" not in read.headers
        assert len(observed) == 12
    deal = json.loads(requests[0].body)
    assert (deal["type"], deal["fee_moment"]) == ("safe_deal", "deal_closed")
    payment = json.loads(requests[1].body)
    assert payment["amount"] == rub("1200.05")
    assert payment["deal"]["settlements"] == [{"type": "payout", "amount": rub("1200.05")}]
    assert payment["payment_method_data"] == {"type": "bank_card"}
    assert payment["capture"] is True and "receipt" not in payment
    assert "deal" not in json.loads(requests[2].body)
    assert "synthetic-payout-token" not in repr(requests[3])


def test_partial_refund_carries_its_exact_payout_reduction():
    request = prepare_refund("part-refund", "key-part", payment_id="synthetic-payments",
                             amount_kop=2_005, payout_reduction_kop=1_500)
    body = json.loads(request.body)
    assert body["amount"] == rub("20.05")
    assert body["deal"]["refund_settlements"] == [{"type": "payout", "amount": rub("15.00")}]
    with httpx.Client(transport=httpx.MockTransport(pretend), trust_env=False) as client:
        result = bank(client).submit(request)
    assert result.amount_kop == 2_005 and result.settlement_kop == 1_500


def test_deal_reports_negative_balance_after_fee_and_zero_remaining_payout():
    def transport(_request):
        return httpx.Response(200, json={"id": "synthetic-deals", "status": "closed",
                                        "balance": rub("-45.00"), "payout_balance": rub("0.00"), "test": True,
                                        "type": "safe_deal", "fee_moment": "deal_closed"})

    with httpx.Client(transport=httpx.MockTransport(transport), trust_env=False) as client:
        result = bank(client).fetch(BankResource.deal, "synthetic-deals")
    assert result.amount_kop == -4500 and result.payout_balance_kop == 0


@pytest.mark.parametrize("status", [302, 404, 429, 500, 503])
def test_uncertain_http_results_are_not_treated_as_final_or_redirected(status):
    calls = []

    def transport(request):
        calls.append(request.url.host)
        return httpx.Response(status, headers={"Location": "https://unexpected.example"})

    with httpx.Client(transport=httpx.MockTransport(transport), follow_redirects=True, trust_env=False) as client:
        with pytest.raises(BankOutcomeUnknown):
            bank(client).submit(charge())
    assert calls == ["api.yookassa.ru"]


@pytest.mark.parametrize("status", [400, 401, 403, 422])
def test_refused_request_exposes_only_status(status):
    with httpx.Client(transport=httpx.MockTransport(
        lambda _: httpx.Response(status, text="provider-message-with-sensitive-data")
    ), trust_env=False) as client:
        with pytest.raises(BankRequestRejected) as caught:
            bank(client).submit(charge())
    assert caught.value.status_code == status
    assert "sensitive" not in str(caught.value)


@pytest.mark.parametrize("alter", [
    lambda body: body.update(test=False),
    lambda body: body.update(test=1),
    lambda body: body.update(paid=False),
    lambda body: body.update(payment_method={"type": "sbp"}),
    lambda body: body.update(amount=rub("1200.06")),
    lambda body: body.update(amount={"value": "1200.05", "currency": "USD"}),
    lambda body: body["deal"].update(id="unrelated-deal"),
    lambda body: body["deal"]["settlements"][0].update(amount=rub("1199")),
    lambda body: body.update(metadata={"rideshare_operation": "another-operation"}),
    lambda body: body.update(confirmation={"confirmation_url": "https://yoomoney.ru.invalid/confirm"}),
    lambda body: body.update(confirmation={"confirmation_url": "https://yoomoney.ru@invalid.example"}),
])
def test_inconsistent_create_response_is_unknown(alter: Callable[[dict], None]):
    def transport(request):
        state = pretend(request).json()
        alter(state)
        return httpx.Response(200, json=state)

    with httpx.Client(transport=httpx.MockTransport(transport), trust_env=False) as client:
        with pytest.raises(BankOutcomeUnknown):
            bank(client).submit(charge())


def test_production_mode_does_not_accept_test_result_and_transport_loss_hides_details():
    with httpx.Client(transport=httpx.MockTransport(pretend), trust_env=False) as client:
        with pytest.raises(BankOutcomeUnknown):
            bank(client, test_mode=False).submit(charge())

    def transport(_request):
        raise httpx.ReadTimeout("sensitive request detail")

    with httpx.Client(transport=httpx.MockTransport(transport), trust_env=False) as client:
        with pytest.raises(BankOutcomeUnknown) as caught:
            bank(client).submit(charge())
    assert "sensitive" not in str(caught.value)
    assert caught.value.__suppress_context__
