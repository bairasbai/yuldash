// ================================================================
//  «Машину видят пассажиры этого рейса» — честная строка, пока сайт шлёт точку водителя на весь рейс
//  (Д2, utils/rideLocationShare.ts). Кабинет показывает её вверху (видна без прокрутки) и под «Едут с тобой».
//  Нет отправки рейса — ничего не рисует.
// ================================================================
import { useEffect, useState } from "react";
import { useLang } from "../i18n/lang";
import { subscribeRideShare } from "../utils/rideShareStore";

export default function RideShareNote() {
  const { appText } = useLang();
  const [on, setOn] = useState(false);

  useEffect(() => {
    const unsubscribe = subscribeRideShare((s) => setOn(s.ride && s.bookingId !== null));
    return () => {
      unsubscribe?.();
    };
  }, []);

  if (!on) return null;
  return (
    <div className="trip-geo trip-geo--live" role="status">
      <p className="trip-geo__line">
        <span className="trip-geo__dot" aria-hidden />
        <span>
          {appText(
            "Машину видят пассажиры этого рейса — пока сайт открыт. Перестанем показывать, когда завершишь последнего.",
            "Машинаңды был рейстың пассажирҙары күрә — сайт асыҡ саҡта. Һуңғыһын тамамлағас, күрһәтмәйбеҙ."
          )}
        </span>
      </p>
    </div>
  );
}
