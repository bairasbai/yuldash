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
import { RideCardSkeleton } from "../components/States";
import { AdminFilterChips, AdminIntro, ListedEmpty, ListedError } from "../components/adminUi";
import { sizeLabel, StatusPillParcel } from "../components/parcelUi";
import { kopExactLabel } from "../utils/format";
import { IconBox, IconCheck, IconPin, IconProfile, IconWallet } from "../components/Icons";

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

  // Фильтр по состоянию есть только в вебе (в приложении — один список, активные сверху).
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

  return (
    <>
      <SubHeader title={appText("Посылки", "Бандеролдәр")} onBack={() => navigate(-1)} />
      <div className="alist">
        <AdminIntro>
          {appText(
            "Все доставки посылок и собранный сбор Юлдаша. Видно только администратору.",
            "Бөтә бандероль илтеүҙәре һәм йыйылған Юлдаш сборы. Тик админға күренә."
          )}
        </AdminIntro>

        {/* ParcelStatementCard: три разных числа, и каждое означает ровно то, что написано. */}
        {statement && (
          <div className="pstatement">
            <div className="pstatement__head">
              <span className="pstatement__icon" aria-hidden><IconWallet size={26} /></span>
              <span className="pstatement__text">
                <small>{appText("Курьеры оплатили", "Курьерҙар түләне")}</small>
                <b>{kopExactLabel(statement.collected_fee_kop)}</b>
                <span>{appText(`Доставлено посылок: ${statement.delivered_count}`, `Тапшырылған бандеролдәр: ${statement.delivered_count}`)}</span>
              </span>
            </div>
            {((statement.owed_commission_kop ?? 0) > 0 || (statement.unbilled_fee_kop ?? 0) > 0) && (
              <>
                <hr className="pstatement__rule" />
                {(statement.owed_commission_kop ?? 0) > 0 && (
                  <div className="pstatement__row">
                    <span className="pstatement__label">
                      <strong>{appText("Ждём от курьеров", "Курьерҙарҙан көтәбеҙ")}</strong>
                      <small>{appText("начислено, ещё не оплачено", "иҫәпләнгән, әле түләнмәгән")}</small>
                    </span>
                    <b className="atext--warn">{kopExactLabel(statement.owed_commission_kop ?? 0)}</b>
                  </div>
                )}
                {(statement.unbilled_fee_kop ?? 0) > 0 && (
                  <div className="pstatement__row">
                    <span className="pstatement__label">
                      <strong>{appText("Сбор «по пути»", "«Юл ыңғайы» йыйымы")}</strong>
                      <small>{appText("выставить некому — это не выручка", "талап итер кеше юҡ — был килем түгел")}</small>
                    </span>
                    <b className="atext--muted">{kopExactLabel(statement.unbilled_fee_kop ?? 0)}</b>
                  </div>
                )}
              </>
            )}
          </div>
        )}

        <AdminFilterChips
          label={appText("Фильтр посылок", "Бандероль фильтры")}
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
            icon={<IconBox size={34} />}
            title={appText("Пока нет посылок", "Әлегә бандеролдәр юҡ")}
            subtitle={appText("Здесь появятся все отправленные посылки.", "Бында бөтә ебәрелгән бандеролдәр күренер.")}
          />
        )}

        {state === "ready" && shown.map((p, i) => <ParcelAdminCard key={p.id} parcel={p} ru={ru} index={i} onReleased={() => load()} />)}
      </div>
    </>
  );
}

type ActionKind = "cancel" | "release" | "close";

