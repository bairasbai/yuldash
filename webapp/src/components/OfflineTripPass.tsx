// ================================================================
//  Офлайн-паспорт поездки — карточка «сверить машину без сети».
//  Зеркало Android (BookingActiveTripScreen.kt: OfflineTripBanner +
//  TripPassCard).
//
//  Когда показывается: сервер не ответил, а снимок брони сохранён
//  локально. Это ровно та минута, ради которой паспорт и заводили —
//  человек стоит у машины на ночной трассе, связи нет, и ему нужно
//  сверить госномер и назвать код посадки.
//
//  Что здесь есть и чего нет. Есть госномер, марка, имя водителя,
//  код посадки, точка встречи и телефон. Нет ничего живого — карты,
//  статусов, «водитель подъезжает»: всё это без сети было бы
//  враньём. Прямо об этом и написано в шапке карточки.
// ================================================================
import { useLang } from "../i18n/lang";
import type { TripPass } from "../utils/tripPass";
import { formatWhen, priceLabel } from "../utils/format";
import { IconPhone, IconWarn } from "./Icons";

export default function OfflineTripPass({ pass }: { pass: TripPass }) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";

  return (
    <div className="act-card act-card--warn">
      <div className="act-card__title">
        <IconWarn size={18} /> {appText("Нет связи — показываем сохранённое", "Бәйләнеш юҡ — һаҡланғанды күрһәтәбеҙ")}
      </div>
      <p className="act-card__text">
        {appText(
          "Это снимок поездки с последнего раза, когда была сеть. Номер машины и код посадки в нём настоящие — сверяй смело.",
          "Был сәфәрҙең селтәр булған сағындағы күренеше. Машина номеры ла, ултырыу коды ла ысын — тикшерә бир."
        )}
      </p>

      <div className="info-list" style={{ marginTop: 0 }}>
        <div className="info-row">
          <span className="info-row__k">{appText("Маршрут", "Юл")}</span>
          <span className="info-row__v">
            {pass.fromCity} → {pass.toCity}
          </span>
        </div>
        {pass.departAt && (
          <div className="info-row">
            <span className="info-row__k">{appText("Выезд", "Сығыу")}</span>
            <span className="info-row__v">{formatWhen(pass.departAt, ru)}</span>
          </div>
        )}
        <div className="info-row">
          <span className="info-row__k">{appText("Водитель", "Йөрөтөүсе")}</span>
          <span className="info-row__v">{pass.driverName || appText("—", "—")}</span>
        </div>
        {pass.driverCar && (
          <div className="info-row">
            <span className="info-row__k">{appText("Машина", "Машина")}</span>
            <span className="info-row__v">{pass.driverCar}</span>
          </div>
        )}
        {/* Госномер — то, ради чего паспорт и нужен: у подъезда две одинаковые белые «Лады». */}
        {pass.driverPlate && (
          <div className="info-row">
            <span className="info-row__k">{appText("Госномер", "Дәүләт номеры")}</span>
            <span className="info-row__v">
              <b>{pass.driverPlate}</b>
            </span>
          </div>
        )}
        {pass.pickup && (
          <div className="info-row">
            <span className="info-row__k">{appText("Точка встречи", "Осрашыу урыны")}</span>
            <span className="info-row__v">{pass.pickup}</span>
          </div>
        )}
        {pass.price > 0 && (
          <div className="info-row">
            <span className="info-row__k">{appText("Цена", "Хаҡ")}</span>
            <span className="info-row__v">{priceLabel(pass.price, ru)}</span>
          </div>
        )}
      </div>

      {/* Код посадки крупно: его называют вслух, часто в темноте и в перчатках. */}
      {pass.boardingCode && (
        <div className="code-card" style={{ marginTop: 12 }}>
          <div className="code-card__label">{appText("Код посадки", "Ултырыу коды")}</div>
          <div className="code-card__value">{pass.boardingCode}</div>
          <div className="code-card__hint">
            {appText("Назови его водителю при посадке", "Ултырғанда йөрөтөүсегә әйт")}
          </div>
        </div>
      )}

      {/* Звонок работает и без интернета: это обычная сотовая связь. */}
      {pass.driverPhone && (
        <a className="btn-soft trip-btn" href={`tel:${pass.driverPhone}`} style={{ marginTop: 12 }}>
          <IconPhone size={18} /> {appText("Позвонить водителю", "Йөрөтөүсегә шылтыратыу")}
        </a>
      )}
    </div>
  );
}
