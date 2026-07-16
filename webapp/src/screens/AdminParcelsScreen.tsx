// ================================================================
//  Доставки посылок → /admin/parcels (RequireAdmin).
//  GET /admin/parcels — все заявки (контроль/поддержка) + плашка дохода
//  (собрано ₽ по доставленным + число доставленных). Активные — сверху.
//  Приватное (телефон получателя + код вручения) доступно админу для поддержки/споров.
//  Двуязычно, все состояния, тач-цели ≥48px.
// ================================================================
import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { fetchAdminParcels, type ParcelsStatement } from "../api/admin";
import type { Parcel } from "../api/parcels";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList, ErrorState } from "../components/States";
import { sizeLabel, StatusPillParcel } from "../components/parcelUi";
import { rubLabel, formatRelative } from "../utils/format";
import { IconArrow, IconBox, IconPhone } from "../components/Icons";

type State = "loading" | "error" | "ready";

const FILTERS: { key: string; ru: string; ba: string }[] = [
  { key: "active", ru: "Активные", ba: "Әүҙем" },
  { key: "delivered", ru: "Доставлены", ba: "Тапшырылған" },
  { key: "canceled", ru: "Отменены", ba: "Кире алынған" },
  { key: "", ru: "Все", ba: "Барыһы" },
];

const ACTIVE_STATUSES = ["created", "accepted", "in_transit"];

/** Порядок сортировки: активные (в движении) сверху, затем завершённые/отменённые. */
function sortWeight(status: string): number {
  const i = ACTIVE_STATUSES.indexOf(status);
  if (i >= 0) return i; // 0..2 — активные по стадии
  if (status === "delivered") return 3;
  return 4; // canceled и прочее
}

export default function AdminParcelsScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [filter, setFilter] = useState<string>("active");
  const [state, setState] = useState<State>("loading");
  const [parcels, setParcels] = useState<Parcel[]>([]);
  const [statement, setStatement] = useState<ParcelsStatement | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setState("loading");
    fetchAdminParcels({ limit: 300, signal })
      .then((res) => {
        setParcels(res.parcels);
        setStatement(res.statement);
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

  const shown = useMemo(() => {
    let rows = parcels;
    if (filter === "active") rows = rows.filter((p) => ACTIVE_STATUSES.includes(p.status));
    else if (filter) rows = rows.filter((p) => p.status === filter);
    return [...rows].sort((a, b) => sortWeight(a.status) - sortWeight(b.status) || b.id - a.id);
  }, [parcels, filter]);

  const activeN = parcels.filter((p) => ACTIVE_STATUSES.includes(p.status)).length;

  return (
    <>
      <SubHeader
        title={appText("Доставки посылок", "Бандероль доставкалары")}
        subtitle={appText("Контроль и поддержка", "Контроль һәм ярҙам")}
        onBack={() => navigate(-1)}
      />

      {/* Плашка дохода платформы: собрано ₽ + доставлено N. */}
      <div className="income-hero" style={{ marginBottom: 8 }}>
        <span className="income-hero__cap">{appText("Собрано по доставкам", "Доставкалар буйынса йыйылды")}</span>
        <span className="income-hero__net">{rubLabel(statement?.collected_fee_kop ?? 0)}</span>
        <span className="income-hero__note">
          {appText(
            `Доставлено: ${statement?.delivered_count ?? 0} · В работе: ${activeN}`,
            `Тапшырылған: ${statement?.delivered_count ?? 0} · Эштә: ${activeN}`
          )}
        </span>
      </div>

      <div className="chip-scroll" role="tablist" aria-label={appText("Фильтр посылок", "Бандероль фильтры")}>
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
          <div className="state__emoji"><IconBox size={40} /></div>
          <h2>{appText("Здесь пусто", "Бында буш")}</h2>
          <p>{appText("Посылок в этом разделе нет.", "Был бүлектә бандеролдәр юҡ.")}</p>
        </div>
      )}

      {state === "ready" && shown.length > 0 && (
        <div className="admin-cards">
          {shown.map((p) => (
            <ParcelAdminCard key={p.id} parcel={p} ru={ru} />
          ))}
        </div>
      )}
    </>
  );
}

const DTYPE_LABEL: Record<string, [string, string]> = {
  poputka: ["По пути", "Юл ыңғайы"],
  courier: ["Курьер", "Курьер"],
  buy_bring: ["Купи и привези", "Һатып ал да килтер"],
};

function ParcelAdminCard({ parcel: p, ru }: { parcel: Parcel; ru: boolean }) {
  const { appText } = useLang();
  const dtype = DTYPE_LABEL[p.delivery_type] ?? DTYPE_LABEL.poputka;

  return (
    <div className="admin-card">
      <div className="admin-card__head">
        <div className="repeat-route" style={{ margin: 0 }}>
          <span>{p.from_city || appText("Откуда", "Ҡайҙан")}</span>
          <span className="repeat-route__arrow"><IconArrow size={16} /></span>
          <span>{p.to_city || appText("Куда", "Ҡайҙа")}</span>
        </div>
        <StatusPillParcel status={p.status} />
      </div>

      <div className="admin-card__sub">
        #{p.id} · {sizeLabel(p.size, ru)} · {appText(dtype[0], dtype[1])} · {rubLabel(p.fee_kop)}
      </div>
      {p.description && <p className="admin-card__reason">{p.description}</p>}

      <div className="admin-card__sub">
        {appText("Получатель", "Алыусы")}: <b>{p.receiver_name || "—"}</b>
        {p.receiver_phone && (
          <a className="admin-card__phone" href={`tel:${p.receiver_phone}`} style={{ marginLeft: 8 }}>
            <IconPhone size={14} /> {p.receiver_phone}
          </a>
        )}
      </div>

      {p.confirm_code && (
        <div className="admin-card__sub">
          {appText("Код вручения", "Тапшырыу коды")}:{" "}
          <b style={{ fontFamily: "monospace", letterSpacing: 1 }}>{p.confirm_code}</b>
        </div>
      )}
      <div className="admin-card__sub">
        {p.courier_id
          ? appText(`Курьер назначен (id ${p.courier_id})`, `Курьер билдәләнгән (id ${p.courier_id})`)
          : appText("Курьер не найден", "Курьер табылманы")}
      </div>
      {p.created_at && (
        <div className="admin-card__sub">{formatRelative(p.created_at, ru)}</div>
      )}
    </div>
  );
}
