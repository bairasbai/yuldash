// ================================================================
//  Пожаловаться → /report (RequireAuth). Форма жалобы на попутчика:
//  выбор кого (из тех, с кем была поездка — GET /reportable-users, или
//  предзаполнено ?user=&name= из профиля/поездки) + категория (§9,
//  закрытый перечень) + описание → POST /reports. Анонимно: цель не
//  видит автора. Опционально сразу заблокировать. Спасибо-состояние.
//  Все состояния; мягкая деградация 404/405 (до мержа release).
// ================================================================
import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { useLang } from "../i18n/lang";
import {
  blockUser,
  fetchReportableUsers,
  sendReport,
  type ReportableUser,
  type ReportCategory,
} from "../api/safety";
import { ApiError } from "../api/client";
import { LoadingList, ErrorState } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconCheck, IconShield, IconCar } from "../components/Icons";

type Status = "loading" | "error" | "form" | "sent";

export default function ReportScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();
  const [params] = useSearchParams();

  // Предзаполнение из профиля водителя / карточки поездки.
  const prefillId = params.get("user") ? Number(params.get("user")) : null;
  const prefillName = params.get("name") ?? "";
  const bookingId = params.get("booking") ? Number(params.get("booking")) : null;
  const orderId = params.get("order") ? Number(params.get("order")) : null;
  const hasContext = bookingId != null || orderId != null;

  const [status, setStatus] = useState<Status>(prefillId || hasContext ? "form" : "loading");
  const [people, setPeople] = useState<ReportableUser[]>([]);
  const [targetId, setTargetId] = useState<number | null>(prefillId);
  const [category, setCategory] = useState<ReportCategory | null>(null);
  const [reason, setReason] = useState("");
  const [alsoBlock, setAlsoBlock] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const categories: { key: ReportCategory; label: string }[] = useMemo(
    () => [
      { key: "rude", label: appText("Грубость", "Ҡупаллыҡ") },
      { key: "dangerous_driving", label: appText("Опасное вождение", "Хәүефле йөрөтөү") },
      { key: "safety_threat", label: appText("Угроза безопасности", "Именлеккә ҡурҡыныс") },
      { key: "price_fraud", label: appText("Обман с ценой", "Хаҡ менән алдау") },
      { key: "no_show", label: appText("Не приехал", "Килмәне") },
      { key: "dirty_car", label: appText("Грязная машина", "Бысраҡ машина") },
      { key: "late", label: appText("Опоздание", "Һуңланы") },
      { key: "other", label: appText("Другое", "Башҡа") },
    ],
    [appText]
  );

  // Список «на кого можно пожаловаться» нужен только когда цель не задана заранее.
  const load = useCallback(
    (signal?: AbortSignal) => {
      if (prefillId || hasContext) return;
      setStatus("loading");
      fetchReportableUsers(signal)
        .then((list) => {
          setPeople(list);
          setStatus("form");
        })
        .catch((e) => {
          if (signal?.aborted || e?.name === "AbortError") return;
          if (e instanceof ApiError && (e.status === 404 || e.status === 405)) {
            setPeople([]);
            setStatus("form");
            return;
          }
          setStatus("error");
        });
    },
    [prefillId, hasContext]
  );

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  // Кнопка активна, когда выбрана категория и (есть контекст поездки, или выбрана цель).
  const canSend = category != null && (hasContext || targetId != null) && !submitting;

  async function submit() {
    if (!category) return;
    setSubmitting(true);
    setError(null);
    try {
      await sendReport({
        target_user_id: hasContext ? null : targetId,
        category,
        reason,
        booking_id: bookingId,
        order_id: orderId,
      });
      if (alsoBlock && targetId != null) {
        try {
          await blockUser(targetId);
        } catch {
          /* блокировка — приятный бонус; провал не рушит жалобу */
        }
      }
      setStatus("sent");
    } catch (e) {
      const notReady = e instanceof ApiError && (e.status === 404 || e.status === 405);
      setError(
        notReady
          ? appText(
              "Жалобы скоро заработают. Пока напиши нам в поддержку — разберёмся.",
              "Зарланыуҙар тиҙҙән эшләй. Хәҙергә ярҙамға яҙ — хәл итәбеҙ."
            )
          : appText(
              "Не получилось отправить жалобу. Проверь связь и попробуй ещё раз.",
              "Зарланыуҙы ебәреп булманы. Бәйләнеште тикшереп, ҡабат ҡара."
            )
      );
    } finally {
      setSubmitting(false);
    }
  }

  // --- Спасибо-состояние ---
  if (status === "sent") {
    return (
      <>
        <SubHeader
          title={appText("Жалоба отправлена", "Зарланыу ебәрелде")}
          onBack={() => navigate("/settings")}
        />
        <div className="state state--big state--ok">
          <div className="state__icon"><IconShield size={34} /></div>
          <h2>{appText("Спасибо, что сказал", "Әйткәнең өсөн рәхмәт")}</h2>
          <p>
            {appText(
              "Мы разберёмся по-человечески. Жалоба анонимна — попутчик не узнает, кто её оставил.",
              "Кешеләрсә хәл итәбеҙ. Зарланыу аноним — юлдаш кем яҙғанын белмәй."
            )}
          </p>
          <button type="button" className="btn-primary" onClick={() => navigate("/settings")}>
            {appText("Готово", "Әҙер")}
          </button>
        </div>
      </>
    );
  }

  return (
    <>
      <SubHeader
        title={appText("Пожаловаться", "Зарланырға")}
        subtitle={appText("Анонимно и по-соседски", "Аноним һәм күршеләрсә")}
        onBack={() => navigate(-1)}
      />

      {status === "loading" && <LoadingList count={2} />}
      {status === "error" && <ErrorState onRetry={() => load()} />}

      {status === "form" && (
        <div className="form">
          {/* На кого жалоба */}
          {prefillId && prefillName ? (
            <div className="report-target">
              <span className="report-target__label">{appText("На кого", "Кемгә")}</span>
              <span className="report-target__name">{prefillName}</span>
            </div>
          ) : hasContext ? (
            <div className="report-target">
              <span className="report-target__label">{appText("На кого", "Кемгә")}</span>
              <span className="report-target__name">
                {appText("Второй участник поездки", "Сәфәрҙең икенсе яғы")}
              </span>
            </div>
          ) : people.length === 0 ? (
            <div className="state" style={{ paddingTop: 8 }}>
              <div className="state__icon"><IconCar size={34} /></div>
              <h2>{appText("Пока не на кого жаловаться", "Хәҙергә зарланырға кем юҡ")}</h2>
              <p>
                {appText(
                  "Пожаловаться можно на попутчика, с которым уже была поездка. Как проедете вместе — здесь появится выбор.",
                  "Бергә сәфәр булған юлдашҡа зарланырға мөмкин. Бергә барғас — бында һайлау сыға."
                )}
              </p>
            </div>
          ) : (
            <label className="field">
              <span className="field__label">{appText("На кого жалоба", "Кемгә зарланыу")}</span>
              <select
                className="field__input"
                value={targetId ?? ""}
                onChange={(e) => setTargetId(e.target.value ? Number(e.target.value) : null)}
              >
                <option value="">{appText("Выбери попутчика", "Юлдашты һайла")}</option>
                {people.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </select>
            </label>
          )}

          {/* Категория и описание показываем, когда есть кому адресовать жалобу */}
          {(prefillId || hasContext || people.length > 0) && (
            <>
              <span className="field__label" style={{ marginTop: 4 }}>
                {appText("Что случилось", "Ни булды")}
              </span>
              <div className="opt-grid">
                {categories.map((c) => (
                  <button
                    key={c.key}
                    type="button"
                    className={"opt-chip" + (category === c.key ? " is-active" : "")}
                    onClick={() => setCategory(c.key)}
                    aria-pressed={category === c.key}
                  >
                    {category === c.key && <IconCheck size={16} />}
                    {c.label}
                  </button>
                ))}
              </div>

              <label className="field">
                <span className="field__label">{appText("Опиши подробнее", "Тулыраҡ яҙ")}</span>
                <textarea
                  className="field__input field__area"
                  value={reason}
                  onChange={(e) => setReason(e.target.value.slice(0, 1000))}
                  placeholder={appText(
                    "Расскажи, что произошло — это поможет разобраться.",
                    "Ни булғанын яҙ — был хәл итергә ярҙам итә."
                  )}
                  rows={4}
                />
                <span className="field__hint">
                  {appText(
                    "Без оскорблений — просто по фактам. Жалоба анонимна.",
                    "Мыҫҡыллауһыҙ — тик факттар буйынса. Зарланыу аноним."
                  )}
                </span>
              </label>

              {targetId != null && !hasContext && (
                <label className="list-row list-row--check">
                  <div className="list-row__main">
                    <div className="list-row__title">{appText("Заблокировать тоже", "Шулай уҡ блокларға")}</div>
                    <div className="list-row__sub">
                      {appText("Не показывать друг другу поездки", "Сәфәрҙәрҙе бер-береңә күрһәтмәҫкә")}
                    </div>
                  </div>
                  <input
                    type="checkbox"
                    className="checkbox"
                    checked={alsoBlock}
                    onChange={(e) => setAlsoBlock(e.target.checked)}
                    aria-label={appText("Заблокировать тоже", "Шулай уҡ блокларға")}
                  />
                </label>
              )}

              {error && <div className="danger-zone__err">{error}</div>}

              <button type="button" className="btn-primary" disabled={!canSend} onClick={() => void submit()}>
                {submitting
                  ? appText("Отправляем…", "Ебәрәбеҙ…")
                  : appText("Отправить жалобу", "Зарланыуҙы ебәрергә")}
              </button>
            </>
          )}
        </div>
      )}
    </>
  );
}
