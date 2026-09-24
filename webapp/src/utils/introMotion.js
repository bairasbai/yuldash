// Compose 1.11.3: Spring.StiffnessLow=200, Float threshold=.01.
// EasingFunctionsKt bytecode: Expo(.16,1,.3,1), Sine(.37,0,.63,1), Cubic(.32,0,.67,0).
const STIFFNESS = 200;
const THRESHOLD = 0.01;
export const INTRO_EASE = {
  outExpo: [0.16, 1, 0.3, 1],
  inOutSine: [0.37, 0, 0.63, 1],
  inCubic: [0.32, 0, 0.67, 0],
};

export function springDurationMs(start, target, damping, stiffness = STIFFNESS, threshold = THRESHOLD) {
  const displacement = Math.abs(target - start) / threshold;
  if (displacement === 0) return 0;
  const real = -damping * Math.sqrt(stiffness);
  const imaginary = Math.sqrt(stiffness * (1 - damping * damping));
  const amplitude = Math.hypot(displacement, (-real * displacement) / imaginary);
  return Math.trunc(1000 * Math.log(1 / amplitude) / real);
}

export function springValue(start, target, elapsedMs, damping, stiffness = STIFFNESS) {
  if (elapsedMs <= 0) return start;
  const duration = springDurationMs(start, target, damping, stiffness);
  if (elapsedMs >= duration) return target;
  const omega = Math.sqrt(stiffness);
  const real = -damping * omega;
  const imaginary = omega * Math.sqrt(1 - damping * damping);
  const delta = start - target;
  const seconds = elapsedMs / 1000;
  return target + Math.exp(real * seconds) *
    (delta * Math.cos(imaginary * seconds) + (-real * delta / imaginary) * Math.sin(imaginary * seconds));
}

export function cubicBezier(progress, [x1, y1, x2, y2]) {
  if (progress <= 0) return 0;
  if (progress >= 1) return 1;
  const coord = (t, a, b) => 3 * (1 - t) ** 2 * t * a + 3 * (1 - t) * t * t * b + t ** 3;
  let low = 0, high = 1;
  for (let i = 0; i < 24; i++) {
    const mid = (low + high) / 2;
    if (coord(mid, x1, x2) < progress) low = mid;
    else high = mid;
  }
  return coord((low + high) / 2, y1, y2);
}

export const MOTES = [
  [0.14, 0.10, 2.0, 0.60, 0.0], [0.27, 0.30, 1.5, 0.85, 1.2],
  [0.78, 0.16, 2.2, 0.50, 2.1], [0.86, 0.36, 1.6, 0.70, 0.6],
  [0.66, 0.24, 1.7, 0.90, 2.7], [0.10, 0.40, 1.4, 0.55, 1.7],
  [0.90, 0.50, 1.5, 0.65, 3.0],
];

export function moteFrame([x, y, radius, speed, phase], t, sceneAlpha) {
  const raw = (y - t * speed) % 1;
  const ny = raw < 0 ? raw + 1 : raw;
  const twinkle = 0.5 + 0.5 * Math.sin(t * 6.2832 * speed + phase);
  return { x, y: 0.06 + ny * 0.46, radius, alpha: Math.min(0.6, Math.max(0, (0.12 + 0.4 * twinkle) * sceneAlpha)) };
}

const clamp01 = (value) => Math.min(1, Math.max(0, value));
export function introMotionFrame(elapsedMs, exitElapsedMs = null) {
  const drift = cubicBezier(clamp01(elapsedMs / 4700), INTRO_EASE.inOutSine);
  const sceneAlpha = cubicBezier(clamp01(elapsedMs / 820), INTRO_EASE.inOutSine);
  const exitProgress = exitElapsedMs === null ? 0 : clamp01(exitElapsedMs / 500);
  return {
    sceneAlpha,
    sceneScale: 1.08 - 0.08 * drift,
    columnScale: (1 + 0.05 * drift) * (1 + 0.06 * cubicBezier(exitProgress, INTRO_EASE.inCubic)),
    columnAlpha: 1 - cubicBezier(exitProgress, INTRO_EASE.inOutSine),
    logoScale: springValue(0.96, 1, elapsedMs, 0.72),
    moteProgress: (elapsedMs % 9000) / 9000,
  };
}
