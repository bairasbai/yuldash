// ================================================================
//  Память прокрутки списка.
//
//  Человек листает ленту поездок, открывает одну, жмёт «назад» — и лента
//  снова в самом верху. Приходится листать всё заново, и так каждый раз.
//  В приложении список помнит, где ты был; на сайте этого не было —
//  переходы внутри одной страницы браузер сам не восстанавливает.
//
//  Помним в рамках вкладки (sessionStorage): вернулся через минуту — то же
//  место, открыл сайт завтра — начинаем сверху, это уже другой заход.
// ================================================================
import { useEffect, useRef } from "react";

const PREFIX = "yuldash.scroll.";

/**
 * @param key   свой на каждый список («rides», «requests»)
 * @param ready список отрисован — до этого прокручивать некуда
 */
export function useScrollMemory(key: string, ready: boolean): void {
  const restored = useRef(false);

  // Запоминаем на каждой прокрутке: уход со страницы бывает и без размонтирования
  // (свернули вкладку, телефон выгрузил её из памяти).
  useEffect(() => {
    const save = () => {
      try {
        sessionStorage.setItem(PREFIX + key, String(Math.round(window.scrollY)));
      } catch {
        /* приватный режим — просто не помним */
      }
    };
    window.addEventListener("scroll", save, { passive: true });
    return () => {
      save();
      window.removeEventListener("scroll", save);
    };
  }, [key]);

  useEffect(() => {
    if (!ready || restored.current) return;
    restored.current = true;
    let y = 0;
    try {
      y = Number(sessionStorage.getItem(PREFIX + key) || 0);
    } catch {
      return;
    }
    if (!y) return;
    // Ждём кадр: список только что отрисован, высота страницы ещё не устоялась.
    requestAnimationFrame(() => window.scrollTo({ top: y, behavior: "auto" }));
  }, [key, ready]);
}
