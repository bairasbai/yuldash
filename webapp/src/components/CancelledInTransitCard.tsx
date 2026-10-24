// ================================================================
//  «Поездку отменили в пути» — экран поездки пассажира, когда водитель (или администратор) снял
//  поездку, а человек уже сидел в машине (решение Александра 23.10.2026, сервер —
//  `cancelled_in_transit`). Зеркало Android CancelledInTransit.kt.
//
//  Человек, возможно, стоит на трассе. Поэтому не «посмотри другие поездки», а путь к помощи:
//  SOS (с бронью — дежурный и близкие узнают машину), «Застрял на трассе», «Сообщить о проблеме»,
//  пока сервер держит окно (`problem_report_sec`, 6 ч от отмены), машина и госномер — по ним
//  родные и полиция ищут машину. Телефона водителя и чата нет: сервер их после отмены не отдаёт.
//
//  Оценка водителя (решение Александра 24.10.2026): иначе водитель уходил от плохого отзыва, отменив
//  поездку перед прибытием. Звёзды — ниже помощи и разбора, пока сервер разрешает (`can_rate` из деталей:
//  срок и пауза как у завершённой) и своей оценки нет; поставил — «Твоя оценка».
// ================================================================
import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import RoadsideHelp from "./RoadsideHelp";
import FileIncidentCard from "./FileIncidentCard";
import ReceiptRateDriver from "./ReceiptRateDriver";
import { IconCar, IconFlag, IconStar, IconWarn } from "./Icons";
import { sosPath } from "../utils/sosLink";

export default function CancelledInTransitCard({
  bookingId,
  car,
  plate,
  driverId,
  driverName,
  problemReportSec = 0,
  canRate = false,
  myStars = 0,
}: {
  bookingId: number;
  /** «серая Лада Веста» — цвет и модель. Пусто — водитель не указал. */
  car: string;
  plate: string;
  /** Водитель брони (`peer_id` из /bookings/{id}/role). Нет — старый сервер, разбора нет. */
  driverId?: number;
  driverName: string;
  /** Сколько секунд ещё открыт «Сообщить о проблеме» (считает сервер). */
  problemReportSec?: number;
  /** Можно оценить водителя (`can_rate` из деталей брони; старый сервер — нет). */
  canRate?: boolean;
  /** Сколько звёзд я уже поставил (`my_stars`; 0 — не ставил). */
  myStars?: number;
}) {
  const { appText } = useLang();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [filed, setFiled] = useState(false);
  const [note, setNote] = useState("");
  const canReport = problemReportSec > 0 && !!driverId;
  // Оценка, поставленная здесь: своя на каждую бронь — поздний ответ чужой брони сюда не ляжет.
  const [rated, setRated] = useState<{ id: number; stars: number } | null>(null);
  const currentId = useRef(bookingId);
  currentId.current = bookingId;
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);
  const justRated = rated?.id === bookingId ? rated.stars : 0;
  const shownStars = justRated || (myStars >= 1 && myStars <= 5 ? myStars : 0);

  return (
    <>
      {/* Тёплая плашка, а не красная: человеку нужна ясность, что делать, а не тревога. */}
      <div className="act-card act-card--warn" role="status">
        <div className="act-card__title">
          <IconWarn size={18} /> {appText("Поездку отменили в пути", "Сәфәр юлда кире алынды")}
        </div>
        <p className="act-card__text" style={{ marginBottom: 0 }}>
          {appText(
            "Если нужна помощь — нажми SOS или «Застрял на трассе». Машина и госномер — ниже.",
            "Ярҙам кәрәк булһа — SOS йәки «Юлда ҡалдым» төймәһенә баҫ. Машина һәм дәүләт номеры — түбәндә."
          )}
        </p>
      </div>

      <div className="info-list">
        {car || plate ? (
          <>
            {car && (
              <div className="info-row">
                <span className="info-row__k">{appText("Машина", "Машина")}</span>
                <span className="info-row__v">{car}</span>
              </div>
            )}
            {plate && (
              <div className="info-row">
                <span className="info-row__k">{appText("Госномер", "Дәүләт номеры")}</span>
                <span className="info-row__v">{plate}</span>
              </div>
            )}
          </>
        ) : (
          <div className="info-row">
            <span className="info-row__k" style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
              <IconCar size={16} />
              {appText("Водитель не указал машину и госномер", "Йөрөтөүсе машинаһын һәм дәүләт номерын күрһәтмәгән")}
            </span>
          </div>
        )}
      </div>

      {/* SOS — тот же экран помощи; бронь в адресе: по ней дежурный узнаёт, из какой машины высадили. */}
      <button type="button" className="sos-btn" onClick={() => navigate(sosPath(bookingId))}>
        <IconWarn size={16} /> {appText("SOS — нужна помощь", "SOS — ярҙам кәрәк")}
      </button>

      {/* «Застрял» — сервер принимает его и по отменённой брони. */}
      <RoadsideHelp target={{ kind: "booking", id: bookingId }} />

      {canReport && (
        <div className="act-card">
          <div className="act-card__title">
            <IconFlag size={18} /> {appText("Что-то было не так?", "Нимәлер дөрөҫ булманымы?")}
          </div>
          <p className="act-card__text">
            {appText(
              "Если водитель высадил тебя без причины или в дороге что-то случилось — расскажи, разберёмся.",
              "Йөрөтөүсе һине сәбәпһеҙ төшөрөп ҡалдырһа йәки юлда нимәлер булһа — һөйлә, тикшерербеҙ."
            )}
          </p>
          {filed ? (
            <div className="notice" role="status">
              {appText(
                "Разбор открыт. Позовём водителя объясниться, а решение объясним обеим сторонам.",
                "Ҡарау асылды. Йөрөтөүсене аңлатырға саҡырабыҙ, ә ҡарарҙы ике яҡҡа ла аңлатабыҙ."
              )}
            </div>
          ) : open ? (
            // Тип спора не подставляем: что случилось в дороге, знает человек.
            <FileIncidentCard
              respondentId={driverId!}
              respondentName={driverName.trim() || appText("водителя", "йөрөтөүсене")}
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
          )}
          {note && <p className="demand__quiet">{note}</p>}
        </div>
      )}

      {/* Оценка — ниже помощи и разбора: сначала безопасность, потом звёзды. */}
      {shownStars > 0 ? (
        <div className="act-card" role="status">
          {justRated > 0 && (
            <div className="act-card__title">
              <IconStar size={18} /> {appText("Спасибо! Оценка учтена.", "Рәхмәт! Баһа иҫәпкә алынды.")}
            </div>
          )}
          <p className="act-card__text" style={{ marginBottom: 0 }}>
            {appText("Твоя оценка", "Һинең баһаң")}: {appText(`${shownStars} из 5`, `5-тән ${shownStars}`)}
          </p>
        </div>
      ) : canRate ? (
        <div className="act-card">
          <ReceiptRateDriver
            bookingId={bookingId}
            canAct={() => alive.current && currentId.current === bookingId}
            onRated={(stars) => setRated({ id: bookingId, stars })}
            hint={appText(
              "Как ехалось до отмены? Оценка поможет другим выбрать водителя.",
              "Кире алынғансы нисек барҙың? Баһаң башҡаларға йөрөтөүсе һайларға ярҙам итер."
            )}
          />
        </div>
      ) : null}
    </>
  );
}
