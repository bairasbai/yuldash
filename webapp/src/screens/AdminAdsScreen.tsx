// ================================================================
//  Управление рекламой → /admin/ads (RequireAdmin).
//  GET /admin/ads — все объявления (кроме архива) + занятые founder-слоты.
//  GET /ads/stats — показы/клики по каждому.
//  Партнёрские (pending_review): одобрить (с erid-маркировкой ОРД) / отклонить (с причиной).
//  Активные/на паузе: пауза ⇄ публикация, архив («Удалить»). Своё объявление админ заводит
//  и правит здесь же (POST /admin/ads, POST /admin/ads/{id}) — партнёр из райцентра приходит
//  по телефону, а не через кабинет.
//  Зеркало AdminAdsScreen.kt: строка «Founder N/M» + «Создать», форма-карточка CreateAdForm,
//  карточки AdAdminCard (пилюля статуса, «Нет erid», места · до · показы · клики, кнопки в ряд).
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchAdminAds,
  fetchAdStats,
  approveAd,
  rejectAd,
  setAdStatus,
  archiveAd,
  adminCreateAd,
  adminUpdateAd,
  type AdminAd,
  type AdStats,
  type AdminAdInput,
} from "../api/admin";
import { SubHeader } from "./ConsentsScreen";
import { AdminFilterChips, ListedError } from "../components/adminUi";
import { IconCheck } from "../components/Icons";

type State = "loading" | "error" | "ready";

/** Фильтр по статусу есть только в вебе (в приложении — один список). */
const FILTERS: { key: string; ru: string; ba: string }[] = [
  { key: "", ru: "Все", ba: "Барыһы" },
  { key: "pending_review", ru: "На модерации", ba: "Тикшереүҙә" },
  { key: "active", ru: "Активные", ba: "Актив" },
  { key: "rejected", ru: "Отклонённые", ba: "Кире ҡағылған" },
];

const PLAN_OPTIONS: ("founder" | "standard" | "premium")[] = ["founder", "standard", "premium"];

/** Места показа — те же ключи, что в кабинете партнёра (PLACEMENT_OPTIONS). */
const PLACEMENT_OPTIONS: { key: string; ru: string; ba: string }[] = [
  { key: "home", ru: "Главная", ba: "Төп бит" },
  { key: "rides", ru: "Поездки", ba: "Сәфәрҙәр" },
  { key: "nearby", ru: "Рядом", ba: "Янда" },
  { key: "tripDetails", ru: "Поездка", ba: "Сәфәр" },
  { key: "help", ru: "Помощь", ba: "Ярҙам" },
];

function planLabel(p: string, appText: (r: string, b: string) => string): string {
  if (p === "founder") return appText("Основатель", "Нигеҙләүсе");
  if (p === "premium") return appText("Премиум", "Премиум");
  return appText("Стандарт", "Стандарт");
}

