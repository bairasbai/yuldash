// ================================================================
//  «Как шёл торг» — одна дорожка ходов, общая для обеих сторон.
//  Типографика и сетка одинаковы у пассажира и водителя, иначе
//  два экрана одного торга разъезжаются по виду (урок Android:
//  BargainUi.kt — общие примитивы вместо копий).
// ================================================================
import { useLang } from "../i18n/lang";
import type { BargainStep } from "../api/requests";

/**
 * Всего ходов в торге — по три встречных с каждой стороны
 * (`BARGAIN_MAX_ROUNDS` на сервере). Дальше сервер откажет с 409.
 */
const MAX_TOTAL_MOVES = 6;

export default function BargainTrail({
  history,
  mine,
  rounds,
}: {
  history: BargainStep[];
  /** Чьи ходы подсвечиваем как свои: у водителя — driver, у пассажира — passenger. */
  mine: "driver" | "passenger";
  /** Сколько встречных уже сделано. Без него счётчик ходов не показываем. */
  rounds?: number;
}) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  if (history.length < 2) return null; // один ход — это ещё не торг, дорожку не рисуем

  // Торг конечен, и об этом надо сказать заранее: иначе человек тянет цену
  // до упора и упирается в отказ сервера, не поняв, что произошло.
  const left = rounds == null ? null : Math.max(0, MAX_TOTAL_MOVES - rounds);
  const lastMove = left === 1;

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

      {left != null && left > 0 && (
        <div className={"bargain__left" + (lastMove ? " bargain__left--last" : "")}>
          {lastMove
            ? appText(
                "Это последний ход в торге. Дальше — принять или разойтись.",
                "Был һатыулашыуҙағы һуңғы сират. Артабан — ҡабул итеү йәки таралышыу."
              )
            : appText(
                `Осталось ходов: ${left}. Торгуемся по очереди.`,
                `Ҡалған сират: ${left}. Сиратлап һатыулашабыҙ.`
              )}
        </div>
      )}
    </div>
  );
}
