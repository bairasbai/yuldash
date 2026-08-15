// ================================================================
//  Boost — поднятие своей поездки в ленте. RequireAuth.
//  Планы (GET /boost/plans) + выбор поездки (GET /driver/rides) +
//  оплата (POST /boost/create). На вебе:
//   • yookassa → редирект на confirmation_url, назад → «Проверить оплату»
//     (GET /payments/{id}/status);
//   • sbp_manual → реквизиты СБП «на доверии», админ подтвердит;
//   • mock/dev → сразу succeeded.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchBoostPlans,
  createBoost,
  fetchPaymentStatus,
  type BoostPlan,
  type BoostCreateResult,
} from "../api/boost";
import { fetchDriverRides } from "../api/driver";
import type { Ride } from "../api/rides";
import { LoadingList } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import SbpPay from "../components/SbpPay";
import { formatWhen } from "../utils/format";
import { IconArrow, IconRocket, IconCheck, IconClock, IconWarn, IconCar } from "../components/Icons";
import { rememberPayment } from "../utils/pendingPayment";

type Status = "loading" | "error" | "soon" | "ready";

export default function BoostScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();
  const location = useLocation();
  const preRideId = (location.state as { rideId?: number } | null)?.rideId ?? null;

  const [status, setStatus] = useState<Status>("loading");
  const [plans, setPlans] = useState<BoostPlan[]>([]);
  const [rides, setRides] = useState<Ride[]>([]);
  const [rideId, setRideId] = useState<number | null>(preRideId);
  const [tier, setTier] = useState<string | null>(null);

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<BoostCreateResult | null>(null);
  const [checking, setChecking] = useState(false);

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    Promise.all([
      fetchBoostPlans(signal),
      fetchDriverRides("active", signal).catch(() => [] as Ride[]),
    ])
      .then(([pl, rd]) => {
        setPlans(pl);
        setRides(rd);
        if (pl.length && !tier) setTier(pl[0].tier);
        setStatus("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setStatus(e instanceof ApiError && e.status === 404 ? "soon" : "error");
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  // Предвыбор поездки из state (после публикации) — когда список подгрузился.
  useEffect(() => {
    if (rideId == null && rides.length === 1) setRideId(rides[0].id);
  }, [rides, rideId]);

  const canPay = rideId != null && tier != null && !busy;

  async function pay() {
    if (!canPay || rideId == null || tier == null) return;
    setBusy(true);
    setError(null);
    try {
      const res = await createBoost(rideId, tier);
      setResult(res);
      // ЮKassa: уводим в браузерную оплату.
      if (res.status === "pending" && res.method === "yookassa" && res.confirmation_url) {
        // Уходим в банк целиком — номер платежа в памяти не переживёт возврата.
        rememberPayment(res.payment_id, "boost", "/driver");
        window.location.href = res.confirmation_url;
        return;
      }
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось создать оплату. Попробуй снова.", "Түләү булдырырға булманы. Ҡабат ҡара.") // DRAFT
      );
    } finally {
      setBusy(false);
    }
  }

  async function recheck() {
    if (!result) return;
    setChecking(true);
    try {
      const s = await fetchPaymentStatus(result.payment_id);
      if (s.status === "succeeded") {
        setResult({ ...result, status: "succeeded", boosted_until: s.boosted_until ?? null });
      }
    } catch {
      /* оставим как есть, пользователь повторит */
    } finally {
      setChecking(false);
    }
  }

  // ---- Успех ----
  if (result?.status === "succeeded") {
    return (
      <>
        <SubHeader title={appText("Поездка поднята", "Сәфәр күтәрелде")} onBack={() => navigate("/driver")} />
        <div className="state">
          <div className="state__icon"><IconRocket size={34} /></div>
          <h2>{appText("Готово! Ты в топе ленты", "Әҙер! Һин таҫма башында")}</h2>
          <p>
            {appText(
              "Твоя поездка показывается выше других — пассажиры увидят её первой.",
              "Сәфәрең башҡаларҙан юғарыраҡ күренә — юлаусылар уны беренсе күрер."
            )}
          </p>
          <button type="button" className="btn-primary" onClick={() => navigate("/driver")}>
            {appText("В кабинет водителя", "Йөрөтөүсе кабинетына")}
          </button>
        </div>
      </>
    );
  }

  // ---- СБП «на доверии»: реквизиты ----
  if (result?.status === "pending" && result.method === "sbp_manual" && result.payee) {
    return (
      <>
        <SubHeader title={appText("Оплата поднятия", "Күтәреү түләүе")} onBack={() => navigate("/driver")} />
        <div className="pay-sbp">
          <div className="pay-sbp__amount">{result.amount?.toLocaleString("ru-RU")} ₽</div>
          <p className="pay-sbp__hint">
            {appText(
              "Переведи по номеру через СБП. Как получим — поднимем поездку (по-соседски, на доверии).",
              "СБП аша номерға күсер. Алғас — сәфәрҙе күтәрәбеҙ (күршеләрсә, ышаныс менән)."
            )}
          </p>
          <SbpPay
            phone={result.payee.phone}
            bank={result.payee.bank}
            name={result.payee.name}
            amountRub={result.amount}
          />
          <button type="button" className="btn-primary submit-btn" onClick={recheck} disabled={checking}>
            {checking ? appText("Проверяем…", "Тикшерәбеҙ…") : appText("Я оплатил", "Түләнем")}
          </button>
          <p className="receipt__foot">
            {appText(
              "После подтверждения оплаты поездка поднимется автоматически.",
              "Түләү раҫланғас сәфәр үҙе күтәрелә."
            )}
          </p>
        </div>
      </>
    );
  }

  // ---- ЮKassa pending (вернулись из браузера) ----
  if (result?.status === "pending") {
    return (
      <>
        <SubHeader title={appText("Ждём оплату", "Түләүҙе көтәбеҙ")} onBack={() => navigate("/driver")} />
        <div className="state">
          <div className="state__icon"><IconClock size={34} /></div>
          <h2>{appText("Оплата обрабатывается", "Түләү эшкәртелә")}</h2>
          <p>
            {appText(
              "Если ты уже оплатил — нажми «Проверить». Поднятие включится сразу после подтверждения.",
              "Түләнең икән — «Тикшереү»гә баҫ. Раҫланғас күтәреү шунда уҡ эшләй."
            )}
          </p>
          <button type="button" className="btn-primary" onClick={recheck} disabled={checking}>
            {checking ? appText("Проверяем…", "Тикшерәбеҙ…") : appText("Проверить оплату", "Түләүҙе тикшереү")}
          </button>
        </div>
      </>
    );
  }

  return (
    <>
      <SubHeader
        title={appText("Поднять поездку", "Сәфәр күтәреү")}
        subtitle={appText("Покажем её выше в ленте и на карте", "Уны таҫмала һәм картала юғарыраҡ күрһәтәбеҙ")}
        onBack={() => navigate(-1)}
      />

      {status === "loading" && <LoadingList count={2} />}

      {status === "soon" && (
        <div className="state" style={{ paddingTop: 28 }}>
          <div className="state__icon"><IconRocket size={34} /></div>
          <h2>{appText("Поднятие скоро", "Күтәреү тиҙҙән")}</h2>
          <p>{appText("Функция включится после обновления сервиса.", "Был хеҙмәт яңыртыуҙан һуң эшләй башлар.")}</p>
        </div>
      )}

      {status === "error" && (
        <div className="state" style={{ paddingTop: 28 }}>
          <div className="state__icon state__icon--warn"><IconWarn size={34} /></div>
          <h2>{appText("Не получилось загрузить", "Йөкләргә булманы")}</h2>
          <button type="button" className="btn-primary" onClick={() => load()}>
            {appText("Повторить", "Ҡабатлау")}
          </button>
        </div>
      )}

      {status === "ready" && (
        <>
          {/* Выбор поездки */}
          <h2 className="section-title">{appText("Какую поездку поднять", "Ниндәй сәфәр күтәрергә")}</h2>
          {rides.length === 0 ? (
            <div className="state" style={{ paddingTop: 12 }}>
              <div className="state__icon"><IconCar size={34} /></div>
              <p>{appText("Нет активных поездок для поднятия.", "Күтәрер өсөн әүҙем сәфәр юҡ.")}</p>
              <button type="button" className="btn-primary" onClick={() => navigate("/create-ride")}>
                {appText("Опубликовать поездку", "Сәфәр баҫтырырға")}
              </button>
            </div>
          ) : (
            <div className="list">
              {rides.map((r) => (
                <button
                  key={r.id}
                  type="button"
                  className={"boost-ride" + (rideId === r.id ? " is-active" : "")}
                  onClick={() => setRideId(r.id)}
                >
                  <div className="list-row__main">
                    <div className="repeat-route">
                      <span>{r.from_city}</span>
                      <span className="repeat-route__arrow"><IconArrow size={18} /></span>
                      <span>{r.to_city}</span>
                    </div>
                    <div className="list-row__sub">{formatWhen(r.depart_at, ru)}</div>
                  </div>
                  {rideId === r.id && <IconCheck size={20} />}
                </button>
              ))}
            </div>
          )}

          {/* Планы */}
          {rides.length > 0 && (
            <>
              <h2 className="section-title">{appText("Тариф поднятия", "Күтәреү тарифы")}</h2>
              <div className="plan-grid">
                {plans.map((p) => (
                  <button
                    key={p.tier}
                    type="button"
                    className={"plan-card" + (tier === p.tier ? " is-active" : "")}
                    onClick={() => setTier(p.tier)}
                  >
                    <div className="plan-card__icon"><IconRocket size={22} /></div>
                    <div className="plan-card__title">{p.title}</div>
                    <div className="plan-card__hours">
                      {appText(`${p.hours} ч в топе`, `${p.hours} сәғәт башта`)}
                    </div>
                    <div className="plan-card__price">{p.price.toLocaleString("ru-RU")} ₽</div>
                  </button>
                ))}
              </div>

              {error && <div className="auth__error">{error}</div>}

              <button type="button" className="btn-primary submit-btn" onClick={pay} disabled={!canPay}>
                {busy ? appText("Готовим оплату…", "Түләү әҙерләйбеҙ…") : appText("Оплатить и поднять", "Түләп күтәреү")}
              </button>
              <p className="receipt__foot">
                {appText(
                  "Оплата картой через ЮKassa либо переводом по СБП — как настроено на сервере.",
                  "ЮKassa аша карта менән йәки СБП күсереү менән — сервер көйләүенә ҡарап."
                )}
              </p>
            </>
          )}
        </>
      )}
    </>
  );
}
