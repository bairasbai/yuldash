"""Таблица coupon_claim_log + перенос уже накопленных погашений живых людей (leaf-1.3b).

ЗАЧЕМ. Открытая ошибка листа 1.3 (`coupons.py.md`, статус был `bug`): «лимит один купон на
человека» держался только на `CouponRedemption.user_id`, а удаление аккаунта (`account.py`)
стирает её вместе со всей историей. Пассажир гасил купон, удалял аккаунт, регистрировался
заново С ТЕМ ЖЕ номером — и `coupon_activate` не находил прежних записей: купон, рассчитанный
на одного человека, доставался тому же номеру повторно. Для промокодов эту же дыру закрыли
`PromoClaimLog` (волна 149) + `promo_claim_hmac_20261002.py` (круг 4 листа 1.3) — здесь то же
самое для купонов, по образцу, не изобретая заново.

ПОЧЕМУ ПЕРЕНОС, А НЕ ПУСТАЯ ТАБЛИЦА. `coupon_redeem` (`routers/coupons.py`, leaf-1.3b) теперь
пишет `CouponClaimLog` при КАЖДОМ новом погашении — но на проде уже есть ЖИВЫЕ люди, погасившие
купон ДО этой выкладки. Без переноса их прошлые погашения не защищены ничем: человек мог бы
удалить аккаунт и вернуться с тем же номером, обойдя лимит, хотя его собственное старое
погашение прямо здесь, в `couponredemption`. Переносим только ЖИВЫХ — у кого аккаунт УЖЕ
удалён, `CouponRedemption.user_id` ссылался на уже стёртую строку и сама запись `couponredemption`
стёрта вместе с ней (`account.py` чистит её целиком) — восстановить их номер неоткуда,
это и есть та самая прежняя дыра, которую мы теперь не даём открыться ЗАНОВО, а не чиним
задним числом для тех, кто ушёл раньше выкладки.

ИДЕМПОТЕНТНО по РАЗНИЦЕ СЧЁТЧИКОВ на каждую пару (coupon_id, phone_key), НЕ по «таблица
пуста» и НЕ по «пара уже встречалась»: лимит на человека — это COUNT записей, а не факт их
существования — один номер мог ЗАКОННО погасить один и тот же купон несколько раз
(limit_per_user > 1), и схлопывать такие повторы в одну запись значило бы занизить его
настоящий счёт. Если по паре УЖЕ есть N строк в журнале (от предыдущего прогона миграции или
от живого `coupon_redeem`, успевшего отработать раньше), довставляем только недостающие —
разницу между историческим числом погашений и тем, что уже в журнале.

ПРЕДОХРАНИТЕЛЬ ОТ СЛАБОГО СЕКРЕТА — тот же, что в `promo_claim_hmac_20261002.py` (круг 4
листа 1.3, N-3), порог не придумываю заново: если есть исторические погашения к переносу, а
`jwt_secret` пуст/дефолтный/dev-.../короче 16 символов (тот же порог, что и прод-конфиг,
`config.py::validate_production`) — падаем ДО единой записи. Иначе прогон без прод-секрета
(другой рабочий каталог, переменная окружения не доехала) навсегда перезаписал бы историю
HMAC-ом от dev-секрета.

DOWNGRADE удаляет таблицу целиком (если она пуста создана этой же ревизией — обычный путь
при откате сразу после выкладки). Если в ней уже есть записи от ЖИВОГО `coupon_redeem` (код
успел поработать до отката) — удаление стёрло бы данные, нужные самому коду (который продолжит
писать в неё после отката кода, если откатывали только миграцию): в этом случае откат —
безопасный no-op с объяснением, как и у `promo_claim_hmac` (HMAC необратим, из журнала номер
не восстановить — удалять его данные «просто так» при откате так же нечестно, как изобретать
обратное превращение).

Revision ID: coupon_claim_log
Revises: bd_merge_heads_20261002
"""
import datetime as dt

import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect

revision = "coupon_claim_log"
down_revision = "bd_merge_heads_20261002"
branch_labels = None
depends_on = None

_TABLE = "couponclaimlog"


def _table_exists(bind) -> bool:
    return _TABLE in set(inspect(bind).get_table_names())


