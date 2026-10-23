// ================================================================
//  «Отметка не принята» — сервер отказал действию, которое ждало сети в очереди.
//
//  Водитель на трассе без связи нажал «Выехал»; сеть вернулась, очередь отправила отметку,
//  а сервер ответил «Рано: можно за час до выезда» или «Поездка отменена». Раньше очередь молча
//  снимала действие, и человек считал, что пассажир уже предупреждён. Теперь отказ — словами
//  сервера, поверх любого экрана оболочки, пока человек не нажмёт «Понятно» или «К поездке».
//  Тон тёплый, как у .notice: человек ничего не сломал, ему нужно знать, что происходит.
// ================================================================
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { dismissOutboxRefusal, getOutboxRefusals, subscribeOutboxRefusals, type OutboxRefusal } from "../utils/outbox";

/** Как человек называл эту отметку на кнопке. */
export function refusalMark(r: Pick<OutboxRefusal, "kind" | "payload">, appText: (ru: string, ba: string) => string): string {
  if (r.kind === "driver_status") {
    if (r.payload === "departed") return appText("«Выехал»", "«Сыҡтым»");
    if (r.payload === "arriving") return appText("«Подъезжаю»", "«Яҡынлашам»");
    if (r.payload === "done") return appText("«Завершить поездку»", "«Сәфәрҙе тамамлау»");
  }
  if (r.kind === "trip_status") {
    if (r.payload === "arrived") return appText("«Доехала»", "«Барып еттем»");
    if (r.payload === "done") return appText("«Поездка завершена»", "«Сәфәр тамамланды»");
  }
  return appText("Отметка", "Билдәләү");
}

export default function OutboxRefusalBanner() {
  const { appText } = useLang();
  const navigate = useNavigate();
  const [items, setItems] = useState<OutboxRefusal[]>(() => getOutboxRefusals());
  useEffect(() => {
    const sync = () => setItems(getOutboxRefusals());
    sync();
    return subscribeOutboxRefusals(sync);
  }, []);
  const first = items[0];
  if (!first) return null;
  return (
    <div className="refusal-banner" role="alert">
      <span className="refusal-banner__text">
        <b>{refusalMark(first, appText)} {appText("— не принято", "— ҡабул ителмәне")}</b>
        <span>{first.message}</span>
      </span>
      <span className="refusal-banner__actions">
        <button type="button" className="refusal-banner__btn" onClick={() => { dismissOutboxRefusal(first.key); navigate(`/trip/${first.bookingId}`); }}>
          {appText("К поездке", "Сәфәргә")}
        </button>
        <button type="button" className="refusal-banner__btn" onClick={() => dismissOutboxRefusal(first.key)}>
          {appText("Понятно", "Аңлашылды")}
        </button>
      </span>
    </div>
  );
}
