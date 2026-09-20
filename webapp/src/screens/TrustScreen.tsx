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
import { SettingsGroup, SettingsNavRow } from "../components/cabinetUi";
import { IconCheck, IconProfile, IconReceipt, IconShield, IconUsers } from "../components/Icons";
import { SubHeader } from "./ConsentsScreen";

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

  const ladder = [
    appText("Новичок", "Яңы"),
    appText("Знакомый", "Таныш"),
    appText("Проверен", "Тикшерелгән"),
    appText("Свой", "Үҙебеҙҙеке"),
  ];

  return (
    <>
      <SubHeader title={appText("Доверие", "Ышаныс")} onBack={() => navigate(-1)} />
      <div className="cabinet">
        <p className="dl-hint">
          {appText(
            "Юлдаш — поездки между своими. Чем выше доверие, тем шире круг.",
            "Юлдаш — үҙебеҙҙекеләр араһында сәфәрҙәр. Ышаныс юғарыраҡ — түңәрәк киңерәк."
          )}
        </p>

        {state === "loading" && <LoadingList count={2} />}
        {state === "error" && <ErrorState onRetry={() => load()} />}

        {state === "ready" && data && (
          <>
            {/* TrustLevelCard: круг с иконкой уровня (золотой на L3), «Твой уровень», название 24,
                лесенка из четырёх сегментов и «Что тебе доступно». */}
            <section className="trust-card">
              <div className="trust-card__head">
                <span className={"trust-card__badge" + (data.level >= 3 ? " is-top" : "")} aria-hidden>
                  {data.level >= 3 ? <IconUsers size={28} /> : data.level === 2 ? <IconShield size={28} /> : <IconProfile size={28} />}
                </span>
                <span className="trust-card__text">
                  <small>{appText("Твой уровень", "Кимәлең")}</small>
                  <strong>{say(data.title)}</strong>
                </span>
              </div>
              <div className="trust-ladder" aria-hidden>
                {ladder.map((t, i) => (
                  <span key={t} className={"trust-ladder__step" + (i <= data.level ? " is-reached" : "") + (i === data.level ? " is-current" : "")}>
                    <span className="trust-ladder__bar"><span style={{ transitionDelay: `${i * 60}ms` }} /></span>
                    <small>{t}</small>
                  </span>
                ))}
              </div>
              {data.level === 0 && (
                <p className="dl-hint">
                  {appText(
                    "Ты полноправный участник Юлдаша. Уровень открывает новые возможности — двигайся в своём темпе.",
                    "Һин Юлдаштың тулы хоҡуҡлы ҡатнашыусыһы. Кимәл яңы мөмкинлектәр аса — үҙ тиҙлегеңдә бар."
                  )}
                </p>
              )}
              {data.benefits.length > 0 && (
                <div className="trust-benefits">
                  <strong>{appText("Что тебе доступно", "Һиңә нимә асыҡ")}</strong>
                  {data.benefits.map((b, i) => (
                    <span key={i} className="trust-benefit">
                      <IconCheck size={18} />
                      {say(b)}
                    </span>
                  ))}
                </div>
              )}
            </section>

            {/* TrustNextCard: мятная карточка — что откроет следующий уровень и как его получить. */}
            {data.next && (
              <section className="trust-next">
                <div className="trust-next__head">
                  <small>{appText("Следующий уровень", "Киләһе кимәл")}</small>
                  <strong>{say(data.next.title)}</strong>
                  <span>{say(data.next.how)}</span>
                </div>
                {data.next.benefits.length > 0 && (
                  <div className="trust-benefits">
                    {data.next.benefits.map((b, i) => (
                      <span key={i} className="trust-benefit">
                        <IconCheck size={18} />
                        {say(b)}
                      </span>
                    ))}
                  </div>
                )}
                <button type="button" className="btn-primary submit-btn" onClick={openNext}>
                  {data.next.level === 1
                    ? appText("Заполнить профиль", "Профильде тултыр")
                    : data.next.level === 2
                      ? appText("Пройти проверку", "Тикшереүҙе үт")
                      : appText("Ввести код приглашения", "Саҡырыу кодын индер")}
                </button>
              </section>
            )}

            {/* Звать своих может только проверенный: иначе «круг своих» перестанет что-либо значить. */}
            {data.can_invite && (
              <section className="trust-invite">
                <div className="trust-invite__head">
                  <span className="trust-invite__icon" aria-hidden><IconUsers size={22} /></span>
                  <span className="trust-card__text">
                    <strong>{appText("Ты можешь звать своих", "Һин үҙеңдекеләрҙе саҡыра алаһың")}</strong>
                    <small>{appText("Приглашай тех, кому доверяешь", "Ышанған кешеләреңде саҡыр")}</small>
                  </span>
                </div>
                <button type="button" className="btn-soft" onClick={() => navigate("/invites")}>
                  <IconUsers size={18} /> {appText("Позвать своего", "Үҙеңдекен саҡыр")}
                </button>
              </section>
            )}

            <SettingsGroup>
              <SettingsNavRow
                icon={<IconUsers size={24} />}
                title={appText("Позвать своего", "Үҙеңдекен саҡыр")}
                subtitle={appText("Пригласительные коды в круг доверия", "Ышаныс түңәрәгенә саҡырыу кодтары")}
                onClick={() => navigate("/invites")}
              />
              <SettingsNavRow
                icon={<IconReceipt size={24} />}
                title={appText("Согласия и данные", "Ризалыҡтар һәм мәғлүмәт")}
                subtitle={appText("Оферта, политика, геолокация", "Оферта, сәйәсәт, геолокация")}
                onClick={() => navigate("/consents")}
              />
            </SettingsGroup>
          </>
        )}
      </div>
    </>
  );
}
