"""Synthetic HTTP transport shared by the bank tests and the isolated QA stand."""

import json

import httpx
from sqlmodel import Session

from app.rideshare_bank_models import RideshareBankOperation


class SyntheticBank:
    def __init__(self, engine):
        self.engine = engine
        self.requests = []
        self.objects = {}
        self.by_key = {}
        self.lookup_pages = {}
        self.fail_reads = set()
        self.lose_deal_response = False
        self.lose_payment_response = False
        self.lose_settlement_response = set()
        self.on_first_deal = None

    def handle(self, request):
        assert request.url.host == "api.yookassa.ru"
        resource, *path = request.url.path.removeprefix("/v3/").split("/")
        if request.method == "GET":
            if not path:
                params = dict(request.url.params)
                self.requests.append(("LIST", resource, params))
                configured = self.lookup_pages.get((resource, params.get("cursor")))
                if configured is not None:
                    return httpx.Response(200, json=configured)
                objects = [value for key, value in self.objects.items() if key.startswith(f"synthetic-{resource}-")]
                return httpx.Response(200, json={"type": "list", "items": objects})
            self.requests.append(("GET", resource, path[0]))
            if path[0] in self.fail_reads:
                raise httpx.ReadTimeout("Synthetic bank read lost")
            return httpx.Response(200, json=self.objects[path[0]])
        key = request.headers["Idempotence-Key"]
        self.requests.append(("POST", resource, key, request.content))
        with Session(self.engine) as db:
            operation = db.get(RideshareBankOperation, key)
            assert operation.dispatched_at and operation.lease_token and operation.request_ciphertext
            assert request.content not in operation.request_ciphertext
        if key in self.by_key:
            return httpx.Response(200, json=self.objects[self.by_key[key]])
        body = json.loads(request.content)
        provider_id = f"synthetic-{resource}-{key}"
        result = {"id": provider_id, "test": True, "metadata": body["metadata"]}
        if resource == "deals":
            result.update(status="opened", type="safe_deal", fee_moment="deal_closed",
                          balance={"currency": "RUB", "value": "0"}, payout_balance={"currency": "RUB", "value": "0"})
        elif resource == "payments":
            result.update(status="pending", paid=False, amount=body["amount"], deal=body["deal"],
                          confirmation={"type": "redirect", "confirmation_url": "https://yoomoney.ru/checkout/synthetic"})
        elif resource == "payouts":
            result.update(status="pending", amount=body["amount"], deal=body["deal"])
        else:
            assert resource == "refunds"
            payment = self.objects[body["payment_id"]]
            reductions = body.get("deal", {}).get("refund_settlements", payment["deal"]["settlements"])
            result.update(status="pending", amount=body["amount"], payment_id=payment["id"],
                          deal={"id": payment["deal"]["id"], "refund_settlements": reductions})
        self.by_key[key] = provider_id
        self.objects[provider_id] = result
        if resource == "deals" and self.on_first_deal is not None:
            self.on_first_deal()
        if resource == "deals" and self.lose_deal_response:
            self.lose_deal_response = False
            raise httpx.ReadTimeout("Synthetic deal response lost")
        if resource == "payments" and self.lose_payment_response:
            self.lose_payment_response = False
            raise httpx.ReadTimeout("Synthetic response lost")
        if resource in self.lose_settlement_response:
            self.lose_settlement_response.remove(resource)
            raise httpx.ReadTimeout("Synthetic settlement response lost")
        return httpx.Response(200, json=result)

    def finish_payment(self, operation_id=None):
        if operation_id is None:
            payment, = (entry for entry in self.objects.values() if entry["status"] == "pending")
        else:
            payment = self.objects[self.by_key[operation_id]]
            if payment["status"] != "pending":
                raise ValueError("Only a pending synthetic payment can be approved")
        payment.update(status="succeeded", paid=True, payment_method={"type": "bank_card"})
        payment.pop("confirmation", None)
