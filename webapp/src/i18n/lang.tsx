import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { dict, type DictKey, type Lang, type Pair } from "./dict";

const STORAGE_KEY = "yuldash.lang";

interface LangCtx {
  lang: Lang;
  setLang: (l: Lang) => void;
  toggle: () => void;
  /** Перевод по ключу словаря. */
  t: (key: DictKey) => string;
  /** Разовая пара (ru, ba) без ключа — зеркало appText(ru, ba). */
  appText: (ru: string, ba: string) => string;
  pick: (pair: Pair) => string;
}

const Ctx = createContext<LangCtx | null>(null);

function initialLang(): Lang {
  const saved = localStorage.getItem(STORAGE_KEY);
  if (saved === "ru" || saved === "ba") return saved;
  return "ru";
}

export function LangProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<Lang>(initialLang);

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, lang);
    document.documentElement.lang = lang === "ba" ? "ba" : "ru";
  }, [lang]);

  const setLang = useCallback((l: Lang) => setLangState(l), []);
  const toggle = useCallback(
    () => setLangState((p) => (p === "ru" ? "ba" : "ru")),
    []
  );

  const value = useMemo<LangCtx>(() => {
    const idx = lang === "ba" ? 1 : 0;
    return {
      lang,
      setLang,
      toggle,
      t: (key) => dict[key][idx],
      appText: (ru, ba) => (lang === "ba" ? ba : ru),
      pick: (pair) => pair[idx],
    };
  }, [lang, setLang, toggle]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useLang(): LangCtx {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useLang must be used within <LangProvider>");
  return ctx;
}
