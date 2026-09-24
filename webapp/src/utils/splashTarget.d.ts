export type SplashStatus = "loading" | "authed" | "guest" | "unavailable";
export function splashTarget(status: SplashStatus, onboarded: boolean): "/intro" | "/map" | "/login" | null;
export function scheduleSplashNavigation(
  status: SplashStatus,
  onboarded: boolean,
  navigate: (path: string, options: { replace: true }) => void,
  setTimer?: (callback: () => void, ms: number) => ReturnType<typeof setTimeout>,
  clearTimer?: (timer: ReturnType<typeof setTimeout>) => void,
): () => void;
