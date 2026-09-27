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
  getSessionGeneration,
  revokedGeneration,
  revokeSession,
  SESSION_REVOKE_PREFIX,
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
  /** Записать сессию после успешного входа (токены уже в localStorage). */
  login: (access: string, refresh: string, user: Me) => void;
  /** Выйти: гасим сервер (best-effort) и чистим локально. */
  logout: () => Promise<void>;
  /** Перечитать профиль (после правок). */
  refresh: () => Promise<void>;
  retrySession: () => void;
}

function clearSessionData(owner: string): void {
  // Неотправленные сообщения прошлого человека нельзя оставлять: на общем телефоне
  // они ушли бы ОТ НОВОГО аккаунта при первом же появлении сети.
  void Promise.resolve(clearOutbox(owner)).catch(() => {});
  // Черновики анкет — тоже личное: на общем телефоне следующий не должен
  // увидеть чужой ИНН и номер разрешения.
  clearAllDrafts(owner);
  // Согласия, роль, маршруты поиска, номер заказа. Согласие с офертой даёт
  // ЧЕЛОВЕК: без этого следующий вошедший числился бы согласившимся с тем,
  // чего не видел. Язык и тему оставляем — это настройки телефона.
  clearPersonalLocal(owner);
  // P1-1: чистим рантайм-кеши Service Worker — иначе приватные ответы (ленты/координаты)
  // переживают logout и доступны на общем устройстве через DevTools → Cache Storage.
  // Runtime private caching is disabled. Historical caches have no owner keys:
  // expiry of A cannot safely delete shared caches potentially used by B.
}

const Ctx = createContext<AuthCtx | null>(null);

