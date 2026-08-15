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
import { LoadingList, ErrorState } from "../components/States";
import { formatRelative } from "../utils/format";
import { IconCheck, IconPhone, IconWork } from "../components/Icons";

type State = "loading" | "error" | "ready";

const FILTERS: { key: string; ru: string; ba: string }[] = [
  { key: "pending", ru: "На проверке", ba: "Тикшереүҙә" },
  { key: "active", ru: "Активные", ba: "Әүҙем" },
  { key: "rejected", ru: "Отклонённые", ba: "Кире ҡағылған" },
  { key: "", ru: "Все", ba: "Барыһы" },
];

const CATEGORY_LABEL: Record<string, [string, string]> = {
  cafe: ["Кафе", "Кафе"],
  shop: ["Магазин", "Магазин"],
  service: ["Услуги", "Хеҙмәттәр"],
  beauty: ["Красота", "Матурлыҡ"],
  auto: ["Авто", "Авто"],
  health: ["Здоровье", "Һаулыҡ"],
  other: ["Другое", "Башҡа"],
};

function statusBadge(s: string): { cls: string; ru: string; ba: string } {
  switch (s) {
    case "active":
      return { cls: "badge--mint", ru: "Активен", ba: "Әүҙем" };
    case "pending":
      return { cls: "badge--gold", ru: "На проверке", ba: "Тикшереүҙә" };
    case "rejected":
      return { cls: "badge--danger", ru: "Отклонён", ba: "Кире" };
    case "paused":
      return { cls: "badge--muted", ru: "Пауза", ba: "Пауза" };
    case "archived":
      return { cls: "badge--muted", ru: "Архив", ba: "Архив" };
    default:
      return { cls: "badge--muted", ru: s, ba: s };
  }
}

