// ================================================================
//  «Мои предзаказы» — такси на время. RequireAuth → /scheduled
//  (зеркало backend routers/instant.py: GET /instant/scheduled,
//  activate, cancel).
//
//  Список предзаказов с обратным отсчётом, «Начать поиск сейчас»
//  (activate) и «Отменить» (cancel). Блок «пора ехать» для
//  наступивших (бэк лениво активирует их при GET).
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchScheduled,
  activateScheduled,
  cancelScheduled,
  type InstantOrder,
} from "../api/instant";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList } from "../components/States";
import { IconClock, IconArrow, IconTrash, IconCar, IconWarn, IconClockCal } from "../components/Icons";
import { dayMonthShort, hhmm, priceLabel } from "../utils/format";
import { serverDate, serverMs } from "../utils/serverTime";

type Status = "loading" | "error" | "ready";

/** «через 2 ч 15 мин» / «через 8 мин» / «пора ехать». */
function countdown(iso: string | null, appText: (r: string, b: string) => string): string {
  if (!iso) return "";
  const diff = serverMs(iso) - Date.now();
  if (diff <= 0) return appText("Пора ехать", "Китергә ваҡыт");
  const min = Math.round(diff / 60000);
  if (min < 60) return appText(`через ${min} мин`, `${min} минуттан`);
  const h = Math.floor(min / 60);
  const m = min % 60;
  return appText(`через ${h} ч ${m} мин`, `${h} сәғәт ${m} минуттан`);
}

/** «14 июл, 09:30». */
function whenLabel(iso: string | null, ru: boolean): string {
  if (!iso) return "";
  const d = serverDate(iso);
  if (!d) return "";
  const date = dayMonthShort(d, ru);
  const time = hhmm(d);
  return `${date}, ${time}`;
}

