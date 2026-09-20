// ================================================================
//  Навигационное состояние водителя после посадки — зеркало android/TaxiDriverNavigationScreen.kt:
//  карта во весь экран, шторка с пассажиром, назначением и оплатой, «Открыть голосовой
//  навигатор», «Завершить поездку» в подвале. Поверх карты: свернуть, SOS, «вернуться к движению».
// ================================================================
import { useState, type ReactNode } from "react";
import { useLang } from "../i18n/lang";
import { type InstantOrder } from "../api/instant";
import TaxiSheet, { TaxiSheetOverlayButton } from "../components/TaxiSheet";
import YandexMap, { type GeoPoint } from "../components/YandexMap";
import RouteTimeline from "../components/RouteTimeline";
import { IconChat, IconChevron, IconLocate, IconPhone, IconPin, IconShield } from "../components/Icons";
import { YuRoute } from "../components/BrandIcons";
import { hhmm, kopExactLabel } from "../utils/format";
import { passengerPayKop } from "./TaxiDriverCompletedScreen";

export default function TaxiDriverOnboardNavigator({
  order,
  pos,
  busy,
  actionError,
  onBack,
  onChat,
  onSafety,
  onOpenExternalNavigator,
  onFinish,
  tripControls,
}: {
  order: InstantOrder;
  pos: GeoPoint | null;
  busy: boolean;
  actionError: string;
  onBack: () => void;
  onChat: () => void;
  onSafety: () => void;
  onOpenExternalNavigator: () => void;
  onFinish: () => void;
  /** Смена адреса, способ расчёта, «Стоим» — первым в прокрутке, чтобы срочное не терялось. */
  tripControls?: ReactNode;
}) {
  const { appText } = useLang();
  const [following, setFollowing] = useState(true);
  const [recenterTick, setRecenterTick] = useState(0);

  const eta = Math.max(0, Math.round(order.eta_min));
  const arrival = eta > 0 ? hhmm(new Date(Date.now() + eta * 60_000)) : null;
  const fromPt: GeoPoint | null = order.from_lat != null ? { lat: order.from_lat, lng: order.from_lng ?? 0 } : null;
  const toPt: GeoPoint | null = order.to_lat != null ? { lat: order.to_lat, lng: order.to_lng ?? 0 } : null;
  const name = order.passenger_name.trim() || appText("Пассажир", "Пассажир");
  const initial = order.passenger_name.trim().charAt(0).toUpperCase() || "?";
  const stops = (order.stops ?? []).filter((s) => !s.done);

  return (
    <TaxiSheet
      halfBodyFraction={0.42}
      map={<YandexMap from={fromPt} to={toPt} route={!!(fromPt && toPt)} me={pos} height="100%" recenterTick={recenterTick} />}
      overlay={
        <>
          <TaxiSheetOverlayButton position="left" label={appText("Свернуть поездку", "Сәфәрҙе йыйыу")} onClick={onBack}>
            <span className="tdn__chev" aria-hidden><IconChevron size={22} /></span>
          </TaxiSheetOverlayButton>
          <button type="button" className="taxi-sheet__overlay-btn taxi-sheet__overlay-btn--right is-large tdn__sos" onClick={onSafety} aria-label={appText("Экстренная помощь", "Ашығыс ярҙам")}>
            <span className="taxi-sheet__sos">SOS</span>
          </button>
          <button
            type="button"
            className={"taxi-sheet__overlay-btn taxi-sheet__overlay-btn--right tdn__locate" + (following ? " is-active" : "")}
            onClick={() => {
              setFollowing(true);
              setRecenterTick((n) => n + 1);
            }}
            aria-label={following ? appText("Камера следует за машиной", "Камера машина артынан бара") : appText("Вернуться к движению", "Хәрәкәткә кире ҡайтыу")}
          >
            <IconLocate size={21} />
          </button>
        </>
      }
      header={
        <div className="tdn-head">
          <h1>{eta > 0 ? appText(`В пути · ${eta} мин`, `Юлда · ${eta} мин`) : appText("В пути", "Юлда")}</h1>
          <p>{arrival ? appText(`Приедем около ${arrival}`, `Яҡынса ${arrival}-тә барып етәбеҙ`) : appText("Следим за маршрутом", "Юлды күҙәтәбеҙ")}</p>
        </div>
      }
      body={
        <>
          {tripControls}
          {/* DriverNavigatorPassengerCard */}
          <div className="tdn-who">
            <div className="tdn-who__row">
              <span className="tdn-who__avatar" aria-hidden>{initial}</span>
              <span className="tdn-who__text">
                <strong>{name}</strong>
                <small>{appText("Пассажир в машине", "Пассажир машинала")}</small>
              </span>
            </div>
            <div className="tdn-who__actions">
              <button type="button" className="tdn-act" onClick={onChat}>
                <span className="tdn-act__circle"><IconChat size={19} /></span>
                <small>{appText("Чат", "Чат")}</small>
              </button>
              {order.passenger_phone && (
                <a className="tdn-act" href={`tel:${order.passenger_phone}`}>
                  <span className="tdn-act__circle"><IconPhone size={19} /></span>
                  <small>{appText("Звонок", "Шылтыратыу")}</small>
                </a>
              )}
              <button type="button" className="tdn-act" onClick={onSafety}>
                <span className="tdn-act__circle"><IconShield size={19} /></span>
                <small>{appText("Безопасность", "Именлек")}</small>
              </button>
            </div>
          </div>
          {/* DriverNavigatorDestination */}
          <div className="tdn-dest">
            <span className="tdn-dest__icon" aria-hidden><IconPin size={20} /></span>
            <span className="tdn-dest__text">
              <small>{appText("До места осталось", "Барып етергә ҡалды")}</small>
              <strong>{order.to_text.trim() || appText("Точка назначения", "Барыу нөктәһе")}</strong>
            </span>
          </div>
          {/* DriverNavigatorPayment */}
          <div className="tdn-pay">
            <span>{appText("Пассажир платит", "Пассажир түләй")}</span>
            <b>{kopExactLabel(passengerPayKop(order))}</b>
          </div>
          <button type="button" className="tdn-navi" onClick={onOpenExternalNavigator}>
            <YuRoute size={18} />
            {appText("Открыть голосовой навигатор", "Тауышлы навигаторҙы асыу")}
          </button>
          {actionError && <p className="tdn-err">{actionError}</p>}
        </>
      }
      extra={
        <div className="tdn-route">
          <RouteTimeline from={order.from_text} to={order.to_text} compact />
          {stops.map((s, i) => (
            <span key={i} className="tdn-route__stop">
              <IconPin size={18} />
              <span>{s.text || appText("Точка на карте", "Картала нөктә")}</span>
            </span>
          ))}
        </div>
      }
      footer={
        <button type="button" className="btn-primary tdn__finish" onClick={onFinish} disabled={busy}>
          {busy ? <span className="spinner spinner--sm spinner--on-filled" aria-hidden /> : appText("Завершить поездку", "Сәфәрҙе тамамлау")}
        </button>
      }
    />
  );
}
