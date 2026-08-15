// ================================================================
//  💳 Перевод по СБП: номер, банк, получатель + кнопка «Оплатить в Сбербанке».
//  Зеркало Android SbpTransferSheet / SberPayBlock.
//
//  QR из приложения здесь не повторяем намеренно: человек и так с телефона,
//  сканировать собственный экран нечем. Вместо этого — прямая ссылка, которая
//  открывает Сбербанк с уже подставленным номером; не сработала (десктоп,
//  другой банк) — рядом номер и пошаговая инструкция.
// ================================================================
import { useState } from "react";
import { useLang } from "../i18n/lang";
import { IconCheck, IconCopy } from "./Icons";

/** Ссылка «перевод по номеру» в Сбербанк-онлайн. Только цифры — так её ждёт банк. */
export function sberPayLink(phone: string): string {
  return `https://www.sberbank.com/sms/pbpn?requisiteNumber=${phone.replace(/\D/g, "")}`;
}

export default function SbpPay({
  phone,
  bank,
  name,
  amountRub,
}: {
  phone: string;
  bank?: string;
  name?: string;
  amountRub?: number;
}) {
  const { appText } = useLang();
  const [copied, setCopied] = useState(false);

  if (!phone) {
    return (
      <p className="sbp-pay__note">
        {appText("Реквизиты уточняются — напиши в поддержку.", "Реквизиттар асыҡлана — ярҙамға яҙ.")}
      </p>
    );
  }

  async function copy() {
    try {
      await navigator.clipboard.writeText(phone);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1600);
    } catch {
      /* буфер недоступен — номер на экране, наберут руками */
    }
  }

  return (
    <div className="sbp-pay">
      {amountRub != null && <div className="pay-sbp__amount">{amountRub.toLocaleString("ru-RU")} ₽</div>}

      <div className="pay-sbp__row">
        <div>
          <div className="pay-sbp__label">{appText("Номер (СБП)", "Номер (СБП)")}</div>
          <div className="pay-sbp__value">{phone}</div>
        </div>
        <button type="button" className="btn-soft" onClick={copy}>
          {copied ? <IconCheck size={18} /> : <IconCopy size={18} />}
          {copied ? appText("Скопировано", "Күсерелде") : appText("Копировать", "Күсереү")}
        </button>
      </div>

      {bank && (
        <div className="pay-sbp__row">
          <div>
            <div className="pay-sbp__label">{appText("Банк получателя", "Алыусы банкы")}</div>
            <div className="pay-sbp__value">{bank}</div>
          </div>
        </div>
      )}

      {name && (
        <div className="pay-sbp__row">
          <div>
            <div className="pay-sbp__label">{appText("Получатель · СБП", "Алыусы · СБП")}</div>
            <div className="pay-sbp__value">{name}</div>
          </div>
        </div>
      )}

      <a className="btn-primary sbp-pay__go" href={sberPayLink(phone)} target="_blank" rel="noreferrer">
        {appText("Оплатить в Сбербанке", "Сбербанкта түләү")}
      </a>

      <p className="sbp-pay__note">
        {appText(
          `Другой банк: открой его → Переводы → По номеру телефона (СБП) → ${bank || "банк получателя"} → вставь номер${
            amountRub != null ? ` и сумму ${amountRub} ₽` : ""
          }.`,
          `Башҡа банк: ас → Күсереүҙәр → Телефон номеры буйынса (СБП) → ${bank || "алыусы банкы"} → номерҙы${
            amountRub != null ? ` һәм ${amountRub} ₽ сумманы` : ""
          } индер.`
        )}
      </p>
    </div>
  );
}
