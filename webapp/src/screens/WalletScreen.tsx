// ================================================================
//  Кошелёк водителя (wallet.py). RequireAuth.
//  Крупная карточка баланса (GET /wallet/balance) + история операций
//  (GET /wallet/ledger): назначение / дата / сумма со знаком и цветом
//  (приход зелёным, списание приглушённым). Все состояния.
//  Эндпоинты появятся на проде после мержа release → мягкая деградация.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchWalletBalance,
  fetchWalletLedger,
  type LedgerEntry,
  type WalletBalance,
} from "../api/wallet";
import { LoadingList } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { rubLabel, formatWhen } from "../utils/format";
import { IconWallet, IconArrow } from "../components/Icons";

type Status = "loading" | "error" | "soon" | "ready";

/** Понятное двуязычное название операции ledger. */
function kindLabel(e: LedgerEntry, ru: boolean): string {
  switch (e.kind) {
    case "earn":
      return ru ? "Начисление за поездку" : "Сәфәр өсөн иҫәпләү";
    case "fee":
      return ru ? "Комиссия платформы" : "Платформа комиссияһы";
    case "payout":
      return ru ? "Выплата на карту" : "Картаға түләү";
    default:
      return ru ? "Корректировка" : "Төҙәтеү";
  }
}

/** «+1 200 ₽» / «−150 ₽» — знак по сумме (amount_kop уже знаковый). */
function signedRub(kop: number): string {
  const sign = kop >= 0 ? "+ " : "− ";
  return sign + rubLabel(Math.abs(kop));
}

export default function WalletScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [status, setStatus] = useState<Status>("loading");
  const [balance, setBalance] = useState<WalletBalance | null>(null);
  const [ledger, setLedger] = useState<LedgerEntry[]>([]);

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    Promise.all([
      fetchWalletBalance(signal),
      fetchWalletLedger(100, signal).catch(() => [] as LedgerEntry[]),
    ])
      .then(([bal, led]) => {
        setBalance(bal);
        setLedger(led);
        setStatus("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setStatus(
          e instanceof ApiError && (e.status === 404 || e.status === 405)
            ? "soon"
            : "error"
        );
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  return (
    <>
      <SubHeader
        title={appText("Кошелёк", "Янсыҡ")}
        subtitle={appText(
          "Заработок с безналичных поездок",
          "Аҡсаһыҙ сәфәрҙәрҙән табыш"
        )}
        onBack={() => navigate(-1)}
      />

      {status === "loading" && <LoadingList count={2} />}

      {status === "soon" && (
        <div className="state" style={{ paddingTop: 28 }}>
          <div className="state__emoji">👛</div>
          <h2>{appText("Кошелёк скоро", "Янсыҡ тиҙҙән")}</h2>
          <p>
            {appText(
              "Раздел включится после ближайшего обновления. Все безналичные поездки уже считаются.",
              "Был бүлек яҡын яңыртыуҙан һуң эшләй башлар. Аҡсаһыҙ сәфәрҙәр иҫәпләнә инде."
            )}
          </p>
        </div>
      )}

      {status === "error" && (
        <div className="state" style={{ paddingTop: 28 }}>
          <div className="state__emoji">📡</div>
          <h2>{appText("Не получилось загрузить", "Йөкләргә булманы")}</h2>
          <button type="button" className="btn-primary" onClick={() => load()}>
            {appText("Повторить", "Ҡабатларға")}
          </button>
        </div>
      )}

      {status === "ready" && balance && (
        <>
          <div className="wallet-card">
            <div className="wallet-card__top">
              <span className="wallet-card__chip">
                <IconWallet size={18} />
              </span>
              <span className="wallet-card__label">
                {appText("Доступно на балансе", "Балансыңда бар")}
              </span>
            </div>
            <div className="wallet-card__amount">
              {rubLabel(balance.balance_kop)}
            </div>
            <p className="wallet-card__hint">
              {appText(
                "Наличные идут напрямую тебе — здесь только безналичные поездки.",
                "Аҡса һиңә тура килә — бында тик аҡсаһыҙ сәфәрҙәр."
              )}
            </p>
          </div>

          <h2 className="section-title">{appText("История", "Тарих")}</h2>

          {ledger.length === 0 ? (
            <div className="state" style={{ paddingTop: 12 }}>
              <div className="state__emoji">🧾</div>
              <p>
                {appText(
                  "Пока операций нет. Заверши безналичную поездку — начисление появится здесь.",
                  "Әле операциялар юҡ. Аҡсаһыҙ сәфәр тамамла — иҫәпләү бында күренер."
                )}
              </p>
            </div>
          ) : (
            <div className="list">
              {ledger.map((e) => {
                const positive = e.amount_kop >= 0;
                return (
                  <div key={e.id} className="ledger-row">
                    <span
                      className={
                        "ledger-row__icon" + (positive ? "" : " is-out")
                      }
                    >
                      <IconArrow size={18} />
                    </span>
                    <div className="ledger-row__main">
                      <div className="ledger-row__title">
                        {e.note || kindLabel(e, ru)}
                      </div>
                      <div className="ledger-row__sub">
                        {formatWhen(e.created_at, ru)}
                      </div>
                    </div>
                    <div
                      className={
                        "ledger-row__amount" + (positive ? " is-in" : "")
                      }
                    >
                      {signedRub(e.amount_kop)}
                    </div>
                  </div>
                );
              })}
            </div>
          )}

          <p className="receipt__foot">
            {appText(
              "Выплаты пока проводятся вручную по реестру. Вопросы — в поддержку.",
              "Түләүҙәр әлегә ҡулдан үткәрелә. Һорауҙар — ярҙамға."
            )}
          </p>
        </>
      )}
    </>
  );
}
