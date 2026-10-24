// ================================================================
//  «Отклонить» бронь — сначала вопрос (приёмка 18.10, п.6; Android: RideshareDecline.kt).
//
//  Было: одно касание сразу снимало бронь. Промахнулся мимо «Подтвердить» — и человек уже
//  получил отказ, а вернуть его нечем. Стало: «Отклонить бронь?» с именем пассажира, бронь
//  снимается только после «Да, отклонить». Сервер сам узнаёт отказ (бронь ждала подтверждения,
//  снимает водитель) и пишет пассажиру «Водитель отклонил бронь», а не «Бронь отменена».
// ================================================================
import { useState } from "react";
import AlertDialog from "./AlertDialog";
import { useLang } from "../i18n/lang";

export default function DeclineBookingButton({
  passengerName,
  disabled,
  onDecline,
}: {
  passengerName: string;
  disabled?: boolean;
  onDecline: () => void;
}) {
  const { appText } = useLang();
  const [asking, setAsking] = useState(false);
  const who = passengerName || appText("Пассажир", "Пассажир");
  return (
    <>
      <button type="button" className="btn-ghost" disabled={disabled} onClick={() => setAsking(true)}>
        {appText("Отклонить", "Кире ҡағыу")}
      </button>
      {asking && (
        <AlertDialog
          title={appText("Отклонить бронь?", "Бронде кире ҡағырғамы?")}
          text={appText(
            `${who} получит уведомление, что ты отклонил бронь. Место снова станет свободным.`,
            `${who} бронде кире ҡаҡҡаныңды белер. Урын яңынан буш булыр.`
          )}
          onClose={() => setAsking(false)}
          confirm={{
            label: appText("Да, отклонить", "Эйе, кире ҡағырға"),
            tone: "danger",
            onClick: () => {
              setAsking(false);
              onDecline();
            },
          }}
          dismiss={{ label: appText("Не отклонять", "Кире ҡаҡмаҫҡа"), onClick: () => setAsking(false) }}
        />
      )}
    </>
  );
}
