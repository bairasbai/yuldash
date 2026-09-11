import { useCallback, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { fetchInstantOrder, fetchMyOrders, type InstantOrder } from "../api/instant";
import { useLang } from "../i18n/lang";
import { setActiveTaxiOrder, useActiveTaxiOrder } from "../navSignals";
import { useVisibleInterval } from "../utils/useVisibleInterval";
import { IconCar, IconChevron } from "./Icons";

const LIVE_STATUSES = new Set(["accepted", "arriving", "onboard"]);

function isLive(order: InstantOrder): boolean {
  return LIVE_STATUSES.has(order.status);
}

/** Android ActiveTripBar: поездка остаётся перед глазами на пяти главных вкладках. */
export default function ActiveTaxiBar({ visible }: { visible: boolean }) {
  const { status, user } = useAuth();
  const { appText } = useLang();
  const navigate = useNavigate();
  const order = useActiveTaxiOrder();
  const enabled = status === "authed" && user?.role === "passenger";

  const refresh = useCallback(() => {
    if (!enabled) return;
    const request = order
      ? fetchInstantOrder(order.id).then((fresh) => (isLive(fresh) ? fresh : null))
      : fetchMyOrders(8).then((rows) => rows.find(isLive) ?? null);
    request.then(setActiveTaxiOrder).catch(() => {
      // Разовый обрыв не скрывает уже известную поездку. Следующий круг перечитает её.
    });
  }, [enabled, order?.id]);

  useEffect(() => {
    if (!enabled) {
      setActiveTaxiOrder(null);
      return;
    }
    refresh();
  }, [enabled, refresh]);

  useVisibleInterval(15_000, refresh, enabled);

  if (!visible || !enabled || !order) return null;

  const phase =
    order.status === "arriving"
      ? appText("на месте", "урынында")
      : order.status === "onboard"
        ? appText("в пути", "юлда")
        : appText("едет", "килә");
  const eta = order.eta_min > 0 ? appText(`${order.eta_min} мин`, `${order.eta_min} мин`) : "";

  return (
    <button
      type="button"
      className="active-taxi-bar"
      onClick={() => navigate("/taxi")}
      aria-label={appText("Открыть текущую поездку", "Әүҙем сәфәрҙе асыу")}
    >
      <span className="active-taxi-bar__icon" aria-hidden><IconCar size={22} /></span>
      <span className="active-taxi-bar__main">
        <strong>{order.driver_name || appText("Водитель", "Йөрөтөүсе")}</strong>
        <small>{phase}{eta ? ` · ${eta}` : ""}</small>
      </span>
      <span className="active-taxi-bar__open">
        {appText("Открыть", "Асырға")} <IconChevron size={18} />
      </span>
    </button>
  );
}