export default function AdminAdsScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [filter, setFilter] = useState<string>("");
  const [state, setState] = useState<State>("loading");
  const [ads, setAds] = useState<AdminAd[]>([]);
  const [founder, setFounder] = useState<{ used: number; limit: number }>({ used: 0, limit: 10 });
  const [stats, setStats] = useState<AdStats>({});
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<AdminAd | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setState("loading");
    fetchAdminAds(signal)
      .then((res) => {
        setAds(res.items);
        setFounder({ used: res.founder_used, limit: res.founder_limit });
        setState("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setState("error");
      });
    // Статистика — не критична: ошибку глотаем.
    fetchAdStats(signal).then(setStats).catch(() => {});
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  function patch(id: string, next: Partial<AdminAd>) {
    setAds((prev) => prev.map((a) => (a.id === id ? { ...a, ...next } : a)));
  }
  function drop(id: string) {
    setAds((prev) => prev.filter((a) => a.id !== id));
  }

  const shown = filter ? ads.filter((a) => a.status === filter) : ads;

  return (
    <>
      <SubHeader title={appText("Управление рекламой", "Реклама идаралау")} onBack={() => navigate(-1)} />
      <div className="alist">
        {state === "loading" && <div className="spinner-wrap"><span className="spinner" aria-hidden /></div>}
        {state === "error" && <ListedError onRetry={() => load()} />}

        {state === "ready" && (
          <>
            {/* Счётчик founder-слотов + «Создать». */}
            <div className="acard__row">
              <span className="ads-founder">Founder {founder.used}/{founder.limit}</span>
              <span className="acard__spacer" />
              <button
                type="button"
                className="ads-btn ads-btn--fill"
                onClick={() => {
                  setEditing(null);
                  setShowForm((v) => !v);
                }}
              >
                + {appText("Создать", "Булдырыу")}
              </button>
            </div>

            {(showForm || editing) && (
              <CreateAdForm
                key={editing?.id ?? "new"}
                founderFull={founder.used >= founder.limit}
                edit={editing}
                onDone={() => {
                  setShowForm(false);
                  setEditing(null);
                  load();
                }}
              />
            )}

            <AdminFilterChips
              label={appText("Фильтр рекламы", "Реклама фильтры")}
              options={FILTERS.map((f) => ({ key: f.key, label: appText(f.ru, f.ba) }))}
              value={filter}
              onChange={setFilter}
            />

            {shown.length === 0 && (
              <div className="ads-empty">
                <IconCheck size={40} />
                <strong>{appText("Объявлений пока нет", "Иғландар юҡ әле")}</strong>
                <span>{appText("Создай первое — оно появится в приложении после публикации", "Беренсене булдыр — баҫтырғас ҡушымтала күренер")}</span>
              </div>
            )}

            {shown.map((a) => (
              <AdCard
                key={a.id}
                ad={a}
                stat={stats[a.id]}
                onPatch={patch}
                onDrop={drop}
                onEdit={() => {
                  setEditing(a);
                  setShowForm(false);
                }}
              />
            ))}
          </>
        )}
      </div>
    </>
  );
}

