// ================================================================
//  Ранний доступ: «оставь номер — позовём, когда включим такси».
//  Зеркало Android (InstantOrderScreen.kt, TaxiComingSoonCard)
//  + backend waitlist.py: POST /waitlist.
//
//  Экран «такси скоро в твоём городе» в вебе заканчивался ничем:
//  человек читал объяснение и уходил, а мы даже не узнавали, что
//  он приходил. Между тем город включают именно тогда, когда в нём
//  набирается достаточно людей — и водителей (сверка с Android,
//  2026-08-30).
//
//  Ручка публичная: вход для записи в очередь не требуем. Требовать
//  его — ровно тот барьер, из-за которого человек и уйдёт.
// ================================================================
import { useEffect, useState } from "react";
import { useLang } from "../i18n/lang";
import { joinWaitlist, type WaitlistRole } from "../api/instant";
import { fetchMe } from "../api/auth";
import { getToken } from "../api/client";

/** Тот же формат, что на сервере: 10–15 цифр, можно с «+». */
const PHONE_RE = /^\+?\d{10,15}$/;

export default function WaitlistForm({ city = "" }: { city?: string }) {
  const { appText } = useLang();
  const [phone, setPhone] = useState("");
  const [town, setTown] = useState(city);
  const [role, setRole] = useState<WaitlistRole>("passenger");
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState(false);
  const [error, setError] = useState("");

  // Телефон подставляем из профиля, если человек уже вошёл: набирать его заново
  // ради очереди — лишний повод закрыть экран. Гостя не трогаем.
  useEffect(() => {
    if (!getToken()) return;
    const ac = new AbortController();
    fetchMe(ac.signal)
      .then((me) => {
        const p = (me.phone || "").replace(/[\s\-()]/g, "");
        if (PHONE_RE.test(p)) setPhone((prev) => prev || p);
      })
      .catch(() => {});
    return () => ac.abort();
  }, []);

  useEffect(() => {
    if (city) setTown((prev) => prev || city);
  }, [city]);

  function submit() {
    const normalized = phone.replace(/[\s\-()]/g, "");
    if (!PHONE_RE.test(normalized)) {
      setError(appText("Проверь номер: 10–15 цифр, можно с +", "Номерҙы тикшер: 10–15 һан, + менән дә мөмкин"));
      return;
    }
    setBusy(true);
    setError("");
    joinWaitlist({ phone: normalized, city: town, role })
      .then(() => setSent(true))
      .catch(() =>
        setError(
          appText("Не получилось отправить. Проверь сеть и повтори.", "Ебәреп булманы. Селтәрҙе тикшереп ҡабатла.")
        )
      )
      .finally(() => setBusy(false));
  }

  if (sent) {
    return (
      <div className="act-card act-card--mint">
        <div className="act-card__title">{appText("Ты в списке! 🎉", "Һин исемлектә! 🎉")}</div>
        <p className="act-card__text" style={{ marginBottom: 0 }}>
          {role === "driver"
            ? appText(
                "Позовём одним из первых — 0% комиссии первые 3 месяца.",
                "Беренселәрҙән булып саҡырырбыҙ — тәүге 3 айҙа 0% комиссия."
              )
            : appText(
                "Сообщим, как только такси заработает в твоём городе.",
                "Такси һинең ҡалаңда эшләй башлағас та хәбәр итербеҙ."
              )}
        </p>
      </div>
    );
  }

  return (
    <div className="act-card">
      <div className="act-card__title">{appText("Хочу первым", "Беренсе булырға телим")}</div>
      <p className="act-card__text">
        {appText(
          "Оставь номер — позовём, как только включим такси у вас. Город включаем, когда в нём набирается достаточно людей и водителей.",
          "Номерыңды ҡалдыр — таксины ҡабыҙыу менән саҡырабыҙ. Ҡаланы кеше лә, йөрөтөүсе лә етерлек булғас тоташтырабыҙ."
        )}
      </p>

      {/* Кем человек будет. Водителю — свой ответ и своя очередь: их ждут сильнее. */}
      <div className="chips">
        <button
          type="button"
          className={"chip" + (role === "passenger" ? " chip--on" : "")}
          onClick={() => setRole("passenger")}
          aria-pressed={role === "passenger"}
        >
          {appText("Буду ездить", "Йөрөйәсәкмен")}
        </button>
        <button
          type="button"
          className={"chip" + (role === "driver" ? " chip--on" : "")}
          onClick={() => setRole("driver")}
          aria-pressed={role === "driver"}
        >
          {appText("Буду возить", "Йөрөтәсәкмен")}
        </button>
      </div>

      <label className="field">
        <span className="field__label">{appText("Телефон", "Телефон")}</span>
        <input
          className="field__input"
          inputMode="tel"
          value={phone}
          onChange={(e) => {
            setPhone(e.target.value);
            setError("");
          }}
          placeholder="+7 999 000-00-00"
          autoComplete="tel"
        />
      </label>
      <label className="field">
        <span className="field__label">{appText("Город или село", "Ҡала йәки ауыл")}</span>
        <input
          className="field__input"
          value={town}
          onChange={(e) => setTown(e.target.value.slice(0, 80))}
          placeholder={appText("Баймак", "Баймаҡ")}
          autoComplete="address-level2"
        />
      </label>

      {error && (
        <div className="notice" role="status">
          {error}
        </div>
      )}

      <button type="button" className="btn-primary" style={{ width: "100%" }} onClick={submit} disabled={busy}>
        {busy ? appText("Отправляем…", "Ебәрәбеҙ…") : appText("Позовите меня", "Мине саҡырығыҙ")}
      </button>
    </div>
  );
}
