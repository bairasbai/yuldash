import { useLang } from "../i18n/lang";

/** Переключатель RU/BA. Живёт в шапке (белый текст на зелёном градиенте). */
export default function LangToggle() {
  const { lang, setLang } = useLang();
  return (
    <div className="lang-toggle" role="group" aria-label="Язык / Тел">
      <button
        type="button"
        data-active={lang === "ru"}
        aria-pressed={lang === "ru"}
        onClick={() => setLang("ru")}
      >
        RU
      </button>
      <button
        type="button"
        data-active={lang === "ba"}
        aria-pressed={lang === "ba"}
        onClick={() => setLang("ba")}
      >
        БА
      </button>
    </div>
  );
}
