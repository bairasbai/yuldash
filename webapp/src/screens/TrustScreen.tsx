// ================================================================
//  «Доверие» — уровень L0–L3 и путь к следующему.
//  Зеркало Android TrustScreens.kt + backend trust.py: GET /me/trust.
//
//  Раньше уровень тут ВЫЧИСЛЯЛСЯ на клиенте: по полю «проверен»
//  и числу приглашённых. В коде даже стояло «у бэкенда нет отдельного
//  /me/trust» — а он есть с самого начала, и приложение читает
//  именно его. Значит человек на сайте видел не свой уровень,
//  а нашу догадку о нём: у сервера свои правила (кто пригласил,
//  какие проверки пройдены), и они не совпадали с догадкой
//  (сверка с Android, 2026-08-30).
//
//  Названия уровней и то, что каждый даёт, приходят с сервера сразу
//  на двух языках: лестница доверия — часть продукта, и она должна
//  звучать одинаково в приложении, в вебе и в пуше.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { fetchMyTrust, type TrustPhrase, type TrustSummary } from "../api/trust";
import { LoadingList, ErrorState } from "../components/States";
import { IconCheck, IconChevron, IconProfile, IconReceipt, IconShield } from "../components/Icons";
import { SubHeader } from "./ConsentsScreen";

/** Порядок уровней фиксирован: 0 → 3. Названия каждого берём с сервера. */
const LEVELS = [0, 1, 2, 3];

export default function TrustScreen() {
  const { appText, lang } = useLang();
  /** Строки с сервера приходят объектом {ru, ba} — выбираем нужную сами. */
  const say = (p: TrustPhrase) => (lang === "ba" ? p.ba : p.ru);
  const navigate = useNavigate();
  const [data, setData] = useState<TrustSummary | null>(null);
  const [state, setState] = useState<"loading" | "error" | "ready">("loading");

  const openNext = () => {
    const level = data?.next?.level;
    if (level === 1) navigate("/profile/edit");
    else if (level === 2) navigate("/verify-driver");
    else if (level != null) navigate("/invites");
  };

  const load = useCallback((signal?: AbortSignal) => {
    setState("loading");
    fetchMyTrust(signal)
      .then((t) => {
        setData(t);
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

  return (
    <>
      <SubHeader
        title={appText("Доверие", "Ышаныс")}
        subtitle={appText("Твой круг «между своими»", "«Үҙебеҙ араһында» түңәрәгең")}
        onBack={() => navigate(-1)}
      />

      {state === "loading" && <LoadingList count={3} />}
      {state === "error" && <ErrorState onRetry={() => load()} />}

      {state === "ready" && data && (
        <>
          <div className="trust-hero">
            <div className="trust-hero__badge">
              <IconShield size={30} />
            </div>
            <div className="trust-hero__level">
              L{data.level} · {say(data.title)}
            </div>
            <div className="trust-progress" aria-hidden>
              {[1, 2, 3].map((i) => (
                <span key={i} className={i <= data.level ? "on" : ""} />
              ))}
            </div>
            {data.next ? (
              <p className="trust-hero__next">
                {appText("Следующий уровень — ", "Киләһе кимәл — ")}
                <b>{say(data.next.title)}</b>: {say(data.next.how)}
              </p>
            ) : (
              <p className="trust-hero__next">
                {appText("Ты на высшем уровне доверия. Спасибо!", "Һин иң юғары ышаныс кимәлендә. Рәхмәт!")}
              </p>
            )}
          </div>

          {/* Что даёт нынешний уровень — списком, а не одной фразой: это его смысл. */}
          <div className="list">
            {data.benefits.map((b, i) => (
              <div key={i} className="list-row trust-row reached">
                <div className="trust-row__dot on">
                  <IconCheck size={16} />
                </div>
                <div className="list-row__main">
                  <div className="list-row__sub">{say(b)}</div>
                </div>
              </div>
            ))}
          </div>

          {/* Что откроет следующий уровень. Показываем ДО того, как человек его получит:
              иначе «поднимись выше» это просьба без причины. */}
          {data.next && (
            <>
              <h2 className="section-title">
                {appText("Что даст следующий уровень", "Киләһе кимәл нимә бирә")}
              </h2>
              <div className="list">
                {data.next.benefits.map((b, i) => (
                  <div key={i} className="list-row trust-row">
                    <div className="trust-row__dot">
                      <span>L{data.next?.level}</span>
                    </div>
                    <div className="list-row__main">
                      <div className="list-row__sub">{say(b)}</div>
                    </div>
                  </div>
                ))}
              </div>
              <button type="button" className="btn-primary trust-next-action" onClick={openNext}>
                {data.next.level === 1
                  ? appText("Заполнить профиль", "Профильде тултыр")
                  : data.next.level === 2
                    ? appText("Пройти проверку", "Тикшереүҙе үт")
                    : appText("Ввести код приглашения", "Саҡырыу кодын индер")}
              </button>
            </>
          )}

          {/* Лестница целиком — чтобы видеть, где ты и сколько ещё впереди. */}
          <div className="list">
            {LEVELS.map((n) => {
              const reached = n <= data.level;
              const isNext = data.next?.level === n;
              return (
                <div key={n} className={"list-row trust-row" + (reached ? " reached" : "")}>
                  <div className={"trust-row__dot" + (reached ? " on" : "")}>
                    {reached ? <IconCheck size={16} /> : <span>L{n}</span>}
                  </div>
                  <div className="list-row__main">
                    <div className="list-row__title">
                      L{n}
                      {n === data.level ? ` · ${say(data.title)}` : ""}
                      {isNext && data.next ? ` · ${say(data.next.title)}` : ""}
                    </div>
                  </div>
                </div>
              );
            })}
          </div>

          {/* Звать своих может только проверенный: иначе «круг своих» перестанет
              что-либо значить. Кнопка появляется ровно тогда, когда право есть. */}
          {data.can_invite && (
            <button
              type="button"
              className="btn-primary"
              style={{ marginTop: 14 }}
              onClick={() => navigate("/invites")}
            >
              {appText("Позвать своего", "Үҙеңдекен саҡырыу")}
            </button>
          )}

          <div className="list trust-links">
            <button type="button" className="list-row" onClick={() => navigate("/invites")}>
              <span className="trust-row__dot"><IconProfile size={16} /></span>
              <span className="list-row__main">
                <span className="list-row__title">{appText("Позвать своего", "Үҙеңдекен саҡыр")}</span>
                <span className="list-row__sub">{appText("Пригласительные коды в круг доверия", "Ышаныс түңәрәгенә саҡырыу кодтары")}</span>
              </span>
              <IconChevron size={18} />
            </button>
            <button type="button" className="list-row" onClick={() => navigate("/consents")}>
              <span className="trust-row__dot"><IconReceipt size={16} /></span>
              <span className="list-row__main">
                <span className="list-row__title">{appText("Согласия и данные", "Ризалыҡтар һәм мәғлүмәт")}</span>
                <span className="list-row__sub">{appText("Оферта, политика, геолокация", "Оферта, сәйәсәт, геолокация")}</span>
              </span>
              <IconChevron size={18} />
            </button>
          </div>
        </>
      )}
    </>
  );
}
