// ================================================================
//  Модерация бизнесов → /admin/partners (RequireAdmin).
//  GET /admin/partners — очередь модерации (pending сверху с бэка).
//  Одобрить (→ active): POST /admin/partners/{id}/approve.
//  Отклонить с причиной (→ rejected): POST /admin/partners/{id}/reject {reason}.
//  Двуязычно, все состояния, тач-цели ≥48px.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchAdminPartners,
  approvePartner,
  rejectPartner,
  type AdminPartner,
} from "../api/admin";
import { SubHeader } from "./ConsentsScreen";
import { RideCardSkeleton } from "../components/States";
import { AdminFilterChips, AdminIntro, ListedEmpty, ListedError } from "../components/adminUi";
import { IconPhone, IconPin, IconStar, IconStore, IconTicket } from "../components/Icons";

type State = "loading" | "error" | "ready";

/** Фильтр по статусу есть только в вебе (в приложении — один список, pending сверху). */
const FILTERS: { key: string; ru: string; ba: string }[] = [
  { key: "pending", ru: "На проверке", ba: "Тикшереүҙә" },
  { key: "active", ru: "Активные", ba: "Әүҙем" },
  { key: "rejected", ru: "Отклонённые", ba: "Кире ҡағылған" },
  { key: "", ru: "Все", ba: "Барыһы" },
];

/** Подпись категории — как couponCategoryLabel в приложении; незнакомую отдаём как есть. */
function categoryLabel(category: string, appText: (r: string, b: string) => string): string {
  switch (category.toLowerCase()) {
    case "cafe":
      return appText("Кафе", "Кафе");
    case "restaurant":
      return appText("Ресторан", "Ресторан");
    case "food":
    case "grocery":
      return appText("Продукты", "Аҙыҡ-түлек");
    case "beauty":
      return appText("Красота", "Матурлыҡ");
    case "auto":
    case "car":
      return appText("Авто", "Авто");
    case "pharmacy":
      return appText("Аптека", "Дарыухана");
    case "fuel":
    case "gas":
      return appText("Заправка", "Заправка");
    case "shop":
    case "store":
      return appText("Магазин", "Кибет");
    case "":
      return appText("Заведение", "Урын");
    default:
      return category;
  }
}

/** Иконка категории (couponCategoryIcon): витрина для еды, звезда для красоты, ярлык для прочего. */
function categoryIcon(category: string) {
  const c = category.toLowerCase();
  if (["cafe", "restaurant", "food", "кафе", "ресторан", "еда"].includes(c)) return <IconStore size={22} />;
  if (["beauty", "салон", "красота"].includes(c)) return <IconStar size={22} />;
  return <IconTicket size={22} />;
}

/** AdminPartnerStatusChip: активен — мятный, отклонён — красный, пауза/проверка — жёлтый, архив — muted. */
function statusChip(s: string): { tone: string; ru: string; ba: string } {
  switch (s) {
    case "active":
      return { tone: "ok", ru: "Активен", ba: "Актив" };
    case "rejected":
      return { tone: "bad", ru: "Отклонён", ba: "Кире ҡағылған" };
    case "paused":
      return { tone: "wait", ru: "Пауза", ba: "Пауза" };
    case "archived":
      return { tone: "muted", ru: "Архив", ba: "Архив" };
    default:
      return { tone: "wait", ru: "На проверке", ba: "Тикшереүҙә" };
  }
}