/** AdAdminCard: пилюля статуса на 14 % своего цвета, «партнёр · тариф», «Нет erid», текст, места/срок/статистика, кнопки. */
function AdCard({
  ad,
  stat,
  onPatch,
  onDrop,
  onEdit,
}: {
  ad: AdminAd;
  stat?: { impressions: number; clicks: number };
  onPatch: (id: string, next: Partial<AdminAd>) => void;
  onDrop: (id: string) => void;
  onEdit: () => void;
}) {
  const { appText } = useLang();
  const [busy, setBusy] = useState<null | string>(null);
  const [error, setError] = useState<string | null>(null);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState(ad.reject_reason || "");

  const tone =
    ad.status === "pending_review" || ad.status === "paused"
      ? "gold"
      : ad.status === "rejected" || (ad.status === "active" && ad.expired && !ad.live)
        ? "red"
        : ad.status === "active" && ad.live
          ? "green"
          : "muted";
  const statusLabel =
    ad.status === "active"
      ? ad.live
        ? appText("Активно", "Актив")
        : ad.expired
          ? appText("Истекло", "Бөттө")
          : appText("Запланировано", "Планлы")
      : ad.status === "pending_review"
        ? appText("На модерации", "Тикшереүҙә")
        : ad.status === "rejected"
          ? appText("Отклонено", "Кире ҡағылды")
          : ad.status === "paused"
            ? appText("Пауза", "Туҡтатылған")
            : ad.status === "draft"
              ? appText("Черновик", "Ҡаралама")
              : ad.status;

  function fail(e: unknown) {
    setError(
      e instanceof ApiError && e.message
        ? e.message
        : appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")
    );
  }

  async function run(key: string, fn: () => Promise<unknown>, after: () => void) {
    if (busy) return;
    setBusy(key);
    setError(null);
    try {
      await fn();
      after();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(null);
    }
  }

  const imp = stat?.impressions ?? 0;
  const clk = stat?.clicks ?? 0;
  const places = ad.placements.length > 0 ? ad.placements.join(",") : "—";
  const meta =
    appText(`Места: ${places}`, `Урын: ${places}`) +
    (ad.ends_at ? appText(` · до ${ad.ends_at.slice(0, 10)}`, ` · ${ad.ends_at.slice(0, 10)} тиклем`) : "") +
    appText(`  ·  показы ${imp} · клики ${clk}`, `  ·  күрһәтеү ${imp} · баҫыу ${clk}`);

  return (
    <article className="ads-card">
      <div className="acard__row">
        <strong className="acard__title acard__grow">{ad.title || appText("Без названия", "Исемһеҙ")}</strong>
        <span className={`ads-pill ads-pill--${tone}`}>{statusLabel}</span>
      </div>
      <small className="ads-meta ads-meta--bold">
        {(ad.partner || (ad.owner_id != null ? appText("Партнёр", "Партнёр") : "—")) + " · " + planLabel(ad.plan, appText)}
      </small>
      {/* Без маркировки объявление в эфир не идёт: показ без erid — нарушение закона о рекламе. */}
      {!ad.erid && <span className="ads-pill ads-pill--red acard__self">{appText("Нет erid — не показывается", "erid юҡ — күрһәтелмәй")}</span>}
      {ad.text && <small className="ads-meta">{ad.text}</small>}
      <small className="ads-meta">{meta}</small>
      {ad.status === "rejected" && ad.reject_reason && (
        <small className="ads-meta ads-meta--red">{appText(`Причина отказа: ${ad.reject_reason}`, `Кире ҡағыу сәбәбе: ${ad.reject_reason}`)}</small>
      )}

      {error && <div className="auth__error">{error}</div>}

      {/* Модерация: pending_review → одобрить / отклонить (с причиной). */}
      {ad.status === "pending_review" &&
        (rejecting ? (
          <>
            <label className="field">
              <span className="field__label">{appText("Причина отказа (партнёр увидит)", "Кире ҡағыу сәбәбе (партнёр күрә)")}</span>
              <textarea className="field__input field__area" rows={2} value={reason} onChange={(e) => setReason(e.target.value)} autoFocus />
            </label>
            <div className="acard__actions">
              <button type="button" className="ads-btn" onClick={() => setRejecting(false)} disabled={busy !== null}>
                {appText("Отмена", "Кире")}
              </button>
              <button
                type="button"
                className="ads-btn ads-btn--danger"
                onClick={() => run("reject", () => rejectAd(ad.id, reason.trim()), () => { onPatch(ad.id, { status: "rejected", reject_reason: reason.trim() }); setRejecting(false); })}
                disabled={busy !== null || !reason.trim()}
              >
                {busy === "reject" ? appText("…", "…") : appText("Отклонить", "Кире ҡағырға")}
              </button>
            </div>
          </>
        ) : (
          <div className="acard__actions">
            <button
              type="button"
              className="ads-btn ads-btn--fill"
              onClick={() => run("approve", () => approveAd(ad.id, ad.erid), () => onPatch(ad.id, { status: "active" }))}
              disabled={busy !== null}
            >
              {busy === "approve" ? appText("…", "…") : appText("Одобрить", "Раҫларға")}
            </button>
            <button type="button" className="ads-btn ads-btn--red" onClick={() => setRejecting(true)} disabled={busy !== null}>
              {appText("Отклонить", "Кире ҡағырға")}
            </button>
          </div>
        ))}

      <div className="acard__actions ads-actions">
        {ad.status === "active" ? (
          <button
            type="button"
            className="ads-btn ads-btn--grow"
            onClick={() => run("paused", () => setAdStatus(ad.id, "paused"), () => onPatch(ad.id, { status: "paused" }))}
            disabled={busy !== null}
          >
            {busy === "paused" ? appText("…", "…") : appText("Пауза", "Туҡтатылған")}
          </button>
        ) : ad.status !== "pending_review" ? (
          <button
            type="button"
            className="ads-btn ads-btn--fill ads-btn--grow"
            onClick={() => run("active", () => setAdStatus(ad.id, "active"), () => onPatch(ad.id, { status: "active" }))}
            disabled={busy !== null}
          >
            {busy === "active" ? appText("…", "…") : appText("Опубликовать", "Баҫтырырға")}
          </button>
        ) : null}
        <button type="button" className="ads-btn" onClick={onEdit} disabled={busy !== null}>
          {appText("Изменить", "Үҙгәртергә")}
        </button>
        <button
          type="button"
          className="ads-btn ads-btn--red"
          onClick={() => run("archive", () => archiveAd(ad.id), () => onDrop(ad.id))}
          disabled={busy !== null}
        >
          {busy === "archive" ? appText("…", "…") : appText("Удалить", "Бөтөрөргә")}
        </button>
      </div>
    </article>
  );
}

