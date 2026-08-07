// ================================================================
//  Секция «Пуш-уведомления» для Настроек. Честная клиентская подписка
//  Web Push с мягкой деградацией на каждом шаге:
//   • iOS без установки на «Домой» → подсказка «Сначала добавь на экран Домой»;
//   • браузер без Web Push → спокойная заглушка;
//   • нет VAPID-ключа / нет эндпоинта на сервере → «включится после настройки»;
//   • запрет уведомлений → как снять запрет.
//  Логика подписки — src/push/webPush.ts (точка правды). Здесь только UI.
// ================================================================
import { useState } from "react";
import { useLang } from "../i18n/lang";
import {
  enableWebPush,
  disableWebPush,
  isWebPushEnabled,
  isIos,
  isStandalone,
  pushSupported,
  permission,
  vapidKey,
  type EnableResult,
} from "../push/webPush";

type Msg = { kind: "ok" | "info" | "warn"; text: string } | null;
type Busy = "idle" | "enabling" | "disabling";

export default function PushToggle() {
  const { appText } = useLang();

  const [enabled, setEnabled] = useState(() => isWebPushEnabled());
  const [busy, setBusy] = useState<Busy>("idle");
  const [msg, setMsg] = useState<Msg>(null);

  const iosNeedsInstall = isIos() && !isStandalone();
  const supported = pushSupported();
  const denied = supported && permission() === "denied";
  const noKey = !vapidKey();

  function explain(r: EnableResult): Msg {
    switch (r) {
      case "ok":
        return { kind: "ok", text: appText("Пуши включены на этом устройстве", "Был ҡорамалда пуштар ҡабыҙылды") };
      case "ok-pending-server":
        return {
          kind: "info",
          text: appText(
            "Подписка создана. Пуши заработают, когда мы настроим отправку на сервере.",
            "Яҙылыу булдырылды. Сервер яғында көйләгәс, пуштар эшләй башлай."
          ),
        };
      case "need-standalone":
        return {
          kind: "warn",
          text: appText(
            "На iPhone сначала добавь Юлдаш на экран «Домой» (Поделиться → «На экран Домой»), потом включи пуши.",
            "iPhone-да тәүҙә Юлдашты «Өй» экранына өҫтә (Уртаҡлашырға → «Өй экранына»), һуңынан пуштарҙы ҡабыҙ."
          ),
        };
      case "no-key":
        return {
          kind: "info",
          text: appText(
            "Пуши включатся после настройки ключей на сервере.",
            "Сервер яғында асҡыстар көйләнгәс, пуштар эшләй башлай."
          ),
        };
      case "denied":
        return {
          kind: "warn",
          text: appText(
            "Уведомления запрещены. Разреши их для Юлдаша в настройках браузера или телефона.",
            "Хәбәрҙәр тыйылған. Юлдаш өсөн уларҙы браузер йәки телефон көйләүҙәрендә рөхсәт ит."
          ),
        };
      default:
        return {
          kind: "warn",
          text: appText(
            "Не получилось включить пуши. Проверь связь и попробуй ещё раз.",
            "Пуштарҙы ҡабыҙып булманы. Бәйләнеште тикшереп, ҡабат ҡара."
          ),
        };
    }
  }

  async function onEnable() {
    setBusy("enabling");
    setMsg(null);
    const r = await enableWebPush();
    setEnabled(r === "ok" || r === "ok-pending-server");
    setMsg(explain(r));
    setBusy("idle");
  }

  async function onDisable() {
    setBusy("disabling");
    await disableWebPush();
    setEnabled(false);
    setMsg({ kind: "info", text: appText("Пуши выключены на этом устройстве", "Был ҡорамалда пуштар һүндерелде") });
    setBusy("idle");
  }

  return (
    <div className="push-block">
      <div className="push-block__head">
        <div className="push-block__main">
          <div className="push-block__title">{appText("Пуш-уведомления", "Пуш-хәбәрҙәр")}</div>
          <div className="push-block__sub">
            {enabled
              ? appText("Включены на этом устройстве", "Был ҡорамалда ҡабыҙылған")
              : appText("Отклики и сообщения даже когда приложение закрыто", "Ябыҡ саҡта ла яуап һәм хәбәрҙәр")}
          </div>
        </div>
        <span className={"push-dot" + (enabled ? " push-dot--on" : "")} aria-hidden />
      </div>

      {/* iOS в обычном табе Safari — сперва установка на «Домой» */}
      {iosNeedsInstall && (
        <p className="push-block__hint">
          {appText(
            "Сначала добавь Юлдаш на экран «Домой» — тогда пуши станут доступны.",
            "Тәүҙә Юлдашты «Өй» экранына өҫтә — шунда пуштар асыла."
          )}
        </p>
      )}

      {/* Браузер без Web Push (и это не iOS-случай выше) */}
      {!supported && !iosNeedsInstall && (
        <p className="push-block__hint">
          {appText(
            "Этот браузер пока не поддерживает пуши. Открой Юлдаш как установленное приложение.",
            "Был браузер пуштарҙы әлегә ҡулламай. Юлдашты ҡуйылған ҡушымта итеп ас."
          )}
        </p>
      )}

      {/* Кнопки: включить / выключить — когда среда позволяет */}
      {supported && !iosNeedsInstall && (
        <div className="push-block__actions">
          {!enabled ? (
            <button
              type="button"
              className="btn-primary"
              style={{ marginTop: 0 }}
              disabled={busy !== "idle" || denied}
              onClick={onEnable}
            >
              {busy === "enabling"
                ? appText("Включаем…", "Ҡабыҙабыҙ…")
                : appText("Включить пуши", "Пуштарҙы ҡабыҙырға")}
            </button>
          ) : (
            <button
              type="button"
              className="btn-soft"
              disabled={busy !== "idle"}
              onClick={onDisable}
            >
              {busy === "disabling"
                ? appText("Выключаем…", "Һүндерәбеҙ…")
                : appText("Выключить пуши", "Пуштарҙы һүндерергә")}
            </button>
          )}
          {noKey && !enabled && (
            <span className="push-block__note">
              {appText("Скоро", "Тиҙҙән")}
            </span>
          )}
        </div>
      )}

      {msg && <p className={"push-block__msg push-block__msg--" + msg.kind}>{msg.text}</p>}
    </div>
  );
}