def upgrade() -> None:
    bind = op.get_bind()
    created_here = not _table_exists(bind)
    if created_here:
        op.create_table(
            _TABLE,
            sa.Column("id", sa.Integer(), primary_key=True),
            sa.Column("coupon_id", sa.Integer(), sa.ForeignKey("coupon.id"), nullable=False),
            sa.Column("phone_key", sa.String(), nullable=False, server_default=""),
            sa.Column("created_at", sa.DateTime(), nullable=False),
        )
        op.create_index("ix_couponclaimlog_coupon_id", _TABLE, ["coupon_id"])
        op.create_index("ix_couponclaimlog_phone_key", _TABLE, ["phone_key"])
        op.create_index("ix_couponclaimlog_created_at", _TABLE, ["created_at"])

    # Исторические погашения ЖИВЫХ людей — join по user_id, который `CouponRedemption` ещё
    # хранит (удалённый аккаунт уносит свою строку целиком вместе с собой, см. docstring выше).
    historical = bind.execute(sa.text(
        'SELECT cr.coupon_id, u.phone, cr.redeemed_at '
        'FROM couponredemption cr JOIN "user" u ON u.id = cr.user_id '
        "WHERE cr.status = 'redeemed' AND u.phone IS NOT NULL AND u.phone != ''"
    )).fetchall()

    from app.routers.promo import _phone_claim_key  # та же функция, что в coupons.py (не дублируем)

    # Группируем по (coupon_id, phone_key) СПИСКОМ, не множеством: лимит на человека — это
    # COUNT, не EXISTS. Один номер мог ЗАКОННО погасить один и тот же купон несколько раз
    # (limit_per_user > 1) — схлопнуть в одну запись значило бы задним числом занизить его
    # настоящий счёт и выдать лишний слот после удаления аккаунта.
    per_pair = {}
    for coupon_id, phone, redeemed_at in historical:
        key = _phone_claim_key(phone)
        if not key:
            continue
        # redeemed_at обязан быть на настоящих данных (coupon_redeem пишет его атомарно вместе
        # со status='redeemed') — запасной CURRENT_TIMESTAMP только на случай аномальной строки
        # (ручная правка в БД, древние данные), не ломаем NOT NULL.
        per_pair.setdefault((coupon_id, key), []).append(redeemed_at or dt.datetime.utcnow())

    # Идемпотентность — по РАЗНИЦЕ счётчиков, не «пара уже встречалась»: если по этой паре
    # УЖЕ есть N строк в журнале (от предыдущего прогона или от живого coupon_redeem, успевшего
    # отработать до этой миграции), довставляем только недостающие, а не все заново.
    to_insert = []
    if per_pair:
        from collections import Counter
        existing_counts = Counter(
            (row[0], row[1])
            for row in bind.execute(sa.text(f"SELECT coupon_id, phone_key FROM {_TABLE}")).fetchall()
        )
        for (coupon_id, phone_key), timestamps in per_pair.items():
            already = existing_counts.get((coupon_id, phone_key), 0)
            for created_at in timestamps[already:]:
                to_insert.append((coupon_id, phone_key, created_at))

    if to_insert:
        # Предохранитель (по образцу promo_claim_hmac_20261002.py, N-3 — тот же порог, не
        # изобретаю заново): необратимо писать HMAC слабым секретом, когда есть что переносить.
        from app.config import DEFAULT_JWT_SECRET, settings
        secret = settings.jwt_secret or ""
        weak = (
            not secret
            or secret == DEFAULT_JWT_SECRET
            or secret.startswith("dev-secret")
            or len(secret) < 16  # тот же порог, что прод-конфиг (config.py::validate_production)
        )
        if weak:
            raise RuntimeError(
                f"coupon_claim_log: JWT_SECRET слишком слабый (пустой/дефолтный/dev-.../короче "
                f"16 символов), а к переносу есть {len(to_insert)} историческ(ая/их) погашени(е/й). "
                "HMAC этим секретом необратимо станет единственным следом номера — задай "
                "настоящий прод-секрет (как требует прод-конфиг) и повтори миграцию."
            )

        for coupon_id, phone_key, redeemed_at in to_insert:
            bind.execute(
                sa.text(
                    f"INSERT INTO {_TABLE} (coupon_id, phone_key, created_at) "
                    "VALUES (:cid, :key, :created_at)"
                ),
                {"cid": coupon_id, "key": phone_key, "created_at": redeemed_at},
            )


def downgrade() -> None:
    bind = op.get_bind()
    if not _table_exists(bind):
        return
    count = bind.execute(sa.text(f"SELECT COUNT(*) FROM {_TABLE}")).scalar_one()
    if count == 0:
        op.drop_table(_TABLE)
        return
    # Таблица не пуста — либо перенос исторических данных, либо живой `coupon_redeem` уже
    # что-то записал. HMAC необратим (как и у `promo_claim_hmac`): удалить эти строки —
    # то же самое, что стереть единственный оставшийся след номера, который они и призваны
    # хранить. Безопасный no-op вместо лживого отката.
    pass
