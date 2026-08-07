// ================================================================
//  Заявки на оплату → /admin/payment-requests (RequireAdmin).
//  СБП «на доверии»: пользователь перевёл — админ подтверждает получение.
//  GET /admin/payments/pending — очередь; POST /admin/payments/{id}/confirm|reject.
//  Сверху — сводка подтверждённых (GET /admin/payments/summary).
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
  type PendingPayment,
  type PaymentsSummary,
} from "../api/admin";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList, ErrorState } from "../components/States";
import { formatRelative } from "../utils/format";
import { IconCheck, IconPhone, IconWallet } from "../components/Icons";

type State = "loading" | "error" | "ready";

const PURPOSE: Record<string, [string, string]> = {
  boost: ["Поднятие поездки", "Сәфәр күтәреү"],
  donate: ["Донат", "Донат"],
  support: ["Поддержка", "Ярҙам"],
  ad: ["Реклама", "Реклама"],
};

export default function AdminPaymentRequestsScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [state, setState] = useState<State>("loading");
  const [items, setItems] = useState<PendingPayment[]>([]);
  const [summary, setSummary] = useState<PaymentsSummary | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [rowError, setRowError] = useState<{ id: number; msg: string } | null>(null);

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
    // Сводка — не критична: ошибку глотаем.
    fetchPaymentsSummary(signal).then(setSummary).catch(() => {});
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

  return (
    <>
      <SubHeader
        title={appText("Заявки на оплату", "Түләү заявкалары")}
        subtitle={appText("Подтверждение переводов (СБП)", "Күсереүҙәрҙе раҫлау (СБП)")}
        onBack={() => navigate(-1)}
      />

      {summary && (
        <div className="stat-grid" style={{ marginBottom: 4 }}>
          <div className="stat-tile">
            <b>{summary.boost.sum_rub.toLocaleString("ru-RU")} ₽</b>
            <span>{appText("буст", "буст")} · {summary.boost.count}</span>
          </div>
          <div className="stat-tile">
            <b>{summary.donate.sum_rub.toLocaleString("ru-RU")} ₽</b>
            <span>{appText("донаты", "донаттар")} · {summary.donate.count}</span>
          </div>
          <div className="stat-tile">
            <b>{summary.support.sum_rub.toLocaleString("ru-RU")} ₽</b>
            <span>{appText("поддержка", "ярҙам")} · {summary.support.count}</span>
          </div>
        </div>
      )}

      {state === "loading" && <LoadingList count={3} />}
      {state === "error" && <ErrorState onRetry={() => load()} />}

      {state === "ready" && items.length === 0 && (
        <div className="state" style={{ paddingTop: 24 }}>
          <div className="state__icon"><IconWallet size={40} /></div>
          <h2>{appText("Всё подтверждено", "Барыһы раҫланған")}</h2>
          <p>{appText("Новых переводов на подтверждение нет.", "Раҫларға яңы күсереүҙәр юҡ.")}</p>
        </div>
      )}

      {state === "ready" && items.length > 0 && (
        <div className="admin-cards">
          {items.map((p) => {
            const purpose = PURPOSE[p.purpose] ?? [p.purpose, p.purpose];
            return (
              <div key={p.payment_id} className="admin-card">
                <div className="admin-card__head">
                  <div className="admin-card__title">{p.amount.toLocaleString("ru-RU")} ₽</div>
                  <span className="badge badge--gold">{appText(purpose[0], purpose[1])}</span>
                </div>
                <div className="admin-card__sub">
                  {appText("Плательщик", "Түләүсе")}: <b>{p.payer_name || appText("—", "—")}</b>
                </div>
                {p.payer_phone && (
                  <a className="admin-card__phone" href={`tel:${p.payer_phone}`}>
                    <IconPhone size={14} /> {p.payer_phone}
                  </a>
                )}
                {p.note && <div className="admin-card__sub">{p.note}</div>}
                <div className="admin-card__sub">{formatRelative(p.created_at, ru)}</div>

                {rowError?.id === p.payment_id && <div className="auth__error">{rowError.msg}</div>}

                <div className="field-row" style={{ marginTop: 12 }}>
                  <button
                    type="button"
                    className="btn-soft"
                    style={{ flex: 1 }}
                    onClick={() => decide(p.payment_id, false)}
                    disabled={busyId !== null}
                  >
                    {busyId === p.payment_id ? appText("…", "…") : appText("Не пришли", "Килмәне")}
                  </button>
                  <button
                    type="button"
                    className="btn-primary"
                    style={{ flex: 1 }}
                    onClick={() => decide(p.payment_id, true)}
                    disabled={busyId !== null}
                  >
                    {busyId === p.payment_id ? (
                      appText("…", "…")
                    ) : (
                      <><IconCheck size={18} /> {appText("Деньги пришли", "Аҡса килде")}</>
                    )}
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </>
  );
}
