/** A completed normal onboarding always enters login, including Skip. */
export function finishOnboarding(role, store, track, navigate) {
  store.setRole(role);
  store.setOnboarded();
  track("onboarding_complete", { role });
  navigate("/login", { replace: true });
}

/** Simple mode is a distinct Android branch: it does not commit a role. */
export function enableSimpleOnboarding(store, track, navigate) {
  store.setSimpleMode(true);
  store.setOnboarded();
  track("onboarding_simple_mode");
  navigate("/simple", { replace: true });
}

const clamp = (value, count) => Math.max(0, Math.min(count - 1, value));

export function pagerPosition(scrollLeft, pageWidth, count) {
  return clamp(pageWidth > 0 ? scrollLeft / pageWidth : 0, count);
}

/** Scroll-snap owns gestures; this controller observes position and schedules button targets. */
export function createOnboardingPagerController(count, onPage, onPosition) {
  let page = 0;
  let target = 0;
  let commanding = false;
  return {
    get page() { return page; },
    get target() { return target; },
    onScroll(scrollLeft, pageWidth) {
      const position = pagerPosition(scrollLeft, pageWidth, count);
      onPosition(position);
      const next = clamp(Math.round(position), count);
      if (next !== page) {
        const old = page;
        page = next;
        onPage(next, old);
      }
      if (!commanding) target = next;
      if (Math.abs(position - target) < 0.02) commanding = false;
      return next;
    },
    requestDelta(delta) {
      target = clamp(target + delta, count);
      commanding = true;
      return target;
    },
    settle(scrollLeft, pageWidth) {
      target = clamp(Math.round(pagerPosition(scrollLeft, pageWidth, count)), count);
      commanding = false;
      return target;
    },
    align(pageWidth) { return page * pageWidth; },
  };
}

/** A cancelled animation frame must not block the next scroll observation. */
export function createPagerFrameGate(request, cancel, task) {
  let frame = 0;
  return {
    schedule() {
      if (frame) return;
      frame = request(() => { frame = 0; task(); });
    },
    cancel() {
      if (frame) cancel(frame);
      frame = 0;
    },
  };
}
