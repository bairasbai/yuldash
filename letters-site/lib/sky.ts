/**
 * Небо сайта = один параметр от 0 до 1.
 * 0 — глубокая ночь в день первого письма, 1 — утро 21 августа.
 * Каждый день небо чуть светлеет. К встрече оно рассветает полностью.
 */

export type SkyPalette = {
  zenith: string;
  upper: string;
  mid: string;
  horizon: string;
  /** Тёплое зарево у самой кромки гор. */
  glow: string;
  /** Насколько видны звёзды: 1 — все, 0 — растворились. */
  stars: number;
  /** Насколько поднялось солнце: 0 — его нет, 1 — краешек над горами. */
  sun: number;
  ridgeFar: string;
  ridgeNear: string;
};

type Stop = { at: number } & Omit<SkyPalette, "stars" | "sun"> & {
    stars: number;
    sun: number;
  };

const STOPS: Stop[] = [
  {
    at: 0,
    zenith: "#04060f",
    upper: "#070b1c",
    mid: "#0d1430",
    horizon: "#16204a",
    glow: "#1e2a5c",
    stars: 1,
    sun: 0,
    ridgeFar: "#0a1024",
    ridgeNear: "#04060e",
  },
  {
    at: 0.45,
    zenith: "#060a1a",
    upper: "#0b1330",
    mid: "#172352",
    horizon: "#2d3a72",
    glow: "#4a4080",
    stars: 0.85,
    sun: 0,
    ridgeFar: "#101838",
    ridgeNear: "#060913",
  },
  {
    at: 0.75,
    zenith: "#0d1836",
    upper: "#1c2a5c",
    mid: "#3b4a86",
    horizon: "#8a6a8e",
    glow: "#d4795f",
    stars: 0.42,
    sun: 0,
    ridgeFar: "#1d2750",
    ridgeNear: "#0a0f20",
  },
  {
    at: 0.92,
    zenith: "#152747",
    upper: "#294a7c",
    mid: "#4f7099",
    horizon: "#dc8468",
    glow: "#ff9c5a",
    stars: 0.14,
    sun: 0.35,
    ridgeFar: "#2b3663",
    ridgeNear: "#111832",
  },
  {
    // Верх неба остаётся синим намеренно: так выглядит настоящий рассвет,
    // и только так белый текст читается поверх него. Всё тепло — внизу.
    at: 1,
    zenith: "#123055",
    upper: "#26538a",
    mid: "#5378a6",
    horizon: "#f0a878",
    glow: "#ffb066",
    stars: 0,
    sun: 1,
    ridgeFar: "#41567f",
    ridgeNear: "#1c2540",
  },
];

function hexToRgb(hex: string): [number, number, number] {
  const v = parseInt(hex.slice(1), 16);
  return [(v >> 16) & 255, (v >> 8) & 255, v & 255];
}

function rgbToHex([r, g, b]: [number, number, number]): string {
  const h = (n: number) => Math.round(n).toString(16).padStart(2, "0");
  return `#${h(r)}${h(g)}${h(b)}`;
}

function mixHex(a: string, b: string, t: number): string {
  const [r1, g1, b1] = hexToRgb(a);
  const [r2, g2, b2] = hexToRgb(b);
  return rgbToHex([r1 + (r2 - r1) * t, g1 + (g2 - g1) * t, b1 + (b2 - b1) * t]);
}

/** Плавное «замедление» на краях, чтобы переход не был линейно-механическим. */
function ease(t: number): number {
  return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
}

export function skyPalette(progress: number): SkyPalette {
  const p = Math.min(1, Math.max(0, progress));

  let lo = STOPS[0];
  let hi = STOPS[STOPS.length - 1];
  for (let i = 0; i < STOPS.length - 1; i++) {
    if (p >= STOPS[i].at && p <= STOPS[i + 1].at) {
      lo = STOPS[i];
      hi = STOPS[i + 1];
      break;
    }
  }

  const span = hi.at - lo.at;
  const t = span === 0 ? 0 : ease((p - lo.at) / span);

  return {
    zenith: mixHex(lo.zenith, hi.zenith, t),
    upper: mixHex(lo.upper, hi.upper, t),
    mid: mixHex(lo.mid, hi.mid, t),
    horizon: mixHex(lo.horizon, hi.horizon, t),
    glow: mixHex(lo.glow, hi.glow, t),
    ridgeFar: mixHex(lo.ridgeFar, hi.ridgeFar, t),
    ridgeNear: mixHex(lo.ridgeNear, hi.ridgeNear, t),
    stars: lo.stars + (hi.stars - lo.stars) * t,
    sun: lo.sun + (hi.sun - lo.sun) * t,
  };
}

/**
 * Звёзды должны стоять на одних и тех же местах при каждой загрузке,
 * иначе сервер и браузер нарисуют разное небо. Поэтому не Math.random,
 * а свой генератор с фиксированным зерном.
 */
function mulberry32(seed: number) {
  return function () {
    seed |= 0;
    seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export type Star = {
  x: number;
  y: number;
  size: number;
  base: number;
  delay: number;
  duration: number;
};

export function makeStars(count = 130, seed = 20260821): Star[] {
  const rnd = mulberry32(seed);
  const stars: Star[] = [];
  // Округляем: длинные хвосты дробей раздувают HTML и ничего не дают глазу.
  const r = (v: number, digits = 2) => Number(v.toFixed(digits));
  for (let i = 0; i < count; i++) {
    // Ближе к горизонту звёзд меньше — там их съедает зарево.
    const y = Math.pow(rnd(), 1.5) * 82;
    stars.push({
      x: r(rnd() * 100),
      y: r(y),
      size: r(0.8 + rnd() * 1.8),
      base: r(0.35 + rnd() * 0.65),
      delay: r(rnd() * 6),
      duration: r(3 + rnd() * 5),
    });
  }
  return stars;
}
