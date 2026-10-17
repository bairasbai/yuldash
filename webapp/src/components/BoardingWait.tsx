// ================================================================
//  «Сообщить о проблеме» пассажира — разбор по брони с водителем (тип «Водитель не приехал»
//  уже выбран). Зеркало Android: PassengerBoardingWait (RideshareBoarding.kt).
//
//  Своего «Я сел» у пассажира больше нет (решение Александра 17.10.2026): нажать его
//  можно было из дома, чтобы водитель уже не отметил «Не пришёл». Посадку отмечает
//  водитель, а пассажиру остаётся сверить машину по коду и, если что-то не так
//  (водитель не приехал, отметил не того), открыть разбор по брони. Три места (приёмка PR174):
//    · «waiting» — до посадки: «Водитель отметит посадку»;
//    · «marked» — водитель отметил «Сел»: отмены «в пути» нет (сервер: onboard_no_cancel),
//      и если человек не садился — путь только этот;
//    · «after» — завершённая поездка, пока сервер держит окно (problem_report_sec, 6 часов):
//      водитель мог отметить не того и сразу закрыть поездку.
// ================================================================
import { useState } from "react";
import { useLang } from "../i18n/lang";
import FileIncidentCard from "./FileIncidentCard";
import { IconCar, IconCheck, IconFlag } from "./Icons";

export type PassengerReportStage = "waiting" | "marked" | "after";

const COPY: Record<PassengerReportStage, { title: [string, string]; text: [string, string] }> = {
  waiting: {
    title: ["Водитель отметит посадку", "Йөрөтөүсе ултырыуҙы билдәләр"],
    text: [
      "Сверь машину и назови код. Когда сядешь, водитель отметит посадку в приложении.",
      "Машинаны тикшер һәм кодты әйт. Ултырғас, йөрөтөүсе ултырыуҙы ҡушымтала билдәләр.",
    ],
  },
  marked: {
    title: ["Водитель отметил посадку", "Йөрөтөүсе ултырыуҙы билдәләне"],
    text: ["Не садился — сообщи о проблеме.", "Ултырмаған булһаң — проблема тураһында хәбәр ит."],
  },
  after: {
    title: ["Не садился в машину?", "Машинаға ултырманыңмы?"],
    text: [
      "Водитель отметил посадку без тебя или не приехал — сообщи о проблеме, разберёмся.",
      "Йөрөтөүсе һинһеҙ ултырыуҙы билдәләгән йәки килмәгән булһа — проблема тураһында хәбәр ит, тикшерербеҙ.",
    ],
  },
};

export default function BoardingWait({
  bookingId,
  driverId,
  driverName,
  stage = "waiting",
}: {
  bookingId: number;
  /** Вторая сторона брони (`peer_id` из /bookings/{id}/role). Нет — старый сервер, разбора нет. */
  driverId?: number;
  driverName: string;
  stage?: PassengerReportStage;
}) {
  const { appText } = useLang();
  const [open, setOpen] = useState(false);
  const [filed, setFiled] = useState(false);
  const [note, setNote] = useState("");
  const copy = COPY[stage];
  const Icon = stage === "marked" ? IconCheck : stage === "after" ? IconFlag : IconCar;

  return (
    <div className="act-card">
      <div className="act-card__title">
        <Icon size={18} /> {appText(copy.title[0], copy.title[1])}
      </div>
      <p className="act-card__text">{appText(copy.text[0], copy.text[1])}</p>
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
            respondentName={driverName.trim() || appText("водителя", "йөрөтөүсене")}
            bookingId={bookingId}
            initialType="driver_no_show"
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
