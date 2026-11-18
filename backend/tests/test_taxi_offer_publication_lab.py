"""Storage-only publication inputs; no eligibility or reservation claim."""
from datetime import datetime,timezone

import pytest
from sqlalchemy import create_engine,event
from sqlmodel import Session

from app.taxi_offer_publication_lab import publish_sql_offer_lab

ARGS=dict(order_id=1,expected_revision=1,expected_lifecycle="a"*32,
          expected_searching_at=datetime(2030,1,1),expected_search_round=0,
          expected_offer_token=None,driver_id=2,offer_ttl_seconds=30,max_offers=5)


@pytest.mark.parametrize("value",[None,object(),"postgresql://unused"])
def test_nonconnection_rejected(value):
    with pytest.raises(TypeError):publish_sql_offer_lab(value,**ARGS)


@pytest.mark.parametrize("field,value",[
    ("order_id",True),("order_id",0),("order_id",2**31),("driver_id",False),("driver_id",-1),("driver_id",2**31),
    ("expected_revision",True),("expected_revision",None),("expected_revision",-1),("expected_revision",2**63),
    ("expected_lifecycle",None),("expected_lifecycle","A"*32),("expected_lifecycle","g"*32),("expected_lifecycle","a"*31),
    ("expected_searching_at",None),("expected_searching_at","2030-01-01"),("expected_searching_at",datetime(2030,1,1,tzinfo=timezone.utc)),
    ("expected_search_round",True),("expected_search_round",-1),("expected_search_round",2**31-1),("expected_search_round",1.5),
    ("expected_offer_token",""),("expected_offer_token","A"*32),("expected_offer_token",7),
    ("offer_ttl_seconds",True),("offer_ttl_seconds",0),("offer_ttl_seconds",-1),("offer_ttl_seconds",1.5),
    ("max_offers",True),("max_offers",0),("max_offers",2**31),("max_offers",1.5),
])
def test_bad_input_before_sql(tmp_path,field,value):
    engine=create_engine(f"sqlite:///{tmp_path/'input.db'}");calls=[]
    event.listen(engine,"before_cursor_execute",lambda *args:calls.append(args))
    try:
        with engine.connect() as c,c.begin():
            with pytest.raises(ValueError):publish_sql_offer_lab(c,**(ARGS|{field:value}))
            assert calls==[]
    finally:engine.dispose()


def test_engine_session_dialect_and_unknown_driver_rejected(tmp_path,monkeypatch):
    engine=create_engine(f"sqlite:///{tmp_path/'engine.db'}");calls=[]
    event.listen(engine,"before_cursor_execute",lambda *args:calls.append(args))
    try:
        with Session(engine) as session:
            for value in (engine,session):
                with pytest.raises(TypeError):publish_sql_offer_lab(value,**ARGS)
        with engine.connect() as c,c.begin():
            with pytest.raises(ValueError):publish_sql_offer_lab(c,**ARGS)
            monkeypatch.setattr(c.dialect,"name","postgresql")
            monkeypatch.setattr(c.dialect,"driver","unsupported")
            with pytest.raises(ValueError):publish_sql_offer_lab(c,**ARGS)
        assert calls==[]
    finally:engine.dispose()
