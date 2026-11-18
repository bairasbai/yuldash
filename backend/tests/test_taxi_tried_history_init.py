"""First-cycle input gates, not production initialization or UUID never-reuse proof."""
import pytest
from sqlalchemy import create_engine,event
from sqlmodel import Session
from app.taxi_tried_history_init import begin_first_sql_search


@pytest.mark.parametrize("value",[None,object(),"postgresql://unused"])
def test_nonconnection_rejected(value):
    with pytest.raises(TypeError): begin_first_sql_search(value,1,None)


@pytest.mark.parametrize("field,value",[("order_id",True),("order_id",0),("order_id",-1),("order_id",2**31),
    ("order_id","1"),("expected_revision",True),("expected_revision",-1),
    ("expected_revision",2**63),("expected_revision","1")])
def test_bad_inputs_rejected_before_sql(tmp_path,field,value):
    engine=create_engine(f"sqlite:///{tmp_path/'inputs.db'}")
    calls=[]
    event.listen(engine,"before_cursor_execute",lambda *args:calls.append(args))
    try:
        with engine.connect() as c,c.begin():
            args=dict(order_id=1,expected_revision=None);args[field]=value
            with pytest.raises(ValueError,match=field):begin_first_sql_search(c,**args)
            assert calls==[]
    finally:engine.dispose()


def test_engine_session_dialect_rejected(tmp_path):
    engine=create_engine(f"sqlite:///{tmp_path/'dialect.db'}")
    calls=[]
    event.listen(engine,"before_cursor_execute",lambda *args:calls.append(args))
    try:
        with Session(engine) as session:
            for value in (engine,session):
                with pytest.raises(TypeError):begin_first_sql_search(value,1,None)
        with engine.connect() as c,c.begin():
            with pytest.raises(ValueError):begin_first_sql_search(c,1,None)
        assert calls==[]
    finally:engine.dispose()


def test_unsupported_driver_rejected_before_sql(tmp_path,monkeypatch):
    engine=create_engine(f"sqlite:///{tmp_path/'driver.db'}")
    calls=[]
    try:
        with engine.connect() as c,c.begin():
            monkeypatch.setattr(c.dialect,"name","postgresql")
            monkeypatch.setattr(c.dialect,"driver","unsupported")
            event.listen(c,"before_cursor_execute",lambda *args:calls.append(args))
            with pytest.raises(ValueError,match="psycopg2"):begin_first_sql_search(c,1,None)
            assert calls==[]
    finally:engine.dispose()
