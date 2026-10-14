// ================================================================
//  Опции мест в машине (2026-10-14): водитель — «сзади максимум двое»
//  и «переднее место свободно», пассажир — «хочу переднее». Зеркало
//  Android SeatOptions.kt. Тумблер — тот же SettingSwitchRow, что у
//  соседних настроек (Material Switch с мягким переездом бегунка).
// ================================================================
import { useLang } from "../i18n/lang";
import type { Ride } from "../api/rides";
import { frontSeatState, seatHint } from "../utils/seatOptions";
import { hasOwnFrontSeat, type OwnSeatBooking } from "../utils/ownBookings";
import { SettingSwitchRow } from "./cabinetUi";
import { IconCar, IconUsers } from "./Icons";

/** Карточка «Места в машине» в форме поездки и в правке своей поездки. */
export function SeatOptionsFields({
  backMaxTwo,
  frontSeatAvailable,
  onBackMaxTwo,
  onFrontSeatAvailable,
}: {
  backMaxTwo: boolean;
  frontSeatAvailable: boolean;
  onBackMaxTwo: (v: boolean) => void;
  onFrontSeatAvailable: (v: boolean) => void;
}) {
  const { appText } = useLang();
  const [hintRu, hintBa] = seatHint(backMaxTwo, frontSeatAvailable);
  return (
    <div className="seat-options">
      <span className="field__label">{appText("Места в машине", "Машинала урындар")}</span>
      <SettingSwitchRow
        icon={<IconUsers size={20} />}
        title={appText("Сзади максимум двое", "Артта күп тигәндә икәү")}
        subtitle={appText("Третьего на заднем ряду не будет", "Артҡы рәттә өсөнсө булмаҫ")}
        checked={backMaxTwo}
        onChange={onBackMaxTwo}
      />
      <SettingSwitchRow
        icon={<IconCar size={20} />}
        title={appText("Переднее место свободно", "Алғы урын буш")}
        subtitle={appText("Выключи, если спереди едет свой человек", "Алда үҙ кешең барһа, һүндер")}
        checked={frontSeatAvailable}
        onChange={onFrontSeatAvailable}
      />
      <p className="field__hint seat-options__hint" aria-live="polite">
        {appText(hintRu, hintBa)}
      </p>
    </div>
  );
}

/** Значки в карточке поездки: «сзади двое», «переднее свободно / занято». */
export function SeatBadges({ ride, ownBooking }: {
  ride: Pick<Ride, "id" | "back_max_two" | "front_seat_available" | "front_seat_taken">;
  ownBooking?: OwnSeatBooking | null;
}) {
  const { appText } = useLang();
  const front = frontSeatState(ride);
  const ownFront = hasOwnFrontSeat(ride.id, ownBooking);
  return (
    <>
      {ownFront && (
        <span className="badge badge--mint">{appText("Переднее место — твоё", "Алғы урын — һинеке")}</span>
      )}
      {!ownFront && front === "free" && (
        <span className="badge badge--mint">{appText("Переднее свободно", "Алғы урын буш")}</span>
      )}
      {!ownFront && front === "taken" && (
        <span className="badge">{appText("Переднее занято", "Алғы урын алынған")}</span>
      )}
      {ride.back_max_two && (
        <span className="badge badge--mint">{appText("Сзади двое", "Артта икәү")}</span>
      )}
    </>
  );
}

/**
 * «Хочу переднее место» при брони. Честно заранее: переднее свободно — тумблер;
 * обещано другому — так и говорим; водитель его не предлагает — тоже говорим.
 */
export function FrontSeatChoice({
  ride,
  ownBooking,
  checked,
  onChange,
}: {
  ride: Pick<Ride, "id" | "front_seat_available" | "front_seat_taken">;
  ownBooking?: OwnSeatBooking | null;
  checked: boolean;
  onChange: (v: boolean) => void;
}) {
  const { appText } = useLang();
  const state = frontSeatState(ride);
  if (hasOwnFrontSeat(ride.id, ownBooking)) {
    return <p className="sheet__note">{appText("Переднее место — твоё", "Алғы урын — һинеке")}</p>;
  }
  if (state === "none") {
    return (
      <p className="sheet__note">
        {appText(
          "Спереди едет человек водителя — для пассажиров места сзади.",
          "Алда йөрөтөүсенең үҙ кешеһе бара — юлсыларға урын артта."
        )}
      </p>
    );
  }
  if (state === "taken") {
    return (
      <p className="sheet__note">
        {appText(
          "Переднее место уже занято. Сзади места есть.",
          "Алғы урын инде алынған. Артта урын бар."
        )}
      </p>
    );
  }
  return (
    <SettingSwitchRow
      icon={<IconCar size={20} />}
      title={appText("Хочу переднее место", "Алғы урынды теләйем")}
      subtitle={appText(
        "Переднее одно на машину — достанется тому, кто попросил первым",
        "Алғы урын машинала берәү — уны тәүге һораған кеше ала"
      )}
      checked={checked}
      onChange={onChange}
    />
  );
}