export default function ScheduledOrdersScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [status, setStatus] = useState<Status>("loading");
  const [rows, setRows] = useState<InstantOrder[]>([]);
  const [dueNow, setDueNow] = useState<InstantOrder[]>([]);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [, force] = useState(0); // тик для обратного отсчёта

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    fetchScheduled(signal)
      .then((r) => {
        setRows(r.scheduled);
        setDueNow(r.activated); // бэк лениво активировал наступившие
        setStatus("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        // 404 = эндпоинта ещё нет на проде → мягко «пусто».
        if (e instanceof ApiError && e.status === 404) {
          setRows([]);
          setDueNow([]);
          setStatus("ready");
        } else setStatus("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  // Тик обратного отсчёта раз в минуту.
  useEffect(() => {
    const iv = window.setInterval(() => force((n) => n + 1), 30000);
    return () => window.clearInterval(iv);
  }, []);

  async function activate(id: number) {
    setBusyId(id);
    try {
      await activateScheduled(id);
      navigate("/taxi"); // заказ ушёл в поиск → экран заказа восстановит его
    } catch (e) {
      setBusyId(null);
      if (e instanceof ApiError) load();
    }
  }

  /**
   * Отмена предзаказа спрашивает подтверждение: корзина стоит рядом с «начать
   * поиск», и промах пальцем стоил бы человеку машины на 6 утра. Вернуть предзаказ
   * после отмены нельзя — только создать заново.
   */
  const [confirmId, setConfirmId] = useState<number | null>(null);

  async function cancel(id: number) {
    const prev = rows;
    setConfirmId(null);
    setRows(rows.filter((r) => r.id !== id)); // оптимистично
    try {
      await cancelScheduled(id);
    } catch {
      setRows(prev); // откат
    }
  }

  return (
    <>
      <SubHeader
        title={appText("Мои предзаказы", "Алдан заказдарым")}
        subtitle={appText("Такси на время — заранее", "Ваҡытҡа такси — алдан")}
        onBack={() => navigate(-1)}
      />

      {status === "loading" && <LoadingList count={2} />}

      {status === "error" && (
        <div className="state" style={{ paddingTop: 40 }}>
          <div className="state__icon state__icon--warn"><IconWarn size={34} /></div>
          <h2>{appText("Не получилось загрузить", "Йөкләргә булманы")}</h2>
          <button type="button" className="btn-primary" onClick={() => load()}>
            {appText("Повторить", "Ҡабатлау")}
          </button>
        </div>
      )}

      {status === "ready" && (
        <>
          {/* Пора ехать — наступившие, уже ушли в поиск */}
          {dueNow.length > 0 && (
            <>
              <h2 className="section-title">{appText("Пора ехать", "Китергә ваҡыт")}</h2>
              {dueNow.map((o) => (
                <button
                  key={o.id}
                  type="button"
                  className="trust-cta"
                  style={{ marginTop: 10 }}
                  onClick={() => navigate("/taxi")}
                >
                  <span className="trust-cta__emoji"><IconCar size={22} /></span>
                  <span className="trust-cta__text">
                    {appText("Заказ активирован — ищем машину", "Заказ әүҙемләште — машина эҙләйбеҙ")}
                    {" · "}
                    {o.from_text || appText("Точка А", "А нөктә")} → {o.to_text || appText("Точка Б", "Б нөктә")}
                  </span>
                  <IconArrow size={20} />
                </button>
              ))}
            </>
          )}

          {rows.length === 0 && dueNow.length === 0 ? (
            <div className="state" style={{ paddingTop: 40 }}>
              <div className="state__icon"><IconClockCal size={34} /></div>
              <h2>{appText("Пока предзаказов нет", "Әле алдан заказдар юҡ")}</h2>
              <p>
                {appText(
                  "Закажи такси заранее — на время. Мы напомним и найдём машину к нужному часу.",
                  "Такси алдан — ваҡытҡа заказ ит. Иҫкә төшөрәбеҙ һәм кәрәкле сәғәткә машина табабыҙ."
                )}
              </p>
              <button type="button" className="btn-primary" onClick={() => navigate("/taxi")}>
                <IconCar size={18} /> {appText("Заказать такси", "Такси заказ итеү")}
              </button>
            </div>
          ) : (
            rows.length > 0 && (
              <>
                {dueNow.length > 0 && (
                  <h2 className="section-title">{appText("Запланировано", "Планлаштырылған")}</h2>
                )}
                <div className="list" style={{ marginTop: dueNow.length > 0 ? 0 : 12 }}>
                  {rows.map((o) => (
                    <div key={o.id} className="sched-card">
                      <div className="sched-card__head">
                        <span className="sched-card__when">
                          <IconClock size={16} /> {whenLabel(o.scheduled_at, ru)}
                        </span>
                        <span className="badge badge--gold">{countdown(o.scheduled_at, appText)}</span>
                      </div>
                      <div className="repeat-route" style={{ marginTop: 8 }}>
                        <span>{o.from_text || appText("Точка А", "А нөктә")}</span>
                        <span className="repeat-route__arrow">
                          <IconArrow size={18} />
                        </span>
                        <span>{o.to_text || appText("Точка Б", "Б нөктә")}</span>
                      </div>
                      <div className="sched-card__meta">
                        {priceLabel(o.price_estimate, ru)}
                        {" · "}
                        {o.category === "comfort" ? appText("Комфорт", "Комфорт") : appText("Эконом", "Эконом")}
                      </div>
                      <div className="sched-card__actions">
                        <button
                          type="button"
                          className="btn-soft"
                          style={{ flex: 1 }}
                          onClick={() => activate(o.id)}
                          disabled={busyId === o.id}
                        >
                          {busyId === o.id
                            ? appText("Запускаем…", "Ебәрәбеҙ…")
                            : appText("Начать поиск сейчас", "Хәҙер эҙләй башлау")}
                        </button>
                        <button
                          type="button"
                          className="icon-btn"
                          onClick={() => setConfirmId(o.id)}
                          aria-label={appText("Отменить предзаказ", "Алдан заказды кире алыу")}
                        >
                          <IconTrash size={20} />
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
                <p className="taxi-note">
                  {appText(
                    "Совет: открой приложение к назначенному времени — так мы точно найдём машину.",
                    "Кәңәш: билдәләнгән ваҡытҡа ҡушымтаны ас — шунда машина табыуы аныҡ."
                  )}
                </p>
              </>
            )
          )}
        </>
      )}

      {/* Подтверждение отмены: вернуть предзаказ назад нельзя */}
      {confirmId != null && (
        <div className="sheet-backdrop" onClick={() => setConfirmId(null)}>
          <div className="sheet" onClick={(e) => e.stopPropagation()}>
            <h2 className="sheet__title">
              {appText("Отменить предзаказ?", "Алдан заказды кире алырғамы?")}
            </h2>
            <p className="sheet__comment">
              {appText(
                "Поиск машины в назначенное время не начнётся.",
                "Билдәләнгән ваҡытта машина эҙләү башланмаясаҡ."
              )}
            </p>
            <button type="button" className="btn-danger" onClick={() => void cancel(confirmId)}>
              {appText("Отменить предзаказ", "Алдан заказды кире алыу")}
            </button>
            <button
              type="button"
              className="btn-soft"
              style={{ marginTop: 8 }}
              onClick={() => setConfirmId(null)}
            >
              {appText("Оставить", "Ҡалдырырға")}
            </button>
          </div>
        </div>
      )}
    </>
  );
}
