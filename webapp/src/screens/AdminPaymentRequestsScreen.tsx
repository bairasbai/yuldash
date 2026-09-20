// ================================================================
//  Заявки на оплату → /admin/payment-requests (RequireAdmin).
//  СБП «на доверии»: пользователь перевёл — админ подтверждает получение.
//  GET /admin/payments/pending — очередь; POST /admin/payments/{id}/confirm|reject.
//  Сверху — сводка подтверждённых (GET /admin/payments/summary).
//  Как в приложении (AdminPaymentRequestsScreen, SecondaryScreens.kt), здесь же
//  раздел «Долги за такси»: водитель нажал «Я оплатил» — подтвердить, отклонить
//  или списать с причиной (GET /admin/debts + confirm/reject/forgive). Отдельный
//  веб-экран /admin/debts остался и переиспользует карточку DebtCard отсюда.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchPendingPayments,
  fetchPaymentsSummary,
  confirmPayment,
  rejectPayment,
  fetchAdminDebts,
  confirmDebt,
  rejectDebt,
  forgiveDebt,
  type PendingPayment,
  type PaymentsSummary,
  type AdminDebt,
} from "../api/admin";
import { SubHeader } from "./ConsentsScreen";
import { AdminIntro, ListedEmpty, ListedError, ListedLoading } from "../components/adminUi";
import { kopExactLabel } from "../utils/format";

type State = "loading" | "error" | "ready";

const PURPOSE: Record<string, [string, string]> = {
  boost: ["Буст поездки", "Сәфәр бусты"],
  donate: ["Донат", "Донат"],
  support: ["Поддержка", "Ярҙам"],
  ad: ["Реклама", "Реклама"],
};

/**
 * Карточка долга за такси: «Долг за такси» жёлтым + сумма 19 Bold с копейками
 * (админ сверяет её с переводом), водитель · телефон, недели, «Подтвердить» /
 * контурная «Отклонить», текстовая «Списать долг» → карточка с причиной.
 */
export function DebtCard({
  debt,
  busy,
  onDone,
}: {
  debt: AdminDebt;
  busy: boolean;
  onDone: (msg: string) => void;
}) {
  const { appText } = useLang();
  const [forgiving, setForgiving] = useState(false);
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [lock, setLock] = useState(false);

  async function run(fn: () => Promise<unknown>, ok: string) {
    if (lock || busy) return;
    setLock(true);
    setError(null);
    try {
      await fn();
      setForgiving(false);
      setReason("");
      onDone(ok);
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось. Проверь сеть и повтори.", "Булманы. Сетте тикшереп ҡабатла.")
      );
    } finally {
      setLock(false);
    }
  }

  const disabled = lock || busy;
  const noName = appText("Без имени", "Исемһеҙ");

  return (
    <article className="acard">
      <div className="acard__row acard__row--between">
        <span className="atext atext--warn">{appText("Долг за такси", "Такси бурысы")}</span>
        <b className="acard__amount">{kopExactLabel(debt.amount_kop)}</b>
      </div>
      <span className="acard__sub">
        {(debt.driver_name || noName) + (debt.driver_phone ? ` · ${debt.driver_phone}` : "")}
      </span>
      {debt.weeks.length > 0 && (
        <small className="acard__date">{appText("Недели: ", "Аҙналар: ") + debt.weeks.join(", ")}</small>
      )}

      {error && <div className="auth__error">{error}</div>}

      {forgiving ? (
        /* Списание необратимо и про деньги: причина обязательна и уходит в журнал. */
        <div className="settings-confirm settings-confirm--card">
          <strong>{appText("Списать долг?", "Бурысты һүндерергәме?")}</strong>
          <span>
            {appText(
              `${debt.driver_name || "Водитель"} · ${kopExactLabel(debt.amount_kop)}. Долг исчезнет, такси разблокируется. Отменить это нельзя.`,
              `${debt.driver_name || "Йөрөтөүсе"} · ${kopExactLabel(debt.amount_kop)}. Бурыс юғала, такси асыла. Кире ҡайтарып булмай.`
            )}
          </span>
          <label className="field">
            <span className="field__label">{appText("Причина (останется в журнале)", "Сәбәп (журналда ҡала)")}</span>
            <input
              className="field__input"
              value={reason}
              onChange={(e) => setReason(e.target.value.slice(0, 300))}
              autoFocus
            />
          </label>
          <div className="settings-confirm__row">
            <button type="button" className="btn-ghost settings-confirm__muted" onClick={() => setForgiving(false)}>
              {appText("Отмена", "Кире ҡағыу")}
            </button>
            <button
              type="button"
              className="btn-ghost settings-confirm__danger"
              disabled={!reason.trim() || disabled}
              onClick={() =>
                run(() => forgiveDebt(debt.debt_id, reason.trim()), appText("Долг списан", "Бурыс һүндерелде"))
              }
            >
              {appText("Списать", "Һүндереү")}
            </button>
          </div>
        </div>
      ) : (
        <>
          <div className="acard__actions">
            <button
              type="button"
              className="abtn"
              disabled={disabled}
              onClick={() => run(() => confirmDebt(debt.debt_id), appText("Оплата подтверждена", "Түләү раҫланды"))}
            >
              {appText("Подтвердить", "Раҫлау")}
            </button>
            <button
              type="button"
              className="abtn abtn--outline abtn--red"
              disabled={disabled}
              onClick={() => run(() => rejectDebt(debt.debt_id), appText("Отклонено", "Кире ҡағылды"))}
            >
              {appText("Отклонить", "Кире ҡағыу")}
            </button>
          </div>
          {/* Бывает, что долга по-человечески быть не должно: пассажир не заплатил,
              поездка сорвалась не по вине водителя. */}
          <button type="button" className="abtn abtn--text abtn--muted" disabled={disabled} onClick={() => setForgiving(true)}>
            {appText("Списать долг", "Бурысты һүндереү")}
          </button>
        </>
      )}
    </article>
  );
}

