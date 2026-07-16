import { useLang } from "../i18n/lang";
import { track } from "../analytics";

/** Переключатель RU/BA. Живёт в шапке (белый текст на зелёном градиенте). */
export default function LangToggle() {
  const { lang, setLang } = useLang();
  const choose = (l: "ru" | "ba") => {
    if (l !== lang) track("lang_switch", { to: l });
    setLang(l);
  };
  return (
    <div className="lang-toggle" role="group" aria-label="Язык / Тел">
      <button
        type="button"
        data-active={lang === "ru"}
        aria-pressed={lang === "ru"}
        onClick={() => choose("ru")}
      >
        RU
      </button>
      <button
        type="button"
        data-active={lang === "ba"}
        aria-pressed={lang === "ba"}
        onClick={() => choose("ba")}
      >
        БА
      </button>
    </div>
  );
}
