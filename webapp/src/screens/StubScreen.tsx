import { useLang } from "../i18n/lang";
import ScreenHeader from "../components/ScreenHeader";
import { IconChat } from "../components/Icons";

/** Заглушка для вкладок фазы 1. */
export default function StubScreen({ title }: { title: string }) {
  const { t } = useLang();
  return (
    <>
      <ScreenHeader title={title} />
      <div className="state">
        <div className="state__icon"><IconChat size={34} /></div>
        <h2>{t("soon")}</h2>
        <p>{t("soonHint")}</p>
      </div>
    </>
  );
}
