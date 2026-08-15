// ================================================================
//  🔄 «Вышла новая версия» — зеркало Android UpdateBanner.
//
//  У сайта, добавленного на экран «Домой», нет магазина приложений:
//  человек может месяцами открывать вкладку, которая давно устарела.
//  Service worker скачивает новую версию сам, но применяет её только
//  после перезагрузки — до этого исправленный баг у человека всё ещё есть.
//
//  Поэтому баннер: обновление СКАЧАНО, осталось нажать. «Позже» уважаем —
//  посреди заказа такси перезагружать страницу нельзя, и решает человек.
// ================================================================
import { useEffect, useState } from "react";
import { registerSW } from "virtual:pwa-register";
import { useLang } from "../i18n/lang";
import { IconCheck } from "./Icons";

export default function UpdateBanner() {
  const { appText } = useLang();
  const [ready, setReady] = useState(false);
  const [dismissed, setDismissed] = useState(false);
  const [apply, setApply] = useState<(() => void) | null>(null);

  useEffect(() => {
    // registerSW идемпотентен: повторный вызов подхватывает уже
    // зарегистрированный worker и просто вешает обработчики.
    const update = registerSW({
      immediate: true,
      onNeedRefresh() {
        setReady(true);
      },
    });
    setApply(() => () => void update(true));
  }, []);

  if (!ready || dismissed) return null;

  return (
    <div className="update-banner" role="status">
      <span className="update-banner__ic" aria-hidden>
        <IconCheck size={18} />
      </span>
      <span className="update-banner__text">
        <b>{appText("Вышла новая версия", "Яңы версия сыҡты")}</b>
        <span>
          {appText(
            "Обновление уже скачано — нажми, чтобы применить.",
            "Яңыртыу инде йөкләнде — ҡулланыр өсөн баҫ."
          )}
        </span>
      </span>
      <span className="update-banner__actions">
        <button type="button" className="update-banner__later" onClick={() => setDismissed(true)}>
          {appText("Позже", "Һуңыраҡ")}
        </button>
        <button type="button" className="update-banner__go" onClick={() => apply?.()}>
          {appText("Обновить", "Яңыртыу")}
        </button>
      </span>
    </div>
  );
}
