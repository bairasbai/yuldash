// ================================================================
//  «Поддержать Юлдаш 🌱» → /support-yuldash (RequireAuth).
//  Зеркало Android SupportBoostScreen (верхняя половина).
//
//  Поддержка ДОБРОВОЛЬНАЯ и никак не влияет на поездки: ни на цену,
//  ни на очередь, ни на то, как быстро приедет машина. Говорим об этом
//  прямо — иначе «поддержи» читается как скрытая плата за проезд.
//
//  Деньги идут платформе, а не водителю: его заработок не меняется.
// ================================================================
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError, getSessionGeneration } from "../api/client";
import {
  supportDonate,
  SUPPORT_PRESETS_RUB,
  SUPPORT_MIN_RUB,
  SUPPORT_MAX_RUB,
  type SupportResult,
} from "../api/support-donate";
import { SubHeader } from "./ConsentsScreen";
import SbpPay from "../components/SbpPay";
import { IconHeart } from "../components/Icons";
import { rememberPayment } from "../utils/pendingPayment";

export default function SupportYuldashScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [amount, setAmount] = useState<number>(SUPPORT_PRESETS_RUB[0]);
  const [custom, setCustom] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<SupportResult | null>(null);

  const customNum = Number(custom.replace(/\D/g, ""));
  const effective = custom.trim() ? customNum : amount;
  const valid = effective >= SUPPORT_MIN_RUB && effective <= SUPPORT_MAX_RUB;

  async function pay() {
    const owner = getSessionGeneration();
    if (!valid || busy) return;
    setBusy(true);
    setError(null);
    try {
      const r = await supportDonate(effective);
      // ЮKassa вернула ссылку — уводим на неё, дальше платит банк.
      if (r.confirmation_url) {
        rememberPayment(r.payment_id, "donate", "/support-yuldash", owner);
        window.location.href = r.confirmation_url;
        return;
      }
      setResult(r);
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
      );
    } finally {
      setBusy(false);
    }
  }

  // ---- Оплачено сразу (dev/mock или карта прошла) ----
  if (result?.status === "succeeded") {
    return (
      <>
        <SubHeader title={appText("Спасибо 🌿", "Рәхмәт 🌿")} onBack={() => navigate("/profile")} />
        <div className="state" style={{ paddingTop: 32 }}>
          <div className="state__icon"><IconHeart size={34} /></div>
          <h2>{appText("Поддержка принята", "Ярҙам ҡабул ителде")}</h2>
          <p>
            {appText(
              "Спасибо! Деньги идут на серверы, карты и SMS — то, без чего Юлдаш не работает.",
              "Рәхмәт! Аҡса серверҙарға, карталарға һәм SMS-ҡа китә — Юлдаш шуларһыҙ эшләмәй."
            )}
          </p>
          <button type="button" className="btn-primary" onClick={() => navigate("/profile")}>
            {appText("В профиль", "Профилгә")}
          </button>
        </div>
      </>
    );
  }

  // ---- СБП «на доверии»: реквизиты перевода ----
  if (result?.status === "pending" && result.method === "sbp_manual" && result.payee) {
    return (
      <>
        <SubHeader title={appText("Ждём перевод", "Күсереүҙе көтәбеҙ")} onBack={() => navigate("/profile")} />
        <div className="pay-sbp">
          <SbpPay
            phone={result.payee.phone}
            bank={result.payee.bank}
            name={result.payee.name}
            amountRub={result.amount ?? effective}
          />
          <p className="pay-sbp__hint">
            {appText(
              "Когда админ увидит перевод — поддержка засчитается.",
              "Админ күсереүҙе күргәс — ярҙам иҫәпләнә."
            )}
          </p>
          <button type="button" className="btn-primary submit-btn" onClick={() => navigate("/profile")}>
            {appText("Готово", "Әҙер")}
          </button>
          <p className="receipt__foot">
            {appText(
              "Спасибо! Деньги идут на серверы, карты и SMS.",
              "Рәхмәт! Аҡса серверҙарға, карталарға һәм SMS-ҡа китә."
            )}
          </p>
        </div>
      </>
    );
  }

  return (
    <>
      <SubHeader
        title={appText("Поддержать Юлдаш 🌱", "Юлдашҡа ярҙам итеү 🌱")}
        subtitle={appText("Серверы, карты, SMS и поддержка", "Серверҙар, карталар, SMS һәм ярҙам")}
        onBack={() => navigate(-1)}
      />

      <div className="act-card act-card--mint">
        <div className="act-card__title">{appText("Добровольная поддержка", "Ирекле ярҙам")}</div>
        <p className="act-card__text" style={{ marginBottom: 0 }}>
          {appText(
            "Помогает оплачивать серверы, карты, SMS и поддержку. На поездки это не влияет: цена, очередь и время подачи остаются прежними.",
            "Серверҙарҙы, карталарҙы, SMS һәм ярҙам хеҙмәтен түләргә ярҙам итә. Сәфәрҙәргә тәьҫир итмәй: хаҡ та, сират та, килеү ваҡыты ла үҙгәрмәй."
          )}
        </p>
      </div>

      <div className="chips" style={{ marginTop: 14 }}>
        {SUPPORT_PRESETS_RUB.map((p) => (
          <button
            key={p}
            type="button"
            className={"chip" + (!custom.trim() && amount === p ? " chip--on" : "")}
            onClick={() => {
              setAmount(p);
              setCustom("");
            }}
          >
            {p} ₽
          </button>
        ))}
      </div>

      <label className="field" style={{ marginTop: 12 }}>
        <span className="field__label">{appText("Своя сумма", "Үҙеңдең сумма")}</span>
        <input
          className="field__input"
          type="text"
          inputMode="numeric"
          value={custom}
          onChange={(e) => setCustom(e.target.value)}
          placeholder={appText("Например, 200", "Мәҫәлән, 200")}
          aria-label={appText("Сумма, ₽", "Сумма, ₽")}
        />
        <span className="field__hint">
          {appText(
            `От ${SUPPORT_MIN_RUB} до ${SUPPORT_MAX_RUB.toLocaleString("ru-RU")} ₽`,
            `${SUPPORT_MIN_RUB}-дан ${SUPPORT_MAX_RUB.toLocaleString("ru-RU")} ₽-ҡа тиклем`
          )}
        </span>
      </label>

      {error && <div className="auth__error">{error}</div>}

      <button
        type="button"
        className="btn-primary submit-btn"
        style={{ marginTop: 14 }}
        onClick={pay}
        disabled={!valid || busy}
      >
        {busy
          ? appText("Отправляем…", "Ебәрәбеҙ…")
          : valid
            ? appText(`Поддержать на ${effective} ₽`, `${effective} ₽ менән ярҙам итеү`)
            : appText("Поддержать", "Ярҙам итеү")}
      </button>

      <button type="button" className="btn-soft" style={{ marginTop: 10 }} onClick={() => navigate(-1)}>
        {appText("Не сейчас", "Хәҙер түгел")}
      </button>
    </>
  );
}