/**
 * Точка правды сессии Юлдаша.
 * На старте: есть токен → тихо тянем GET /me (клиент сам обновит по refresh при 401).
 * Регистрирует в HTTP-клиенте обработчики 401 (разлогин) и рефреша (тихое продление).
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<Status>(() => {
    try { return getToken() ? "loading" : "guest"; }
    catch { return "unavailable"; }
  });
  const [user, setUser] = useState<Me | null>(null);
  // Чтобы обработчик 401 не тянул stale-замыкания.
  const mounted = useRef(true);
  const observedGeneration = useRef<string | null>(null);
  const storageUnavailable = useRef(false);
  const retrySessionRef = useRef<() => void>(() => {});
  const loadCurrentSessionRef = useRef<() => void>(() => {});
  const retrySession = useCallback(() => retrySessionRef.current(), []);

  const applyGuest = useCallback(() => {
    if (!mounted.current) return;
    setUser(null);
    setStatus("guest");
  }, []);

  // Клиент дергает эти хуки при 401 / истёкшем access.
  useEffect(() => {
    setRefreshHandler(refreshSession);
    setUnauthorizedHandler((owner, persisted) => {
      if (persisted) clearSessionData(owner);
      let current: string;
      try { current = getSessionGeneration(); }
      catch {
        storageUnavailable.current = true;
        setUser(null); setStatus("unavailable");
        return;
      }
      if (current === revokedGeneration(owner)) {
        observedGeneration.current = current;
        syncPersonalSession(current);
        applyGuest();
      } else if (!persisted && current === owner && mounted.current) {
        storageUnavailable.current = true;
        setUser(null);
        setStatus("unavailable");
      } else if (observedGeneration.current !== current) {
        loadCurrentSessionRef.current();
      }
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
    const showStorageUnavailable = () => {
      ac?.abort();
      storageUnavailable.current = true;
      retryGeneration = null;
      setUser(null);
      setStatus("unavailable");
    };
    const loadSession = () => {
      retryGeneration = null;
      ac?.abort();
      setUser(null);
      let generation: string;
      let token: string | null;
      try {
        generation = getSessionGeneration();
        token = getToken();
      } catch {
        showStorageUnavailable();
        return;
      }
      storageUnavailable.current = false;
      observedGeneration.current = generation;
      syncPersonalSession(generation);
      if (!token) {
        setStatus("guest");
        return;
      }
      setStatus("loading");
      const request = new AbortController();
      ac = request;
      fetchMe(request.signal)
      .then((me) => {
        if (!mounted.current || request.signal.aborted || generation !== getSessionGeneration()) return;
        setUser(me);
        setStatus("authed");
      })
      .catch((e) => {
        if (!mounted.current || request.signal.aborted || e?.name === "AbortError") return;
        try { if (generation !== getSessionGeneration()) return; }
        catch { showStorageUnavailable(); return; }
        // Не удалось проверить профиль — это не доказательство выхода.
        retryGeneration = generation;
        setStatus("unavailable");
      });
    };
    loadCurrentSessionRef.current = loadSession;
    const retry = () => {
      if (storageUnavailable.current) { loadSession(); return; }
      try {
        if (retryGeneration !== null && retryGeneration === getSessionGeneration() && getToken()) loadSession();
      } catch { showStorageUnavailable(); }
    };
    retrySessionRef.current = retry;
    const storageChanged = (event: StorageEvent) => {
      const revokedOwner = event.key?.startsWith(SESSION_REVOKE_PREFIX)
        ? event.key.slice(SESSION_REVOKE_PREFIX.length) : null;
      if (event.key !== "yuldash.session" && event.key !== null && revokedOwner === null) return;
      // A queued obsolete event must not reload the current account. The stored
      // value now includes the token pair, whereas generation changes only on
      // login/logout: same-account refresh must not reset the profile or forms.
      try {
        if (event.storageArea && event.storageArea !== localStorage) return;
        if (event.key !== null && event.newValue !== localStorage.getItem(event.key)) return;
        if (revokedOwner !== null && event.newValue === "1") clearSessionData(revokedOwner);
        if (!storageUnavailable.current && observedGeneration.current === getSessionGeneration()) return;
      } catch { showStorageUnavailable(); return; }
      loadSession();
    };
    loadSession();
    if (typeof window !== "undefined") window.addEventListener?.("storage", storageChanged);
    if (typeof window !== "undefined") {
      window.addEventListener?.("online", retry);
      window.addEventListener?.("focus", retry);
    }
    return () => {
      mounted.current = false;
      ac?.abort();
      retrySessionRef.current = () => {};
      loadCurrentSessionRef.current = () => {};
      if (typeof window !== "undefined") window.removeEventListener?.("storage", storageChanged);
      if (typeof window !== "undefined") {
        window.removeEventListener?.("online", retry);
        window.removeEventListener?.("focus", retry);
      }
    };
  }, [applyGuest]);

  const login = useCallback((access: string, refresh: string, me: Me) => {
    let previousOwner: string | null = null;
    try { previousOwner = getSessionGeneration(); } catch { /* Replacement can recover damaged storage. */ }
    const generation = setSession(access, refresh);
    if (previousOwner !== null) clearSessionData(previousOwner);
    if (generation !== getSessionGeneration()) { loadCurrentSessionRef.current(); return; }
    storageUnavailable.current = false;
    observedGeneration.current = generation;
    syncPersonalSession(generation);
    setUser(me);
    setStatus("authed");
  }, []);

  const logout = useCallback(async () => {
    const generation = getSessionGeneration();
    // Пуши гасим ДО выхода: серверу нужен ещё живой токен, чтобы отвязать подписку.
    // Иначе на общем телефоне следующий вошедший получал бы чужие уведомления.
    try {
      await disableWebPush(generation);
    } catch {
      /* не критично — выход важнее */
    }
    if (generation !== getSessionGeneration()) return;
    try {
      await logoutServer(generation);
    } catch {
      /* нет сети / уже протух — всё равно чистим локально */
    }
    if (generation !== getSessionGeneration()) return;
    try { revokeSession(generation); }
    catch (error) {
      if (generation === getSessionGeneration()) {
        storageUnavailable.current = true;
        setUser(null); setStatus("unavailable");
      }
      throw error;
    }
    clearSessionData(generation);
    if (getSessionGeneration() !== revokedGeneration(generation)) {
      loadCurrentSessionRef.current();
      return;
    }
    storageUnavailable.current = false;
    observedGeneration.current = revokedGeneration(generation);
    setUser(null);
    setStatus("guest");
    syncPersonalSession(revokedGeneration(generation));
    if (typeof caches !== "undefined") {
      const clearedGeneration = revokedGeneration(generation);
      void clearSessionCaches(() => clearedGeneration === getSessionGeneration()).catch(() => {});
    }
  }, []);

  const refresh = useCallback(async () => {
    const generation = getSessionGeneration();
    try {
      const me = await fetchMe();
      if (!mounted.current || generation !== getSessionGeneration()) return;
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
