export type Mote = readonly [number, number, number, number, number];
export const INTRO_EASE: Record<"outExpo" | "inOutSine" | "inCubic", readonly [number, number, number, number]>;
export const MOTES: Mote[];
export function springDurationMs(start: number, target: number, damping: number, stiffness?: number, threshold?: number): number;
export function springValue(start: number, target: number, elapsedMs: number, damping: number, stiffness?: number): number;
export function cubicBezier(progress: number, points: readonly [number, number, number, number]): number;
export function moteFrame(mote: Mote, t: number, sceneAlpha: number): { x: number; y: number; radius: number; alpha: number };
export function introMotionFrame(elapsedMs: number, exitElapsedMs?: number | null): {
  sceneAlpha: number; sceneScale: number; columnScale: number; columnAlpha: number; logoScale: number; moteProgress: number;
};
