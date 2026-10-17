// ================================================================
//  «Твоя точка для пассажира» — водитель на экране поездки (ActiveTripScreen).
//  Логика и правила приватности — utils/tripLocationShare.ts; здесь только проводка
//  к настоящему браузеру (navigator.geolocation, WebSocket) и слова на экране.
//
//  До «Выехал» — одна строка «зачем»: человек заранее знает, почему сайт спросит место.
//  В пути — состояние: спрашиваем / отправляем / нет связи / доступ закрыт / не определяется.
// ================================================================
import { useEffect, useRef, useState } from "react";
import { useLang } from "../i18n/lang";
import { getSessionGeneration, getToken } from "../api/client";
import { wsBase } from "../api/chat";
import {
  startDriverLocationShare,
  tripLocationUrl,
  type ShareHandle,
  type ShareState,
  type SocketLike,
} from "../utils/tripLocationShare";
import { IconLocate, IconWarn } from "./Icons";

export default function DriverLocationShare({ bookingId, inTransit }: { bookingId: number; inTransit: boolean }) {
  const { appText } = useLang();
  const [state, setState] = useState<ShareState | null>(null);
  const handle = useRef<ShareHandle | null>(null);

  useEffect(() => {
    if (!bookingId || !inTransit) {
      setState(null);
      return;
    }
    const generation = getSessionGeneration();
    const geo = typeof navigator !== "undefined" && navigator.geolocation ? navigator.geolocation : null;
    const h = startDriverLocationShare({
      geolocation: geo,
      // Настоящий WebSocket шире SocketLike (у его обработчиков this и Event) — логике хватает подмножества.
      openSocket: () => new WebSocket(tripLocationUrl(wsBase(), bookingId)) as unknown as SocketLike,
      token: getToken,
      now: Date.now,
      setTimer: (fn, ms) => window.setTimeout(fn, ms),
      clearTimer: (t) => window.clearTimeout(t as number),
      onState: setState,
      isCurrent: () => generation === getSessionGeneration(),
    });
    handle.current = h;
    return () => {
      h.stop();
      if (handle.current === h) handle.current = null;
    };
  }, [bookingId, inTransit]);

  // До выезда: просить место рано, но сказать «зачем» — самое время.
  if (!inTransit) {
    return (
      <p className="trip-geo trip-geo--idle">
        <IconLocate size={16} />
        <span>
          {appText(
            "Нажмёшь «Выехал» — сайт спросит доступ к месту, чтобы пассажир видел твою машину на карте.",
            "«Сыҡтым» тип баҫһаң — сайт урыныңа рөхсәт һорар, пассажир машинаңды картала күрһен өсөн."
          )}
        </span>
      </p>
    );
  }
  if (!state || state === "ended") return null;

  const problem = state === "denied" || state === "unavailable" || state === "unsupported";
  const text =
    state === "asking"
      ? appText(
          "Спрашиваем доступ к месту — так пассажир увидит твою машину на карте.",
          "Урыныңа рөхсәт һорайбыҙ — шулай пассажир машинаңды картала күрер."
        )
      : state === "sharing"
        ? appText(
            "Отправляем твою точку пассажиру — только пока эта страница открыта.",
            "Урыныңды пассажирға ебәрәбеҙ — был бит асыҡ саҡта ғына."
          )
        : state === "reconnecting"
          ? appText(
              "Нет связи с сервером — точка уйдёт, когда связь вернётся.",
              "Сервер менән бәйләнеш юҡ — бәйләнеш ҡайтҡас, урының китер."
            )
          : state === "denied"
            ? appText(
                "Доступ к месту закрыт — пассажир видит только «Выехал» и «Подъезжаю», без машины на карте. Разреши геолокацию для сайта в настройках браузера и нажми «Повторить».",
                "Урыныңа рөхсәт ябыҡ — пассажир «Сыҡтым» һәм «Килеп етәм» генә күрә, картала машинаһыҙ. Браузер көйләүҙәрендә сайтҡа геолокацияны рөхсәт ит һәм «Ҡабатлау» тип баҫ."
              )
            : state === "unavailable"
              ? appText(
                  "Не получается определить место. Проверь, что геолокация на телефоне включена, и нажми «Повторить».",
                  "Урыныңды билдәләп булмай. Телефонда геолокация ҡабыҙылғанын тикшер һәм «Ҡабатлау» тип баҫ."
                )
              : appText(
                  "Этот браузер не умеет передавать место. Пассажир видит «Выехал» и «Подъезжаю».",
                  "Был браузер урынды ебәрә белмәй. Пассажир «Сыҡтым» һәм «Килеп етәм» генә күрә."
                );

  return (
    <div className={"trip-geo" + (problem ? " trip-geo--warn" : state === "sharing" ? " trip-geo--live" : "")} role="status">
      <p className="trip-geo__line">
        {problem ? <IconWarn size={16} /> : <span className="trip-geo__dot" aria-hidden />}
        <span>{text}</span>
      </p>
      {(state === "denied" || state === "unavailable") && (
        <button type="button" className="btn-ghost trip-geo__retry" onClick={() => handle.current?.retry()}>
          {appText("Повторить", "Ҡабатлау")}
        </button>
      )}
    </div>
  );
}