export default function AdminPartnersScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [filter, setFilter] = useState<string>("pending");
  const [state, setState] = useState<State>("loading");
  const [partners, setPartners] = useState<AdminPartner[]>([]);

  const load = useCallback((signal?: AbortSignal) => {
    setState("loading");
    fetchAdminPartners({ limit: 300, signal })
      .then((list) => {
        // pending — сверху, затем по дате (свежие выше), как в приложении.
        const rank = (p: AdminPartner) => (p.status === "pending" ? 1 : 0);
        setPartners([...list].sort((a, b) => rank(b) - rank(a) || (b.created_at ?? "").localeCompare(a.created_at ?? "")));
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

  function patch(id: number, next: Partial<AdminPartner>) {
    setPartners((prev) => prev.map((p) => (p.id === id ? { ...p, ...next } : p)));
  }

  const shown = filter ? partners.filter((p) => p.status === filter) : partners;
  const pendingN = partners.filter((p) => p.status === "pending").length;

  return (
    <>
      <SubHeader title={appText("Бизнесы-партнёры", "Партнёр-бизнестар")} onBack={() => navigate(-1)} />
      <div className="alist">
        <AdminIntro>
          {appText(
            "Проверь заведения, которые хотят размещать купоны. Одобри — бизнес сможет публиковать скидки.",
            "Купон ҡуйырға теләгән урындарҙы тикшер. Раҫла — бизнес ташлама баҫтыра алыр."
          )}
        </AdminIntro>
        {pendingN > 0 && (
          <span className="pending-pill">{appText(`Ждут проверки: ${pendingN}`, `Тикшереүҙе көтә: ${pendingN}`)}</span>
        )}

        <AdminFilterChips
          label={appText("Фильтр бизнесов", "Бизнес фильтры")}
          options={FILTERS.map((f) => ({ key: f.key, label: appText(f.ru, f.ba) }))}
          value={filter}
          onChange={setFilter}
        />

        {state === "loading" && (
          <>
            <RideCardSkeleton />
            <RideCardSkeleton />
          </>
        )}
        {state === "error" && <ListedError onRetry={() => load()} />}

        {state === "ready" && shown.length === 0 && (
          <ListedEmpty
            title={appText("Пока нет заявок", "Әлегә заявкалар юҡ")}
            subtitle={appText("Здесь появятся заведения, которые хотят стать партнёрами.", "Бында партнёр булырға теләгән урындар күренер.")}
          />
        )}

        {state === "ready" && shown.map((p, i) => <PartnerCard key={p.id} partner={p} index={i} onPatch={patch} />)}
      </div>
    </>
  );
}

function PartnerCard({
  partner,
  index,
  onPatch,
}: {
  partner: AdminPartner;
  index: number;
  onPatch: (id: number, next: Partial<AdminPartner>) => void;
}) {
  const { appText } = useLang();
  const [busy, setBusy] = useState<null | "approve" | "reject">(null);
  const [error, setError] = useState<string | null>(null);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState("");

  const chip = statusChip(partner.status);

  function fail(e: unknown) {
    setError(
      e instanceof ApiError && e.message
        ? e.message
        : appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
    );
  }

  async function approve() {
    if (busy) return;
    setBusy("approve");
    setError(null);
    try {
      const updated = await approvePartner(partner.id);
      onPatch(partner.id, updated);
    } catch (e) {
      fail(e);
    } finally {
      setBusy(null);
    }
  }

  async function reject() {
    if (busy) return;
    setBusy("reject");
    setError(null);
    try {
      // Пустая причина → «Не прошло модерацию», как в приложении.
      const updated = await rejectPartner(partner.id, reason.trim() || appText("Не прошло модерацию", "Модерацияны үтмәне"));
      onPatch(partner.id, updated);
      setRejecting(false);
      setReason("");
    } catch (e) {
      fail(e);
    } finally {
      setBusy(null);
    }
  }

  // Действия — только для тех, кого ещё можно модерировать.
  const canAct = partner.status === "pending" || partner.status === "rejected";

  return (
    <article className="acard" style={{ animationDelay: `calc(var(--cascade-in) * ${Math.min(index, 6)})` }}>
      <div className="acard__row acard__row--md">
        <span className="partner-tile" aria-hidden>{categoryIcon(partner.category)}</span>
        <span className="acard__stack acard__grow">
          <strong className="acard__title">{partner.name || appText("Без названия", "Исемһеҙ")}</strong>
          <span className="acard__sub">
            {categoryLabel(partner.category, appText)}
            {partner.city ? `  ·  ${partner.city}` : ""}
          </span>
        </span>
        <span className={`abadge abadge--${chip.tone}`}>{appText(chip.ru, chip.ba)}</span>
      </div>
      {partner.address && (
        <span className="acard__text acard__iconline">
          <IconPin size={16} /> {partner.address}
        </span>
      )}
      {partner.phone && (
        <a className="acard__text acard__iconline acard__link" href={`tel:${partner.phone}`}>
          <IconPhone size={16} /> {partner.phone}
        </a>
      )}
      {partner.description && <span className="acard__sub">{partner.description}</span>}
      {/* Есть только в вебе: подписка бизнеса и дата заявки. */}
      <small className="acard__date">
        {partner.subscription_active ? appText("Подписка активна", "Яҙылыу әүҙем") : appText("Без подписки", "Яҙылыуһыҙ")}
        {partner.subscription_plan ? ` · ${partner.subscription_plan}` : ""}
        {partner.created_at ? ` · ${partner.created_at.slice(0, 10)}` : ""}
      </small>
      {partner.status === "rejected" && partner.reject_reason && (
        <span className="partner-rejected">{appText("Отклонён: ", "Кире ҡағылды: ") + partner.reject_reason}</span>
      )}

      {error && <div className="auth__error">{error}</div>}

      {canAct && !rejecting && (
        <div className="acard__actions">
          {partner.status === "pending" && (
            <button type="button" className="abtn abtn--46" onClick={approve} disabled={busy !== null}>
              {busy === "approve" ? appText("…", "…") : appText("Одобрить", "Раҫлау")}
            </button>
          )}
          <button type="button" className="abtn abtn--46 abtn--outline abtn--red" onClick={() => setRejecting(true)} disabled={busy !== null}>
            {appText("Отклонить", "Кире ҡағыу")}
          </button>
        </div>
      )}

      {canAct && rejecting && (
        /* Диалог отклонения с причиной — в вебе карточкой внутри. */
        <div className="settings-confirm settings-confirm--card">
          <strong>{appText("Отклонить бизнес", "Бизнесты кире ҡағыу")}</strong>
          <span>{appText("Напиши причину — заведение увидит её и сможет исправить.", "Сәбәпте яҙ — урын уны күрер һәм төҙәтә алыр.")}</span>
          <label className="field">
            <span className="field__label">{appText("Причина отказа", "Кире ҡағыу сәбәбе")}</span>
            <textarea className="field__input field__area" rows={2} value={reason} onChange={(e) => setReason(e.target.value)} autoFocus />
          </label>
          <div className="settings-confirm__row">
            <button type="button" className="btn-ghost settings-confirm__muted" onClick={() => setRejecting(false)} disabled={busy !== null}>
              {appText("Отмена", "Баш тартыу")}
            </button>
            <button type="button" className="btn-ghost settings-confirm__danger" onClick={reject} disabled={busy !== null}>
              {busy === "reject" ? appText("…", "…") : appText("Отклонить", "Кире ҡағыу")}
            </button>
          </div>
        </div>
      )}
    </article>
  );
}
