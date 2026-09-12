import { useLang } from "../i18n/lang";
import { IconCheck } from "./Icons";

/** Тот же индекс шага, что taxiProgressIndex в Android MobilityUi.kt. */
export function taxiProgressIndex(status: string): number {
  switch (status) {
    case "arriving":
      return 1;
    case "onboard":
      return 2;
    case "done":
      return 3;
    default:
      return 0;
  }
}

/** Индекс шага доставки — courierProgressIndex в Android MobilityUi.kt. */
export function courierProgressIndex(status: string): number {
  switch (status) {
    case "in_transit":
    case "returning":
      return 1;
    case "delivered":
    case "returned":
      return 2;
    default:
      return 0;
  }
}

/**
 * Рельса прогресса — зеркало Android MobilityProgressRail: точки по числу шагов, линии 2dp,
 * точка текущего шага 18, пройденные 14 с галочкой; акцент — жёлтый такси или зелёный курьера.
 */
export function ProgressRail({
  labels,
  current,
  accent = "taxi",
}: {
  labels: string[];
  current: number;
  accent?: "taxi" | "courier";
}) {
  return (
    <div
      className={"progress-rail progress-rail--" + accent}
      role="progressbar"
      aria-valuemin={0}
      aria-valuemax={labels.length - 1}
      aria-valuenow={current}
      aria-valuetext={labels[current]}
    >
      {labels.map((label, index) => {
        const done = index < current;
        const active = index === current;
        return (
          <div key={label} className="progress-rail__step">
            <div className="progress-rail__line-row">
              <span className={"progress-rail__line" + (index === 0 ? " is-hidden" : index <= current ? " is-on" : "")} />
              <span className={"progress-rail__dot" + (active ? " is-active" : done ? " is-done" : "")} aria-hidden>
                {done ? <IconCheck size={12} /> : active ? <span className="progress-rail__core" /> : null}
              </span>
              <span className={"progress-rail__line" + (index === labels.length - 1 ? " is-hidden" : index < current ? " is-on" : "")} />
            </div>
            <span className={"progress-rail__label" + (active ? " is-active" : "")}>{label}</span>
          </div>
        );
      })}
    </div>
  );
}

/** Рельса поездки такси: «Едет · На месте · В пути · Готово» (Android TaxiTripProgress). */
export default function TaxiTripProgress({ status, accent = "taxi" }: { status: string; accent?: "taxi" | "courier" }) {
  const { appText } = useLang();
  const labels = [
    appText("Едет", "Килә"),
    appText("На месте", "Урында"),
    appText("В пути", "Юлда"),
    appText("Готово", "Әҙер"),
  ];
  return <ProgressRail labels={labels} current={taxiProgressIndex(status)} accent={accent} />;
}

/** Рельса доставки: «Забрать · В пути · Вручить»; при возврате подписи меняются (Android CourierDeliveryProgress). */
export function CourierDeliveryProgress({ status }: { status: string }) {
  const { appText } = useLang();
  const labels = [
    appText("Забрать", "Алыу"),
    status === "returning" ? appText("Возврат", "Кире илтеү") : appText("В пути", "Юлда"),
    status === "returned" ? appText("Возвращено", "Кире бирелде") : appText("Вручить", "Тапшырыу"),
  ];
  return <ProgressRail labels={labels} current={courierProgressIndex(status)} accent="courier" />;
}
