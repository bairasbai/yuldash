/**
 * Свои иконки вместо эмодзи.
 *
 * Эмодзи выглядят по-разному на каждом телефоне и всегда чуть дешевле
 * остального интерфейса. Эти нарисованы одной линией толщиной 1.1—1.3,
 * в одной сетке 24×24, и подхватывают цвет текста.
 */

type IconProps = { size?: number; className?: string };

const base = (size: number) => ({
  width: size,
  height: size,
  viewBox: "0 0 24 24",
  fill: "none" as const,
  stroke: "currentColor" as const,
  strokeWidth: 1.15,
  strokeLinecap: "round" as const,
  strokeLinejoin: "round" as const,
  "aria-hidden": true,
});

/** Искра — знак «думаю о тебе». Четыре луча, вытянутые по вертикали. */
export function Spark({ size = 24, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <path
        d="M12 2.6c.5 4.6 1.6 6.9 4.6 8.2.7.3.7 1.1 0 1.4-3 1.3-4.1 3.6-4.6 8.2-.5-4.6-1.6-6.9-4.6-8.2-.7-.3-.7-1.1 0-1.4 3-1.3 4.1-3.6 4.6-8.2Z"
        fill="currentColor"
        fillOpacity="0.14"
      />
      <path d="M19.4 4.2c.2 1.5.6 2.2 1.7 2.7-1.1.5-1.5 1.2-1.7 2.7-.2-1.5-.6-2.2-1.7-2.7 1.1-.5 1.5-1.2 1.7-2.7Z" />
    </svg>
  );
}

/**
 * Свеча — запасной конверт «когда грустно».
 * Пропорции важнее деталей: узкая и высокая читается свечой,
 * широкая и короткая — пузырьком.
 */
export function Candle({ size = 24, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      {/* Пламя каплей, с оттянутым кончиком */}
      <path
        d="M12 1.6c2.2 2.5 3.3 4.3 3.3 5.6a3.3 3.3 0 0 1-6.6 0c0-1.3 1.1-3.1 3.3-5.6Z"
        fill="currentColor"
        fillOpacity="0.22"
      />
      <path d="M12 10.6v1.5" />
      <path d="M10.1 12.1h3.8v9.4a.9.9 0 0 1-.9.9h-2a.9.9 0 0 1-.9-.9v-9.4Z" />
      {/* Оплывший воск по левому краю */}
      <path d="M10.1 15.2c.9.5.9 1.7 0 2.3" strokeOpacity="0.5" />
    </svg>
  );
}

/** Замок — письмо, до которого ещё не дошёл срок. */
export function Lock({ size = 24, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <path d="M8.2 10.2V7.6a3.8 3.8 0 0 1 7.6 0v2.6" />
      <rect x="5.4" y="10.2" width="13.2" height="9.4" rx="2.4" />
      <circle cx="12" cy="14.9" r="1.05" fill="currentColor" stroke="none" />
    </svg>
  );
}

/** Стрелка назад. */
export function ArrowLeft({ size = 24, className }: IconProps) {
  return (
    <svg {...base(size)} className={className}>
      <path d="M14.6 5.8 8.4 12l6.2 6.2" />
    </svg>
  );
}

/* ── Погода ────────────────────────────────────────────────────────── */

function SunShape() {
  return (
    <>
      <circle cx="12" cy="12" r="4" />
      <path d="M12 3.4v1.8M12 18.8v1.8M20.6 12h-1.8M5.2 12H3.4M18.1 5.9l-1.3 1.3M7.2 16.8l-1.3 1.3M18.1 18.1l-1.3-1.3M7.2 7.2 5.9 5.9" />
    </>
  );
}

function CloudShape({ y = 0 }: { y?: number }) {
  return (
    <path
      d={`M7.4 ${18 + y}h9.4a3.6 3.6 0 0 0 .3-7.2 5.2 5.2 0 0 0-9.9-1 3.6 3.6 0 0 0 .2 8.2Z`}
    />
  );
}

export function WeatherIcon({
  kind,
  size = 24,
  className,
}: IconProps & {
  kind: "clear" | "mostly" | "cloud" | "fog" | "drizzle" | "rain" | "snow" | "storm";
}) {
  return (
    <svg {...base(size)} className={className}>
      {kind === "clear" && <SunShape />}

      {kind === "mostly" && (
        <>
          <circle cx="9.4" cy="8.6" r="3.1" />
          <path d="M9.4 3.2v1.3M14.8 8.6h-1.3M13.2 4.8l-.9.9M5.6 4.8l.9.9M4 8.6h1.3" />
          <CloudShape y={-1} />
        </>
      )}

      {kind === "cloud" && <CloudShape y={-1} />}

      {kind === "fog" && (
        <>
          <CloudShape y={-4} />
          <path d="M5.6 18.4h12.8M7.6 21h8.8" />
        </>
      )}

      {kind === "drizzle" && (
        <>
          <CloudShape y={-4} />
          <path d="M9.2 18.6l-.7 2M12.4 18.6l-.7 2M15.6 18.6l-.7 2" />
        </>
      )}

      {kind === "rain" && (
        <>
          <CloudShape y={-4} />
          <path d="M8.8 18.2l-1.3 3.2M12.4 18.2l-1.3 3.2M16 18.2l-1.3 3.2" />
        </>
      )}

      {kind === "snow" && (
        <>
          <CloudShape y={-4} />
          <path d="M8.6 19.4v2.2M7.6 20.5h2M12 19.4v2.2M11 20.5h2M15.4 19.4v2.2M14.4 20.5h2" />
        </>
      )}

      {kind === "storm" && (
        <>
          <CloudShape y={-4} />
          <path d="M12.8 17.9 10.4 21h2.9l-1.4 2.6" />
        </>
      )}
    </svg>
  );
}
