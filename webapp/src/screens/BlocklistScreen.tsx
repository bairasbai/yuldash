// ================================================================
//  Чёрный список → /blocklist (RequireAuth). Кого пользователь
//  заблокировал (GET /blocks) + разблокировать (DELETE /blocks/{id}).
//  Все состояния: загрузка / ошибка / пусто / список. Мягкая
//  деградация 404/405 (до мержа release) → честное «пусто».
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { fetchBlocks, unblockUser, type BlockedUser } from "../api/safety";
import { ApiError } from "../api/client";
import { LoadingList, ErrorState } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconBlock, IconShield } from "../components/Icons";

type Status = "loading" | "error" | "ready";

function initials(name: string): string {
  const p = name.trim().split(/\s+/).filter(Boolean);
  return p.length ? (p[0][0] + (p[1]?.[0] ?? "")).toUpperCase() : "?";
}

export default function BlocklistScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [status, setStatus] = useState<Status>("loading");
  const [blocks, setBlocks] = useState<BlockedUser[]>([]);
  const [busy, setBusy] = useState<number | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    fetchBlocks(signal)
      .then((list) => {
        setBlocks(list);
        setStatus("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        // До мержа release эндпоинта может не быть → показываем пустое, не ошибку.
        if (e instanceof ApiError && (e.status === 404 || e.status === 405)) {
          setBlocks([]);
          setStatus("ready");
          return;
        }
        setStatus("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  async function unblock(u: BlockedUser) {
    setBusy(u.blocked_user_id);
    // Оптимистично убираем из списка; при ошибке возвращаем.
    const prev = blocks;
    setBlocks((b) => b.filter((x) => x.blocked_user_id !== u.blocked_user_id));
    try {
      await unblockUser(u.blocked_user_id);
    } catch {
      setBlocks(prev); // не вышло — вернём карточку на место
    } finally {
      setBusy(null);
    }
  }

  return (
    <>
      <SubHeader
        title={appText("Чёрный список", "Ҡара исемлек")}
        subtitle={appText("Кого ты заблокировал", "Кемде блокланың")}
        onBack={() => navigate(-1)}
      />

      {status === "loading" && <LoadingList count={3} />}
      {status === "error" && <ErrorState onRetry={() => load()} />}

      {status === "ready" && blocks.length === 0 && (
        <div className="state state--big">
          <div className="state__icon"><IconShield size={34} /></div>
          <h2>{appText("Никто не заблокирован", "Бер кем дә блокланмаған")}</h2>
          <p>
            {appText(
              "Заблокированные попутчики не будут видеть твои поездки и писать тебе. Пока список пуст — и хорошо.",
              "Блокланған юлдаштар сәфәрҙәреңде күрмәй һәм һиңә яҙмай. Хәҙергә исемлек буш — яҡшы."
            )}
          </p>
        </div>
      )}

      {status === "ready" && blocks.length > 0 && (
        <div className="list">
          {blocks.map((u) => (
            <div key={u.blocked_user_id} className="list-row">
              <span className="list-row__icon">{initials(u.name)}</span>
              <div className="list-row__main">
                <div className="list-row__title">{u.name}</div>
                <div className="list-row__sub">{appText("Заблокирован", "Блокланған")}</div>
              </div>
              <button
                type="button"
                className="btn-soft btn-soft--sm"
                disabled={busy === u.blocked_user_id}
                onClick={() => void unblock(u)}
              >
                {busy === u.blocked_user_id
                  ? appText("…", "…")
                  : appText("Разблокировать", "Блоктан сығарырға")}
              </button>
            </div>
          ))}
        </div>
      )}

      {status === "ready" && (
        <p className="receipt__foot" style={{ display: "flex", alignItems: "center", justifyContent: "center", gap: 8 }}>
          <IconBlock size={16} />
          {appText(
            "Заблокировать можно на странице жалобы или в профиле попутчика.",
            "Блокларға зарланыу битендә йәки юлдаш профилендә мөмкин."
          )}
        </p>
      )}
    </>
  );
}
