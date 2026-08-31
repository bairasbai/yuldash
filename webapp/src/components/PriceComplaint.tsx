// ================================================================
//  «Что-то не так с ценой?» — жалоба на цену.
//  Зеркало Android (InstantOrderScreen.kt, TaxiPriceComplaintButton)
//  + backend instant.py: POST /instant/price-complaint.
//
//  Зачем это вообще. Человек, которому цена показалась несправедливой,
//  либо уходит молча, либо ругается с водителем — и то и другое мы
//  не видим. Кнопка превращает это в данные: на какой сумме люди
//  отваливаются и какую строку счёта не понимают.
//
//  Заказ необязателен: жалуются чаще на ОЦЕНКУ до поездки, чем на
//  завершённую. Координат в жалобе нет и быть не должно — уходят
//  только строки счёта, как их видел человек.
//
//  В вебе кнопки не было: жалоба уходила в никуда, то есть никуда
//  и не уходила (сверка с Android, 2026-08-30).
// ================================================================
import { useState } from "react";
import { useLang } from "../i18n/lang";
import { sendPriceComplaint } from "../api/instant";

/**
 * Причины закрытым списком. Свободный текст в поле причины никто не прочитает,
 * а «дорого для такого расстояния» — это уже данные, по которым меняют тариф.
 */
const REASONS: { code: string; ru: string; ba: string }[] = [
  { code: "expensive_for_distance", ru: "Дорого для такого расстояния", ba: "Был ара өсөн ҡиммәт" },
  { code: "was_cheaper", ru: "Было дешевле минуту назад", ba: "Бер минут элек арзаныраҡ ине" },
  { code: "line_unclear", ru: "Не понимаю строку в счёте", ba: "Иҫәптәге юлды аңламайым" },
  { code: "other", ru: "Другое", ba: "Башҡа" },
];

export default function PriceComplaint({
  price,
  breakdown,
  orderId,
  kind = "taxi",
}: {
  /** Сумма, которую человек видел на экране. Именно она, а не пересчитанная нами. */
  price: number;
  /** Строки счёта глазами человека: поездка, подача, опции, погода. */
  breakdown?: Record<string, number | string>;
  orderId?: number | null;
  kind?: "taxi" | "courier";
}) {
  const { appText } = useLang();
  const [open, setOpen] = useState(false);
  const [comment, setComment] = useState("");
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);

  // Отправили — благодарим и убираем кнопку. Второй раз жаловаться на ту же цену незачем.
  if (sent) {
    return (
      <div className="act-card act-card--mint">
        <p className="act-card__text" style={{ margin: 0 }}>
          {appText("Спасибо. Посмотрим и ответим", "Рәхмәт. Ҡарап сығып яуап бирербеҙ")}
        </p>
      </div>
    );
  }

  function send(reason: string) {
    if (busy) return;
    setBusy(true);
    sendPriceComplaint({
      order_id: orderId ?? null,
      kind,
      price,
      reason,
      comment,
      breakdown: breakdown ?? {},
    })
      .then(() => setSent(true))
      .catch(() => {
        // Жалоба не ушла — но говорить «спасибо» нельзя: человек решит, что его услышали.
        setSent(false);
      })
      .finally(() => {
        setBusy(false);
        setOpen(false);
      });
  }

  if (!open) {
    return (
      <button type="button" className="link-btn link-btn--quiet" onClick={() => setOpen(true)}>
        {appText("Что-то не так с ценой?", "Хаҡ менән нимәлер дөрөҫ түгелме?")}
      </button>
    );
  }

  return (
    <div className="sheet-backdrop" onClick={() => setOpen(false)}>
      <div className="sheet" onClick={(e) => e.stopPropagation()}>
        <h2 className="sheet__title">{appText("Что не так с ценой?", "Хаҡта нимә дөрөҫ түгел?")}</h2>
        {REASONS.map((r) => (
          <button
            key={r.code}
            type="button"
            className="btn-soft"
            style={{ width: "100%", marginBottom: 8 }}
            disabled={busy}
            onClick={() => send(r.code)}
          >
            {appText(r.ru, r.ba)}
          </button>
        ))}
        <label className="field">
          <input
            className="field__input"
            value={comment}
            onChange={(e) => setComment(e.target.value.slice(0, 300))}
            placeholder={appText("Можно словами (необязательно)", "Һүҙ менән дә була (мотлаҡ түгел)")}
            maxLength={300}
          />
        </label>
        <button type="button" className="btn-ghost" onClick={() => setOpen(false)} disabled={busy}>
          {appText("Закрыть", "Ябыу")}
        </button>
      </div>
    </div>
  );
}
