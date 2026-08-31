// ================================================================
//  «Скидки по пути» (coupons.py). Публичная витрина.
//  Вкладки: «Скидки рядом» (GET /coupons, фильтр город-чипами, по умолчанию
//  родной город из me().city) + «Мои купоны» (GET /my/coupons, вход нужен).
//  Карточка: заведение + категория + иконка, discount_text золотым,
//  «осталось N», premium. Активировать → POST /coupons/{id}/activate →
//  крупный моноширинный КОД + дисклеймер. Все состояния, мягкая деградация.
// ================================================================
import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchCoupons,
  fetchMyCoupons,
  activateCoupon,
  reportCoupon,
  type Coupon,
  type CouponActivation,
} from "../api/coupons";
import { LoadingList } from "../components/States";
import ScreenHeader from "../components/ScreenHeader";
import {
  IconCopy,
  IconCheck,
  IconPin,
  IconChevron,
  IconStore,
  IconCar,
  IconStar,
  IconHospital,
  IconGift,
  IconTicket,
  IconWarn,
  IconProfile,
  IconPhone,
  IconFlag,
} from "../components/Icons";
import { track } from "../analytics";

type Tab = "near" | "mine";
type Status = "loading" | "error" | "soon" | "ready";

/** Линиевая иконка по категории заведения (мягкий фолбэк). */
function categoryIcon(cat: string): typeof IconStore {
  const c = (cat || "").toLowerCase();
  if (c.includes("cafe") || c.includes("food") || c.includes("кафе") || c.includes("рестор")) return IconStore;
  if (c.includes("shop") || c.includes("store") || c.includes("магаз")) return IconStore;
  if (c.includes("auto") || c.includes("car") || c.includes("авто")) return IconCar;
  if (c.includes("beauty") || c.includes("салон") || c.includes("красот")) return IconStar;
  if (c.includes("health") || c.includes("med") || c.includes("аптек") || c.includes("здоров")) return IconHospital;
  if (c.includes("fun") || c.includes("entertain") || c.includes("развлеч")) return IconGift;
  return IconTicket;
}

