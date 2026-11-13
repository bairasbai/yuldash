import { subscribeSessionChanges } from "../utils/sessionCredentials";
import { sessionMutation, sessionEpoch, storageFenced, confirmStorageSession, reportStorageFailure } from "../utils/storageHealth";
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
  reconcileSession,
  getSessionGeneration,
  setSession,
  setRefreshHandler,
  setUnauthorizedHandler,
} from "../api/client";
import {
  fetchMe,
  flushPendingLogouts,
  refreshSession,
  type Me,
} from "../api/auth";
import { disableLocalPushAfterLogout } from "../push/webPush";
import { clearOutbox } from "../utils/outbox";
import { clearAllDrafts } from "../utils/formDraft";
import { clearPersonalLocal, syncPersonalSession } from "../utils/privacy";
import { clearSessionCaches } from "../utils/sessionCaches";

type Status = "loading" | "authed" | "guest" | "unavailable";

interface AuthCtx {
  /** Пока проверяем сохранённую сессию — экраны показывают сплэш/спиннер. */
  status: Status;
  user: Me | null;
  isAuthed: boolean;
  /** Подтвердить сохранение сессии после проверки кода. */
  login: (access: string, refresh: string, user: Me, expectedGeneration: string, logoutToken?: string) => Promise<string>;
  /** Подтвердить локальный выход и передать отзыв серверу при наличии связи. */
  logout: () => Promise<void>;
  /** Перечитать профиль (после правок). */
  refresh: () => Promise<void>;
  retrySession: () => void;
}

