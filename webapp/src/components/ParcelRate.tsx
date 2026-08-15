// ================================================================
//  ⭐ Взаимная оценка доставки. Зеркало Android-блока в ParcelsScreen.
//
//  Оценка — это то, из чего складывается доверие «между своими»: курьер
//  без истории для отправителя просто незнакомый человек с его коробкой.
//  Поэтому спрашиваем обе стороны, а не только заказчика.
//
//  Пара слов необязательна: заставлять писать после каждой посылки —
//  верный способ не получить ни оценок, ни слов.
// ================================================================
import { useState } from "react";
import { useLang } from "../i18n/lang";
import { rateParcel } from "../api/parcels";
import { IconStar } from "./Icons";

export default function ParcelRate({
  parcelId,
  role,
}: {
  parcelId: number;
  /** Кого оцениваем — от этого зависит только вопрос. */
  role: "sender" | "courier";
}) {
  const { appText } = useLang();
  const [stars, setStars] = useState(0);
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (done) {
    return (
      <div className="consents__status ok" style={{ marginTop: 14 }}>
        {appText("Спасибо, оценка учтена!", "Рәхмәт, баһа иҫәпкә алынды!")}
      </div>
    );
  }

  async function send(n: number) {
    if (busy) return;
    const prev = stars;
    setStars(n);
    setBusy(true);
    setError(null);
    try {
      await rateParcel(parcelId, n, text.trim());
      setDone(true);
    } catch {
      // Возвращаем звёзды: они показывают, что стоит НА СЕРВЕРЕ, а не что
      // человек нажал. Иначе он уйдёт с экрана, вернётся и увидит оценку,
      // которой нет.
      setStars(prev);
      setError(
        appText("Не получилось сохранить оценку. Проверь сеть.", "Баһаны һаҡлап булманы. Селтәрҙе тикшер.")
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="rate-card">
      <div className="rate-card__title">
        {role === "courier"
          ? appText("Как справился курьер?", "Курьер нисек эшләне?")
          : appText("Как всё прошло с отправителем?", "Ебәреүсе менән нисек үтте?")}
      </div>

      <div className="rate-stars">
        {[1, 2, 3, 4, 5].map((n) => (
          <button
            key={n}
            type="button"
            className={"rate-star" + (n <= stars ? " is-on" : "")}
            onClick={() => void send(n)}
            disabled={busy}
            aria-label={appText(`Поставить ${n} из 5`, `5-тән ${n} ҡуйырға`)}
          >
            <IconStar size={34} />
          </button>
        ))}
      </div>

      <label className="field" style={{ marginTop: 8 }}>
        <span className="field__label">{appText("Пару слов (необязательно)", "Бер-ике һүҙ (мотлаҡ түгел)")}</span>
        <input
          className="field__input"
          value={text}
          maxLength={300}
          onChange={(e) => setText(e.target.value)}
          placeholder={appText("Всё вовремя, спасибо", "Бөтәһе ваҡытында, рәхмәт")}
        />
      </label>

      {error && <div className="auth__error">{error}</div>}
    </div>
  );
}
