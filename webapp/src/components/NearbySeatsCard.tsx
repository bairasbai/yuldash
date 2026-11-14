import { Link } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { smallerGroupLabel, type SmallerGroup } from "../nearbySeats";

export default function NearbySeatsCard({ requested, group, onSearch }: {
  requested: number;
  group: SmallerGroup;
  onSearch: (seats: number) => void;
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
      <Link className="nearby-filtered__reset" to="/route-watches">
        {appText("Следить за маршрутом", "Маршрутты күҙәтеү")}
      </Link>
    </div>
  );
}
