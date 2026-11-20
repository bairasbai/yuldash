import type { WatchRoute } from "../api/routeWatch";
import { useLang } from "../i18n/lang";
import { smallerGroupLabel, type SmallerGroup } from "../nearbySeats";
import WatchRouteButton from "./WatchRouteButton";

export default function NearbySeatsCard({ requested, group, onSearch, watch }: {
  requested: number;
  group: SmallerGroup;
  onSearch: (seats: number) => void;
  watch: WatchRoute;
}) {
  const { appText, lang } = useLang();
  return (
    <div className="nearby-filtered">
      <p role="status">
        {appText(`Для группы из ${requested} поездок нет.`, `${requested} кешелек төркөмгә сәфәр юҡ.`)}
        {" "}{smallerGroupLabel(group, lang !== "ba")}
      </p>
      <button type="button" className="nearby-filtered__reset" onClick={() => onSearch(group.seats)}>
        {appText(`Искать на ${group.seats}`, `${group.seats} урынға эҙләү`)}
      </button>
      <WatchRouteButton route={watch} />
    </div>
  );
}
