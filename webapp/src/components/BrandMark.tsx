/**
 * Знак бренда Юлдаш: изумрудный «камень» со светлой дорогой и золотой точкой
 * назначения (зелёный маршрут + жёлтая точка — как в фирменной карте).
 * Самодостаточный SVG, тянется по размеру. Без внешних ассетов.
 */
export default function BrandMark({ size = 96 }: { size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 96 96"
      fill="none"
      role="img"
      aria-label="Юлдаш"
    >
      <defs>
        <linearGradient id="ym-bg" x1="0" y1="0" x2="0" y2="96" gradientUnits="userSpaceOnUse">
          <stop stopColor="#0e7d44" />
          <stop offset="1" stopColor="#073f25" />
        </linearGradient>
      </defs>
      <rect width="96" height="96" rx="26" fill="url(#ym-bg)" />
      {/* дорога-серпантин */}
      <path
        d="M30 74 C 30 58, 66 54, 66 40 C 66 28, 44 28, 44 20"
        stroke="#7fe3ab"
        strokeWidth="7"
        strokeLinecap="round"
        strokeDasharray="1 13"
        opacity="0.9"
      />
      <path
        d="M30 74 C 30 58, 66 54, 66 40 C 66 28, 44 28, 44 20"
        stroke="#2fb36e"
        strokeWidth="7"
        strokeLinecap="round"
        opacity="0.35"
      />
      {/* точка назначения */}
      <circle cx="44" cy="19" r="8" fill="#f5b301" />
      <circle cx="44" cy="19" r="3.4" fill="#3a2600" />
      {/* точка старта */}
      <circle cx="30" cy="74" r="5.5" fill="#eafff3" />
    </svg>
  );
}
