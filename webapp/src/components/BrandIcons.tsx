// ================================================================
//  BrandIcons — брендовые иконки Юлдаша, портированные 1:1 из Android
//  (res/drawable/yu_*.xml, VectorDrawable). viewBox 0 0 48 48 как в
//  исходниках, цвет наследуется через currentColor (fill/stroke).
//  Обводка strokeWidth 2.2 (маршрут — 2.4), скруглённые концы —
//  как в Compose. НЕ добавлять новые иконки «от себя»: только то,
//  что есть в андроид-наборе, чтобы веб и приложение совпадали.
// ================================================================
type P = { size?: number; className?: string };

/** Общая обёртка: тот же viewBox и стиль штриха, что у yu_*.xml. */
function Svg({
  size = 24,
  strokeWidth = 2.2,
  className,
  children,
}: P & { strokeWidth?: number; children: React.ReactNode }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 48 48"
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
      className={className}
    >
      {children}
    </svg>
  );
}

// Заливка currentColor (для «плотных» путей: звезда, луна, знак такси и т.п.)
const F = { fill: "currentColor" } as const;

// ─────────── Нижняя навигация ───────────
export const YuMapTab = (p: P) => (
  <Svg {...p}>
    <path {...F} d="M9 13L18 9L18 35L9 39Z" />
    <path {...F} d="M18 9L29 13L29 39L18 35Z" />
    <path {...F} d="M29 13L39 9L39 35L29 39Z" />
    <path {...F} d="M31.5 32a4.5 4.5 0 1 0 9 0a4.5 4.5 0 1 0 -9 0Z" />
  </Svg>
);
export const YuTripList = (p: P) => (
  <Svg {...p}>
    <path d="M11 11c0-1.66 1.34-3 3-3h20c1.66 0 3 1.34 3 3v26c0 1.66-1.34 3-3 3h-20c-1.66 0-3-1.34-3-3z" />
  </Svg>
);
export const YuRequestAdd = (p: P) => (
  <Svg {...p}>
    <path d="M13 11c0-1.66 1.34-3 3-3h16c1.66 0 3 1.34 3 3v24c0 1.66-1.34 3-3 3h-16c-1.66 0-3-1.34-3-3z" />
  </Svg>
);
export const YuChat = (p: P) => (
  <Svg {...p}>
    <path d="M10 12H38V31H22L15 38V31H10Z" />
  </Svg>
);
export const YuProfile = (p: P) => (
  <Svg {...p}>
    <path d="M17 31Q24 23 31 31" />
  </Svg>
);

// ─────────── Режимы поездки ───────────
const carBody = (
  <>
    <path d="M8 27L11 19Q12 16 16 16H32Q36 16 37 19L40 27" />
    <path d="M10 27H38Q41 27 41 30V36H7V30Q7 27 10 27" />
  </>
);
export const YuModeRideshare = (p: P) => <Svg {...p}>{carBody}</Svg>;
export const YuModeTaxi = (p: P) => (
  <Svg {...p}>
    {carBody}
    <path
      {...F}
      d="M19 9.8c0-0.48 0.19-0.94 0.53-1.27 0.34-0.34 0.8-0.53 1.27-0.53h6.4c0.48 0 0.94 0.19 1.27 0.53 0.34 0.34 0.53 0.8 0.53 1.27v0.4c0 0.48-0.19 0.94-0.53 1.27-0.34 0.34-0.8 0.53-1.27 0.53h-6.4c-0.48 0-0.94-0.19-1.27-0.53-0.34-0.34-0.53-0.8-0.53-1.27z"
    />
  </Svg>
);
export const YuModeCourier = (p: P) => (
  <Svg {...p}>
    <path d="M11 30Q18 22 25 30" />
    <path
      {...F}
      d="M24 23.5c0-0.83 0.67-1.5 1.5-1.5h7c0.83 0 1.5 0.67 1.5 1.5v7c0 0.83-0.67 1.5-1.5 1.5h-7c-0.83 0-1.5-0.67-1.5-1.5z"
    />
    <path d="M29 22V17" />
  </Svg>
);
export const YuModeParcel = (p: P) => (
  <Svg {...p}>
    <path d="M10 17L24 10L38 17L24 24Z" />
    <path d="M10 17L10 34L24 41L24 24Z" />
    <path d="M38 17L38 34L24 41L24 24Z" />
    <path d="M17 13.5L31 20.5" />
    <path d="M31 20.5L31 28" />
  </Svg>
);

