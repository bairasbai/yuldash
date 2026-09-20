// ================================================================
//  MobilityRouteTimeline (MobilityUi.kt): зелёная точка «Откуда», линия, жёлтый
//  квадратик «Куда»; подписи 12 medium muted, значения Bold (compact — 14/1 строка).
// ================================================================
import { useLang } from "../i18n/lang";

export default function RouteTimeline({
  from,
  to,
  fromLabel,
  toLabel,
  compact = false,
}: {
  from: string;
  to: string;
  fromLabel?: string;
  toLabel?: string;
  compact?: boolean;
}) {
  const { appText } = useLang();
  const safeFrom = from.trim() || appText("Точка А", "А нөктәһе");
  const safeTo = to.trim() || appText("Точка Б", "Б нөктәһе");
  const fl = fromLabel ?? appText("Откуда", "Ҡайҙан");
  const tl = toLabel ?? appText("Куда", "Ҡайҙа");
  return (
    <div className={"route-tl" + (compact ? " route-tl--compact" : "")} role="img" aria-label={`${fl}: ${safeFrom}. ${tl}: ${safeTo}`}>
      <span className="route-tl__rail" aria-hidden>
        <i className="route-tl__a" />
        <i className="route-tl__line" />
        <i className="route-tl__b" />
      </span>
      <span className="route-tl__text">
        <span className="route-tl__item">
          <small>{fl}</small>
          <strong>{safeFrom}</strong>
        </span>
        <span className="route-tl__item">
          <small>{tl}</small>
          <strong>{safeTo}</strong>
        </span>
      </span>
    </div>
  );
}
