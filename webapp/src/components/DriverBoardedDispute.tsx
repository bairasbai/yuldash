// ================================================================
//  Водитель на экране поездки, бронь «в пути», окно «Отменить» вышло (Q4, приёмка PR174):
//  «Если пассажир на самом деле не садился — сообщи о проблеме». Неявку по «в пути» не
//  ставим — спор решает двусторонний разбор (тип «Пассажир не вышел» уже выбран).
//  То же, что в кабинете («Едут с тобой», DriverPassengers.tsx); зеркало Android —
//  BoardedDisputeBlock (RideshareNoShow.kt).
// ================================================================
import { useState } from "react";
import { useLang } from "../i18n/lang";
import FileIncidentCard from "./FileIncidentCard";

export default function DriverBoardedDispute({
  bookingId,
  passengerId,
  passengerName = "",
  onError,
}: {
  bookingId: number;
  /** Пассажир брони (`peer_id` из /bookings/{id}/role). Нет — старый сервер, разбора нет. */
  passengerId?: number;
  passengerName?: string;
  onError: (text: string) => void;
}) {
  const { appText } = useLang();
  const [open, setOpen] = useState(false);
  const [filed, setFiled] = useState(false);

  return (
    <div className="driver-dispute">
      <div className="list-row__sub" role="note">
        {appText(
          "Если пассажир на самом деле не садился — сообщи о проблеме, разберёмся.",
          "Пассажир ысынлап ултырмаған булһа — проблема тураһында хәбәр ит, тикшерербеҙ."
        )}
      </div>
      {filed ? (
        <div className="notice" role="status">
          {appText(
            "Разбор открыт. Позовём пассажира объясниться, а решение объясним обеим сторонам.",
            "Ҡарау асылды. Юлсыны аңлатырға саҡырабыҙ, ә ҡарарҙы ике яҡҡа ла аңлатабыҙ."
          )}
        </div>
      ) : passengerId ? (
        open ? (
          <FileIncidentCard
            respondentId={passengerId}
            respondentName={passengerName.trim() || appText("пассажира", "юлсыны")}
            bookingId={bookingId}
            initialType="passenger_no_show"
            onCancel={() => setOpen(false)}
            onFiled={() => {
              setOpen(false);
              setFiled(true);
            }}
            onError={onError}
          />
        ) : (
          <button
            type="button"
            className="btn-soft btn-soft--sm"
            onClick={() => {
              onError("");
              setOpen(true);
            }}
          >
            {appText("Сообщить о проблеме", "Проблема тураһында хәбәр итергә")}
          </button>
        )
      ) : null}
    </div>
  );
}
