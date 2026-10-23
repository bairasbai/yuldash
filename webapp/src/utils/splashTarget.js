/** Вошедшему человеку повторное знакомство не нужно, даже если флаг ещё не записан. */
export function splashTarget(status, onboarded) {
  if (status === "loading" || status === "unavailable") return null;
  if (status === "authed") return "/map";
  if (!onboarded) return "/intro";
  return "/login";
}

/** One replace transition; cancellation covers auth changes and unmount. */
export function scheduleSplashNavigation(status, onboarded, navigate, setTimer = setTimeout, clearTimer = clearTimeout) {
  const target = splashTarget(status, onboarded);
  if (target === null) return () => {};
  let active = true;
  const timer = setTimer(() => {
    if (!active) return;
    active = false;
    navigate(target, { replace: true });
  }, target === "/intro" ? 60 : 140);
  return () => {
    active = false;
    clearTimer(timer);
  };
}
