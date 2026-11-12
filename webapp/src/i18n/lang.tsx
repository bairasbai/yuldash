import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from "react";
import { dict, type DictKey, type Lang, type Pair } from "./dict";
import { getLanguage, setDeviceLanguage, subscribeLanguage } from "./languagePreference";
import { getToken } from "../api/client";
import { updateMe } from "../api/auth";

interface LangCtx {
  lang: Lang;
  setLang: (l: Lang) => Promise<boolean>;
  toggle: () => void;
  /** Перевод по ключу словаря. */
  t: (key: DictKey) => string;
  /** Разовая пара (ru, ba) без ключа — зеркало appText(ru, ba). */
  appText: (ru: string, ba: string) => string;
  pick: (pair: Pair) => string;
}

const Ctx = createContext<LangCtx | null>(null);

export function LangProvider({ children }: { children: ReactNode }) {
  const lang = useSyncExternalStore(subscribeLanguage, getLanguage);
  const [saveStatus, setSaveStatus] = useState<"idle" | "saving" | "failed">("idle");
  const saving = useRef(false);

  useEffect(() => {
    // Сервер использует язык профиля для уведомлений.
    if (getToken()) {
      void updateMe({ language: lang }).catch(() => {
        /* Выбор на устройстве уже сохранён. */
      });
    }
  }, [lang]);

  const setLang = useCallback(async (next: Lang) => {
    if (saving.current) return false;
    saving.current = true;
    setSaveStatus("saving");
    try {
      await setDeviceLanguage(next);
      setSaveStatus("idle");
      return true;
    } catch {
      setSaveStatus("failed");
      return false;
    } finally {
      saving.current = false;
    }
  }, []);

  const toggle = useCallback(() => {
    void setLang(getLanguage() === "ru" ? "ba" : "ru");
  }, [setLang]);

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

  return (
    <Ctx.Provider value={value}>
      {children}
      {saveStatus === "saving" && (
        <p className="language-save-status" role="status">
          {value.appText("Сохраняем язык…", "Телде һаҡлайбыҙ…")}
        </p>
      )}
      {saveStatus === "failed" && (
        <p className="language-save-status" role="alert">
          {value.appText("Не получилось сохранить язык. Попробуй ещё раз.", "Телде һаҡлап булманы. Ҡабатлап ҡара.")}
        </p>
      )}
    </Ctx.Provider>
  );
}

export function useLang(): LangCtx {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useLang must be used within <LangProvider>");
  return ctx;
}
