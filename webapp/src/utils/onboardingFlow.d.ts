import type { Role } from "../flags";

export function finishOnboarding(role: Role, store: { setRole(role: Role): void; setOnboarded(): void }, track: (event: string, props?: { role: Role }) => void, navigate: (to: string, options: { replace: true }) => void): void;
export function enableSimpleOnboarding(store: { setSimpleMode(value: boolean): void; setOnboarded(): void }, track: (event: string) => void, navigate: (to: string, options: { replace: true }) => void): void;
export function pagerPosition(scrollLeft: number, pageWidth: number, count: number): number;
export function createOnboardingPagerController(count: number, onPage: (next: number, old: number) => void, onPosition: (position: number) => void): {
  readonly page: number;
  readonly target: number;
  onScroll(scrollLeft: number, pageWidth: number): number;
  requestDelta(delta: number): number;
  settle(scrollLeft: number, pageWidth: number): number;
  align(pageWidth: number): number;
};
export function createPagerFrameGate(request: (callback: () => void) => number, cancel: (frame: number) => void, task: () => void): { schedule(): void; cancel(): void };
