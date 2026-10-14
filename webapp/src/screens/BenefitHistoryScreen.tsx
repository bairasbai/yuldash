import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { fetchPartnerMe, type PartnerMe } from "../api/coupons";
import { LoadingList, ErrorState } from "../components/States";
import ComingSoon from "../components/ComingSoon";
import { SubHeader } from "./ConsentsScreen";
import CouponsScreen from "./CouponsScreen";
import { RedeemCard } from "./PartnerCabinetScreen";
import { useBenefitOwner } from "../benefitOwner";

/** Закрытая витрина не отрезает выданный код и его подтверждение на кассе. */
export default function BenefitHistoryScreen({ partner = false }: { partner?: boolean }) {
  const owner = useBenefitOwner();
  return partner ? <ExistingPartnerCode /> : <CouponsScreen historyOnly isCurrent={owner.isCurrent} />;
}

export function ExistingPartnerCode() {
  const { appText } = useLang();
  const navigate = useNavigate();
  const owner = useBenefitOwner();
  const [me, setMe] = useState<PartnerMe | null>(null);
  const [status, setStatus] = useState<"loading" | "error" | "ready">("loading");
  const [reload, setReload] = useState(0);
  const [redeem, setRedeem] = useState(false);
  useEffect(() => {
    if (!owner.isCurrent()) return;
    const request = new AbortController();
    setStatus("loading");
    fetchPartnerMe(request.signal).then((current) => { if (!request.signal.aborted && owner.isCurrent()) { setMe(current); setStatus("ready"); } })
      .catch(() => { if (!request.signal.aborted && owner.isCurrent()) setStatus("error"); });
    return () => request.abort();
  }, [reload, owner.isCurrent]);
  if (!owner.isCurrent()) return <ComingSoon kind="business" variant="inline" />;
  return <>
    <SubHeader title={appText("Погасить код клиента", "Клиент кодын һүндереү")} onBack={() => navigate(-1)} />
    {status === "loading" ? <LoadingList count={2} /> : status === "error" ? <ErrorState onRetry={() => setReload((x) => x + 1)} /> :
      !me?.partner ? <ComingSoon kind="business" variant="inline" /> : <div className="alist">
        <h2>{me.partner.name}</h2>
        {!redeem && <button type="button" className="btn-primary" onClick={() => { if (owner.isCurrent()) setRedeem(true); }}>{appText("Погасить код клиента", "Клиент кодын һүндереү")}</button>}
        {redeem && <RedeemCard onDismiss={() => { if (owner.isCurrent()) setRedeem(false); }} onRedeemed={() => {}} isCurrent={owner.isCurrent} />}
      </div>}
  </>;
}
