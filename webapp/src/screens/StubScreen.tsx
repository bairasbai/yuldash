import { useLang } from "../i18n/lang";
import ScreenHeader from "../components/ScreenHeader";

/** Заглушка для вкладок фазы 1 (Карта, Заявка, Чат, Профиль). */
export default function StubScreen({
  title,
  emoji,
}: {
  title: string;
  emoji: string;
}) {
  const { t } = useLang();
  return (
    <>
      <ScreenHeader title={title} />
      <div className="state">
        <div className="state__emoji">{emoji}</div>
        <h2>{t("soon")}</h2>
        <p>{t("soonHint")}</p>
      </div>
    </>
  );
}
