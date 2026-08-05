"use client";

/**
 * Сургучная печать.
 *
 * Круглая печать выдаёт себя сразу — настоящий воск растекается неровно.
 * Поэтому край собирается из точек с чуть разными радиусами и сглаживается
 * кривыми, а объём даёт не одна тень, а связка: подложка, внутренний
 * рельеф, блик сверху-слева и вдавленный оттиск.
 */

/** Отклонения радиуса по кругу. Фиксированные — печать всегда одна и та же. */
const WOBBLE = [
  1, 0.965, 1.03, 0.985, 1.045, 0.95, 1.015, 0.99, 1.05, 0.96, 1.02, 0.975,
  1.035, 0.955, 1.01, 0.995, 1.04, 0.97, 1.025, 0.98,
];

function wobblyCircle(cx: number, cy: number, r: number): string {
  const pts = WOBBLE.map((k, i) => {
    const a = (i / WOBBLE.length) * Math.PI * 2;
    return [cx + Math.cos(a) * r * k, cy + Math.sin(a) * r * k] as const;
  });

  // Через середины отрезков: точки становятся контрольными, контур — гладким
  let d = "";
  for (let i = 0; i < pts.length; i++) {
    const cur = pts[i];
    const next = pts[(i + 1) % pts.length];
    const mid = [(cur[0] + next[0]) / 2, (cur[1] + next[1]) / 2];
    if (i === 0) {
      const prev = pts[pts.length - 1];
      const startMid = [(prev[0] + cur[0]) / 2, (prev[1] + cur[1]) / 2];
      d += `M${startMid[0].toFixed(2)},${startMid[1].toFixed(2)}`;
    }
    d += ` Q${cur[0].toFixed(2)},${cur[1].toFixed(2)} ${mid[0].toFixed(2)},${mid[1].toFixed(2)}`;
  }
  return `${d} Z`;
}

export default function WaxSeal({ size = 58 }: { size?: number }) {
  const S = 100;
  const outline = wobblyCircle(50, 50, 44);

  return (
    <svg
      width={size}
      height={size}
      viewBox={`0 0 ${S} ${S}`}
      aria-hidden
      style={{ filter: "drop-shadow(0 3px 6px rgb(0 0 0 / 0.42))" }}
    >
      <defs>
        <radialGradient id="wax-body" cx="0.36" cy="0.3" r="0.85">
          <stop offset="0%" stopColor="#b8434e" />
          <stop offset="45%" stopColor="#8f2f38" />
          <stop offset="100%" stopColor="#5e1a22" />
        </radialGradient>

        {/* Рельеф: светлое сверху-слева, тень снизу-справа */}
        <linearGradient id="wax-relief" x1="0.2" y1="0" x2="0.8" y2="1">
          <stop offset="0%" stopColor="#ffffff" stopOpacity="0.24" />
          <stop offset="42%" stopColor="#ffffff" stopOpacity="0" />
          <stop offset="100%" stopColor="#000000" stopOpacity="0.32" />
        </linearGradient>

        <clipPath id="wax-clip">
          <path d={outline} />
        </clipPath>
      </defs>

      <path d={outline} fill="url(#wax-body)" />
      <path d={outline} fill="url(#wax-relief)" />

      {/* Вдавленный ободок внутри печати */}
      <g clipPath="url(#wax-clip)">
        <path
          d={wobblyCircle(50, 50, 35)}
          fill="none"
          stroke="#000000"
          strokeOpacity="0.26"
          strokeWidth="1.6"
        />
        <path
          d={wobblyCircle(50, 49.2, 35)}
          fill="none"
          stroke="#ffffff"
          strokeOpacity="0.13"
          strokeWidth="1.2"
        />

        {/* Блик от света — размытое пятно, а не глянцевый кружок */}
        <ellipse
          cx="36"
          cy="30"
          rx="19"
          ry="13"
          fill="#ffffff"
          opacity="0.14"
          transform="rotate(-24 36 30)"
          style={{ filter: "blur(4px)" }}
        />
      </g>

      {/* Оттиск. Тёмная буква и светлая подсветка снизу — так читается
          вдавленность, а не наклейка сверху. */}
      <text
        x="50"
        y="50"
        textAnchor="middle"
        dominantBaseline="central"
        fontFamily="var(--font-letter), Georgia, serif"
        fontSize="30"
        letterSpacing="1"
        fill="#4a1119"
        opacity="0.75"
      >
        Б·И
      </text>
      <text
        x="50"
        y="51.1"
        textAnchor="middle"
        dominantBaseline="central"
        fontFamily="var(--font-letter), Georgia, serif"
        fontSize="30"
        letterSpacing="1"
        fill="#e8b98a"
        opacity="0.34"
      >
        Б·И
      </text>
    </svg>
  );
}
