// ================================================================
//  Журнал готовности к работе → /admin/pretrip (RequireAdmin). Есть только в вебе:
//  в приложении журнал — раздел экрана «Таксисты», откуда и берём PretripJournalSection.
//  GET /admin/taxi/pretrip?day= (580-ФЗ).
//
//  Смысл записи: при разборе ДТП или проверки видно, что водитель
//  заявил в этот день. Координат пассажиров и маршрутов здесь нет —
//  только факт подтверждения, время и заметка самого водителя.
// ================================================================
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { SubHeader } from "./ConsentsScreen";
import { PretripJournalSection } from "./AdminTaxiScreen";

export default function AdminPretripScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  return (
    <>
      <SubHeader title={appText("Готовность к работе", "Эшкә әҙерлек")} onBack={() => navigate(-1)} />
      <div className="alist">
        <PretripJournalSection />
        <p className="dl-hint">
          {appText(
            "Это самодекларация водителя, а не медосмотр. Запись хранится как след на случай разбора.",
            "Был — йөрөтөүсенең үҙ раҫлауы, медосмотр түгел. Яҙма тикшереү осрағына эҙ булып һаҡлана."
          )}
        </p>
      </div>
    </>
  );
}
