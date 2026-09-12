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

/**
 * Рельса прогресса поездки — зеркало Android MobilityProgressRail / TaxiTripProgress:
 * четыре точки «Едет · На месте · В пути · Готово», линии 2dp, точка текущего шага 18,
 * пройденные 14 с галочкой; акцент — жёлтый такси.
 */
export default function TaxiTripProgress({ status, accent = "taxi" }: { status: string; accent?: "taxi" | "courier" }) {
  const { appText } = useLang();
  const labels = [
    appText("Едет", "Килә"),
    appText("На месте", "Урында"),
    appText("В пути", "Юлда"),
    appText("Готово", "Әҙер"),
  ];
  const current = taxiProgressIndex(status);
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
