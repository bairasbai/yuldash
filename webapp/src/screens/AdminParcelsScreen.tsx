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
import {
  fetchAdminParcels,
  releaseParcelCourier,
  adminCancelParcel,
  adminCloseParcel,
  type ParcelsStatement,
} from "../api/admin";
import { ApiError } from "../api/client";
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
          <div className="state__icon"><IconBox size={40} /></div>
          <h2>{appText("Здесь пусто", "Бында буш")}</h2>
          <p>{appText("Посылок в этом разделе нет.", "Был бүлектә бандеролдәр юҡ.")}</p>
        </div>
      )}

      {state === "ready" && shown.length > 0 && (
        <div className="admin-cards">
          {shown.map((p) => (
            <ParcelAdminCard key={p.id} parcel={p} ru={ru} onReleased={() => load()} />
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

function ParcelAdminCard({
  parcel: p,
  ru,
  onReleased,
}: {
  parcel: Parcel;
  ru: boolean;
  onReleased: () => void;
}) {
  const { appText } = useLang();
  const dtype = DTYPE_LABEL[p.delivery_type] ?? DTYPE_LABEL.poputka;
  const [release, setRelease] = useState<number | null>(null);
  /** Разбор вручную: отменить доставку или закрыть её итогом. */
  const [resolve, setResolve] = useState(false);
  const [outcome, setOutcome] = useState<"canceled" | "returned" | "delivered">("canceled");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState("");

  async function doRelease(id: number) {
    if (busy) return;
    setBusy(true);
    setNote("");
    try {
      await releaseParcelCourier(id, reason.trim());
      setRelease(null);
      setReason("");
      onReleased();
    } catch (e) {
      setNote(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось снять курьера.", "Курьерҙы алып булманы.")
      );
    } finally {
      setBusy(false);
    }
  }

  /** Итог разбора: «отменена» — отдельной ручкой, остальное — закрытием с итогом. */
  async function doResolve(id: number) {
    if (busy) return;
    setBusy(true);
    setNote("");
    try {
      if (outcome === "canceled") await adminCancelParcel(id, reason.trim());
      else await adminCloseParcel(id, outcome, reason.trim());
      setResolve(false);
      setReason("");
      onReleased();
    } catch (e) {
      setNote(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось закрыть доставку.", "Илтеүҙе ябып булманы.")
      );
    } finally {
      setBusy(false);
    }
  }

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

      {/* Курьер пропал и не отвечает — снимаем, посылка вернётся в общий список.
          Без этой кнопки заявка висела «в работе» у человека, который её не повезёт. */}
      {p.courier_id && !["delivered", "canceled", "returned"].includes(String(p.status)) && (
        release === p.id ? (
          <>
            <label className="field" style={{ marginTop: 10 }}>
              <span className="field__label">
                {appText("Почему снимаем (увидят обе стороны)", "Ниңә алабыҙ (ике яҡ та күрәсәк)")}
              </span>
              <input
                className="field__input"
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                maxLength={200}
                placeholder={appText("«Не выходит на связь второй день»", "«Икенсе көн бәйләнешкә сыҡмай»")}
              />
            </label>
            <div className="act-card__actions" style={{ marginTop: 10 }}>
              <button
                type="button"
                className="btn-danger"
                onClick={() => doRelease(p.id)}
                disabled={busy || !reason.trim()}
              >
                {busy ? appText("Снимаем…", "Алабыҙ…") : appText("Снять курьера", "Курьерҙы алыу")}
              </button>
              <button
                type="button"
                className="btn-ghost"
                onClick={() => {
                  setRelease(null);
                  setReason("");
                }}
              >
                {appText("Отмена", "Кире алыу")}
              </button>
            </div>
          </>
        ) : (
          <button
            type="button"
            className="btn-soft"
            style={{ width: "100%", marginTop: 10 }}
            onClick={() => {
              setRelease(p.id);
              setReason("");
            }}
          >
            {appText("Снять курьера с доставки", "Курьерҙы илтеүҙән алыу")}
          </button>
        )
      )}

      {/* Разбор вручную: звонит бабушка, курьер пропал, стороны договорились сами.
          Причину получают обе стороны — молчаливая отмена читается как «сервис
          забрал посылку», а это худшее, что можно сделать с доверием. */}
      {!["delivered", "canceled", "returned"].includes(String(p.status)) &&
        (resolve ? (
          <>
            <span className="field__label" style={{ marginTop: 12, display: "block" }}>
              {appText("Чем закончилось", "Нимә менән бөттө")}
            </span>
            <div className="seg" style={{ marginTop: 6 }}>
              {(
                [
                  ["canceled", appText("Отменена", "Кире алынды")],
                  ["returned", appText("Вернулась отправителю", "Ебәреүсегә ҡайтты")],
                  ["delivered", appText("Всё-таки доставлена", "Барыбер тапшырылды")],
                ] as ["canceled" | "returned" | "delivered", string][]
              ).map(([k, label]) => (
                <button
                  key={k}
                  type="button"
                  className={"seg__item" + (outcome === k ? " is-active" : "")}
                  onClick={() => setOutcome(k)}
                >
                  {label}
                </button>
              ))}
            </div>

            <label className="field" style={{ marginTop: 10 }}>
              <span className="field__label">
                {appText("Причина (её увидят стороны)", "Сәбәп (яҡтар күрәсәк)")}
              </span>
              <input
                className="field__input"
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                maxLength={200}
                placeholder={appText("«Договорились сами, посылку забрали»", "«Үҙҙәре килешкән, аҫылманы алғандар»")}
              />
            </label>

            <p className="demand__quiet">
              {appText(
                "При «вернулась» и «отменена» комиссию за неоказанную услугу не берём.",
                "«Ҡайтты» һәм «кире алынды» осрағында күрһәтелмәгән хеҙмәт өсөн комиссия алмайбыҙ."
              )}
            </p>

            <div className="act-card__actions" style={{ marginTop: 10 }}>
              <button
                type="button"
                className="btn-danger"
                onClick={() => doResolve(p.id)}
                disabled={busy || !reason.trim()}
              >
                {busy ? appText("Закрываем…", "Ябабыҙ…") : appText("Закрыть доставку", "Илтеүҙе ябыу")}
              </button>
              <button
                type="button"
                className="btn-ghost"
                onClick={() => {
                  setResolve(false);
                  setReason("");
                }}
              >
                {appText("Отмена", "Кире алыу")}
              </button>
            </div>
          </>
        ) : (
          <button
            type="button"
            className="btn-ghost"
            style={{ width: "100%", marginTop: 10 }}
            onClick={() => {
              setResolve(true);
              setReason("");
            }}
          >
            {appText("Разобрать вручную", "Ҡул менән хәл итеү")}
          </button>
        ))}

      {note && <p className="demand__quiet">{note}</p>}
    </div>
  );
}