export default function AdminPaymentRequestsScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [state, setState] = useState<State>("loading");
  const [items, setItems] = useState<PendingPayment[]>([]);
  const [debts, setDebts] = useState<AdminDebt[]>([]);
  const [summary, setSummary] = useState<PaymentsSummary | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [rowError, setRowError] = useState<{ id: number; msg: string } | null>(null);
  const [toast, setToast] = useState<string | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setState("loading");
    fetchPendingPayments(signal)
      .then((list) => {
        setItems(list);
        setState("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setState("error");
      });
    // Сводка и долги — не критичны: ошибку глотаем, список платежей важнее.
    fetchPaymentsSummary(signal).then(setSummary).catch(() => {});
    fetchAdminDebts(signal).then(setDebts).catch(() => {});
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  async function decide(paymentId: number, confirm: boolean) {
    if (busyId) return;
    setBusyId(paymentId);
    setRowError(null);
    try {
      if (confirm) await confirmPayment(paymentId);
      else await rejectPayment(paymentId);
      setItems((prev) => prev.filter((p) => p.payment_id !== paymentId));
      // Обновим сводку после подтверждения (мягко).
      if (confirm) fetchPaymentsSummary().then(setSummary).catch(() => {});
    } catch (e) {
      setRowError({
        id: paymentId,
        msg:
          e instanceof ApiError && e.message
            ? e.message
            : appText("Не получилось. Попробуй ещё раз.", "Булманы. Ҡабат ҡара."), // DRAFT
      });
    } finally {
      setBusyId(null);
    }
  }

  const noName = appText("Без имени", "Исемһеҙ");

  return (
    <>
      <SubHeader title={appText("Заявки на оплату", "Түләү заявкалары")} onBack={() => navigate(-1)} />
      <div className="alist">
        <AdminIntro>
          {appText(
            "Сверь свою карту по сумме и имени, потом подтверди — буст запустится. Донаты просто засчитываются.",
            "Картаңды сумма һәм исем буйынса тикшер, аҙаҡ раҫла — буст эшләй. Донаттар иҫәпләнә."
          )}
        </AdminIntro>

        {summary && (
          <div className="acard acard--tight">
            <span className="acard__sub">{appText("Донаты подтверждённые", "Раҫланған донаттар")}</span>
            <b className="acard__amount acard__amount--green">
              {summary.donate.count} {appText("чел.", "кеше")} · {summary.donate.sum_rub.toLocaleString("ru-RU")} ₽
            </b>
            {/* Буст и поддержка — веб считает их тоже; той же строкой, чтобы не терять цифры. */}
            <span className="acard__sub">
              {appText("Буст", "Буст")} · {summary.boost.count} · {summary.boost.sum_rub.toLocaleString("ru-RU")} ₽
              {"  ·  "}
              {appText("Поддержка", "Ярҙам")} · {summary.support.count} · {summary.support.sum_rub.toLocaleString("ru-RU")} ₽
            </span>
          </div>
        )}

        {toast && <p className="dl-hint">{toast}</p>}

        {/* Долги водителей по комиссии за такси (Модель А «на доверии») — на подтверждение. */}
        {debts.length > 0 && (
          <>
            <strong className="acard__title">{appText("Долги за такси", "Такси бурыстары")}</strong>
            <AdminIntro>
              {appText(
                "Водитель перевёл комиссию по СБП и нажал «Я оплатил». Сверь по имени и сумме — подтверди, и такси у него разблокируется.",
                "Йөрөтөүсе комиссияны СБП аша күсереп «Мин түләнем» баҫҡан. Исем һәм сумма буйынса тикшер — раҫла, такси блокан асыла."
              )}
            </AdminIntro>
            {debts.map((g) => (
              <DebtCard
                key={g.debt_id}
                debt={g}
                busy={busyId !== null}
                onDone={(msg) => {
                  setToast(msg);
                  load();
                }}
              />
            ))}
          </>
        )}

        {state === "loading" && <ListedLoading />}
        {state === "error" && <ListedError onRetry={() => load()} />}

        {state === "ready" && items.length === 0 && debts.length === 0 && (
          <ListedEmpty
            title={appText("Нет заявок на оплату", "Түләү заявкалары юҡ")}
            subtitle={appText(
              "Здесь появятся оплаты буста, донаты и долги за такси на подтверждение.",
              "Бында буст түләүҙәре, донаттар һәм такси бурыстары раҫлауға күренер"
            )}
          />
        )}

        {state === "ready" &&
          items.map((p) => {
            const purpose = PURPOSE[p.purpose] ?? [p.purpose, p.purpose];
            return (
              <article key={p.payment_id} className="acard">
                <div className="acard__row acard__row--between">
                  <span className="atext atext--green">{appText(purpose[0], purpose[1])}</span>
                  <b className="acard__amount">{p.amount.toLocaleString("ru-RU")} ₽</b>
                </div>
                <span className="acard__sub">
                  {(p.payer_name || noName) + (p.payer_phone ? ` · ${p.payer_phone}` : "")}
                </span>
                {p.note && <strong className="acard__note">{p.note}</strong>}
                {p.created_at.length >= 10 && <small className="acard__date">{p.created_at.slice(0, 10)}</small>}

                {rowError?.id === p.payment_id && <div className="auth__error">{rowError.msg}</div>}

                <div className="acard__actions">
                  <button
                    type="button"
                    className="abtn"
                    onClick={() => decide(p.payment_id, true)}
                    disabled={busyId !== null}
                  >
                    {busyId === p.payment_id ? appText("…", "…") : appText("Подтвердить", "Раҫлау")}
                  </button>
                  <button
                    type="button"
                    className="abtn abtn--outline abtn--red"
                    onClick={() => decide(p.payment_id, false)}
                    disabled={busyId !== null}
                  >
                    {busyId === p.payment_id ? appText("…", "…") : appText("Отклонить", "Кире ҡағыу")}
                  </button>
                </div>
              </article>
            );
          })}
      </div>
    </>
  );
}
