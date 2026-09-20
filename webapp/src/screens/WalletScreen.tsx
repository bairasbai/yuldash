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
import { LoadingList, ErrorState, EmptyStateCard } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { SectionHeader } from "../components/cabinetUi";
import { rubLabel, formatWhen } from "../utils/format";
import { IconWallet, IconArrow, IconReceipt, IconClock, IconLock } from "../components/Icons";

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
  const sign = kop >= 0 ? "+" : "−"; // как kopToRub в приложении: знак вплотную к сумме
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
      <SubHeader title={appText("Кошелёк", "Янсыҡ")} onBack={() => navigate(-1)} />

      {status === "loading" && <LoadingList count={2} />}

      {status === "soon" && (
        <EmptyStateCard
          icon={<IconWallet size={30} />}
          title={appText("Кошелёк скоро", "Янсыҡ тиҙҙән")}
          text={appText(
            "Раздел включится после ближайшего обновления. Все безналичные поездки уже считаются.",
            "Был бүлек яҡын яңыртыуҙан һуң эшләй башлар. Аҡсаһыҙ сәфәрҙәр иҫәпләнә инде."
          )}
        />
      )}

      {status === "error" && <ErrorState onRetry={() => load()} />}

      {status === "ready" && balance && (
        <div className="cabinet">
          {/* WalletBalanceCard: тёмно-зелёный градиент, круг с иконкой, сумма 34/40, подпись про выплаты. */}
          <section className="wallet-card">
            <div className="wallet-card__top">
              <span className="wallet-card__chip" aria-hidden><IconWallet size={22} /></span>
              <span className="wallet-card__label">{appText("Баланс кошелька", "Янсыҡ балансы")}</span>
            </div>
            <div className="wallet-card__amount">{rubLabel(balance.balance_kop)}</div>
            <p className="wallet-card__hint">
              {payout?.enabled === true
                ? appText("Доступно к выводу через СБП", "СБП аша сығарырға мөмкин")
                : payout?.enabled === false
                  ? appText(
                      "Здесь бонусы и возвраты. За поездки платят тебе напрямую.",
                      "Бында бонустар һәм ҡайтарыуҙар. Сәфәрҙәр өсөн һиңә туранан-тура түләйҙәр."
                    )
                  : appText("Бонусы и возвраты", "Бонустар һәм ҡайтарыуҙар")}
            </p>
          </section>

          {/* Выплаты на карту — блок виден, только если ручка status уже на проде */}
          {payout && <PayoutSection payout={payout} onChanged={() => load()} />}

          <SectionHeader
            title={appText("История операций", "Операциялар тарихы")}
            subtitle={appText("Начисления за поездки и комиссии сервиса.", "Сәфәрҙәр өсөн килем һәм сервис комиссияһы.")}
          />

          {ledger.length === 0 ? (
            <EmptyStateCard
              icon={<IconReceipt size={30} />}
              title={appText("Пока операций нет", "Әле операциялар юҡ")}
              text={appText(
                "Заверши безналичную поездку — начисление появится здесь.",
                "Аҡсаһыҙ сәфәр тамамла — иҫәпләү бында күренер."
              )}
            />
          ) : (
            <div className="ledger">
              {ledger.map((e) => {
                const positive = e.amount_kop >= 0;
                return (
                  <div key={e.id} className="ledger-row">
                    <span className={"ledger-row__icon" + (positive ? "" : " is-out")} aria-hidden>
                      <IconArrow size={18} />
                    </span>
                    <div className="ledger-row__main">
                      <div className="ledger-row__title">{e.note || kindLabel(e, ru)}</div>
                      <div className="ledger-row__sub">{formatWhen(e.created_at, ru)}</div>
                    </div>
                    <div className={"ledger-row__amount" + (positive ? " is-in" : "")}>{signedRub(e.amount_kop)}</div>
                  </div>
                );
              })}
            </div>
          )}
          {ledger.length >= 100 && (
            <p className="dl-hint" style={{ textAlign: "center" }}>
              {appText("Показаны последние 100 операций", "Һуңғы 100 операция күрһәтелгән")}
            </p>
          )}
        </div>
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
  const [confirming, setConfirming] = useState(false); // «Вывести N ₽?» — как AlertDialog в приложении

  const minRub = Math.round(payout.min_kop / 100);
  const maxRub = Math.round(payout.max_kop / 100);

  if (!payout.enabled) {
    // PayoutSoonCard: честно и спокойно — вывод готовим, расчёты работают как раньше.
    return (
      <div className="payout-soon">
        <span className="payout-soon__icon" aria-hidden><IconClock size={20} /></span>
        <span className="payout-soon__text">
          <strong>{appText("Выплаты на карту — скоро", "Картаға түләүҙәр — оҙаҡламай")}</strong>
          <small>
            {appText(
              "Готовим вывод на карту. Пока комиссия и расчёты работают как раньше.",
              "Картаға сығарыуҙы әҙерләйбеҙ. Әлегә комиссия һәм иҫәпләшеүҙәр элеккесә эшләй."
            )}
          </small>
        </span>
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

  const amountRub = Math.max(0, parseInt(amount, 10) || 0);
  const amountKop = amountRub * 100;
  const balanceRub = Math.round(payout.balance_kop / 100);
  const amountError =
    amountRub <= 0
      ? null
      : amountKop < payout.min_kop
        ? appText(`Минимум ${minRub} ₽`, `Кәм тигәндә ${minRub} ₽`)
        : amountKop > payout.max_kop
          ? appText(`Максимум ${maxRub} ₽ за раз`, `Бер юлы иң күбе ${maxRub} ₽`)
          : amountKop > payout.balance_kop
            ? appText(`На балансе только ${balanceRub} ₽`, `Баланста ${balanceRub} ₽ ғына`)
            : null;
  const canWithdraw = amountRub > 0 && !amountError && !busy;

  return (
    <section className="payout-card">
      <div className="payout-card__head">
        <span className="payout-card__icon" aria-hidden><IconWallet size={20} /></span>
        <span className="payout-card__text">
          <strong>{appText("Вывод на карту", "Картаға сығарыу")}</strong>
          <small>
            {payout.has_requisite
              ? appText(`Карта ····${payout.card_last4}`, `Карта ····${payout.card_last4}`)
              : appText("Карта пока не добавлена", "Карта әлегә өҫтәлмәгән")}
          </small>
        </span>
        {payout.has_requisite && !cardOpen && (
          <button type="button" className="btn-ghost payout-card__change" onClick={() => setCardOpen(true)} disabled={busy}>
            {appText("Изменить", "Үҙгәртеү")}
          </button>
        )}
      </div>

      {/* Карта для выплат. ПРИВАТНОСТЬ: полный номер не покидает устройство — на сервер идут ТОЛЬКО последние 4. */}
      {(!payout.has_requisite || cardOpen) && (
        <>
          <label className="field dl-field">
            <span className="field__label">{appText("Номер карты для выплат", "Түләүҙәр өсөн карта номеры")}</span>
            <input
              className="field__input"
              inputMode="numeric"
              autoComplete="off"
              value={cardNum}
              onChange={(e) => setCardNum(e.target.value)}
              placeholder="0000 0000 0000 0000"
            />
            <span className="field__hint">
              <IconLock size={14} />{" "}
              {appText(
                "Мы сохраняем только последние 4 цифры — полный номер никуда не отправляется.",
                "Беҙ тик һуңғы 4 һанды ғына һаҡлайбыҙ — тулы номер бер ҡайҙа ла ебәрелмәй."
              )}
            </span>
          </label>
          <button type="button" className="btn-soft" onClick={saveCard} disabled={busy}>
            {busy ? appText("Сохраняем…", "Һаҡлайбыҙ…") : payout.has_requisite ? appText("Сохранить карту", "Картаны һаҡларға") : appText("Добавить карту", "Карта өҫтәү")}
          </button>
        </>
      )}

      {/* Вывод: поле суммы с подсказкой лимитов, «Всё», «Вывести N ₽» и подтверждение перед списанием. */}
      {payout.has_requisite && (
        <>
          <label className="field dl-field">
            <span className="field__label">{appText("Сумма, ₽", "Сумма, ₽")}</span>
            <div className="payout-card__amount-row">
              <input
                className="field__input"
                type="number"
                inputMode="numeric"
                value={amount}
                onChange={(e) => { setAmount(e.target.value); setConfirming(false); }}
                placeholder={String(minRub)}
              />
              {payout.balance_kop >= payout.min_kop && (
                <button
                  type="button"
                  className="btn-ghost"
                  onClick={() => { setAmount(String(Math.min(balanceRub, maxRub))); setConfirming(false); }}
                >
                  {appText("Всё", "Барыһы")}
                </button>
              )}
            </div>
            <span className={"field__hint" + (amountError ? " is-error" : "")}>
              {amountError ?? appText(`От ${minRub} до ${maxRub} ₽ за раз`, `Бер юлы ${minRub} — ${maxRub} ₽`)}
            </span>
          </label>
          {!confirming ? (
            <button type="button" className="btn-primary submit-btn" onClick={() => setConfirming(true)} disabled={!canWithdraw}>
              {amountRub > 0 ? appText(`Вывести ${amountRub} ₽`, `${amountRub} ₽ сығарыу`) : appText("Вывести", "Сығарыу")}
            </button>
          ) : (
            <div className="payout-card__confirm">
              <strong>{appText(`Вывести ${amountRub} ₽?`, `${amountRub} ₽ сығарырғамы?`)}</strong>
              <span>
                {appText(
                  `Деньги уйдут на карту ····${payout.card_last4}. Обычно приходят за несколько минут.`,
                  `Аҡса ····${payout.card_last4} картаһына китә. Ғәҙәттә бер нисә минутта килә.`
                )}
              </span>
              <div className="payout-card__confirm-row">
                <button type="button" className="btn-primary" onClick={() => { setConfirming(false); void withdraw(); }} disabled={busy}>
                  {busy ? appText("Отправляем…", "Ебәрәбеҙ…") : appText("Да, вывести", "Эйе, сығарырға")}
                </button>
                <button type="button" className="btn-ghost" onClick={() => setConfirming(false)} disabled={busy}>
                  {appText("Отмена", "Кире алыу")}
                </button>
              </div>
            </div>
          )}
        </>
      )}

      {error && <div className="auth__error">{error}</div>}
      {okNote && (
        <div className="consents__status ok" style={{ marginTop: 0 }}>
          {okNote}
        </div>
      )}
    </section>
  );
}
