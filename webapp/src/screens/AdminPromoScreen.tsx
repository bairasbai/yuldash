// ================================================================
//  Промокоды и кампании → /admin/promo (RequireAdmin).
//  GET /admin/promo — список со счётчиками applied/active (по active платим блогеру).
//  POST /admin/promo — создать код/кампанию (code → upper, уникальность).
//  POST /admin/promo/{id}/status {active} — вкл/выкл кампанию.
//  Двуязычно, все состояния, тач-цели ≥48px.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchAdminPromos,
  createAdminPromo,
  setPromoStatus,
  type AdminPromo,
  type AdminPromoIn,
} from "../api/admin";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList, ErrorState } from "../components/States";
import { formatRelative } from "../utils/format";
import { IconGift, IconCheck } from "../components/Icons";

type State = "loading" | "error" | "ready";

/** datetime-local (localtime) → ISO для бэка; пусто → null. */
function toIso(local: string): string | null {
  if (!local) return null;
  const d = new Date(local);
  return isNaN(d.getTime()) ? null : d.toISOString();
}

export default function AdminPromoScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [state, setState] = useState<State>("loading");
  const [promos, setPromos] = useState<AdminPromo[]>([]);
  const [showForm, setShowForm] = useState(false);

  const load = useCallback((signal?: AbortSignal) => {
    setState("loading");
    fetchAdminPromos(signal)
      .then((list) => {
        setPromos(list);
        setState("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setState("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  function patch(updated: AdminPromo) {
    setPromos((prev) => prev.map((p) => (p.id === updated.id ? updated : p)));
  }
  function prepend(created: AdminPromo) {
    setPromos((prev) => [created, ...prev]);
    setShowForm(false);
  }

  const appliedTotal = promos.reduce((s, p) => s + p.applied, 0);
  const activeTotal = promos.reduce((s, p) => s + p.active, 0);

  return (
    <>
      <SubHeader
        title={appText("Промокоды и кампании", "Промокодтар һәм кампаниялар")}
        subtitle={appText("Блогеры, партнёры, акции", "Блогерҙар, партнёрҙар, акциялар")}
        onBack={() => navigate(-1)}
      />

      <div className="stat-grid" style={{ marginBottom: 4 }}>
        <div className="stat-tile">
          <b>{promos.length}</b>
          <span>{appText("кампаний", "кампания")}</span>
        </div>
        <div className="stat-tile">
          <b>{appliedTotal}</b>
          <span>{appText("применили", "ҡулланды")}</span>
        </div>
        <div className="stat-tile stat-tile--hl">
          <b>{activeTotal}</b>
          <span>{appText("активных", "әүҙем")}</span>
        </div>
      </div>

      <button
        type="button"
        className={showForm ? "btn-soft" : "btn-primary"}
        style={{ marginTop: 4 }}
        onClick={() => setShowForm((v) => !v)}
      >
        {showForm ? appText("Скрыть форму", "Форманы йәшереү") : appText("Создать промокод", "Промокод булдырыу")}
      </button>

      {showForm && <PromoForm onCreated={prepend} />}

      {state === "loading" && <LoadingList count={3} />}
      {state === "error" && <ErrorState onRetry={() => load()} />}

      {state === "ready" && promos.length === 0 && (
        <div className="state" style={{ paddingTop: 24 }}>
          <div className="state__icon"><IconGift size={40} /></div>
          <h2>{appText("Пока нет кампаний", "Әле кампаниялар юҡ")}</h2>
          <p>{appText("Создай первый промокод — для блогера или акции.", "Блогер йәки акция өсөн беренсе промокод булдыр.")}</p>
        </div>
      )}

      {state === "ready" && promos.length > 0 && (
        <div className="admin-cards">
          {promos.map((p) => (
            <PromoCard key={p.id} promo={p} ru={ru} onPatch={patch} />
          ))}
        </div>
      )}
    </>
  );
}

const EMPTY: AdminPromoIn = {
  code: "",
  title: "",
  owner_phone: "",
  campaign: "",
  kind: "welcome",
  perk_value: 0,
  limit_total: 0,
  limit_per_user: 1,
};

function PromoForm({ onCreated }: { onCreated: (p: AdminPromo) => void }) {
  const { appText } = useLang();
  const [form, setForm] = useState<AdminPromoIn>(EMPTY);
  const [validFrom, setValidFrom] = useState("");
  const [validUntil, setValidUntil] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function upd<K extends keyof AdminPromoIn>(key: K, value: AdminPromoIn[K]) {
    setForm((f) => ({ ...f, [key]: value }));
  }

  async function submit() {
    if (busy) return;
    if (!form.code.trim()) {
      setError(appText("Нужен код промокода", "Промокод коды кәрәк")); // DRAFT
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const created = await createAdminPromo({
        ...form,
        code: form.code.trim().toUpperCase(),
        owner_phone: form.owner_phone?.trim() || undefined,
        valid_from: toIso(validFrom),
        valid_until: toIso(validUntil),
      });
      onCreated(created);
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось создать. Попробуй снова.", "Булдырып булманы. Ҡабат ҡара.") // DRAFT
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="admin-card" style={{ marginTop: 10 }}>
      <label className="field__label">{appText("Код", "Код")}</label>
      <input
        className="field__input"
        value={form.code}
        onChange={(e) => upd("code", e.target.value)}
        placeholder={appText("Например: SOSED", "Мәҫәлән: SOSED")}
        style={{ textTransform: "uppercase" }}
      />

      <label className="field__label" style={{ marginTop: 10 }}>{appText("Название", "Исем")}</label>
      <input
        className="field__input"
        value={form.title}
        onChange={(e) => upd("title", e.target.value)}
        placeholder={appText("Кампания блогера / акция", "Блогер кампанияһы / акция")}
      />

      <label className="field__label" style={{ marginTop: 10 }}>{appText("Тип бонуса", "Бонус төрө")}</label>
      <select
        className="field__input"
        value={form.kind}
        onChange={(e) => upd("kind", e.target.value as "welcome" | "boost")}
      >
        <option value="welcome">{appText("Приветствие (атрибуция)", "Сәләм (атрибуция)")}</option>
        <option value="boost">{appText("Бесплатные поднятия", "Бушлай күтәреүҙәр")}</option>
      </select>

      {form.kind === "boost" && (
        <>
          <label className="field__label" style={{ marginTop: 10 }}>
            {appText("Сколько поднятий", "Күпме күтәреү")}
          </label>
          <input
            className="field__input"
            type="number"
            inputMode="numeric"
            value={String(form.perk_value ?? 0)}
            onChange={(e) => upd("perk_value", Math.max(0, Number(e.target.value) || 0))}
          />
        </>
      )}

      <label className="field__label" style={{ marginTop: 10 }}>
        {appText("Телефон блогера (необязательно)", "Блогер телефоны (мотлаҡ түгел)")}
      </label>
      <input
        className="field__input"
        value={form.owner_phone ?? ""}
        onChange={(e) => upd("owner_phone", e.target.value)}
        placeholder="+7…"
      />

      <label className="field__label" style={{ marginTop: 10 }}>{appText("Кампания (метка)", "Кампания (билдә)")}</label>
      <input
        className="field__input"
        value={form.campaign}
        onChange={(e) => upd("campaign", e.target.value)}
        placeholder={appText("Например: instagram-май", "Мәҫәлән: instagram-май")}
      />

      <div className="field-row" style={{ marginTop: 10 }}>
        <div style={{ flex: 1 }}>
          <label className="field__label">{appText("Всего (0 = без лимита)", "Барлығы (0 = сикһеҙ)")}</label>
          <input
            className="field__input"
            type="number"
            inputMode="numeric"
            value={String(form.limit_total ?? 0)}
            onChange={(e) => upd("limit_total", Math.max(0, Number(e.target.value) || 0))}
          />
        </div>
        <div style={{ flex: 1 }}>
          <label className="field__label">{appText("На человека", "Кешегә")}</label>
          <input
            className="field__input"
            type="number"
            inputMode="numeric"
            value={String(form.limit_per_user ?? 1)}
            onChange={(e) => upd("limit_per_user", Math.max(1, Number(e.target.value) || 1))}
          />
        </div>
      </div>

      <div className="field-row" style={{ marginTop: 10 }}>
        <div style={{ flex: 1 }}>
          <label className="field__label">{appText("С даты (необяз.)", "Датанан (мотлаҡ түгел)")}</label>
          <input
            className="field__input"
            type="datetime-local"
            value={validFrom}
            onChange={(e) => setValidFrom(e.target.value)}
          />
        </div>
        <div style={{ flex: 1 }}>
          <label className="field__label">{appText("До даты (необяз.)", "Датаға тиклем")}</label>
          <input
            className="field__input"
            type="datetime-local"
            value={validUntil}
            onChange={(e) => setValidUntil(e.target.value)}
          />
        </div>
      </div>

      {error && <div className="auth__error">{error}</div>}

      <button type="button" className="btn-primary" onClick={submit} disabled={busy}>
        {busy ? appText("Создаём…", "Булдырабыҙ…") : appText("Создать промокод", "Промокод булдырыу")}
      </button>
    </div>
  );
}

function PromoCard({
  promo,
  ru,
  onPatch,
}: {
  promo: AdminPromo;
  ru: boolean;
  onPatch: (p: AdminPromo) => void;
}) {
  const { appText } = useLang();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function toggle() {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      const updated = await setPromoStatus(promo.id, !promo.active_flag);
      onPatch(updated);
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось. Попробуй ещё раз.", "Булманы. Ҡабат ҡара.") // DRAFT
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="admin-card">
      <div className="admin-card__head">
        <div className="admin-card__title" style={{ fontFamily: "monospace", letterSpacing: 1 }}>
          {promo.code}
        </div>
        <span className={`badge ${promo.active_flag ? "badge--mint" : "badge--muted"}`}>
          {promo.active_flag ? appText("Включён", "Ҡабыҙылған") : appText("Выключен", "Һүндерелгән")}
        </span>
      </div>

      {promo.title && <div className="admin-card__sub">{promo.title}</div>}
      <div className="admin-card__sub">
        {promo.kind === "boost"
          ? appText(`Бонус: ${promo.perk_value} поднятий`, `Бонус: ${promo.perk_value} күтәреү`)
          : appText("Приветствие", "Сәләм")}
        {promo.owner_id ? <> · {appText("блогер", "блогер")}</> : <> · {appText("акция Юлдаша", "Юлдаш акцияһы")}</>}
        {promo.campaign && <> · {promo.campaign}</>}
      </div>

      {/* Статистика: применили всего / из них реально активны (по ним платят блогеру). */}
      <div className="ad-stat-row">
        <span>{appText("Применили", "Ҡулланды")}: <b>{promo.applied}</b></span>
        <span>{appText("Активны", "Әүҙем")}: <b>{promo.active}</b></span>
        {promo.limit_total > 0 && (
          <span>{appText("Лимит", "Сик")}: <b>{promo.redeemed_count}/{promo.limit_total}</b></span>
        )}
      </div>
      {promo.created_at && (
        <div className="admin-card__sub">{formatRelative(promo.created_at, ru)}</div>
      )}

      {error && <div className="auth__error">{error}</div>}

      <button
        type="button"
        className="btn-soft btn-soft--sm"
        style={{ marginTop: 12 }}
        onClick={toggle}
        disabled={busy}
      >
        {busy ? appText("…", "…") : promo.active_flag ? (
          appText("Выключить", "Һүндереү")
        ) : (
          <><IconCheck size={16} /> {appText("Включить", "Ҡабыҙыу")}</>
        )}
      </button>
    </div>
  );
}
