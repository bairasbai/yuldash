import type { Parcel } from "../api/parcels";
import { useLang } from "../i18n/lang";
import { StatusPillParcel } from "./parcelUi";
import ParcelReceiptCard from "./ParcelReceiptCard";
import ParcelRate from "./ParcelRate";
import ParcelProblemActions from "./ParcelProblemActions";

export function isCompletedParcel(parcel: Parcel): boolean {
  return ["delivered", "returned", "canceled"].includes(parcel.status);
}

/** Финальный заказ: без геолокации, активных действий и новых фото вручения. */
export default function CompletedParcelCard({ parcel, onChanged }: {
  parcel: Parcel;
  onChanged: (next?: Parcel) => void;
}) {
  const { appText } = useLang();
  return (
    <div className="parcel-card">
      <div className="parcel-card__head">
        <strong>{appText(`Доставка №${parcel.id}`, `Илтеү №${parcel.id}`)}</strong>
        <StatusPillParcel status={parcel.status} />
      </div>
      <p>{parcel.from_city} → {parcel.to_city}</p>
      {(parcel.status === "delivered" || parcel.status === "returned") && <ParcelReceiptCard parcelId={parcel.id} />}
      {parcel.status === "delivered" && <ParcelRate parcelId={parcel.id} role="sender" />}
      <ParcelProblemActions parcel={parcel} role="courier" onChanged={onChanged} />
    </div>
  );
}