function clearSessionData(): void {
  const clearedGeneration = getSessionGeneration();
  // Неотправленные сообщения прошлого человека нельзя оставлять: на общем телефоне
  // они ушли бы ОТ НОВОГО аккаунта при первом же появлении сети.
  void Promise.resolve(sessionMutation(() => clearedGeneration === getSessionGeneration() ? clearOutbox() : undefined)).catch(() => { if (clearedGeneration === getSessionGeneration()) reportStorageFailure(); });
  // Черновики анкет — тоже личное: на общем телефоне следующий не должен
  // увидеть чужой ИНН и номер разрешения.
  clearAllDrafts();
  // Согласия, роль, маршруты поиска, номер заказа. Согласие с офертой даёт
  // ЧЕЛОВЕК: без этого следующий вошедший числился бы согласившимся с тем,
  // чего не видел. Язык и тему оставляем — это настройки телефона.
  clearPersonalLocal();
  syncPersonalSession(getSessionGeneration());
  // P1-1: чистим рантайм-кеши Service Worker — иначе приватные ответы (ленты/координаты)
  // переживают logout и доступны на общем устройстве через DevTools → Cache Storage.
  if (typeof caches !== "undefined") {
    void Promise.resolve(sessionMutation(() => clearSessionCaches(() => clearedGeneration === getSessionGeneration()))).catch(() => { if (clearedGeneration === getSessionGeneration()) reportStorageFailure(); });
  }
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
  const retrySessionRef = useRef<() => void>(() => {});
  const retrySession = useCallback(() => retrySessionRef.current(), []);

  const applyGuest = useCallback(() => {
    if (!mounted.current) return;
    setUser(null);
    setStatus("guest");
  }, []);

  // Клиент дергает эти хуки при 401 / истёкшем access.
  useEffect(() => {
    setRefreshHandler(refreshSession);
    setUnauthorizedHandler(() => {
      clearSessionData();
      applyGuest();
      void flushPendingLogouts();
    });
    return () => {
      setRefreshHandler(null);
      setUnauthorizedHandler(null);
    };
  }, [applyGuest]);

  // Старт и смена аккаунта в другой вкладке: проверяем текущую сессию.
  useEffect(() => {
    mounted.current = true;
    let ac: AbortController | null = null;
    let retryGeneration: string | null = null;
    const loadSession = () => {
      retryGeneration = null;
      ac?.abort();
      syncPersonalSession(getSessionGeneration());
      setUser(null);
      if (!getToken()) {
        setStatus("guest");
        return;
      }
      setStatus("loading");
      const request = new AbortController();
      ac = request;
      const generation = getSessionGeneration();
      fetchMe(request.signal)
      .then((me) => {
        if (!mounted.current || request.signal.aborted || generation !== getSessionGeneration() || !getToken()) return;
        confirmStorageSession(generation);
        setUser(me);
        setStatus("authed");
      })
      .catch((e) => {
        if (generation !== getSessionGeneration()) return;
        if (request.signal.aborted || e?.name === "AbortError") return;
        // Не удалось проверить профиль — это не доказательство выхода.
        retryGeneration = generation;
        setStatus("unavailable");
      });
    };
    const retry = () => {
      if (retryGeneration !== null && retryGeneration === getSessionGeneration() && getToken()) loadSession();
    };
    retrySessionRef.current = retry;
    const onForeground = () => {
      void reconcileSession().then(retry).catch(() => {});
      void flushPendingLogouts();
    };
    const unsubscribe = subscribeSessionChanges(loadSession);
    loadSession();
    void flushPendingLogouts();
    if (typeof window !== "undefined") {
      window.addEventListener?.("online", onForeground);
      window.addEventListener?.("focus", onForeground);
      window.addEventListener?.("pageshow", onForeground);
    }
    const watch = typeof document === "undefined" ? undefined : setInterval(() => {
      if (document.visibilityState === "visible") void reconcileSession().catch(() => {});
    }, 5000);
    if (typeof document !== "undefined") document.addEventListener?.("visibilitychange", onForeground);
    return () => {
      clearInterval(watch);
      mounted.current = false;
      ac?.abort();
      retrySessionRef.current = () => {};
      unsubscribe();
      if (typeof document !== "undefined") document.removeEventListener?.("visibilitychange", onForeground);
      if (typeof window !== "undefined") {
        window.removeEventListener?.("online", onForeground);
        window.removeEventListener?.("focus", onForeground);
        window.removeEventListener?.("pageshow", onForeground);
      }
    };
  }, [applyGuest]);

  const login = useCallback(async (access: string, refresh: string, me: Me, expectedGeneration: string, logoutToken?: string) => {
    const epoch = sessionEpoch();
    return sessionMutation(async permit => {
      if (epoch !== sessionEpoch() || storageFenced()) throw new Error("Session operation superseded");
      const generation = await setSession(access, refresh, permit, expectedGeneration, logoutToken);
      if (!generation || generation !== getSessionGeneration() || storageFenced()) throw new Error("Session operation superseded");
      syncPersonalSession(generation);
      confirmStorageSession(generation);
      setUser(me);
      setStatus("authed");
      void flushPendingLogouts();
      return generation;
    });
  }, []);

  const logout = useCallback(async () => {
    const generation = getSessionGeneration();
    if (storageFenced()) return;
    try {
      await sessionMutation(async permit => {
        await reconcileSession();
        if (generation !== getSessionGeneration()) return;
        if (!getToken()) return;
        const guest = await setSession(null, null, permit, generation);
        if (!guest || guest !== getSessionGeneration()) return;
        setUser(null);
        setStatus("guest");
        clearSessionData();
        void flushPendingLogouts();
        void disableLocalPushAfterLogout(guest);
      });
    } catch (error) {
      if (!storageFenced()) throw error;
    }
  }, []);

  const refresh = useCallback(async () => {
    const generation = getSessionGeneration();
    try {
      const me = await fetchMe();
      if (!mounted.current || generation !== getSessionGeneration() || !getToken()) return;
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
      retrySession,
    }),
    [status, user, login, logout, refresh, retrySession]
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useAuth(): AuthCtx {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useAuth must be used within <AuthProvider>");
  return ctx;
}