function ParcelAdminCard({
  parcel: p,
  ru,
  index,
  onReleased,
}: {
  parcel: Parcel;
  ru: boolean;
  index: number;
  onReleased: () => void;
}) {
  const { appText } = useLang();
  /** Рычаги админа для зависшей доставки: три честных выхода, все с причиной для сторон. */
  const [action, setAction] = useState<ActionKind | null>(null);
  const [closeStatus, setCloseStatus] = useState<"returned" | "delivered" | "canceled">("returned");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");

  const finished = ["delivered", "canceled", "cancelled", "returned"].includes(String(p.status));
  const delivered = p.delivered_at ? p.delivered_at.slice(0, 10) : "";

  function open(kind: ActionKind) {
    setAction(kind);
    setReason("");
    setErr("");
  }

  async function confirm() {
    if (busy || !action) return;
    setBusy(true);
    setErr("");
    try {
      if (action === "cancel") await adminCancelParcel(p.id, reason.trim());
      else if (action === "release") await releaseParcelCourier(p.id, reason.trim());
      else await adminCloseParcel(p.id, closeStatus, reason.trim());
      setAction(null);
      setReason("");
      onReleased();
    } catch (e) {
      setErr(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
      );
    } finally {
      setBusy(false);
    }
  }

  const title =
    action === "cancel"
      ? appText("Отменить доставку", "Илтеүҙе кире алыу")
      : action === "release"
        ? appText("Снять курьера", "Курьерҙы алыу")
        : appText("Закрыть доставку", "Илтеүҙе ябыу");
  const hint =
    action === "cancel"
      ? appText("Обе стороны получат причину. Комиссию за неоказанную услугу не берём.", "Ике яҡ та сәбәбен ала. Күрһәтелмәгән хеҙмәт өсөн комиссия алмайбыҙ.")
      : action === "release"
        ? appText("Посылка вернётся в общий список — её сможет взять другой курьер.", "Бандероль дөйөм исемлеккә ҡайта — уны башҡа курьер ала ала.")
        : appText(
            "Когда разобрались вне приложения. При «вернулась» и «отменена» комиссия обнуляется.",
            "Ҡулланманан тыш хәл ителгәс. «Ҡайтты» һәм «кире алынды» осрағында комиссия юҡҡа сыға."
          );

  return (
    <article className="acard" style={{ animationDelay: `calc(var(--cascade-in) * ${Math.min(index, 6)})` }}>
      <div className="acard__row">
        <span className="acard__route acard__grow">
          <IconPin size={16} />
          <strong>{p.from_city || "—"}</strong>
          <span>→</span>
          <strong>{p.to_city || "—"}</strong>
        </span>
        <StatusPillParcel status={p.status} />
      </div>
      <span className="acard__sub">{sizeLabel(p.size, ru) + (p.description ? `  ·  ${p.description}` : "")}</span>
      <div className="acard__row">
        <span className="acard__text acard__iconline acard__grow">
          <IconProfile size={15} /> {appText("Получатель: ", "Алыусы: ") + (p.receiver_name || "—")}
        </span>
        {/* Сумма сделки — что отправитель платит курьеру; наш сбор здесь не показываем. */}
        {p.price_kop > 0 && <b className="acard__amount--green acard__caption">{kopExactLabel(p.price_kop)}</b>}
      </div>
      {/* Есть только в вебе: приватное для поддержки — телефон получателя и код вручения. */}
      {(p.receiver_phone || p.confirm_code) && (
        <small className="acard__date">
          {p.receiver_phone ? appText("Телефон: ", "Телефон: ") + p.receiver_phone : ""}
          {p.receiver_phone && p.confirm_code ? "  ·  " : ""}
          {p.confirm_code ? appText("Код вручения: ", "Тапшырыу коды: ") + p.confirm_code : ""}
        </small>
      )}
      {(p.courier || p.courier_id) && (
        <span className="acard__sub acard__iconline">
          <IconBox size={15} /> {appText("Курьер: ", "Курьер: ") + (p.courier?.name || `#${p.courier?.id ?? p.courier_id}`)}
        </span>
      )}
      {delivered && (
        <span className="atext atext--green acard__iconline">
          <IconCheck size={15} /> {appText(`Доставлена ${delivered}`, `${delivered} тапшырылды`)}
        </span>
      )}

      {err && <div className="auth__error">{err}</div>}

      {/* Рычаги — только пока доставка живая: закрытую трогать нечего. */}
      {!finished && action === null && (
        <>
          <div className="acard__actions">
            {(p.courier || p.courier_id) && (
              <button type="button" className="btn-soft" onClick={() => open("release")}>
                {appText("Снять курьера", "Курьерҙы алыу")}
              </button>
            )}
            <button type="button" className="btn-danger" onClick={() => open("cancel")}>
              {appText("Отменить", "Кире алыу")}
            </button>
          </div>
          <button type="button" className="abtn abtn--text abtn--muted" onClick={() => open("close")}>
            {appText("Закрыть вручную", "Ҡулдан ябыу")}
          </button>
        </>
      )}

      {!finished && action !== null && (
        /* AdminParcelActionDialog — в вебе карточкой внутри. */
        <div className="settings-confirm settings-confirm--card">
          <strong>{title}</strong>
          <span>{hint}</span>
          {action === "close" && (
            <div className="inc-resolve__choices" role="radiogroup">
              {(
                [
                  ["returned", appText("Вернулась отправителю", "Ебәреүсегә ҡайтты")],
                  ["delivered", appText("Всё-таки доставлена", "Барыбер тапшырылған")],
                  ["canceled", appText("Отменена", "Кире алынған")],
                ] as ["returned" | "delivered" | "canceled", string][]
              ).map(([k, label]) => (
                <button key={k} type="button" role="radio" aria-checked={closeStatus === k} className={"choice-row choice-row--sm" + (closeStatus === k ? " is-on" : "")} onClick={() => setCloseStatus(k)}>
                  {closeStatus === k ? <IconCheck size={18} /> : <IconPin size={18} />} {label}
                </button>
              ))}
            </div>
          )}
          <label className="field">
            <span className="field__label">{appText("Причина (её увидят стороны)", "Сәбәбе (яҡтар күрәсәк)")}</span>
            <textarea className="field__input field__area" rows={2} value={reason} onChange={(e) => setReason(e.target.value.slice(0, 200))} autoFocus />
          </label>
          <div className="settings-confirm__row">
            <button type="button" className="btn-ghost settings-confirm__muted" onClick={() => setAction(null)} disabled={busy}>
              {appText("Отмена", "Кире алыу")}
            </button>
            <button type="button" className="btn-ghost inc-resolve__save" onClick={confirm} disabled={busy}>
              {busy ? appText("…", "…") : appText("Подтвердить", "Раҫлау")}
            </button>
          </div>
        </div>
      )}
    </article>
  );
}
