/** Android release YuldashApp.splashTarget: onboarding wins over authentication. */
export function splashTarget(status, onboarded) {
  if (status === "loading" || status === "unavailable") return null;
  if (!onboarded) return "/intro";
  return status === "authed" ? "/map" : "/login";
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