// ─────────── Рейтинг / маршрут / безопасность / поддержка ───────────
export const YuStar = (p: P) => (
  <Svg {...p}>
    <path {...F} d="M24 8L28 18L39 19L31 26L34 38L24 32L14 38L17 26L9 19L20 18Z" />
  </Svg>
);
export const YuRoute = (p: P) => (
  <Svg {...p} strokeWidth={2.4}>
    <path d="M12 34C16 29 18 28 22 25 27 21 29 18 36 15" />
    <path d="M12 34C15 36 17 37 19 39" />
  </Svg>
);
export const YuSafeTrip = (p: P) => (
  <Svg {...p}>
    <path d="M24 8L37 13V23C37 32 31 39 24 42 17 39 11 32 11 23V13Z" />
    {carBody}
    <path
      {...F}
      d="M19 9.8c0-0.48 0.19-0.94 0.53-1.27 0.34-0.34 0.8-0.53 1.27-0.53h6.4c0.48 0 0.94 0.19 1.27 0.53 0.34 0.34 0.53 0.8 0.53 1.27v0.4c0 0.48-0.19 0.94-0.53 1.27-0.34 0.34-0.8 0.53-1.27 0.53h-6.4c-0.48 0-0.94-0.19-1.27-0.53-0.34-0.34-0.53-0.8-0.53-1.27z"
    />
  </Svg>
);
export const YuSupport = (p: P) => (
  <Svg {...p}>
    <path d="M18 21Q18 14 24 14 30 14 30 21V30H18Z" />
  </Svg>
);

// ─────────── Тема (день / ночь) ───────────
export const YuSun = (p: P) => (
  <Svg {...p}>
    <path {...F} d="M17 24a7 7 0 1 0 14 0a7 7 0 1 0 -14 0Z" />
    <path d="M24 8L24 13" />
    <path d="M24 35L24 40" />
    <path d="M8 24L13 24" />
    <path d="M35 24L40 24" />
    <path d="M13 13L16 16" />
    <path d="M32 32L35 35" />
    <path d="M32 16L35 13" />
    <path d="M16 32L13 35" />
  </Svg>
);
export const YuMoon = (p: P) => (
  <Svg {...p}>
    <path {...F} d="M31 10A13 13 0 1 0 31 38 11 11 0 1 1 31 10Z" />
  </Svg>
);

// ─────────── Удобства поездки ───────────
export const YuAc = (p: P) => (
  <Svg {...p}>
    <path d="M24 20C18 12 20 8 24 8" />
    <path d="M28 24C36 18 40 20 40 24" />
    <path d="M24 28C30 36 28 40 24 40" />
    <path d="M20 24C12 30 8 28 8 24" />
  </Svg>
);
export const YuChildSeat = (p: P) => (
  <Svg {...p}>
    <path d="M16 11Q25 8 31 15L35 32Q36 39 29 39H16Q12 39 12 34Z" />
    <path d="M20 26L30 35" />
    <path d="M30 25L20 35" />
  </Svg>
);
export const YuLuggage = (p: P) => (
  <Svg {...p}>
    <path d="M11 21c0-2.21 1.79-4 4-4h18c2.21 0 4 1.79 4 4v14c0 2.21-1.79 4-4 4h-18c-2.21 0-4-1.79-4-4z" />
    <path d="M18 17V12H30V17" />
  </Svg>
);
export const YuPet = (p: P) => (
  <Svg {...p}>
    <path d="M17 36Q24 28 31 36 28 41 24 41 20 41 17 36" />
  </Svg>
);
export const YuSmokeFree = (p: P) => (
  <Svg {...p}>
    <path d="M32 27C36 25 36 21 33 20" />
    <path d="M36 20C39 17 38 13 35 11" />
  </Svg>
);
export const YuWomenOnly = (p: P) => (
  <Svg {...p}>
    <path d="M16 16a8 8 0 1 0 16 0a8 8 0 1 0 -16 0Z" />
    <path d="M24 24L24 39" />
    <path d="M18 39L30 39" />
  </Svg>
);
export const YuQuiet = (p: P) => (
  <Svg {...p}>
    <path d="M10 28H16L24 35V13L16 20H10Z" />
    <path d="M31 20Q35 24 31 28" />
  </Svg>
);

// ─────────── Прочие брендовые иконки ───────────
export const YuMultiStop = (p: P) => (
  <Svg {...p}>
    <path d="M12 36C16 30 18 28 22 26" />
    <path d="M26 22C30 18 32 16 36 12" />
  </Svg>
);
export const YuCourierWalk = (p: P) => (
  <Svg {...p}>
    <path d="M17 31Q24 23 31 31" />
    <path d="M20 31L15 41" />
    <path d="M27 31L34 39" />
  </Svg>
);
export const YuAccessible = (p: P) => (
  <Svg {...p}>
    <path d="M20 15V26H29" />
    <path d="M20 21H14" />
    <path d="M29 26L35 36" />
  </Svg>
);
export const YuService = (p: P) => (
  <Svg {...p}>
    <path d="M13 10L20 17 17 22 10 15Q7 23 13 29L31 11Q38 17 32 25L24 17" />
  </Svg>
);
export const YuMapCar = (p: P) => (
  <Svg {...p}>
    <path d="M15 14c0-3.87 3.13-7 7-7h4c3.87 0 7 3.13 7 7v20c0 3.87-3.13 7-7 7h-4c-3.87 0-7-3.13-7-7z" />
    <path d="M18 16c0-1.66 1.34-3 3-3h6c1.66 0 3 1.34 3 3v4c0 1.66-1.34 3-3 3h-6c-1.66 0-3-1.34-3-3z" />
    <path d="M18 29c0-1.1 0.9-2 2-2h8c1.1 0 2 0.9 2 2v4c0 1.1-0.9 2-2 2h-8c-1.1 0-2-0.9-2-2z" />
  </Svg>
);
