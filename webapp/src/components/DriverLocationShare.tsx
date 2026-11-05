// ================================================================
//  «Твоя точка для пассажира» — водитель на экране поездки (ActiveTripScreen).
//  Логика и правила приватности — utils/tripLocationShare.ts и (весь рейс, Д2) utils/rideLocationShare.ts;
//  проводка к браузеру — utils/driverRideShare.ts; здесь — слова на экране.
//
//  До «Выехал» — одна строка «зачем»: человек заранее знает, почему сайт спросит место.
//  В пути — состояние: спрашиваем / отправляем / нет связи / доступ закрыт / не определяется.
// ================================================================
import { useEffect, useRef, useState } from "react";
import { useLang } from "../i18n/lang";
import type { ShareState } from "../utils/tripLocationShare";
import { rideShareEnable, rideShareOpen, rideShareRetry } from "../utils/driverRideShare";
import { subscribeRideShare } from "../utils/rideShareStore";
import { IconLocate, IconWarn } from "./Icons";
import { getSessionGeneration } from "../api/client";

export default function DriverLocationShare({ bookingId, rideId, inTransit }: { bookingId: number; rideId?: number; inTransit: boolean }) {
  const { appText } = useLang();
  const [state, setState] = useState<ShareState | null>(null);
  const [active, setActive] = useState(false);
  const enabling = useRef<{ id: symbol; pendingSeen: boolean } | null>(null);
  const session = getSessionGeneration();

  useEffect(() => {
    if (!bookingId) {
      setState(null);
      return;
    }
    // Отправка одна на вкладку (driverRideShare.ts, Д2): ведёшь рейс — после «Завершить» по этому пассажиру
    // точка идёт дальше через другую живую бронь рейса, а «Едут с тобой» держит её, пока открыт кабинет.
    const release = rideShareOpen(bookingId);
    const unsubscribe = subscribeRideShare((s) => {
      const own = s.bookingId === bookingId || (rideId !== undefined && s.rideId === rideId);
      setState(own ? s.state : null);
      setActive(own && s.ride);
      // beginClick сначала синхронно шлёт старое null-состояние через stopAll.
      // Оно не завершает только что начатый UI-запрос и не разрешает второй тап.
      if (enabling.current) {
        if (own && s.state === "resolving") enabling.current.pendingSeen = true;
        else if (enabling.current.pendingSeen) enabling.current = null;
      }
    });
    return () => {
      unsubscribe?.();
      release?.();
    };
  }, [bookingId, rideId, session]);

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
  const problem = state === "denied" || state === "unavailable" || state === "unsupported";
  const text =
    !state || state === "ended"
      ? appText(
          "Передача места выключена. Можно включить её на этом устройстве без изменения этапа поездки.",
          "Урынды ебәреү һүндерелгән. Сәфәр этабын үҙгәртмәйенсә, ошо ҡоролмала ҡабыҙырға була."
        )
      : state === "resolving"
        ? appText(
            "Проверяем пассажиров рейса. Передача места пока приостановлена.",
            "Рейс пассажирҙарын тикшерәбеҙ. Урынды ебәреү әлегә туҡтатылған."
          )
      : state === "asking"
      ? appText(
          "Спрашиваем доступ к месту — так пассажир увидит твою машину на карте.",
          "Урыныңа рөхсәт һорайбыҙ — шулай пассажир машинаңды картала күрер."
        )
      : state === "sharing"
        ? appText(
            "Машину видят пассажиры этого рейса, пока открыта поездка или кабинет водителя.",
            "Сәфәр йәки йөрөтөүсе кабинеты асыҡ саҡта, был рейстың пассажирҙары машинаңды күрә."
          )
        : state === "reconnecting"
          ? appText(
              "Нет связи с сервером — точка уйдёт, когда связь вернётся.",
              "Сервер менән бәйләнеш юҡ — бәйләнеш ҡайтҡас, урының китер."
            )
          : state === "denied"
            ? appText(
                "Доступ к месту закрыт. Разреши геолокацию для сайта в настройках браузера, затем нажми «Включить передачу места».",
                "Урынға рөхсәт ябыҡ. Браузер көйләүҙәрендә сайтҡа геолокацияны рөхсәт ит, шунан «Урынды ебәреүҙе ҡабыҙырға» тип баҫ."
              )
            : state === "unavailable"
              ? appText(
                  "Не получилось включить передачу места. Проверь сеть и геолокацию на телефоне и попробуй ещё раз.",
                  "Урынды ебәреүҙе ҡабыҙып булманы. Телефонда селтәрҙе һәм геолокацияны тикшереп, яңынан һынап ҡара."
                )
              : appText(
                  "Этот браузер не умеет передавать место. Пассажир видит «Выехал» и «Подъезжаю».",
                  "Был браузер урынды ебәрә белмәй. Пассажир «Сыҡтым» һәм «Яҡынлашам» генә күрә."
                );

  return (
    <div className={"trip-geo" + (problem ? " trip-geo--warn" : state === "sharing" ? " trip-geo--live" : "")} role="status">
      <p className="trip-geo__line">
        {problem ? <IconWarn size={16} /> : <span className="trip-geo__dot" aria-hidden />}
        <span>{text}</span>
      </p>
      {!active && rideId !== undefined && rideId > 0 && state !== "unsupported" && (
        <button type="button" className="btn-ghost trip-geo__retry" disabled={state === "resolving"} onClick={() => {
          if (enabling.current) return;
          const attempt = { id: Symbol("enable-location"), pendingSeen: false };
          enabling.current = attempt;
          void rideShareEnable(bookingId, rideId).finally(() => { if (enabling.current === attempt) enabling.current = null; });
        }}>
          {appText("Включить передачу места", "Урынды ебәреүҙе ҡабыҙырға")}
        </button>
      )}
      {active && state === "unavailable" && (
        <button type="button" className="btn-ghost trip-geo__retry" onClick={() => rideShareRetry()}>
          {appText("Повторить", "Ҡабатлау")}
        </button>
      )}
    </div>
  );
}