/** CreateAdForm: карточка с полями, чипами тарифа и мест показа, «Создать (черновик)» / «Сохранить». */
function CreateAdForm({ founderFull, edit, onDone }: { founderFull: boolean; edit: AdminAd | null; onDone: () => void }) {
  const { appText } = useLang();
  const [partner, setPartner] = useState(edit?.partner ?? "");
  const [contact, setContact] = useState(edit?.partner_contact ?? "");
  const [title, setTitle] = useState(edit?.title ?? "");
  const [text, setText] = useState(edit?.text ?? "");
  const [button, setButton] = useState(edit?.button ?? "");
  const [erid, setErid] = useState(edit?.erid ?? "");
  const [target, setTarget] = useState(edit?.target ?? "");
  const [city, setCity] = useState(edit?.cities.join(",") ?? "");
  const [price, setPrice] = useState("");
  const [plan, setPlan] = useState<"founder" | "standard" | "premium">((edit?.plan as "founder" | "standard" | "premium") || "standard");
  const [places, setPlaces] = useState<string[]>(edit?.placements ?? []);
  const [sending, setSending] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const canSave = !!partner.trim() && !!title.trim() && text.trim().length >= 3 && !!erid.trim() && !sending;

  async function submit() {
    if (!canSave) return;
    setSending(true);
    setErr(null);
    const body: AdminAdInput = {
      partner_name: partner.trim(),
      partner_contact: contact.trim(),
      title: title.trim(),
      text: text.trim(),
      button: button.trim(),
      plan,
      placements: places.join(","),
      erid: erid.trim(),
      target: target.trim(),
      cities: city.trim(),
    };
    try {
      if (edit) await adminUpdateAd(edit.id, body);
      else await adminCreateAd({ ...body, price: Number(price) || 0 });
      onDone();
    } catch (e) {
      setErr(e instanceof ApiError && e.message ? e.message : appText("Не удалось сохранить", "Һаҡлап булманы"));
    } finally {
      setSending(false);
    }
  }

  return (
    <div className="ads-card ads-form">
      <strong className="acard__title">{edit ? appText("Изменить объявление", "Иғланды үҙгәртеү") : appText("Новое объявление", "Яңы иғлан")}</strong>
      <label className="field">
        <span className="field__label">{appText("Рекламодатель", "Рекламала ҡатнашыусы")}</span>
        <input className="field__input" value={partner} onChange={(e) => setPartner(e.target.value)} />
      </label>
      {/* Есть только в вебе: как связаться с партнёром. */}
      <label className="field">
        <span className="field__label">{appText("Как с ним связаться", "Уның менән нисек бәйләнешергә")}</span>
        <input className="field__input" value={contact} onChange={(e) => setContact(e.target.value)} />
      </label>
      <label className="field">
        <span className="field__label">{appText("Заголовок", "Башлыҡ")}</span>
        <input className="field__input" value={title} onChange={(e) => setTitle(e.target.value)} />
      </label>
      <label className="field">
        <span className="field__label">{appText("Текст", "Текст")}</span>
        <textarea className="field__input field__area" rows={2} value={text} onChange={(e) => setText(e.target.value)} />
      </label>
      <div className="ads-form__row">
        <label className="field">
          <span className="field__label">{appText("Кнопка", "Төймә")}</span>
          <input className="field__input" value={button} onChange={(e) => setButton(e.target.value)} />
        </label>
        <label className="field">
          <span className="field__label">{appText("Город", "Ҡала")}</span>
          <input className="field__input" value={city} onChange={(e) => setCity(e.target.value)} />
        </label>
      </div>
      <label className="field">
        <span className="field__label">{appText("Ссылка при клике", "Баҫҡанда һылтанма")}</span>
        <input className="field__input" value={target} onChange={(e) => setTarget(e.target.value)} />
      </label>
      <label className="field">
        <span className="field__label">{appText("erid (маркировка)", "erid (билдәләмә)")}</span>
        <input className="field__input" value={erid} onChange={(e) => setErid(e.target.value)} />
      </label>
      {!edit && (
        <label className="field">
          <span className="field__label">{appText("Цена партнёру, ₽ (0 — без оплаты)", "Партнёр хаҡы, ₽ (0 — түләүһеҙ)")}</span>
          <input className="field__input" inputMode="numeric" value={price} onChange={(e) => setPrice(e.target.value.replace(/\D/g, "").slice(0, 7))} />
        </label>
      )}

      <span className="ads-form__label">{appText("Тариф", "Тариф")}</span>
      <div className="ads-chips" role="radiogroup">
        {PLAN_OPTIONS.map((p) => {
          const sel = plan === p;
          const disabled = p === "founder" && founderFull && !sel;
          return (
            <button
              key={p}
              type="button"
              role="radio"
              aria-checked={sel}
              className={"ads-chip" + (sel ? " is-on" : "") + (disabled ? " is-off" : "")}
              onClick={() => !disabled && setPlan(p)}
              disabled={disabled}
            >
              {planLabel(p, appText) + (disabled ? appText(" (нет мест)", " (урын юҡ)") : "")}
            </button>
          );
        })}
      </div>

      <span className="ads-form__label">{appText("Места показа", "Күрһәтеү урындары")}</span>
      <div className="ads-chips" role="group">
        {PLACEMENT_OPTIONS.map((o) => {
          const sel = places.includes(o.key);
          return (
            <button
              key={o.key}
              type="button"
              aria-pressed={sel}
              className={"ads-chip ads-chip--place" + (sel ? " is-on" : "")}
              onClick={() => setPlaces((prev) => (sel ? prev.filter((k) => k !== o.key) : [...prev, o.key]))}
            >
              {appText(o.ru, o.ba)}
            </button>
          );
        })}
      </div>

      {err && <div className="auth__error">{err}</div>}
      <button type="button" className="ads-btn ads-btn--fill ads-btn--tall" onClick={submit} disabled={!canSave}>
        {sending ? appText("…", "…") : edit ? appText("Сохранить", "Һаҡлау") : appText("Создать (черновик)", "Булдырыу (ҡаралама)")}
      </button>
      {!edit && (
        <small className="ads-meta">
          {appText(
            "Указал цену → объявление попадёт в «Заявки на оплату». Партнёр заплатил → подтвердишь → реклама опубликуется. Цена 0 → публикуешь вручную.",
            "Хаҡ ҡуйһаң → иғлан «Түләү заявкалары»на эләгә. Партнёр түләне → раҫлайһың → реклама баҫтырыла. Хаҡ 0 → үҙең баҫтыр."
          )}
        </small>
      )}
    </div>
  );
}
