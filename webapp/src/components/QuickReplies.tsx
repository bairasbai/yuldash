// ================================================================
//  Быстрые ответы в чате — чипы над полем ввода. Один тап отправляет
//  готовую фразу обычным сообщением (на текущем языке пользователя).
//  Используется в чате брони (ActiveTrip) и такси-чате (InstantChat).
// ================================================================
import { useLang } from "../i18n/lang";

export default function QuickReplies({
  onPick,
  disabled = false,
}: {
  onPick: (text: string) => void;
  disabled?: boolean;
}) {
  const { appText } = useLang();

  // Фразы дорожного этикета — покрывают 90% переписки в поездке.
  const phrases: string[] = [
    appText("Выезжаю", "Сығып китәм"), // DRAFT
    appText("Жду у подъезда", "Подъезд янында көтәм"), // DRAFT
    appText("Опаздываю на 5 минут", "5 минутҡа һуңлайым"), // DRAFT
    appText("Я на месте", "Мин урында"), // DRAFT
    appText("Спасибо!", "Рәхмәт!"), // DRAFT
  ];

  return (
    <div className="chip-scroll quick-replies" role="group" aria-label={appText("Быстрые ответы", "Тиҙ яуаптар")}>
      {phrases.map((p) => (
        <button
          key={p}
          type="button"
          className="chip"
          disabled={disabled}
          onClick={() => onPick(p)}
        >
          {p}
        </button>
      ))}
    </div>
  );
}
