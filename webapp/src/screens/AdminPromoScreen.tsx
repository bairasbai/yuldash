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
import { RideCardSkeleton } from "../components/States";
import { AdminIntro, ListedEmpty, ListedError } from "../components/adminUi";
import { IconGift, IconRocket, IconTicket } from "../components/Icons";

type State = "loading" | "error" | "ready";

export default function AdminPromoScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [state, setState] = useState<State>("loading");
  const [promos, setPromos] = useState<AdminPromo[]>([]);
  const [showForm, setShowForm] = useState(false);

  const load = useCallback((signal?: AbortSignal) => {
    setState("loading");
    fetchAdminPromos(signal)
      .then((list) => {
        // Включённые — сверху, затем по дате (свежие выше), как в приложении.
        const rank = (p: AdminPromo) => (p.active_flag ? 1 : 0);
        setPromos([...list].sort((a, b) => rank(b) - rank(a) || (b.created_at ?? "").localeCompare(a.created_at ?? "")));
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

  // Форма — отдельный экран, как PromoCreateForm в приложении.
  if (showForm) {
    return <PromoForm onBack={() => setShowForm(false)} onCreated={prepend} />;
  }

  return (
    <>
      <SubHeader title={appText("Промокоды и кампании", "Промокодтар һәм акциялар")} onBack={() => navigate(-1)} />
      <div className="alist">
        <AdminIntro>
          {appText(
            "Коды для блогеров и акций. Applied — сколько ввели, Active — сколько стали активными.",
            "Блогерҙар һәм акциялар өсөн кодтар. Applied — нисә кеше индерҙе, Active — нисәһе актив булды."
          )}
        </AdminIntro>
        <button type="button" className="btn-primary btn-accent promo-create" onClick={() => setShowForm(true)}>
          + {appText("Создать код", "Код булдырыу")}
        </button>

        {state === "loading" && (
          <>
            <RideCardSkeleton />
            <RideCardSkeleton />
          </>
        )}
        {state === "error" && <ListedError onRetry={() => load()} />}

        {state === "ready" && promos.length === 0 && (
          <ListedEmpty
            title={appText("Пока нет кодов", "Әлегә кодтар юҡ")}
            subtitle={appText("Создай первый промокод — для блогера или акции.", "Беренсе промокодты булдыр — блогер йәки акция өсөн.")}
          />
        )}

        {state === "ready" && promos.map((p, i) => <PromoCard key={p.id} promo={p} index={i} onPatch={patch} />)}
      </div>
    </>
  );
}

/** «гггг-мм-дд» из ISO или пусто. */
function shortDate(iso: string | null): string {
  return iso && iso.length >= 10 ? iso.slice(0, 10) : "";
}

function PromoForm({ onBack, onCreated }: { onBack: () => void; onCreated: (p: AdminPromo) => void }) {
  const { appText } = useLang();
  const [code, setCode] = useState("");
  const [title, setTitle] = useState("");
  const [campaign, setCampaign] = useState("");
  const [kind, setKind] = useState<"welcome" | "boost">("welcome");
  const [perkValue, setPerkValue] = useState("1");
  const [ownerPhone, setOwnerPhone] = useState("");
  const [limitTotal, setLimitTotal] = useState("100");
  const [validUntil, setValidUntil] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const isBoost = kind === "boost";
  const canSubmit = !!code.trim() && !!campaign.trim();

  async function submit() {
    if (busy) return;
    const c = code.trim();
    if (!c || !campaign.trim()) {
      setError(appText("Заполни код и кампанию.", "Код һәм кампанияны тултыр."));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const body: AdminPromoIn = {
        code: c.toUpperCase(),
        title: title.trim() || c,
        description: "",
        campaign: campaign.trim(),
        kind,
        perk_value: isBoost ? Number(perkValue) || 0 : 0,
        limit_total: Number(limitTotal) || 0,
        limit_per_user: 1, // на человека код всегда один — см. подпись в форме
        owner_phone: ownerPhone.trim() || undefined,
        valid_until: validUntil.trim() || null,
      };
      const created = await createAdminPromo(body);
      onCreated(created);
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось создать. Повтори.", "Булдырып булманы. Ҡабатла.")
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <SubHeader title={appText("Новый промокод", "Яңы промокод")} onBack={onBack} />
      <div className="alist">
        <label className="field">
          <span className="field__label">{appText("Код", "Код")}</span>
          <input
            className="field__input promo-code-input"
            value={code}
            onChange={(e) => setCode(e.target.value.toUpperCase().replace(/\s+/g, ""))}
            placeholder="BLOGER10"
            autoCapitalize="characters"
          />
        </label>
        <label className="field">
          <span className="field__label">{appText("Название", "Исеме")}</span>
          <input className="field__input" value={title} onChange={(e) => setTitle(e.target.value)} placeholder={appText("Осенняя акция", "Көҙгө акция")} />
        </label>
        <label className="field">
          <span className="field__label">{appText("Кампания", "Кампания")}</span>
          <input className="field__input" value={campaign} onChange={(e) => setCampaign(e.target.value)} placeholder="autumn_2026" />
        </label>

        <div className="promo-kind">
          <span className="acard__label">{appText("Что даёт код", "Код нимә бирә")}</span>
          <div className="promo-kind__row" role="radiogroup">
            <button type="button" role="radio" aria-checked={!isBoost} className={"promo-kind__chip" + (!isBoost ? " is-on" : "")} onClick={() => setKind("welcome")}>
              <IconGift size={18} /> {appText("Приветствие", "Сәләмләү")}
            </button>
            <button type="button" role="radio" aria-checked={isBoost} className={"promo-kind__chip" + (isBoost ? " is-on" : "")} onClick={() => setKind("boost")}>
              <IconRocket size={18} /> {appText("Поднятия", "Күтәреү")}
            </button>
          </div>
        </div>

        {isBoost && (
          <label className="field">
            <span className="field__label">{appText("Сколько бесплатных поднятий", "Нисә бушлай күтәреү")}</span>
            <input className="field__input" inputMode="numeric" value={perkValue} onChange={(e) => setPerkValue(e.target.value.replace(/\D/g, ""))} placeholder="3" />
          </label>
        )}
        <label className="field">
          <span className="field__label">{appText("Телефон блогера (необязательно)", "Блогер телефоны (мәжбүри түгел)")}</span>
          <input className="field__input" inputMode="tel" value={ownerPhone} onChange={(e) => setOwnerPhone(e.target.value)} placeholder="+7 917 000 00 00" />
        </label>
        {/* Поле «на человека» убрано намеренно: сервер выдаёт код ОДИН РАЗ на аккаунт. */}
        <label className="field">
          <span className="field__label">{appText("Лимит всего", "Бөтә лимит")}</span>
          <input className="field__input" inputMode="numeric" value={limitTotal} onChange={(e) => setLimitTotal(e.target.value.replace(/\D/g, ""))} placeholder="100" />
        </label>
        <label className="field">
          <span className="field__label">{appText("Действует до (гггг-мм-дд, необязательно)", "Тиклем ғәмәлдә (гггг-мм-дд, мәжбүри түгел)")}</span>
          <input className="field__input" type="date" value={validUntil} onChange={(e) => setValidUntil(e.target.value)} placeholder="2026-12-31" />
        </label>

        {error && <div className="auth__error">{error}</div>}

        <button type="button" className="btn-primary btn-accent promo-create" onClick={submit} disabled={busy || !canSubmit}>
          {busy ? appText("Создаём…", "Булдырабыҙ…") : <><IconTicket size={20} /> {appText("Создать код", "Код булдырыу")}</>}
        </button>
      </div>
    </>
  );
}

function PromoCard({ promo, index, onPatch }: { promo: AdminPromo; index: number; onPatch: (p: AdminPromo) => void }) {
  const { appText } = useLang();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const isBoost = promo.kind.toLowerCase() === "boost";
  const until = shortDate(promo.valid_until);

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
          : appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <article className="acard acard--md" style={{ animationDelay: `calc(var(--cascade-in) * ${Math.min(index, 6)})` }}>
      <div className="acard__row acard__row--md">
        <span className="partner-tile" aria-hidden>{isBoost ? <IconRocket size={22} /> : <IconGift size={22} />}</span>
        <span className="acard__stack acard__grow">
          <strong className="promo-code">{promo.code}</strong>
          <span className="acard__caption acard__text">{promo.title || promo.campaign}</span>
        </span>
        <button
          type="button"
          role="switch"
          aria-checked={promo.active_flag}
          aria-label={promo.code}
          className="acity__switch"
          onClick={toggle}
          disabled={busy}
        >
          <span className={"switch" + (promo.active_flag ? " on" : "")} aria-hidden />
        </button>
      </div>
      {/* Тип + бонус */}
      <span className="abadge abadge--ok acard__self">
        {isBoost ? appText(`Boost · ${promo.perk_value} поднятий`, `Boost · ${promo.perk_value} күтәреү`) : appText("Приветствие", "Сәләмләү")}
      </span>
      {/* Воронка applied → active */}
      <div className="promo-funnel">
        <span className="promo-metric">
          <b>{promo.applied}</b>
          <small>{appText("Ввели", "Индерҙе")}</small>
        </span>
        <span className="promo-funnel__arrow" aria-hidden>→</span>
        <span className="promo-metric">
          <b>{promo.active}</b>
          <small>{appText("Активны", "Актив")}</small>
        </span>
        <span className="acard__spacer" />
        <small className="promo-funnel__of">{appText(`из ${promo.limit_total}`, `${promo.limit_total} тан`)}</small>
      </div>
      <span className="acard__sub">
        {appText(`Лимит: ${promo.limit_total} всего · один раз на человека`, `Лимит: ${promo.limit_total} бөтәһе · бер кешегә бер тапҡыр`)}
      </span>
      {promo.owner_id != null && (
        <small className="acard__date">{appText(`Код блогера (владелец #${promo.owner_id})`, `Блогер коды (эйәһе #${promo.owner_id})`)}</small>
      )}
      {until && <small className="acard__date">{appText(`Действует до ${until}`, `${until} тиклем ғәмәлдә`)}</small>}
      {!promo.active_flag && <span className="abadge abadge--wait acard__self">{appText("Выключен", "Һүндерелгән")}</span>}
      {error && <div className="auth__error">{error}</div>}
    </article>
  );
}
