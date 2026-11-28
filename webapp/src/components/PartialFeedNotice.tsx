import { useLang } from "../i18n/lang";

/** Признак сервера относится к поиску целиком, в том числе к пустой выдаче. */
export default function PartialFeedNotice({ empty = false }: { empty?: boolean }) {
  const { appText } = useLang();
  return (
    <p className="sheet__note" role="status">
      {empty
        ? appText("В просмотренной части совпадений нет. Поиск проверил не все варианты.", "Ҡаралған өлөштә тап килгәндәре юҡ. Эҙләү бөтә варианттарҙы ла тикшермәне.")
        : appText("Показаны не все результаты.", "Бөтә һөҙөмтәләр ҙә күрһәтелмәгән.")}
    </p>
  );
}
