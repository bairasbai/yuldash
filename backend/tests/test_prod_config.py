"""Прод-конфиг-гейт: небезопасные значения по умолчанию не должны пускать прод."""
import pytest

from app.config import Settings


def test_prod_blocks_seed_demo():
    """SEED_DEMO=true в проде → отказ старта (иначе фейковые демо-поездки в боевой базе)."""
    s = Settings(env="prod", seed_demo=True)
    with pytest.raises(Exception) as ei:
        s.validate_production()
    assert "SEED_DEMO" in str(ei.value)


def test_dev_ignores_seed_demo():
    """В dev гейт не применяется — локальная разработка не ломается."""
    Settings(env="dev", seed_demo=True).validate_production()   # не должно бросать
