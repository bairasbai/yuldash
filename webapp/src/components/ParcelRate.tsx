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
import { useEffect, useRef, useState } from "react";
import { useLang } from "../i18n/lang";
import { fetchParcelMyRating, rateParcel } from "../api/parcels";
import { getSessionGeneration } from "../api/client";
import { IconStar } from "./Icons";
import { track } from "../analytics";

type Props = {
  parcelId: number;
  /** Кого оцениваем — от этого зависит только вопрос. */
  role: "sender" | "courier";
};

export default function ParcelRate(props: Props) {
  const generation = getSessionGeneration();
  return <ParcelRatingState key={`${props.parcelId}:${generation}`} {...props} generation={generation} />;
}

function ParcelRatingState({ parcelId, role, generation }: Props & { generation: string }) {
  const { appText } = useLang();
  const [stars, setStars] = useState<number | null>(null);
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [phase, setPhase] = useState<"loading" | "ready" | "error">("loading");
  const [error, setError] = useState<string | null>(null);
  const alive = useRef(true);
  const inFlight = useRef(false);
  const reading = useRef(0);
  const current = () => alive.current && generation === getSessionGeneration();

  async function readStatus(sendError?: string) {
    const request = ++reading.current;
    setPhase("loading");
    try {
      const result = await fetchParcelMyRating(parcelId);
      if (!current() || reading.current !== request) return;
      setStars(result.stars);
      setError(result.stars === null ? sendError ?? null : null);
      setPhase("ready");
    } catch {
      if (current() && reading.current === request) setPhase("error");
    }
  }

  useEffect(() => {
    alive.current = true;
    void readStatus();
    return () => { alive.current = false; reading.current++; };
  }, []); // Смена доставки/аккаунта создаёт новый экземпляр через key.

  async function send(n: number) {
    if (!current() || inFlight.current || phase !== "ready" || stars !== null) return;
    inFlight.current = true;
    setBusy(true);
    setError(null);
    try {
      await rateParcel(parcelId, n, text.trim());
      if (!current()) return;
      setStars(n);
      track("parcel_rate", { stars: n });
    } catch {
      // Ответ мог потеряться ПОСЛЕ сохранения. Прежде повтора читаем факт сервера.
      if (current()) await readStatus(appText("Не получилось сохранить оценку. Проверь сеть.", "Баһаны һаҡлап булманы. Селтәрҙе тикшер."));
    } finally {
      inFlight.current = false;
      if (current()) setBusy(false);
    }
  }

  if (phase === "loading") return <p role="status">{appText("Проверяем оценку…", "Баһаны тикшерәбеҙ…")}</p>;
  if (phase === "error") return (
    <div className="rate-card" role="alert">
      <p>{appText("Не получилось проверить оценку. Повтори попытку.", "Баһаны тикшереп булманы. Ҡабатлап ҡара.")}</p>
      <button type="button" className="btn-soft" onClick={() => void readStatus()}>{appText("Повторить", "Ҡабатлау")}</button>
    </div>
  );
  if (stars !== null) return (
    <div className="consents__status ok" style={{ marginTop: 14 }} role="status">
      <p>{appText("Спасибо, оценка учтена!", "Рәхмәт, баһа иҫәпкә алынды!")}</p>
      <p>{appText(`Твоя оценка: ${stars} из 5.`, `Һинең баһаң: 5-тән ${stars}.`)}</p>
    </div>
  );

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
            className="rate-star"
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
          disabled={busy}
          onChange={(e) => setText(e.target.value)}
          placeholder={appText("Всё вовремя, спасибо", "Бөтәһе ваҡытында, рәхмәт")}
        />
      </label>

      {error && <div className="auth__error">{error}</div>}
    </div>
  );
}