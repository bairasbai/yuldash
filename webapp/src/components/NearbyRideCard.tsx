// ================================================================
//  Ряд «Ближайшие поездки» на карте — зеркало NearbyRideCard / NearbySkeletonCard /
//  NearbyMoreCard / NearbyEmptyCard (RidesRequestsChatScreens.kt): карточки 286×152 в
//  горизонтальной ленте, «Показать ещё» карточкой в конце, пустое состояние карточкой.
// ================================================================
import type { ReactNode } from "react";
import { useLang } from "../i18n/lang";
import type { NearRide } from "../api/discovery";
import { SmallAvatar } from "./adminUi";
import InviteDriverCallout from "./InviteDriverCallout";
import { IconBolt, IconBox, IconCar, IconClock, IconHospital, IconIdCard, IconShield, IconStar } from "./Icons";
import { YuRoute, YuWomenOnly } from "./BrandIcons";
import { pluralRu } from "../utils/format";
import { serverDate } from "../utils/serverTime";

/** «13.09, 08:30» — formatDepart из приложения: местное время человека. */
export function formatDepart(iso: string | null | undefined): string {
  const d = serverDate(iso);
  if (!d) return "—";
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getDate())}.${p(d.getMonth() + 1)}, ${p(d.getHours())}:${p(d.getMinutes())}`;
}

const MONTHS_RU = ["января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"];
const MONTHS_BA = ["ғинуар", "февраль", "март", "апрель", "май", "июнь", "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь"];

/** «12 поездок · с марта 2025» (CompactTrustLine). Пусто — строки нет. */
function trustLine(trips: number | undefined, since: string | undefined, ru: boolean): string {
  const parts: string[] = [];
  const n = trips ?? 0;
  if (n > 0) parts.push(ru ? `${n} ${pluralRu(n, "поездка", "поездки", "поездок")}` : `${n} сәфәр`);
  const m = /^(\d{4})-(\d{1,2})/.exec(since ?? "");
  if (m) {
    const mi = Number(m[2]) - 1;
    if (mi >= 0 && mi < 12) parts.push(ru ? `с ${MONTHS_RU[mi]} ${m[1]}` : `${MONTHS_BA[mi]} ${m[1]}-нан`);
  }
  return parts.join(" · ");
}

/** rideTypeMeta: иконка и подпись категории поездки (кроме обычной). */
function categoryMeta(category: string, appText: (ru: string, ba: string) => string): { icon: ReactNode; label: string } | null {
  switch (category) {
    case "parcel":
      return { icon: <IconBox size={12} />, label: appText("Посылка", "Посылка") };
    case "cargo":
      return { icon: <IconCar size={12} />, label: appText("Груз", "Йөк") };
    case "urgent":
      return { icon: <IconBolt size={12} />, label: appText("Срочно", "Ашығыс") };
    case "hospital":
      return { icon: <IconHospital size={12} />, label: appText("В больницу", "Дауаханаға") };
    default:
      return null;
  }
}

export default function NearbyRideCard({ ride, soonest, onOpen }: { ride: NearRide; soonest: boolean; onOpen: () => void }) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const cat = ride.category && ride.category !== "regular" ? categoryMeta(ride.category, appText) : null;
  const trust = trustLine(ride.driver_trips, ride.driver_since, ru);
  const km = ride.distance_km;
  return (
    <article className="nrc" onClick={onOpen} role="button" tabIndex={0} onKeyDown={(e) => (e.key === "Enter" || e.key === " ") && onOpen()}>
      <div className="nrc__route">
        <strong>
          {ride.from_city} → {ride.to_city}
        </strong>
        {cat && (
          <span className="nrc__cat">
            {cat.icon}
            {cat.label}
          </span>
        )}
        {ride.driver_verified && (
          <span className="nrc__verified" role="img" aria-label={appText("Проверен", "Тикшерелгән")}>
            <IconShield size={18} />
          </span>
        )}
      </div>
      <div className="nrc__when">
        <IconClock size={15} />
        <span>{formatDepart(ride.depart_at)}</span>
        {soonest && <em>{appText("ближайшая", "иң яҡыны")}</em>}
      </div>
      <div className="nrc__driver">
        <SmallAvatar src={ride.driver_avatar} name={ride.driver_name} size={30} />
        <span className="nrc__name">{ride.driver_name.trim() || appText("Водитель", "Йөрөтөүсе")}</span>
        {ride.driver_online && (
          <span className="nrc__online">
            <i aria-hidden />
            {appText("на линии", "эштә")}
          </span>
        )}
        {ride.driver_is_woman && (
          <span className="nrc__woman">
            <YuWomenOnly size={13} />
            {appText("За рулём женщина", "Рулдә ҡатын-ҡыҙ")}
          </span>
        )}
        <span className="nrc__rating">
          <IconStar size={15} />
          {ride.driver_rating.toFixed(1)}
        </span>
      </div>
      <div className="nrc__trust">
        {trust && (
          <>
            <IconIdCard size={13} />
            <span>{trust}</span>
          </>
        )}
      </div>
      <div className="nrc__foot">
        {km != null && (
          <span className="nrc__dist">
            <YuRoute size={13} />
            {km < 1 ? appText("рядом", "янда") : appText(`${km.toFixed(1).replace(".", ",")} км`, `${km.toFixed(1).replace(".", ",")} км`)}
          </span>
        )}
        <b className="nrc__price">{ride.price} ₽</b>
        <button
          type="button"
          className="nrc__go"
          onClick={(e) => {
            e.stopPropagation();
            onOpen();
          }}
        >
          {appText("Поехать", "Барырға")}
        </button>
      </div>
    </article>
  );
}

/** NearbySkeletonCard: та же карточка 286×152 с четырьмя плашками. */
export function NearbySkeletonCard() {
  return (
    <div className="nrc nrc--skeleton" aria-hidden>
      <span className="skeleton" style={{ width: "70%", height: 16 }} />
      <span className="skeleton" style={{ width: "40%", height: 13 }} />
      <span className="skeleton" style={{ width: "55%", height: 13 }} />
      <span className="nrc__spacer" />
      <span className="skeleton nrc__skel-btn" style={{ width: "50%", height: 34 }} />
    </div>
  );
}

/** NearbyMoreCard: «Показать ещё» карточкой 132×152 в конце ленты. */
export function NearbyMoreCard({ loading, onMore }: { loading: boolean; onMore: () => void }) {
  const { appText } = useLang();
  return (
    <button type="button" className="nrc-more" onClick={onMore} disabled={loading}>
      {loading ? appText("Загрузка…", "Йөкләнә…") : appText("Показать\nещё", "Тағы\nкүрһәтеү")}
    </button>
  );
}

/** NearbyEmptyCard: нет поездок / не удалось загрузить; на настоящей пустоте — «Позови водителя». */
export function NearbyEmptyCard({ error, onRetry }: { error: boolean; onRetry: () => void }) {
  const { appText } = useLang();
  return (
    <div className="nrc-empty">
      <span className="nrc-empty__icon" aria-hidden>{error ? <IconClock size={34} /> : <IconCar size={34} />}</span>
      <strong>{error ? appText("Не удалось загрузить", "Йөкләп булманы") : appText("Поездок рядом пока нет", "Яҡында сәфәрҙәр әлегә юҡ")}</strong>
      <span>{error ? appText("Проверь интернет и повтори", "Интернетты тикшереп ҡабатла") : appText("Появятся — покажем здесь", "Барлыҡҡа килһә — бында күрһәтәбеҙ")}</span>
      <button type="button" className="btn-ghost nrc-empty__retry" onClick={onRetry}>
        {error ? appText("Повторить", "Ҡабатлау") : appText("Обновить", "Яңыртыу")}
      </button>
      {!error && <InviteDriverCallout />}
    </div>
  );
}
