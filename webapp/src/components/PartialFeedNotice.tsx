import { useLang } from "../i18n/lang";

/** Признак сервера относится к поиску целиком, в том числе к пустой выдаче. */
export default function PartialFeedNotice() {
  const { appText } = useLang();
  return <p className="sheet__note" role="status">{appText("Показаны не все — уточни село или дату", "Бөтәһе лә күрһәтелмәгән — ауылды йәки көндө аныҡла")}</p>;
}
