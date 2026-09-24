export type IntroStage = {
  meaningPhase: "before" | "in" | "out";
  brand: boolean;
  sheen: boolean;
  underline: boolean;
  slogan: boolean;
  sloganBa: boolean;
  exiting: boolean;
  skip: boolean;
};
export const INTRO_LOGO_SETTLE_MS: number;
export const INTRO_MEANING_MS: number;
export function introInitialStage(reduceMotion: boolean): IntroStage;
export function createIntroTimeline(
  reduceMotion: boolean,
  onStage: (patch: Partial<IntroStage>) => void,
  onComplete: () => void,
  setTimer?: (callback: () => void, ms: number) => ReturnType<typeof setTimeout>,
  clearTimer?: (timer: ReturnType<typeof setTimeout>) => void,
): { skip: () => void; dispose: () => void };
