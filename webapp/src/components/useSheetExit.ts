// ================================================================
//  Уход нижней шторки (.sheet-backdrop / .sheet): шторка уезжает вниз, затемнение гаснет —
//  пара к появлению (sheetUp / fadeIn в ui.css), и только потом шторку убирают со страницы.
//  Раньше шторки пропадали в тот же кадр — «самоделка» (п.3 ⭐ «Готово» в AGENTS.md, приёмки PR151).
//
//  Шторка, которая пользуется уходом, — отдельный компонент: он живёт, пока шторка открыта,
//  поэтому каждое открытие начинается с чистого состояния. Хук — только на useState/useRef/useEffect:
//  тесты бронирования подменяют React ровно этими тремя.
// ================================================================
import { useEffect, useRef, useState, type AnimationEvent, type RefObject } from "react";

/** Запас к длительности ухода, после которого шторка закрывается и без события о конце движения. */
const LEAVE_GRACE_MS = 80;

/** Человек просил «Уменьшить движение» — анимаций нет (index.css), и ждать конца ухода нечего. */
export function prefersReducedMotion(): boolean {
  return typeof window !== "undefined" && !!window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
}

/** Длительность из getComputedStyle («0.26s», «260ms», через запятую) → миллисекунды, самая долгая. */
export function cssTimeMs(value: string): number {
  return Math.max(0, ...value.split(",").map((v) => (v.trim().endsWith("ms") ? parseFloat(v) : parseFloat(v) * 1000) || 0));
}

/** Идущее сейчас CSS-движение элемента с этим именем (появление шторки или затемнения). */
function runningAnimation(el: Element | null | undefined, name: string): Animation | null {
  const list = el?.getAnimations?.() ?? [];
  return list.find((a) => (a as CSSAnimation).animationName === name && a.playState === "running") ?? null;
}

/**
 * Уход шторки. `close` просит её уйти (повторное нажатие во время ухода ничего не делает), а
 * `onClose` вызывается ровно один раз — когда шторка ушла: по концу её `sheetDown` или, если это
 * событие не пришло (вкладка в фоне, стиль не применился), по длительности самого движения.
 *
 * - Уходящая шторка сразу становится `inert`: в ней ничего не нажать — ни пальцем, ни Enter с
 *   клавиатуры или читалки — и по Tab в неё не попасть (приёмка PR154: Esc, а за ним Enter на
 *   «Забронировать» отправлял бронь из исчезающей шторки).
 * - Закрыли, пока шторка ещё поднимается, — её же появление разворачивается с того места, где она
 *   сейчас: без рывка «вверх до конца — и вниз» (приёмка PR154).
 * - «Уменьшить движение» — `onClose` сразу.
 *
 * `backdropClass` — на .sheet-backdrop, `onAnimationEnd` — на саму .sheet (её же держит `sheetRef`).
 */
export function useSheetExit(onClose: () => void, sheetRef: RefObject<HTMLElement>) {
  /** «down» — уезжает вниз (sheetDown); «rewind» — разворачивает недоигранное появление. */
  const [mode, setMode] = useState<"down" | "rewind" | null>(null);
  const started = useRef(false);
  const closed = useRef(false);
  /** Шторка ещё на странице. После размонтирования `onClose` не зовётся ни по какому пути (см. ниже). */
  const mounted = useRef(true);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  function finish() {
    if (closed.current || !mounted.current) return;
    closed.current = true;
    onCloseRef.current();
  }

  function close() {
    if (started.current || closed.current) return;
    started.current = true;
    const sheet = sheetRef.current;
    if (sheet) sheet.inert = true;
    // inert уводит фокус только к следующему кадру, а Enter или пробел, нажатые следом за Esc, приходят
    // раньше — и доставались кнопке или галочке в уходящей шторке. Снимаем фокус сразу; когда шторка
    // уйдёт, он вернётся туда, откуда её открыли.
    const focused = document.activeElement;
    if (sheet && focused instanceof HTMLElement && sheet.contains(focused)) focused.blur();
    if (prefersReducedMotion()) {
      finish();
      return;
    }
    const rising = runningAnimation(sheet, "sheetUp");
    if (!rising) {
      setMode("down");
      return;
    }
    const elapsed = Number(rising.currentTime) || 0;
    if (rising.pending || elapsed <= 0) {
      // Подъём ещё не начался — шторки на экране нет. Разворот с нулевой точки браузер начинает с конца
      // появления: шторка мелькнула бы во весь рост (второй проход приёмки PR154). Убираем сразу.
      finish();
      return;
    }
    // Ещё поднимается: обратный ход того же движения — столько же, сколько она уже ехала вверх.
    const backdrop = sheet?.parentElement;
    const dimming = runningAnimation(backdrop, "fadeIn");
    rising.reverse();
    if (dimming) dimming.reverse();
    else backdrop?.animate?.([{ backgroundColor: "transparent" }], { duration: elapsed, easing: "ease", fill: "forwards" });
    rising.finished.then(finish, finish);
    setMode("rewind");
  }

  useEffect(() => {
    if (!mode) return;
    const el = sheetRef.current;
    const timer = window.setTimeout(finish, (el ? cssTimeMs(window.getComputedStyle(el).animationDuration) : 0) + LEAVE_GRACE_MS);
    return () => window.clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode]);

  // Одно правило: убрали шторку со страницы (экран закрыли, нажали «Назад») — `onClose` больше НЕ зовётся
  // ни по какому пути: ни по таймеру выше, ни по концу движения, ни по `rising.finished` в развороте
  // (отменённое движение отвечает как раз при размонтировании). `onClose` может уводить на другой экран
  // (RideDetailScreen: navigate("/map")), и вызов после размонтирования перехватывал бы «Назад»: человек
  // нажал «Назад» в первые ~260 мс ухода и оказывался на /map вместо предыдущей страницы (приёмка PR166).
  // Родитель, который убрал шторку сам, знает, что её нет. `mounted` возвращается в true при повторном
  // запуске эффекта (StrictMode в разработке сразу после монтирования гоняет очистку и эффект ещё раз).
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  return {
    /** Шторка уходит: в ней уже ничего не нажимается (в том числе бронь). */
    leaving: mode !== null,
    close,
    backdropClass: "sheet-backdrop" + (mode === "down" ? " is-leaving" : ""),
    /** Своё движение «вниз», а не всплывшее от строки или кнопки внутри шторки. */
    onAnimationEnd(e: AnimationEvent<HTMLElement>) {
      if (mode === "down" && e.target === e.currentTarget && e.animationName === "sheetDown") finish();
    },
  };
}
