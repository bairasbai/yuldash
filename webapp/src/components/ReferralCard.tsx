// ================================================================
//  «Позови своего» — мятная карточка реферала под шапкой профиля (ProfileScreen.kt):
//  пригласил соседа → бонус обоим; три цифры (позвал / бонусов / твой код), «Пригласить»
//  (системный шаринг, иначе — копирование текста) и «Ввести код» (пока чужой код не введён).
//  Реферал не загрузился → карточки нет: пустой блок не должен оставлять дыру под шапкой.
// ================================================================
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { fetchReferral, type ReferralMe } from "../api/referral";
import { IconProfile } from "./Icons";

export default function ReferralCard() {
  const { appText } = useLang();
  const navigate = useNavigate();
  const [ref, setRef] = useState<ReferralMe | null>(null);
  const [note, setNote] = useState("");

  useEffect(() => {
    const ac = new AbortController();
    fetchReferral(ac.signal)
      .then(setRef)
      .catch(() => setRef(null));
    return () => ac.abort();
  }, []);

  if (!ref) return null;

  const shareTxt = appText(
    `Я в Юлдаше — попутки между своими по Башкортостану. Мой код: ${ref.code}. Введи его в профиле — получим бонусы. Скачать: https://yulbash.ru`,
    `Мин Юлдашта — Башҡортостан буйлап үҙебеҙ араһында юлдаштар. Кодым: ${ref.code}. Профилдә индер — бонус алырбыҙ. Йөкләргә: https://yulbash.ru`
  );

  async function invite() {
    setNote("");
    try {
      if (navigator.share) {
        await navigator.share({ text: shareTxt });
        return;
      }
      await navigator.clipboard?.writeText(shareTxt);
      setNote(appText("Текст приглашения скопирован", "Саҡырыу тексты күсереп алынды"));
    } catch {
      /* отменил шаринг — ничего не говорим */
    }
  }

  return (
    <section className="refcard appear" style={{ "--i": 0 } as React.CSSProperties}>
      <div className="refcard__head">
        <IconProfile size={24} />
        <h2>{appText("Позови своего", "Үҙеңдекен саҡыр")}</h2>
      </div>
      <p>{appText("Пригласил соседа → бонус обоим: бесплатное поднятие поездки.", "Күршеңде саҡырҙың → икәүегеҙ ҙә бонус (сәфәрҙе бушлай күтәреү) аласаҡ.")}</p>
      <div className="refcard__stats">
        <span className="refcard__stat">
          <small>{appText("Позвал", "Саҡырҙы")}</small>
          <strong>{ref.invited}</strong>
        </span>
        <span className="refcard__stat">
          <small>{appText("Бонусов", "Бонус")}</small>
          <strong>{ref.credits}</strong>
        </span>
        <span className="refcard__stat">
          <small>{appText("Твой код", "Кодың")}</small>
          <strong className="refcard__code">{ref.code}</strong>
        </span>
      </div>
      <div className="refcard__actions">
        <button type="button" className="refcard__btn refcard__btn--primary" onClick={() => void invite()}>
          {appText("Пригласить", "Саҡырыу")}
        </button>
        {!ref.redeemed && (
          <button type="button" className="refcard__btn refcard__btn--outline" onClick={() => navigate("/invites")}>
            {appText("Ввести код", "Код индереү")}
          </button>
        )}
      </div>
      {note && <small className="refcard__note" role="status">{note}</small>}
    </section>
  );
}
