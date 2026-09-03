// ================================================================
//  💸 Долг по комиссии за такси. Зеркало Android-блока в кабинете водителя.
//
//  Комиссию не списываем автоматически: раз в неделю водитель переводит её
//  по СБП и жмёт «Я оплатил», админ подтверждает. Пока долг висит — такси
//  блокируется, но ПОПУТКА работает как обычно, и это важно сказать прямо:
//  иначе человек думает, что его отключили целиком, и уходит.
//
//  Долга нет — карточки нет вовсе: пустая «0 ₽» каждый день перестаёт читаться.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useLang } from "../i18n/lang";
import { fetchDriverDebt, declareDebtPaid, type DriverDebt } from "../api/driver";
import { kopExactLabel, formatWhen } from "../utils/format";
import { IconWarn } from "./Icons";
import SbpPay from "./SbpPay";
import { rememberPayment } from "../utils/pendingPayment";

export default function DebtCard() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";

  const [debt, setDebt] = useState<DriverDebt | null>(null);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState("");

  const load = useCallback((signal?: AbortSignal) => {
    fetchDriverDebt(signal)
      .then(setDebt)
      .catch(() => setDebt(null)); // 404/403 → блока просто нет
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  if (!debt) return null;
  const owed = debt.unpaid_kop;
  const pending = debt.pending_kop;
  if (owed <= 0 && pending <= 0) return null;

  async function pay() {
    if (busy) return;
    setBusy(true);
    setNote("");
    try {
      const r = await declareDebtPaid();
      // Карта — уводим в банк; СБП — долг ушёл на подтверждение.
      if (r.confirmation_url) {
        if (r.payment_id) rememberPayment(r.payment_id, "debt", "/taxi-drive");
        window.location.href = r.confirmation_url;
        return;
      }
      setNote(
        appText(
          "Спасибо! Александр проверит перевод и подтвердит — такси разблокируется.",
          "Рәхмәт! Александр күсереүҙе тикшереп раҫлай — такси асыла."
        )
      );
      load();
    } catch {
      setNote(appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className={"debt-card" + (debt.blocked ? " debt-card--blocked" : "")}>
      <div className="debt-card__head">
        <span className="debt-card__title">
          {debt.blocked ? <IconWarn size={18} /> : null}{" "}
          {debt.blocked
            ? appText("Такси заблокировано", "Такси ябылған")
            : pending > 0 && owed <= 0
              ? appText("Ждём подтверждения оплаты", "Түләү раҫланыуын көтәбеҙ")
              : appText("Долг сервису", "Сервисҡа бурыс")}
        </span>
      </div>

      {owed > 0 && (
        <div className="debt-card__sum">
          <span className="debt-card__k">{appText("К оплате", "Түләргә")}</span>
          <b>{kopExactLabel(owed)}</b>
        </div>
      )}
      {pending > 0 && (
        <div className="debt-card__sum debt-card__sum--pending">
          <span className="debt-card__k">{appText("В обработке", "Эшкәртеүҙә")}</span>
          <b>{kopExactLabel(pending)}</b>
        </div>
      )}

      {/* Долг «сразу после поездки»: дальний межгород не ждёт недельного счёта.
          Говорим отдельной строкой — иначе человек видит общую сумму, платит в воскресенье
          и не понимает, почему его закрыли в среду. */}
      {(debt.pay_now_kop ?? 0) > 0 && (
        <div className="debt-card__sum debt-card__sum--now">
          <span className="debt-card__k">{appText("Нужно сейчас", "Хәҙер кәрәк")}</span>
          <b>{kopExactLabel(debt.pay_now_kop ?? 0)}</b>
        </div>
      )}
      {(debt.pay_now_kop ?? 0) > 0 && debt.pay_now_due_at && (
        <div className="debt-card__due">
          {appText("Эту часть — до ", "Был өлөшөн: ")}
          {formatWhen(debt.pay_now_due_at, ru)}
          {". "}
          {appText(
            "Дальняя поездка: комиссия с неё сравнима с недельной, поэтому её не копят.",
            "Алыҫ сәфәр: унан комиссия аҙналыҡ менән тиң, шуға уны йыймайҙар."
          )}
        </div>
      )}

      {debt.due_at && owed > 0 && (
        <div className="debt-card__due">
          {appText("Оплати до ", "Түлә: ")}
          {formatWhen(debt.due_at, ru)}
        </div>
      )}

      <p className="debt-card__note">
        {debt.blocked
          ? appText(
              "Оплати долг сервису, чтобы снова возить такси. Попутка (плановые поездки) работает как обычно.",
              "Такси йөрөтөр өсөн бурысты түлә. Юлдаш (планлы сәфәрҙәр) ғәҙәттәгесә эшләй."
            )
          : pending > 0 && owed <= 0
            ? appText(
                "Александр проверит перевод и подтвердит. Такси уже работает.",
                "Александр күсереүҙе тикшереп раҫлай. Такси эшләй инде."
              )
            : appText("Переведи долг по реквизитам ниже.", "Бурысты түбәндәге реквизиттар буйынса күсер.")}
      </p>

      {/* Недели — чтобы было видно, откуда сумма, а не «просто должен» */}
      {debt.weeks.length > 0 && (
        <div className="debt-card__weeks">
          {debt.weeks.map((w) => (
            <div key={w.week} className="debt-card__week">
              <span>{w.week}</span>
              <span>
                {kopExactLabel(w.amount_kop)}
                {w.status === "pending" && (
                  <span className="debt-card__pending-tag">
                    {" "}
                    {appText("в обработке", "эшкәртеүҙә")}
                  </span>
                )}
              </span>
            </div>
          ))}
        </div>
      )}

      {owed > 0 && <SbpPay phone={debt.sbp.phone} name={debt.sbp.name} />}

      {note && <div className="safe-note"><p>{note}</p></div>}

      {owed > 0 && (
        <button type="button" className="btn-primary" onClick={pay} disabled={busy}>
          {busy ? appText("Отправляем…", "Ебәрәбеҙ…") : appText("Я оплатил", "Түләнем")}
        </button>
      )}
    </section>
  );
}
