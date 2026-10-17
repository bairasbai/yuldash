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

/**
 * Уход шторки. `close` просит её уйти (повторное нажатие во время ухода ничего не делает), а
 * `onClose` вызывается ровно один раз — когда шторка доехала вниз: по концу её `sheetDown` или,
 * если это событие не пришло (вкладка в фоне, стиль не применился), по длительности самого движения.
 * При «Уменьшить движение» `onClose` — сразу.
 *
 * `backdropClass` — на .sheet-backdrop, `onAnimationEnd` — на саму .sheet (её же держит `sheetRef`).
 */
export function useSheetExit(onClose: () => void, sheetRef: RefObject<HTMLElement>) {
  const [leaving, setLeaving] = useState(false);
  const closed = useRef(false);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  function finish() {
    if (closed.current) return;
    closed.current = true;
    onCloseRef.current();
  }

  function close() {
    if (leaving || closed.current) return;
    if (prefersReducedMotion()) finish();
    else setLeaving(true);
  }

  useEffect(() => {
    if (!leaving) return;
    const el = sheetRef.current;
    const timer = window.setTimeout(finish, (el ? cssTimeMs(window.getComputedStyle(el).animationDuration) : 0) + LEAVE_GRACE_MS);
    return () => window.clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [leaving]);

  return {
    leaving,
    close,
    backdropClass: "sheet-backdrop" + (leaving ? " is-leaving" : ""),
    /** Своё движение «вниз», а не всплывшее от строки или кнопки внутри шторки. */
    onAnimationEnd(e: AnimationEvent<HTMLElement>) {
      if (leaving && e.target === e.currentTarget && e.animationName === "sheetDown") finish();
    },
  };
}
