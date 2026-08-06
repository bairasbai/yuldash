// ================================================================
//  Кошелёк водителя (wallet.py). RequireAuth.
//  Крупная карточка баланса (GET /wallet/balance) + история операций
//  (GET /wallet/ledger) + выплаты на карту (GET /wallet/payout/status:
//  enabled=false → «скоро»; enabled=true → карта ····last4 + «Вывести»
//  POST /wallet/payout с idempotency_key). Все состояния.
//  Эндпоинты появятся на проде после мержа release → мягкая деградация.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchWalletBalance,
  fetchWalletLedger,
  fetchPayoutStatus,
  savePayoutRequisite,
  requestPayout,
  type LedgerEntry,
  type WalletBalance,
  type PayoutStatus,
} from "../api/wallet";
import { LoadingList } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { rubLabel, formatWhen } from "../utils/format";
import { IconWallet, IconArrow, IconReceipt, IconCheck, IconLock } from "../components/Icons";

/** Ключ идемпотентности выплаты — новый uuid на каждую попытку. */
function payoutKey(): string {
  return typeof crypto !== "undefined" && "randomUUID" in crypto
    ? crypto.randomUUID()
    : `po-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

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
  // null = блок выплат скрыт (ручки ещё нет на проде).
  const [payout, setPayout] = useState<PayoutStatus | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    Promise.all([
      fetchWalletBalance(signal),
      fetchWalletLedger(100, signal).catch(() => [] as LedgerEntry[]),
      fetchPayoutStatus(signal).catch(() => null), // 404 до деплоя → блок скрыт
    ])
      .then(([bal, led, po]) => {
        setBalance(bal);
        setLedger(led);
        setPayout(po);
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
          <div className="state__icon">
            <IconWallet size={34} />
          </div>
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
          <div className="state__icon state__icon--warn">
            <IconWallet size={34} />
          </div>
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

          {/* Выплаты на карту — блок виден, только если ручка status уже на проде */}
          {payout && <PayoutSection payout={payout} onChanged={() => load()} />}

          <h2 className="section-title">{appText("История", "Тарих")}</h2>

          {ledger.length === 0 ? (
            <div className="state" style={{ paddingTop: 12 }}>
              <div className="state__icon">
                <IconReceipt size={32} />
              </div>
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

          {!payout?.enabled && (
            <p className="receipt__foot">
              {appText(
                "Выплаты пока проводятся вручную по реестру. Вопросы — в поддержку.",
                "Түләүҙәр әлегә ҡулдан үткәрелә. Һорауҙар — ярҙамға."
              )}
            </p>
          )}
        </>
      )}
    </>
  );
}

// ---------------- Выплаты на карту (Модель Б) ----------------
function PayoutSection({
  payout,
  onChanged,
}: {
  payout: PayoutStatus;
  onChanged: () => void;
}) {
  const { appText } = useLang();

  const [cardOpen, setCardOpen] = useState(!payout.has_requisite);
  const [cardNum, setCardNum] = useState("");
  const [amount, setAmount] = useState("");
  const [busy, setBusy] = useState(false); // общий замок: карта/выплата — двойной клик исключён
  const [error, setError] = useState<string | null>(null);
  const [okNote, setOkNote] = useState<string | null>(null);

  const minRub = Math.round(payout.min_kop / 100);
  const maxRub = Math.round(payout.max_kop / 100);

  if (!payout.enabled) {
    return (
      <div className="consents__status" style={{ marginTop: 12 }}>
        <IconLock size={16} />{" "}
        {appText(
          "Выплаты на карту скоро — включим после подключения платёжного провайдера.",
          "Картаға түләүҙәр тиҙҙән — түләү провайдерын ялғағас ҡабыҙабыҙ."
        )}
      </div>
    );
  }

  async function saveCard() {
    // ПРИВАТНОСТЬ: полный номер не покидает устройство — на сервер идут ТОЛЬКО последние 4.
    const digits = cardNum.replace(/\D/g, "");
    if (digits.length < 12) {
      setError(appText("Проверь номер карты.", "Карта номерын тикшер."));
      return;
    }
    setBusy(true);
    setError(null);
    setOkNote(null);
    try {
      await savePayoutRequisite(digits.slice(-4));
      setCardNum("");
      setCardOpen(false);
      setOkNote(appText("Карта сохранена.", "Карта һаҡланды."));
      onChanged();
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось сохранить карту. Попробуй снова.", "Картаны һаҡлап булманы. Ҡабат ҡара.")
      );
    } finally {
      setBusy(false);
    }
  }

  async function withdraw() {
    const rub = Math.max(0, parseInt(amount, 10) || 0);
    if (rub <= 0) {
      setError(appText("Укажи сумму вывода.", "Сығарыу суммаһын күрһәт."));
      return;
    }
    setBusy(true);
    setError(null);
    setOkNote(null);
    try {
      // Ключ идемпотентности — новый на попытку: ретрай не спишет дважды.
      await requestPayout(rub * 100, payoutKey());
      setAmount("");
      setOkNote(appText("Заявка на выплату принята! Деньги придут на карту.", "Түләү ғаризаһы ҡабул ителде! Аҡса картаға килер."));
      onChanged();
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось вывести. Попробуй снова.", "Сығарып булманы. Ҡабат ҡара.")
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="payout">
      <h2 className="section-title">{appText("Вывод на карту", "Картаға сығарыу")}</h2>

      {/* Карта для выплат */}
      {payout.has_requisite && !cardOpen ? (
        <div className="info-list">
          <div className="info-row">
            <span className="info-row__k">{appText("Карта", "Карта")}</span>
            <span className="info-row__v">
              •••• {payout.card_last4}
              <button
                type="button"
                className="payout__change"
                onClick={() => setCardOpen(true)}
              >
                {appText("Изменить", "Үҙгәртергә")}
              </button>
            </span>
          </div>
        </div>
      ) : (
        <div className="form" style={{ marginTop: 4 }}>
          <label className="field">
            <span className="field__label">{appText("Номер карты для выплат", "Түләүҙәр өсөн карта номеры")}</span>
            <input
              className="field__input"
              inputMode="numeric"
              autoComplete="off"
              value={cardNum}
              onChange={(e) => setCardNum(e.target.value)}
              placeholder="0000 0000 0000 0000"
            />
          </label>
          <p className="payout__privacy">
            <IconLock size={14} />{" "}
            {appText(
              "Мы сохраняем только последние 4 цифры — полный номер никуда не отправляется.",
              "Беҙ тик һуңғы 4 һанды ғына һаҡлайбыҙ — тулы номер бер ҡайҙа ла ебәрелмәй."
            )}
          </p>
          <button type="button" className="btn-soft" onClick={saveCard} disabled={busy}>
            {busy ? appText("Сохраняем…", "Һаҡлайбыҙ…") : appText("Сохранить карту", "Картаны һаҡларға")}
          </button>
        </div>
      )}

      {/* Вывод */}
      {payout.has_requisite && (
        <div className="form" style={{ marginTop: 10 }}>
          <label className="field">
            <span className="field__label">
              {appText(`Сумма, ₽ (от ${minRub} до ${maxRub})`, `Сумма, ₽ (${minRub} — ${maxRub})`)}
            </span>
            <input
              className="field__input"
              type="number"
              inputMode="numeric"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              placeholder={String(minRub)}
            />
          </label>
          <button
            type="button"
            className="btn-primary submit-btn"
            onClick={withdraw}
            disabled={busy}
          >
            {busy ? (
              appText("Выводим…", "Сығарабыҙ…")
            ) : (
              <>
                <IconCheck size={18} /> {appText("Вывести", "Сығарырға")}
              </>
            )}
          </button>
        </div>
      )}

      {error && <div className="auth__error">{error}</div>}
      {okNote && (
        <div className="consents__status ok" style={{ marginTop: 10 }}>
          {okNote}
        </div>
      )}
    </div>
  );
}
