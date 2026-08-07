import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import { fetchReferral } from "../api/referral";
import { LoadingList, ErrorState } from "../components/States";
import { IconCheck, IconShield } from "../components/Icons";
import { SubHeader } from "./ConsentsScreen";

/**
 * Уровни доверия L0–L3. У бэкенда нет отдельного /me/trust — уровень честно считаем
 * из реальных полей GET /me (verified, номер) и числа приглашённых (GET /referral/me).
 * «Между своими»: чем больше проверок и связей — тем выше круг доверия.
 */
export default function TrustScreen() {
  const { appText } = useLang();
  const { user } = useAuth();
  const navigate = useNavigate();
  const [invited, setInvited] = useState<number | null>(null);
  const [state, setState] = useState<"loading" | "error" | "ready">("loading");

  const load = useCallback((signal?: AbortSignal) => {
    setState("loading");
    fetchReferral(signal)
      .then((r) => {
        setInvited(r.invited);
        setState("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setState("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  const hasRealPhone = !!user && !/^tg\d+$/.test(user.phone);
  const verified = !!user?.verified;
  const inv = invited ?? 0;

  // Текущий уровень 0..3
  let current = 0;
  if (hasRealPhone) current = 1;
  if (verified) current = 2;
  if (verified && inv >= 1) current = 3;

  const levels = [
    {
      n: 0,
      title: appText("Гость", "Ҡунаҡ"),
      gives: appText("Смотришь ленту поездок.", "Сәфәр таҫмаһын ҡарайһың."),
    },
    {
      n: 1,
      title: appText("Сосед", "Күрше"),
      gives: appText("Номер подтверждён — можешь бронировать и создавать поездки.", "Номер раҫланған — бронларға һәм сәфәр яһарға була."),
    },
    {
      n: 2,
      title: appText("Проверенный", "Тикшерелгән"),
      gives: appText("Бейдж «проверен», больше доверия попутчиков.", "«Тикшерелгән» билдәһе, юлдаштарҙан күберәк ышаныс."),
    },
    {
      n: 3,
      title: appText("Свой круг", "Үҙ түңәрәк"),
      gives: appText("Позвал своих — высший уровень доверия и бонусы.", "Үҙеңдекеләрҙе саҡырҙың — иң юғары ышаныс һәм бонустар."),
    },
  ];

  const cur = levels[current];
  const nextLevel = current < 3 ? levels[current + 1] : null;

  return (
    <>
      <SubHeader
        title={appText("Доверие", "Ышаныс")}
        subtitle={appText("Твой круг «между своими»", "«Үҙебеҙ араһында» түңәрәгең")}
        onBack={() => navigate(-1)}
      />

      {state === "loading" && <LoadingList count={3} />}
      {state === "error" && <ErrorState onRetry={() => load()} />}
      {state === "ready" && (
        <>
          <div className="trust-hero">
            <div className="trust-hero__badge">
              <IconShield size={30} />
            </div>
            <div className="trust-hero__level">
              L{current} · {cur.title}
            </div>
            <div className="trust-progress" aria-hidden>
              {[1, 2, 3].map((i) => (
                <span key={i} className={i <= current ? "on" : ""} />
              ))}
            </div>
            {nextLevel ? (
              <p className="trust-hero__next">
                {appText("Следующий уровень — ", "Киләһе кимәл — ")}
                <b>{nextLevel.title}</b>: {nextLevel.gives}
              </p>
            ) : (
              <p className="trust-hero__next">
                {appText("Ты на высшем уровне доверия. Спасибо!", "Һин иң юғары ышаныс кимәлендә. Рәхмәт!")}
              </p>
            )}
          </div>

          <div className="list">
            {levels.map((lv) => {
              const reached = lv.n <= current;
              return (
                <div key={lv.n} className={"list-row trust-row" + (reached ? " reached" : "")}>
                  <div className={"trust-row__dot" + (reached ? " on" : "")}>
                    {reached ? <IconCheck size={16} /> : <span>L{lv.n}</span>}
                  </div>
                  <div className="list-row__main">
                    <div className="list-row__title">
                      L{lv.n} · {lv.title}
                    </div>
                    <div className="list-row__sub">{lv.gives}</div>
                  </div>
                </div>
              );
            })}
          </div>
        </>
      )}
    </>
  );
}
