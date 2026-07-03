"use client";

import { useEffect, useState, type RefObject } from "react";
import { useInView, type UseInViewOptions } from "framer-motion";

// useInView + подстраховка таймером.
// IntersectionObserver на iOS Safari (а также при поздней гидрации JS или
// быстром скролле) иногда НЕ срабатывает. Тогда контент, завязанный на
// «появление во вьюпорте» (цифры-счётчики, печатающаяся цитата), остаётся
// скрытым — текст не виден, числа висят на 0. Ровно этот баг уже ловили
// на секциях и увели в чистый CSS (см. Reveal.tsx).
//
// Здесь: считаем элемент показанным, как только он попал во вьюпорт ЛИБО
// прошло fallbackMs. Контент не прячем никогда; в худшем случае теряется
// только сама анимация въезда, но значение/текст остаются на месте.
export function useRevealOnce(
  ref: RefObject<Element>,
  { margin = "-40px", fallbackMs = 1600 }: { margin?: string; fallbackMs?: number } = {},
): boolean {
  const inView = useInView(ref, { once: true, margin: margin as UseInViewOptions["margin"] });
  const [shown, setShown] = useState(false);

  useEffect(() => {
    if (shown) return;
    if (inView) {
      setShown(true);
      return;
    }
    // Наблюдатель молчит — показываем по таймеру (защита от iOS-глюка).
    const t = setTimeout(() => setShown(true), fallbackMs);
    return () => clearTimeout(t);
  }, [inView, shown, fallbackMs]);

  return shown;
}
