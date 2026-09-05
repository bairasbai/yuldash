// ================================================================
//  Чек за доставку. Зеркало Android ParcelReceiptDialog.kt
//  (backend: GET /parcels/{id}/receipt).
//
//  Чек был у попутки и у такси, а у доставки — нет. При этом деньги
//  тут самые запутанные из трёх: цена доставки, комиссия платформы,
//  а у «купи и привези» ещё и стоимость товара, которую курьер
//  потратил из своего кармана. Без бумаги спор «я отдал / он не
//  отдал» упирается в память двух людей.
//
//  Телефонов и адресов в чеке нет: чеком делятся, а адрес
//  получателя — это его дом.
// ================================================================
import { useState } from "react";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { fetchParcelReceipt, type ParcelReceipt } from "../api/parcels";
import { IconCheck, IconWallet } from "./Icons";
import { formatWhen, kopExactLabel } from "../utils/format";

/** Строка счёта. Ноль не показываем: пустые строки превращают чек в шум. */
function Row({ k, v, strong }: { k: string; v: string; strong?: boolean }) {
  return (
    <div className="info-row">
      <span className="info-row__k">{k}</span>
      <span className="info-row__v">{strong ? <b>{v}</b> : v}</span>
    </div>
  );
}

export default function ParcelReceiptCard({ parcelId }: { parcelId: number }) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const [open, setOpen] = useState(false);
  const [data, setData] = useState<ParcelReceipt | null>(null);
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);

  function show() {
    setOpen(true);
    if (data || busy) return;
    setBusy(true);
    setNote("");
    fetchParcelReceipt(parcelId)
      .then(setData)
      .catch((e) =>
        setNote(
          e instanceof ApiError && e.message
            ? e.message
            : appText("Чек не открылся. Попробуй ещё раз.", "Чек асылманы. Тағы ҡабатла.")
        )
      )
      .finally(() => setBusy(false));
  }

  if (!open) {
    return (
      <button type="button" className="link-btn" style={{ marginTop: 8 }} onClick={show}>
        <IconWallet size={14} /> {appText("Чек за доставку", "Илтеү өсөн чек")}
      </button>
    );
  }

  return (
    <div className="act-card">
      <div className="act-card__title">
        <IconWallet size={18} /> {appText("Чек за доставку", "Илтеү өсөн чек")}
      </div>

      {busy && <p className="act-card__text">{appText("Открываем…", "Асабыҙ…")}</p>}
      {note && (
        <div className="notice" role="status">
          {note}
        </div>
      )}

      {data && (
        <>
          <div className="info-list" style={{ marginTop: 0 }}>
            <Row
              k={appText("Маршрут", "Юл")}
              v={`${data.from_city || "—"} → ${data.to_city || "—"}`}
            />
            {data.delivered_at && (
              <Row k={appText("Вручено", "Тапшырылды")} v={formatWhen(data.delivered_at, ru)} />
            )}
            {data.returned_at && (
              <Row k={appText("Возвращено", "Кире ҡайтарылды")} v={formatWhen(data.returned_at, ru)} />
            )}
            {data.distance_km > 0 && (
              <Row k={appText("Расстояние", "Ара")} v={`${data.distance_km.toFixed(1)} ${ru ? "км" : "км"}`} />
            )}
            <Row k={appText("Курьер", "Курьер")} v={data.courier_name} />
          </div>

          {/* Деньги. Разделяем доставку и товар: это разные карманы и разные основания. */}
          <div className="info-list">
            <Row k={appText("Доставка", "Илтеү")} v={kopExactLabel(data.delivery_price_kop)} />
            {data.goods_kop > 0 && (
              <Row k={appText("Товар", "Тауар")} v={kopExactLabel(data.goods_kop)} />
            )}
            {/* Компенсации курьеру: они идут ему целиком, комиссия с них не берётся. */}
            {data.pickup_fee_kop > 0 && (
              <Row
                k={appText("Дорога курьера к посылке", "Курьерҙың аҫылмаға тиклемге юлы")}
                v={kopExactLabel(data.pickup_fee_kop)}
              />
            )}
            {data.weather_fee_kop > 0 && (
              <Row k={appText("Зимняя дорога", "Ҡышҡы юл")} v={kopExactLabel(data.weather_fee_kop)} />
            )}
            {/* Ожидание раздельно по концам: задерживают курьера разные люди. */}
            {data.waiting_sender_kop > 0 && (
              <Row
                k={appText("Ожидание у отправителя", "Ебәреүсе янында көтөү")}
                v={kopExactLabel(data.waiting_sender_kop)}
              />
            )}
            {data.waiting_receiver_kop > 0 && (
              <Row
                k={appText("Ожидание у получателя", "Алыусы янында көтөү")}
                v={kopExactLabel(data.waiting_receiver_kop)}
              />
            )}
            {data.return_fee_kop > 0 && (
              <Row k={appText("Возврат", "Кире ҡайтарыу")} v={kopExactLabel(data.return_fee_kop)} />
            )}
            {data.cancel_fee_kop > 0 && (
              <Row k={appText("Отмена", "Кире алыу")} v={kopExactLabel(data.cancel_fee_kop)} />
            )}
            <Row k={appText("Итого", "Барлығы")} v={kopExactLabel(data.total_kop)} strong />
          </div>

          {/* Долг за товар: отдельной строкой, а не «догадайся по статусу». */}
          {data.owed_to_courier_kop > 0 && (
            <p className="act-card__text">
              {appText(
                `Курьер купил товар на свои — верни ему ${kopExactLabel(data.owed_to_courier_kop)}.`,
                `Курьер тауарҙы үҙ аҡсаһына алған — уға ${kopExactLabel(data.owed_to_courier_kop)} ҡайтар.`
              )}
            </p>
          )}

          {/* Комиссию видит только тот, кто её платит: у курьера это его деньги. */}
          {data.role === "courier" && data.commission_kop > 0 && (
            <div className="info-list">
              <Row k={appText("Комиссия платформы", "Платформа комиссияһы")} v={kopExactLabel(data.commission_kop)} />
              <Row
                k={appText("Комиссия оплачена", "Комиссия түләнгән")}
                v={data.commission_paid ? appText("да", "эйе") : appText("нет", "юҡ")}
              />
            </div>
          )}

          {/* Повторные заезды: в чеке это решение самого отправителя, а не наша надбавка. */}
          {data.redeliver_requests > 0 && (
            <p className="act-card__text">
              {appText(
                `Повторных заездов по твоей просьбе: ${data.redeliver_requests}.`,
                `Һинең үтенес буйынса ҡабат инеүҙәр: ${data.redeliver_requests}.`
              )}
            </p>
          )}

          <p className="trip-hint trip-hint--ok">
            <IconCheck size={16} />{" "}
            {appText("Телефонов и адресов в чеке нет — им можно делиться", "Чекта телефон да, адрес та юҡ — уны уртаҡлашырға була")}
          </p>
        </>
      )}

      <button type="button" className="btn-ghost" onClick={() => setOpen(false)}>
        {appText("Закрыть", "Ябыу")}
      </button>
    </div>
  );
}
