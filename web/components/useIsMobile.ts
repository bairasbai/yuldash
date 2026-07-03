"use client";

import { useEffect, useState } from "react";

/**
 * Телефон/узкий экран (< lg = 1024px)? SSR-безопасно.
 *
 * Возвращает `false` на сервере И на первом клиентском рендере — поэтому
 * разметка гидрации совпадает (нет hydration mismatch). Уже после mount
 * (useEffect) переключается на реальное значение. Это даёт нам «тяжёлый»
 * десктоп по умолчанию и «лёгкий» мобильный вариант сразу после гидрации —
 * ровно там, где нужно резать анимации ради плавности на телефоне.
 */
export function useIsMobile(query = "(max-width: 1023px)"): boolean {
  const [isMobile, setIsMobile] = useState(false);

  useEffect(() => {
    const mql = window.matchMedia(query);
    const update = () => setIsMobile(mql.matches);
    update();
    mql.addEventListener("change", update);
    return () => mql.removeEventListener("change", update);
  }, [query]);

  return isMobile;
}