export default function AdminPartnersScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [filter, setFilter] = useState<string>("pending");
  const [state, setState] = useState<State>("loading");
  const [partners, setPartners] = useState<AdminPartner[]>([]);

  const load = useCallback((signal?: AbortSignal) => {
    setState("loading");
    fetchAdminPartners({ limit: 300, signal })
      .then((list) => {
        setPartners(list);
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
  const activeN = partners.filter((p) => p.status === "active").length;

  return (
    <>
      <SubHeader
        title={appText("Модерация бизнесов", "Бизнестарҙы тикшереү")}
        subtitle={appText("Партнёрские компании", "Партнёр компаниялар")}
        onBack={() => navigate(-1)}
      />

      <div className="stat-grid" style={{ marginBottom: 4 }}>
        <div className="stat-tile">
          <b>{pendingN}</b>
          <span>{appText("на проверке", "тикшереүҙә")}</span>
        </div>
        <div className="stat-tile">
          <b>{activeN}</b>
          <span>{appText("активных", "әүҙем")}</span>
        </div>
        <div className="stat-tile">
          <b>{partners.length}</b>
          <span>{appText("всего", "барлығы")}</span>
        </div>
      </div>

      <div className="chip-scroll" role="tablist" aria-label={appText("Фильтр бизнесов", "Бизнес фильтры")}>
        {FILTERS.map((f) => (
          <button
            key={f.key || "all"}
            type="button"
            role="tab"
            aria-selected={filter === f.key}
            className={"chip" + (filter === f.key ? " chip--on" : "")}
            onClick={() => setFilter(f.key)}
          >
            {appText(f.ru, f.ba)}
          </button>
        ))}
      </div>

      {state === "loading" && <LoadingList count={3} />}
      {state === "error" && <ErrorState onRetry={() => load()} />}

      {state === "ready" && shown.length === 0 && (
        <div className="state" style={{ paddingTop: 24 }}>
          <div className="state__icon"><IconWork size={40} /></div>
          <h2>{appText("Здесь пусто", "Бында буш")}</h2>
          <p>{appText("Бизнесов в этом разделе нет.", "Был бүлектә бизнестар юҡ.")}</p>
        </div>
      )}

      {state === "ready" && shown.length > 0 && (
        <div className="admin-cards">
          {shown.map((p) => (
            <PartnerCard key={p.id} partner={p} ru={ru} onPatch={patch} />
          ))}
        </div>
      )}
    </>
  );
}

function PartnerCard({
  partner,
  ru,
  onPatch,
}: {
  partner: AdminPartner;
  ru: boolean;
  onPatch: (id: number, next: Partial<AdminPartner>) => void;
}) {
  const { appText } = useLang();
  const [busy, setBusy] = useState<null | "approve" | "reject">(null);
  const [error, setError] = useState<string | null>(null);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState("");

  const badge = statusBadge(partner.status);
  const cat = CATEGORY_LABEL[partner.category] ?? [partner.category, partner.category];

  function fail(e: unknown) {
    setError(
      e instanceof ApiError && e.message
        ? e.message
        : appText("Не получилось. Попробуй ещё раз.", "Булманы. Ҡабат ҡара.") // DRAFT
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
      const updated = await rejectPartner(partner.id, reason.trim());
      onPatch(partner.id, updated);
      setRejecting(false);
    } catch (e) {
      fail(e);
    } finally {
      setBusy(null);
    }
  }

  const canModerate = partner.status === "pending";

  return (
    <div className="admin-card">
      <div className="admin-card__head">
        <div className="admin-card__title">{partner.name || appText("Без названия", "Исемһеҙ")}</div>
        <span className={`badge ${badge.cls}`}>{appText(badge.ru, badge.ba)}</span>
      </div>

      <div className="admin-card__sub">
        {appText(cat[0], cat[1])}
        {partner.city && <> · {partner.city}</>}
      </div>
      {partner.address && <div className="admin-card__sub">{partner.address}</div>}
      {partner.phone && (
        <a className="admin-card__phone" href={`tel:${partner.phone}`}>
          <IconPhone size={14} /> {partner.phone}
        </a>
      )}
      {partner.description && <p className="admin-card__reason">{partner.description}</p>}

      <div className="admin-card__sub">
        {partner.subscription_active
          ? appText("Подписка активна", "Яҙылыу әүҙем")
          : appText("Без подписки", "Яҙылыуһыҙ")}
        {partner.subscription_plan && <> · {partner.subscription_plan}</>}
      </div>
      {partner.created_at && (
        <div className="admin-card__sub">{formatRelative(partner.created_at, ru)}</div>
      )}

      {partner.reject_reason && partner.status === "rejected" && (
        <p className="admin-card__sub">{appText("Причина", "Сәбәп")}: {partner.reject_reason}</p>
      )}

      {error && <div className="auth__error">{error}</div>}

      {canModerate && !rejecting && (
        <div className="field-row" style={{ marginTop: 12 }}>
          <button
            type="button"
            className="btn-soft"
            style={{ flex: 1 }}
            onClick={() => setRejecting(true)}
            disabled={busy !== null}
          >
            {appText("Отклонить", "Кире ҡағыу")}
          </button>
          <button
            type="button"
            className="btn-primary"
            style={{ flex: 1 }}
            onClick={approve}
            disabled={busy !== null}
          >
            {busy === "approve" ? appText("…", "…") : (
              <><IconCheck size={18} /> {appText("Одобрить", "Раҫлау")}</>
            )}
          </button>
        </div>
      )}

      {canModerate && rejecting && (
        <>
          <textarea
            className="field__input field__area"
            style={{ marginTop: 12, minHeight: 76, paddingTop: 12 }}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder={appText(
              "Причина отказа — бизнес увидит и исправит",
              "Кире ҡағыу сәбәбе — бизнес күрер һәм төҙәтер"
            )}
          />
          <div className="field-row" style={{ marginTop: 10 }}>
            <button
              type="button"
              className="btn-soft"
              style={{ flex: 1 }}
              onClick={() => setRejecting(false)}
              disabled={busy !== null}
            >
              {appText("Назад", "Артҡа")}
            </button>
            <button
              type="button"
              className="btn-danger"
              style={{ flex: 1, marginTop: 0 }}
              onClick={reject}
              disabled={busy !== null}
            >
              {busy === "reject" ? appText("…", "…") : appText("Отклонить", "Кире ҡағыу")}
            </button>
          </div>
        </>
      )}
    </div>
  );
}
