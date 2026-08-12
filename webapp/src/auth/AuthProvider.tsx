import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import {
  getToken,
  setSession,
  setRefreshHandler,
  setUnauthorizedHandler,
} from "../api/client";
import {
  fetchMe,
  logoutServer,
  refreshSession,
  type Me,
} from "../api/auth";
import { disableWebPush } from "../push/webPush";

type Status = "loading" | "authed" | "guest";

interface AuthCtx {
  /** Пока проверяем сохранённую сессию — экраны показывают сплэш/спиннер. */
  status: Status;
  user: Me | null;
  isAuthed: boolean;
  /** Записать сессию после успешного входа (токены уже в localStorage). */
  login: (access: string, refresh: string, user: Me) => void;
  /** Выйти: гасим сервер (best-effort) и чистим локально. */
  logout: () => Promise<void>;
  /** Перечитать профиль (после правок). */
  refresh: () => Promise<void>;
}

const Ctx = createContext<AuthCtx | null>(null);

/**
 * Точка правды сессии Юлдаша.
 * На старте: есть токен → тихо тянем GET /me (клиент сам обновит по refresh при 401).
 * Регистрирует в HTTP-клиенте обработчики 401 (разлогин) и рефреша (тихое продление).
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<Status>(() =>
    getToken() ? "loading" : "guest"
  );
  const [user, setUser] = useState<Me | null>(null);
  // Чтобы обработчик 401 не тянул stale-замыкания.
  const mounted = useRef(true);

  const applyGuest = useCallback(() => {
    if (!mounted.current) return;
    setUser(null);
    setStatus("guest");
  }, []);

  // Клиент дергает эти хуки при 401 / истёкшем access.
  useEffect(() => {
    setRefreshHandler(refreshSession);
    setUnauthorizedHandler(() => applyGuest());
    return () => {
      setRefreshHandler(null);
      setUnauthorizedHandler(null);
    };
  }, [applyGuest]);

  // Старт: проверяем сохранённую сессию.
  useEffect(() => {
    mounted.current = true;
    if (!getToken()) {
      setStatus("guest");
      return () => {
        mounted.current = false;
      };
    }
    const ac = new AbortController();
    fetchMe(ac.signal)
      .then((me) => {
        if (!mounted.current) return;
        setUser(me);
        setStatus("authed");
      })
      .catch((e) => {
        if (ac.signal.aborted || e?.name === "AbortError") return;
        // 401 уже почистил токен в клиенте; любая другая ошибка — считаем гостем.
        applyGuest();
      });
    return () => {
      mounted.current = false;
      ac.abort();
    };
  }, [applyGuest]);

  const login = useCallback((access: string, refresh: string, me: Me) => {
    setSession(access, refresh);
    setUser(me);
    setStatus("authed");
  }, []);

  const logout = useCallback(async () => {
    // Пуши гасим ДО выхода: серверу нужен ещё живой токен, чтобы отвязать подписку.
    // Иначе на общем телефоне следующий вошедший получал бы чужие уведомления.
    try {
      await disableWebPush();
    } catch {
      /* не критично — выход важнее */
    }
    try {
      await logoutServer();
    } catch {
      /* нет сети / уже протух — всё равно чистим локально */
    }
    setSession(null, null);
    setUser(null);
    setStatus("guest");
    // P1-1: чистим рантайм-кеши Service Worker — иначе приватные ответы (ленты/координаты)
    // переживают logout и доступны на общем устройстве через DevTools → Cache Storage.
    if (typeof caches !== "undefined") {
      caches.keys().then((keys) => Promise.all(keys.map((k) => caches.delete(k)))).catch(() => {});
    }
  }, []);

  const refresh = useCallback(async () => {
    try {
      const me = await fetchMe();
      setUser(me);
      setStatus("authed");
    } catch {
      /* 401 обработает клиент → applyGuest */
    }
  }, []);

  const value = useMemo<AuthCtx>(
    () => ({
      status,
      user,
      isAuthed: status === "authed",
      login,
      logout,
      refresh,
    }),
    [status, user, login, logout, refresh]
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useAuth(): AuthCtx {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useAuth must be used within <AuthProvider>");
  return ctx;
}