export default function CouponsScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();
  const { user, isAuthed } = useAuth();

  const [tab, setTab] = useState<Tab>("near");
  const [status, setStatus] = useState<Status>("loading");
  const [coupons, setCoupons] = useState<Coupon[]>([]);
  const [city, setCity] = useState<string>(() => (user?.city || "").trim());

  const [mineStatus, setMineStatus] = useState<Status>("loading");
  const [mine, setMine] = useState<CouponActivation[]>([]);

  const [busyId, setBusyId] = useState<number | null>(null);
  const [activated, setActivated] = useState<CouponActivation | null>(null);
  const [copied, setCopied] = useState(false);
  const [reportFor, setReportFor] = useState<number | null>(null);
  const [reportReason, setReportReason] = useState("");
  const [reportBusy, setReportBusy] = useState(false);
  const [reportSent, setReportSent] = useState(false);

  /** Жалоба на купон: ставит его перед глазами админа, но с витрины не снимает. */
  async function sendReport(couponId: number) {
    if (reportBusy) return;
    setReportBusy(true);
    try {
      await reportCoupon(couponId, reportReason.trim());
      setReportFor(null);
      setReportSent(true);
      setReportError("");
    } catch {
      // Человек сообщает о проблеме с купоном — он должен знать, дошло ли.
      // Иначе решит, что пожаловался, и будет ждать ответа, которого не будет.
      setReportError(
        appText(
          "Жалоба не отправилась. Проверь связь и попробуй ещё раз.",
          "Шикәйәт китмәне. Бәйләнеште тикшереп ҡабатла."
        )
      );
    } finally {
      setReportBusy(false);
    }
  }

  /** Жалоба не ушла — сказать: человек ждёт разбирательства. */
  const [reportError, setReportError] = useState("");
  const [actError, setActError] = useState<string | null>(null);

  // ---- Витрина «рядом» ----
  const loadNear = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    fetchCoupons({ signal })
      .then((list) => {
        setCoupons(list);
        setStatus("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setStatus(
          e instanceof ApiError && (e.status === 404 || e.status === 405) ? "soon" : "error"
        );
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    loadNear(ac.signal);
    return () => ac.abort();
  }, [loadNear]);

  // ---- «Мои купоны» ----
  const loadMine = useCallback(
    (signal?: AbortSignal) => {
      if (!isAuthed) return;
      setMineStatus("loading");
      fetchMyCoupons(signal)
        .then((list) => {
          setMine(list);
          setMineStatus("ready");
        })
        .catch((e) => {
          if (signal?.aborted || e?.name === "AbortError") return;
          setMineStatus(
            e instanceof ApiError && (e.status === 404 || e.status === 405) ? "soon" : "error"
          );
        });
    },
    [isAuthed]
  );

  useEffect(() => {
    if (tab !== "mine" || !isAuthed) return;
    const ac = new AbortController();
    loadMine(ac.signal);
    return () => ac.abort();
  }, [tab, isAuthed, loadMine]);

  // Города для чипов: из выдачи + родной город пользователя.
  const cities = useMemo(() => {
    const set = new Set<string>();
    if (user?.city) set.add(user.city.trim());
    coupons.forEach((c) => c.city && set.add(c.city.trim()));
    return Array.from(set).filter(Boolean);
  }, [coupons, user?.city]);

  const visible = useMemo(() => {
    if (!city) return coupons;
    const cl = city.toLowerCase();
    return coupons.filter((c) => (c.city || "").trim().toLowerCase() === cl);
  }, [coupons, city]);

  async function onActivate(c: Coupon) {
    if (!isAuthed) {
      navigate("/login");
      return;
    }
    setBusyId(c.id);
    setActError(null);
    try {
      const res = await activateCoupon(c.id);
      track("coupon_activate");
      setActivated(res);
      setCopied(false);
    } catch (e) {
      const detail = e instanceof ApiError ? e.message : "";
      setActError(
        detail || appText("Не получилось активировать. Попробуй снова.", "Активлаштырып булманы. Ҡабат ҡара.")
      );
    } finally {
      setBusyId(null);
    }
  }

  async function copyCode(code: string) {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1600);
    } catch {
      /* clipboard недоступен — тихо */
    }
  }

  // ---- Экран успеха активации (крупный код) ----
  if (activated) {
    const c = activated.coupon;
    return (
      <>
        <ScreenHeader title={appText("Купон активирован", "Купон әүҙем")} />
        <div className="coupon-code">
          <div className="coupon-code__emoji"><IconTicket size={34} /></div>
          {c && <div className="coupon-code__title">{c.title}</div>}
          {c?.discount_text && (
            <div className="coupon-code__discount">{c.discount_text}</div>
          )}
          <div className="coupon-code__label">
            {appText("Покажи код на кассе", "Кассала кодты күрһәт")}
          </div>
          <div className="coupon-code__value">{activated.code}</div>
          <button type="button" className="btn-soft" onClick={() => copyCode(activated.code)}>
            {copied ? <IconCheck size={18} /> : <IconCopy size={18} />}
            {copied ? appText("Скопировано", "Күсерелде") : appText("Копировать код", "Кодты күсереү")}
          </button>
          <p className="coupon-code__disclaimer">
            {appText(
              "Скидку даёт само заведение. Юлдаш не берёт денег с тебя за купон и не хранит твой телефон при погашении.",
              "Ташламаны заведение үҙе бирә. Юлдаш һинән купон өсөн аҡса алмай һәм ҡулланғанда телефоныңды һаҡламай."
            )}
          </p>
          {c?.partner?.phone && (
            <a className="btn-soft" href={`tel:${c.partner.phone}`} style={{ marginTop: 8 }}>
              <IconPhone size={18} /> {appText("Позвонить в заведение", "Заведениеға шылтыратыу")}
            </a>
          )}
          <button
            type="button"
            className="btn-primary submit-btn"
            onClick={() => {
              setActivated(null);
              setTab("mine");
            }}
          >
            {appText("Мои купоны", "Купондарым")}
          </button>

          {/* «Обещали не то» — жалоба. Купон с витрины не снимаем: одна жалоба бывает
              и наветом конкурента, решает человек, а не счётчик. */}
          {c && (reportFor === c.id ? (
            <div className="act-card" style={{ textAlign: "left" }}>
              <div className="act-card__title">
                <IconFlag size={18} /> {appText("Что не так с этой скидкой?", "Был ташлама менән нимә дөрөҫ түгел?")}
              </div>
              <p className="act-card__text">
                {appText(
                  "Напиши в двух словах. Мы посмотрим сами — скидка пока останется на месте.",
                  "Ике һүҙ менән яҙ. Үҙебеҙ ҡарайбыҙ — ташлама әлегә урынында ҡала."
                )}
              </p>
              <label className="field">
                <input
                  className="field__input"
                  value={reportReason}
                  onChange={(e) => setReportReason(e.target.value)}
                  maxLength={500}
                  placeholder={appText("Например: скидку не дали", "Мәҫәлән: ташлама бирмәнеләр")}
                />
              </label>
              <div className="act-card__actions" style={{ marginTop: 10 }}>
                <button
                  type="button"
                  className="btn-primary"
                  onClick={() => sendReport(c.id)}
                  disabled={reportBusy || !reportReason.trim()}
                >
                  {reportBusy ? appText("Отправляем…", "Ебәрәбеҙ…") : appText("Отправить", "Ебәреү")}
                </button>
                <button type="button" className="btn-ghost" onClick={() => setReportFor(null)}>
                  {appText("Отмена", "Баш тартыу")}
                </button>
              </div>
            </div>
          ) : (
            <button
              type="button"
              className="link-btn"
              style={{ marginTop: 10 }}
              onClick={() => {
                setReportFor(c.id);
                setReportReason("");
              }}
            >
              {reportSent
                ? appText("Спасибо, посмотрим", "Рәхмәт, ҡарайбыҙ")
                : appText("Тут что-то не так — сообщить", "Бында нимәлер дөрөҫ түгел — хәбәр итеү")}
            </button>
          ))}
          {reportError && (
            <div className="notice" role="status">
              {reportError}
            </div>
          )}
        </div>
      </>
    );
  }

  return (
    <>
      <ScreenHeader
        title={appText("Скидки по пути", "Юлда ташламалар")}
        subtitle={appText("Приятные скидки от своих заведений", "Үҙ заведениеларҙан рәхәт ташламалар")}
      />

      <div className="seg" style={{ marginTop: 4 }}>
        <button
          type="button"
          className={"seg__item" + (tab === "near" ? " is-active" : "")}
          onClick={() => setTab("near")}
        >
          {appText("Скидки рядом", "Яҡындағы ташламалар")}
        </button>
        <button
          type="button"
          className={"seg__item" + (tab === "mine" ? " is-active" : "")}
          onClick={() => setTab("mine")}
        >
          {appText("Мои купоны", "Купондарым")}
        </button>
      </div>

      {/* ===== Вкладка: скидки рядом ===== */}
      {tab === "near" && (
        <>
          {status === "loading" && <LoadingList count={3} />}

          {status === "soon" && (
            <div className="state" style={{ paddingTop: 28 }}>
              <div className="state__icon"><IconTicket size={34} /></div>
              <h2>{appText("Скидки скоро", "Ташламалар тиҙҙән")}</h2>
              <p>{appText("Заведения уже подключаются. Загляни чуть позже.", "Заведениелар ҡушыла инде. Аҙыраҡ һуңынан кил.")}</p>
            </div>
          )}

          {status === "error" && (
            <div className="state" style={{ paddingTop: 28 }}>
              <div className="state__icon state__icon--warn"><IconWarn size={34} /></div>
              <h2>{appText("Не получилось загрузить", "Йөкләргә булманы")}</h2>
              <button type="button" className="btn-primary" onClick={() => loadNear()}>
                {appText("Повторить", "Ҡабатлау")}
              </button>
            </div>
          )}

          {status === "ready" && (
            <>
              {cities.length > 0 && (
                <div className="chips" style={{ marginTop: 12 }}>
                  <button
                    type="button"
                    className={"chip" + (city === "" ? " chip--on" : "")}
                    onClick={() => setCity("")}
                  >
                    {appText("Все города", "Бар ҡалалар")}
                  </button>
                  {cities.map((c) => (
                    <button
                      key={c}
                      type="button"
                      className={"chip" + (city === c ? " chip--on" : "")}
                      onClick={() => setCity(c)}
                    >
                      {c}
                    </button>
                  ))}
                </div>
              )}

              {actError && <div className="auth__error">{actError}</div>}

              {visible.length === 0 ? (
                <div className="state" style={{ paddingTop: 20 }}>
                  <div className="state__icon"><IconTicket size={34} /></div>
                  <h2>{appText("Пока нет скидок", "Әле ташламалар юҡ")}</h2>
                  <p>
                    {appText(
                      "В этом городе пока нет активных купонов. Загляни позже или смени город.",
                      "Был ҡалала әле әүҙем купондар юҡ. Һуңынан кил йәки ҡаланы алмаштыр."
                    )}
                  </p>
                </div>
              ) : (
                <div>
                  {visible.map((c) => {
                    const Cat = categoryIcon(c.partner?.category || "");
                    return (
                    <div key={c.id} className="coupon-card">
                      <div className="coupon-card__head">
                        <span className="coupon-card__emoji">
                          <Cat size={22} />
                        </span>
                        <div className="coupon-card__partner">
                          <div className="coupon-card__name">
                            {c.partner?.name || appText("Заведение", "Урын")}
                            {c.premium && <span className="badge badge--gold coupon-card__pro">PREMIUM</span>}
                          </div>
                          {(c.partner?.city || c.partner?.address) && (
                            <div className="coupon-card__place">
                              <IconPin size={13} />{" "}
                              {[c.partner?.city, c.partner?.address].filter(Boolean).join(", ")}
                            </div>
                          )}
                        </div>
                      </div>

                      <div className="coupon-card__title">{c.title}</div>
                      {c.discount_text && (
                        <div className="coupon-card__discount">{c.discount_text}</div>
                      )}
                      {c.description && (
                        <div className="coupon-card__desc">{c.description}</div>
                      )}

                      <div className="coupon-card__foot">
                        {c.remaining != null ? (
                          <span className="coupon-card__left">
                            {appText(`осталось ${c.remaining}`, `${c.remaining} ҡалды`)}
                          </span>
                        ) : (
                          <span className="coupon-card__left">
                            {appText("без ограничений", "сикләүһеҙ")}
                          </span>
                        )}
                        <button
                          type="button"
                          className="btn-primary coupon-card__btn"
                          onClick={() => onActivate(c)}
                          disabled={busyId === c.id}
                        >
                          {busyId === c.id
                            ? appText("…", "…")
                            : appText("Активировать", "Активлаштырыу")}
                        </button>
                      </div>
                    </div>
                    );
                  })}
                </div>
              )}
            </>
          )}
        </>
      )}

      {/* ===== Вкладка: мои купоны ===== */}
      {tab === "mine" && (
        <>
          {!isAuthed ? (
            <div className="state" style={{ paddingTop: 24 }}>
              <div className="state__icon"><IconProfile size={34} /></div>
              <h2>{appText("Войди в Юлдаш", "Юлдашҡа ин")}</h2>
              <p>{appText("Активированные купоны хранятся в профиле — войди, чтобы их видеть.", "Активлаштырылған купондар профилдә һаҡлана — күрер өсөн ин.")}</p>
              <button type="button" className="btn-primary" onClick={() => navigate("/login")}>
                {appText("Войти", "Инеү")}
              </button>
            </div>
          ) : (
            <>
              {mineStatus === "loading" && <LoadingList count={2} />}

              {(mineStatus === "soon" || mineStatus === "error") && (
                <div className="state" style={{ paddingTop: 28 }}>
                  <div className={"state__icon" + (mineStatus === "error" ? " state__icon--warn" : "")}>
                    {mineStatus === "soon" ? <IconTicket size={34} /> : <IconWarn size={34} />}
                  </div>
                  <h2>
                    {mineStatus === "soon"
                      ? appText("Скоро здесь", "Тиҙҙән бында")
                      : appText("Не получилось загрузить", "Йөкләргә булманы")}
                  </h2>
                  {mineStatus === "error" && (
                    <button type="button" className="btn-primary" onClick={() => loadMine()}>
                      {appText("Повторить", "Ҡабатлау")}
                    </button>
                  )}
                </div>
              )}

              {mineStatus === "ready" && (
                <>
                  {mine.length === 0 ? (
                    <div className="state" style={{ paddingTop: 24 }}>
                      <div className="state__icon"><IconTicket size={34} /></div>
                      <h2>{appText("Пока нет купонов", "Әле купондар юҡ")}</h2>
                      <p>{appText("Активируй скидку рядом — код появится здесь.", "Яҡындағы ташламаны активлаштыр — код бында күренер.")}</p>
                      <button type="button" className="btn-primary" onClick={() => setTab("near")}>
                        {appText("К скидкам", "Ташламаларға")}
                      </button>
                    </div>
                  ) : (
                    <div className="list">
                      {mine.map((m) => {
                        const redeemed = m.status === "redeemed";
                        const gone = m.status === "canceled" || m.status === "expired";
                        return (
                          <div key={m.code} className="mycoupon-row">
                            <div className="mycoupon-row__main">
                              <div className="mycoupon-row__title">
                                {m.coupon?.title || appText("Купон", "Купон")}
                              </div>
                              {m.coupon?.discount_text && (
                                <div className="mycoupon-row__discount">
                                  {m.coupon.discount_text}
                                </div>
                              )}
                              <div className="mycoupon-row__code">{m.code}</div>
                            </div>
                            <span
                              className={
                                "badge " +
                                (redeemed ? "badge--mint" : gone ? "badge--danger" : "badge--gold")
                              }
                            >
                              {redeemed
                                ? appText("Использован", "Ҡулланылған")
                                : gone
                                ? appText("Недействителен", "Ғәмәлдә түгел")
                                : appText("Активен", "Әүҙем")}
                            </span>
                          </div>
                        );
                      })}
                    </div>
                  )}
                </>
              )}
            </>
          )}
        </>
      )}

      <button
        type="button"
        className="list-row list-row--link"
        style={{ marginTop: 16 }}
        onClick={() => navigate("/partner")}
      >
        <span className="list-row__icon"><IconStore size={22} /></span>
        <div className="list-row__main">
          <div className="list-row__title">{appText("У меня бизнес", "Минең бизнесым бар")}</div>
          <div className="list-row__sub">
            {appText("Разместить свою скидку в Юлдаше", "Юлдашта үҙ ташламаңды урынлаштыр")}
          </div>
        </div>
        <span className="list-row__chev">
          <IconChevron size={20} />
        </span>
      </button>
    </>
  );
}
