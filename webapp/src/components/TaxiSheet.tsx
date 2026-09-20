import { useRef, useState, type PointerEvent as ReactPointerEvent, type ReactNode } from "react";
import { useLang } from "../i18n/lang";

export type TaxiSheetStop = "peek" | "half" | "full";
const STOPS: TaxiSheetStop[] = ["peek", "half", "full"];
/** Насколько надо потянуть шапку, чтобы шторка переехала на соседнее положение — как в Android. */
const SWIPE_THRESHOLD = 48;

/**
 * Общая шторка такси — зеркало Android TaxiSheetScaffold: карта во весь экран, поверх неё
 * панель с тремя положениями (peek / half / full). Высота идёт по содержимому, тянут за всю
 * шапку, жест — шагами: вверх на положение выше, вниз — ниже. Главное действие — в подвале,
 * вне прокрутки, видно в любом положении.
 */
export default function TaxiSheet({
  map,
  header,
  body,
  extra,
  footer,
  overlay,
  stop: controlled,
  onStopChange,
  initialStop = "half",
  halfBodyFraction = 0.38,
}: {
  map: ReactNode;
  header: ReactNode;
  body?: ReactNode;
  extra?: ReactNode;
  footer?: ReactNode;
  overlay?: ReactNode;
  stop?: TaxiSheetStop;
  onStopChange?: (stop: TaxiSheetStop) => void;
  initialStop?: TaxiSheetStop;
  /** Доля экрана под тело в положении half; в full — 0.56, как в Android. */
  halfBodyFraction?: number;
}) {
  const { appText } = useLang();
  const [inner, setInner] = useState<TaxiSheetStop>(initialStop);
  const stop = controlled ?? inner;
  const setStop = (next: TaxiSheetStop) => {
    if (next === stop) return;
    setInner(next);
    onStopChange?.(next);
  };
  const step = (delta: 1 | -1) => {
    const next = Math.min(STOPS.length - 1, Math.max(0, STOPS.indexOf(stop) + delta));
    setStop(STOPS[next]);
  };

  // Копим жест до отпускания: решение принимаем один раз, а не на каждом кадре.
  const startY = useRef<number | null>(null);
  const moved = useRef(false);
  // Шапка — зона хвата, но в ней живут поля и кнопки (адреса заказа): их нажатия — не жест.
  // Сама шапка тоже role="button" — её closest() находит всегда; контрол — только то, что внутри неё.
  const fromControl = (target: EventTarget | null) => {
    if (!(target instanceof Element)) return false;
    const control = target.closest("input, textarea, select, button, a, [role='button']");
    return control !== null && !control.classList.contains("taxi-sheet__grip");
  };
  const onPointerDown = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (fromControl(e.target)) return;
    startY.current = e.clientY;
    moved.current = false;
    e.currentTarget.setPointerCapture(e.pointerId);
  };
  const onPointerMove = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (startY.current != null && Math.abs(e.clientY - startY.current) > 6) moved.current = true;
  };
  const onPointerUp = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (startY.current == null) return;
    const swipe = e.clientY - startY.current;
    startY.current = null;
    if (swipe < -SWIPE_THRESHOLD) step(1);
    else if (swipe > SWIPE_THRESHOLD) step(-1);
  };
  // Тап по шапке без движения — тоже переключает: half ↔ full (клавиатуре и мыши жест недоступен).
  const onGripClick = (e: { target: EventTarget | null }) => {
    if (moved.current || fromControl(e.target)) return;
    setStop(stop === "full" ? "half" : "full");
  };

  const bodyFraction = stop === "full" ? 0.56 : halfBodyFraction;

  return (
    <div className="taxi-sheet-scaffold" data-stop={stop}>
      <div className="taxi-sheet-scaffold__map">{map}</div>
      <section className="taxi-sheet" aria-label={appText("Панель поездки", "Сәфәр панеле")}>
        <div
          className="taxi-sheet__grip"
          role="button"
          tabIndex={0}
          aria-label={stop === "full" ? appText("Свернуть панель", "Панелде йыйыу") : appText("Развернуть панель", "Панелде йәйеү")}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          onPointerCancel={() => { startY.current = null; }}
          onClick={onGripClick}
          onKeyDown={(e) => {
            if (e.target !== e.currentTarget) return;
            if (e.key === "ArrowUp") { e.preventDefault(); step(1); }
            if (e.key === "ArrowDown") { e.preventDefault(); step(-1); }
            if ((e.key === "Enter" || e.key === " ") && e.target === e.currentTarget) { e.preventDefault(); onGripClick(e); }
          }}
        >
          <span className="taxi-sheet__handle" aria-hidden />
          <div className="taxi-sheet__header">{header}</div>
        </div>
        <div className="taxi-sheet__body" style={{ maxHeight: `calc(${bodyFraction} * 100dvh)` }}>
          {stop !== "peek" && body && <div className="taxi-sheet__section">{body}</div>}
          {stop === "full" && extra && <div className="taxi-sheet__section">{extra}</div>}
        </div>
        {footer && <div className="taxi-sheet__footer">{footer}</div>}
      </section>
      {overlay}
    </div>
  );
}

/** Круглая кнопка поверх карты (свернуть, SOS) — Surface(CircleShape, CanonSurface, 48/52). */
export function TaxiSheetOverlayButton({
  position,
  label,
  onClick,
  children,
  large = false,
}: {
  position: "left" | "right";
  label: string;
  onClick: () => void;
  children: ReactNode;
  large?: boolean;
}) {
  return (
    <button
      type="button"
      className={"taxi-sheet__overlay-btn taxi-sheet__overlay-btn--" + position + (large ? " is-large" : "")}
      onClick={onClick}
      aria-label={label}
    >
      {children}
    </button>
  );
}
