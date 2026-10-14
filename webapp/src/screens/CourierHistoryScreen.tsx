import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { useFeatures } from "../features";
import ComingSoon from "../components/ComingSoon";
import { SubHeader } from "./ConsentsScreen";
import { CarryOrders } from "./CourierScreen";

/** Только взятые доставки и история: без линии, новой ленты и кабинета. */
export default function CourierHistoryScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();
  const features = useFeatures();
  return <>
    <SubHeader title={appText("Везу", "Илтәм")} onBack={() => navigate(-1)} />
    <CarryOrders
      onGoAvailable={() => navigate("/map")}
      emptyContent={<ComingSoon kind={features.parcels ? "courier" : "delivery"} variant="inline" />}
    />
  </>;
}
