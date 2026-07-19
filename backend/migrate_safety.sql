-- Система «Справедливость» (Trust, Safety & Fairness) — прод-миграция PostgreSQL.
-- Аддитивно и идемпотентно. На проде init_db() (create_all) сам создаёт НОВЫЕ таблицы
-- (incident, safetyprofile) и их индексы; этот файл нужен для НОВЫХ КОЛОНОК в уже
-- существующих booking/rating (create_all их не добавляет) + ручного/контрольного применения.
-- См. docs/trust-safety.md §5.

-- ---- Booking: отмена / неявка / оплата / курьер ----
ALTER TABLE booking ADD COLUMN IF NOT EXISTS cancelled_by          integer;
ALTER TABLE booking ADD COLUMN IF NOT EXISTS cancel_reason         varchar   NOT NULL DEFAULT '';
ALTER TABLE booking ADD COLUMN IF NOT EXISTS cancel_note           varchar   NOT NULL DEFAULT '';
ALTER TABLE booking ADD COLUMN IF NOT EXISTS cancelled_at          timestamp;
ALTER TABLE booking ADD COLUMN IF NOT EXISTS no_show               boolean   NOT NULL DEFAULT false;
ALTER TABLE booking ADD COLUMN IF NOT EXISTS payment_state         varchar   NOT NULL DEFAULT '';
ALTER TABLE booking ADD COLUMN IF NOT EXISTS parcel_pickup_photo   varchar   NOT NULL DEFAULT '';
ALTER TABLE booking ADD COLUMN IF NOT EXISTS parcel_delivery_photo varchar   NOT NULL DEFAULT '';

-- ---- Rating: защита от мести/накрутки ----
ALTER TABLE rating ADD COLUMN IF NOT EXISTS excluded    boolean NOT NULL DEFAULT false;
ALTER TABLE rating ADD COLUMN IF NOT EXISTS comment     varchar NOT NULL DEFAULT '';
ALTER TABLE rating ADD COLUMN IF NOT EXISTS tags        varchar NOT NULL DEFAULT '';
ALTER TABLE rating ADD COLUMN IF NOT EXISTS incident_id integer;

-- ---- Incident: авто-детект «бампинга» (§1.1). Для прод-БД, где incident уже создан без колонки. ----
ALTER TABLE incident ADD COLUMN IF NOT EXISTS suspected_bump boolean NOT NULL DEFAULT false;

-- ---- Новые таблицы (обычно уже созданы create_all; здесь — как fallback) ----
CREATE TABLE IF NOT EXISTS incident (
    id                       SERIAL PRIMARY KEY,
    booking_id               integer,
    reporter_id              integer   NOT NULL,
    respondent_id            integer   NOT NULL,
    type                     varchar   NOT NULL,
    reporter_role            varchar   NOT NULL DEFAULT '',
    description              varchar   NOT NULL DEFAULT '',
    evidence_urls            varchar   NOT NULL DEFAULT '',
    status                   varchar   NOT NULL DEFAULT 'open',
    suspected_bump           boolean   NOT NULL DEFAULT false,
    respondent_statement     varchar   NOT NULL DEFAULT '',
    respondent_evidence_urls varchar   NOT NULL DEFAULT '',
    responded_at             timestamp,
    resolution               varchar   NOT NULL DEFAULT '',
    fault                    varchar   NOT NULL DEFAULT '',
    resolution_note          varchar   NOT NULL DEFAULT '',
    compensation_kop         integer   NOT NULL DEFAULT 0,
    appeal_text              varchar   NOT NULL DEFAULT '',
    appeal_status            varchar   NOT NULL DEFAULT '',
    resolved_by              integer,
    created_at               timestamp NOT NULL DEFAULT now(),
    updated_at               timestamp NOT NULL DEFAULT now(),
    resolved_at              timestamp
);
CREATE INDEX IF NOT EXISTS ix_incident_booking_id    ON incident (booking_id);
CREATE INDEX IF NOT EXISTS ix_incident_reporter_id   ON incident (reporter_id);
CREATE INDEX IF NOT EXISTS ix_incident_respondent_id ON incident (respondent_id);
CREATE INDEX IF NOT EXISTS ix_incident_type          ON incident (type);
CREATE INDEX IF NOT EXISTS ix_incident_status        ON incident (status);

CREATE TABLE IF NOT EXISTS safetyprofile (
    id              SERIAL PRIMARY KEY,
    user_id         integer   NOT NULL UNIQUE,
    strikes         integer   NOT NULL DEFAULT 0,
    warnings        integer   NOT NULL DEFAULT 0,
    standing        varchar   NOT NULL DEFAULT 'good',
    suspended_until timestamp,
    suspend_reason  varchar   NOT NULL DEFAULT '',
    last_strike_at  timestamp,
    rating_shield   boolean   NOT NULL DEFAULT false,
    created_at      timestamp NOT NULL DEFAULT now(),
    updated_at      timestamp NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_safetyprofile_user_id ON safetyprofile (user_id);
