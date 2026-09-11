import { useLang } from "../i18n/lang";
import { Link } from "react-router-dom";
import type { Ride } from "../api/rides";
import { IconArrow, IconCheck } from "./Icons";
import { serverDate } from "../utils/serverTime";
import { YuStar, YuWomenOnly } from "./BrandIcons";

import { dayMonthShort, hhmm, pluralRu } from "../utils/format";
function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  return (parts[0][0] + (parts[1]?.[0] ?? "")).toUpperCase();
}

function formatWhen(iso: string, ru: boolean): string {
  const d = serverDate(iso);
  if (!d) return "";
  const now = new Date();
  const sameDay = (a: Date, b: Date) =>
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate();
  const tomorrow = new Date(now);
  tomorrow.setDate(now.getDate() + 1);

  const time = hhmm(d);
  if (sameDay(d, now)) return `${ru ? "Сегодня" : "Бөгөн"} ${time}`;
  if (sameDay(d, tomorrow)) return `${ru ? "Завтра" : "Иртәгә"} ${time}`;
  return `${dayMonthShort(d, ru)} ${time}`;
}

export default function RideCard({ ride, index, to }: { ride: Ride; index: number; to?: string }) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";

  const priceLabel =
    ride.price > 0
      ? `${ride.price.toLocaleString("ru-RU")} ₽`
      : appText("Бесплатно", "Түләүһеҙ");

  const card = (
    <article
      className="ride-card"
      style={{ animationDelay: `${Math.min(index, 8) * 40}ms` }}
    >
      <div className="ride-card__route">
        <span>{ride.from_city}</span>
        <span className="ride-card__arrow">
          <IconArrow size={20} />
        </span>
        <span>{ride.to_city}</span>
      </div>

      <div className="ride-card__meta">
        <span>{formatWhen(ride.depart_at, ru)}</span>
        <span>
          <b>{ride.seats_left}</b> {appText(pluralRu(ride.seats_left, "место", "места", "мест"), "урын")}
        </span>
        {ride.women_only && (
          <span className="badge badge--woman">
            <YuWomenOnly size={12} className="amenity-ic" />
            {appText("Только для женщин", "Тик ҡатын-ҡыҙ өсөн")}
          </span>
        )}
        {/* «За рулём женщина» — не то же самое, что «только для женщин»: первое про то,
            КТО везёт, второе про то, кого берут. Пассажирке важно и то и другое. */}
        {ride.driver_is_woman && !ride.women_only && (
          <span className="badge badge--woman">
            <YuWomenOnly size={12} className="amenity-ic" />
            {appText("За рулём женщина", "Рулдә ҡатын-ҡыҙ")}
          </span>
        )}
        {ride.boosted && (
          <span className="badge badge--boost">
            {appText("Поднято", "Күтәрелгән")}
          </span>
        )}
      </div>

      <div className="ride-card__foot">
        <div className="ride-card__driver">
          {ride.driver_avatar ? (
            <img
              className="ride-card__avatar"
              src={ride.driver_avatar}
              alt={ride.driver_name}
              loading="lazy"
            />
          ) : (
            <span className="ride-card__avatar" aria-hidden>
              {initials(ride.driver_name)}
            </span>
          )}
          <div style={{ minWidth: 0 }}>
            <div className="ride-card__driver-name">
              {ride.driver_name}
              {ride.driver_verified && (
                <span className="badge badge--mint" style={{ marginLeft: 6 }}>
                  <IconCheck size={14} /> {appText("Проверен", "Тикшерелгән")}
                </span>
              )}
            </div>
            <div className="ride-card__driver-sub">
              <YuStar size={12} className="star" /> {ride.driver_rating.toFixed(1)}
              {ride.driver_car ? ` · ${ride.driver_car}` : ""}
            </div>
          </div>
        </div>
        <div className="ride-card__price">{priceLabel}</div>
      </div>
    </article>
  );
  return to ? <Link to={to} style={{ display: "block", color: "inherit", textDecoration: "none" }}>{card}</Link> : card;
}
