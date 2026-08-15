// ================================================================
//  Повтор, который идёт только пока экран открыт и виден.
//
//  Обычный setInterval на телефоне продолжает тикать, когда человек ушёл
//  в другое приложение: жжёт батарею и трафик впустую, а данные всё равно
//  устареют к возвращению. Приложение так не делает — там цикл живёт только
//  в состоянии RESUMED (`repeatOnLifecycle` в `MapScreen.kt`).
//
//  Здесь то же самое: пока вкладка скрыта — молчим; вернулись — сразу
//  обновляем один раз и снова заводим повтор.
// ================================================================
import { useEffect, useRef } from "react";

export function useVisibleInterval(
  ms: number,
  fn: () => void,
  /** Выключить повтор совсем (экран не в том состоянии). */
  enabled = true
): void {
  const fnRef = useRef(fn);
  fnRef.current = fn;

  useEffect(() => {
    if (!enabled || ms <= 0) return;

    let timer: number | null = null;

    const stop = () => {
      if (timer !== null) {
        window.clearInterval(timer);
        timer = null;
      }
    };
    const start = () => {
      stop();
      timer = window.setInterval(() => fnRef.current(), ms);
    };

    const onVisibility = () => {
      if (document.visibilityState === "visible") {
        // Вернулись — сначала свежие данные, потом обычный ритм.
        fnRef.current();
        start();
      } else {
        stop();
      }
    };

    if (document.visibilityState === "visible") start();
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      stop();
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [ms, enabled]);
}
