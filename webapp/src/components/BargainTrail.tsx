// ================================================================
//  «Как шёл торг» — одна дорожка ходов, общая для обеих сторон.
//  Типографика и сетка одинаковы у пассажира и водителя, иначе
//  два экрана одного торга разъезжаются по виду (урок Android:
//  BargainUi.kt — общие примитивы вместо копий).
// ================================================================
import { useLang } from "../i18n/lang";
import type { BargainStep } from "../api/requests";

export default function BargainTrail({
  history,
  mine,
}: {
  history: BargainStep[];
  /** Чьи ходы подсвечиваем как свои: у водителя — driver, у пассажира — passenger. */
  mine: "driver" | "passenger";
}) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  if (history.length < 2) return null; // один ход — это ещё не торг, дорожку не рисуем

  return (
    <div className="bargain">
      <div className="bargain__label">{appText("Как шёл торг", "Һатыулашыу нисек барҙы")}</div>
      <div className="bargain__steps">
        {history.map((s, i) => (
          <span key={`${s.by}-${s.price}-${i}`}>
            {i > 0 && <span className="bargain__arrow">→ </span>}
            <span className={"bargain__step" + (s.by === mine ? " bargain__step--mine" : "")}>
              {s.price.toLocaleString("ru-RU")} {ru ? "₽" : "һ"}
            </span>
          </span>
        ))}
      </div>
    </div>
  );
}
