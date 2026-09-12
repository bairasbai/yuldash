// ================================================================
//  Одна очередь модерации → /admin/moderation (RequireAdmin).
//  Зеркало Android AdminModerationScreen.kt.
//
//  Смысл: «что я ещё не смотрел» одним списком — бизнесы на проверке
//  и купоны без решения. Порядок задаёт сервер: задержанные
//  автопроверкой (их не видят люди) → с жалобами → просто новые.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  approveCoupon,
  blockCoupon,
  fetchModerationQueue,
  type AdminCoupon,
  type AdminPartner,
} from "../api/admin";
import { SubHeader } from "./ConsentsScreen";
import { RideCardSkeleton } from "../components/States";
import { AdminIntro, ListedEmpty, ListedError } from "../components/adminUi";

type State = "loading" | "error" | "ready";

export default function AdminModerationScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [state, setState] = useState<State>("loading");
  const [partners, setPartners] = useState<AdminPartner[]>([]);
  const [coupons, setCoupons] = useState<AdminCoupon[]>([]);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [rejectFor, setRejectFor] = useState<string | null>(null);
  const [reason, setReason] = useState("");

  const load = useCallback((signal?: AbortSignal) => {
    setState("loading");
    fetchModerationQueue(100, signal)
      .then((q) => {
        setPartners(q.partners);
        setCoupons(q.coupons);
        setState("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        if (e instanceof ApiError && e.status === 404) {
          setPartners([]);
          setCoupons([]);
          setState("ready");
        } else setState("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  async function act(key: string, fn: () => Promise<unknown>) {
    setBusyId(key);
    try {
      await fn();
      setRejectFor(null);
      setReason("");
      load();
    } finally {
      setBusyId(null);
    }
  }

  const total = partners.length + coupons.length;

  /** Метка автопроверки → слово, понятное человеку. */
  function flagLabel(flag: string): string {
    if (flag === "contact") return appText("телефон или ссылка", "телефон йәки һылтанма");
    if (flag === "abuse") return appText("резкие слова", "ҡаты һүҙҙәр");
    if (flag === "warn") return appText("похоже на развод", "алдау һымаҡ");
    return appText("метка проверки", "тикшереү билдәһе");
  }

  /** Почему купон в очереди — человеческим языком, а не кодом состояния. */
  function reviewReason(c: AdminCoupon): { tone: string; text: string } {
    if (c.review === "held")
      return {
        tone: "red",
        text: appText(`Задержан проверкой (${flagLabel(c.review_flag)}) — людям не виден`, `Тикшереү тотто (${flagLabel(c.review_flag)}) — кешеләргә күренмәй`),
      };
    if (c.reports_count > 0)
      return {
        tone: "warn",
        text: appText(`Жалоб: ${c.reports_count} — люди говорят, что тут что-то не так`, `Зар: ${c.reports_count} — кешеләр ниҙер дөрөҫ түгел ти`),
      };
    return { tone: "green", text: appText("Виден людям, но ты его ещё не смотрел", "Кешеләргә күренә, әммә һин ҡарамағанһың") };
  }

  return (
    <>
      <SubHeader title={appText("Модерация", "Тикшереү")} onBack={() => navigate(-1)} />
      <div className="alist">
        <AdminIntro>
          {appText(
            "Всё, что ещё не смотрел. Сверху — то, что ждёт тебя: задержанное проверкой и то, на что пожаловались. Ниже — уже видное людям, но не проверенное.",
            "Һин ҡарамағандың бөтәһе. Өҫтә — һине көткәне: тикшереү тотҡаны һәм зарланғаны. Аҫта — кешеләргә күренә, әммә тикшерелмәгәне."
          )}
        </AdminIntro>

        {/* Бизнесы одобряются на своём экране — здесь только плашка-счётчик с переходом, как в приложении. */}
        {partners.length > 0 && (
          <div className="queue-section">
            <strong>
              {appText(`Бизнесы ждут первого одобрения: ${partners.length}`, `Бизнестар беренсе раҫлауҙы көтә: ${partners.length}`)}
            </strong>
            <button type="button" className="btn-ghost queue-section__go" onClick={() => navigate("/admin/partners")}>
              {appText("Открыть список бизнесов", "Бизнестар исемлеген асыу")}
            </button>
          </div>
        )}

        {state === "loading" && (
          <>
            <RideCardSkeleton />
            <RideCardSkeleton />
          </>
        )}
        {state === "error" && <ListedError onRetry={() => load()} />}

        {state === "ready" && total === 0 && (
          <ListedEmpty
            title={appText("Всё проверено", "Бөтәһе тикшерелгән")}
            subtitle={appText("Новые купоны и бизнесы появятся здесь сами.", "Яңы купондар һәм бизнестар бында үҙҙәре күренер.")}
          />
        )}

        {state === "ready" &&
          coupons.map((c, i) => {
            const key = `c${c.id}`;
            const why = reviewReason(c);
            return (
              <article key={key} className="acard" style={{ animationDelay: `calc(var(--cascade-in) * ${Math.min(i, 6)})` }}>
                <span className={`atag atag--${why.tone} atag--wrap`}>{why.text}</span>
                <strong className="acard__title">{c.title}</strong>
                {c.discount_text && <span className="atext atext--green acard__caption">{c.discount_text}</span>}
                {c.description && <span className="acard__sub">{c.description}</span>}
                {/* Название бизнеса и город — данные, а не надпись: переводить нечего. */}
                <span className="acard__sub">{c.partner_name} · {c.city}</span>

                {rejectFor === key ? (
                  /* Снятие купона: причину обязательно — её увидит владелец бизнеса и сможет поправить. */
                  <div className="settings-confirm settings-confirm--card">
                    <strong>{appText("Снять купон", "Купонды алыу")}</strong>
                    <span>
                      {appText(
                        "Напиши причину — её увидит владелец бизнеса и сможет поправить текст.",
                        "Сәбәбен яҙ — уны бизнес хужаһы күрер һәм текстты төҙәтә алыр."
                      )}
                    </span>
                    <label className="field">
                      <input
                        className="field__input"
                        value={reason}
                        onChange={(e) => setReason(e.target.value.slice(0, 500))}
                        placeholder={appText("Например: скидки на деле нет", "Мәҫәлән: ташлама ысынында юҡ")}
                        autoFocus
                      />
                    </label>
                    <div className="settings-confirm__row">
                      <button
                        type="button"
                        className="btn-ghost settings-confirm__muted"
                        onClick={() => {
                          setRejectFor(null);
                          setReason("");
                        }}
                      >
                        {appText("Отмена", "Кире ҡағыу")}
                      </button>
                      <button
                        type="button"
                        className="btn-ghost settings-confirm__danger"
                        onClick={() => act(key, () => blockCoupon(c.id, reason.trim()))}
                        disabled={busyId === key || !reason.trim()}
                      >
                        {appText("Снять", "Алыу")}
                      </button>
                    </div>
                  </div>
                ) : (
                  <div className="acard__actions">
                    <button type="button" className="abtn abtn--tiny" onClick={() => act(key, () => approveCoupon(c.id))} disabled={busyId === key}>
                      {appText("Всё в порядке", "Бөтәһе яҡшы")}
                    </button>
                    <button
                      type="button"
                      className="abtn abtn--tiny abtn--outline abtn--red"
                      onClick={() => {
                        setRejectFor(key);
                        setReason("");
                      }}
                      disabled={busyId === key}
                    >
                      {appText("Снять", "Алыу")}
                    </button>
                  </div>
                )}
              </article>
            );
          })}
      </div>
    </>
  );
}
