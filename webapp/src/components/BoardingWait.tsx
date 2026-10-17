// ================================================================
//  Пассажир до посадки: «Водитель отметит посадку» + «Сообщить о проблеме».
//  Зеркало Android (нижняя панель экрана поездки, RideshareBoarding.kt).
//
//  Своего «Я сел» у пассажира больше нет (решение Александра 17.10.2026): нажать его
//  можно было из дома, чтобы водитель уже не отметил «Не пришёл». Посадку отмечает
//  водитель, а пассажиру остаётся сверить машину по коду и, если что-то не так
//  (водитель не приехал, отметил не того), открыть разбор по брони.
// ================================================================
import { useState } from "react";
import { useLang } from "../i18n/lang";
import FileIncidentCard from "./FileIncidentCard";
import { IconCar } from "./Icons";

export default function BoardingWait({
  bookingId,
  driverId,
  driverName,
}: {
  bookingId: number;
  /** Вторая сторона брони (`peer_id` из /bookings/{id}/role). Нет — старый сервер, разбора нет. */
  driverId?: number;
  driverName: string;
}) {
  const { appText } = useLang();
  const [open, setOpen] = useState(false);
  const [filed, setFiled] = useState(false);
  const [note, setNote] = useState("");

  return (
    <div className="act-card">
      <div className="act-card__title">
        <IconCar size={18} /> {appText("Водитель отметит посадку", "Йөрөтөүсе ултырыуҙы билдәләр")}
      </div>
      <p className="act-card__text">
        {appText(
          "Сверь машину и назови код. Когда сядешь, водитель отметит посадку в приложении.",
          "Машинаны тикшер һәм кодты әйт. Ултырғас, йөрөтөүсе ултырыуҙы ҡушымтала билдәләр."
        )}
      </p>
      {filed ? (
        <div className="notice" role="status">
          {appText(
            "Разбор открыт. Позовём водителя объясниться, а решение объясним обеим сторонам.",
            "Ҡарау асылды. Йөрөтөүсене аңлатырға саҡырабыҙ, ә ҡарарҙы ике яҡҡа ла аңлатабыҙ."
          )}
        </div>
      ) : driverId ? (
        open ? (
          <FileIncidentCard
            respondentId={driverId}
            respondentName={driverName}
            bookingId={bookingId}
            onCancel={() => setOpen(false)}
            onFiled={() => {
              setOpen(false);
              setFiled(true);
            }}
            onError={setNote}
          />
        ) : (
          <button
            type="button"
            className="btn-soft btn-soft--sm"
            onClick={() => {
              setNote("");
              setOpen(true);
            }}
          >
            {appText("Сообщить о проблеме", "Проблема тураһында хәбәр итергә")}
          </button>
        )
      ) : null}
      {note && <p className="demand__quiet">{note}</p>}
    </div>
  );
}
