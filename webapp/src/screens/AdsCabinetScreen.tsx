// ================================================================
//  Кабинет рекламы (ads.py, self-serve). RequireAuth.
//  Мои объявления (GET /ads/mine) + статистика (GET /ads/mine/stats):
//  статус, пакет, показы/клики/CTR, остаток срока, оплачено/нет.
//  Действия по статусу: создать (/ads/new), править черновик/отклонённое
//  (/ads/:id/edit), оплатить размещение (POST /ads/{id}/pay — СБП «на
//  доверии», подтверждает админ), продлить истёкшее (снова через редактор).
//  Появится на проде после мержа release → мягкая деградация 404/405.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchAdPackages,
  fetchAdsMine,
  fetchAdsMineStats,
  payAd,
  renewAd,
  submitAd,
  type AdMine,
  type AdPackage,
  type AdStat,
} from "../api/ads";
import { LoadingList, ErrorState, EmptyStateCard } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { CabinetMetric } from "../components/cabinetUi";
import { IconReceipt, IconMegaphone, IconWarn } from "../components/Icons";

type Status = "loading" | "error" | "soon" | "ready";

/** Двуязычная подпись статуса объявления + тон бейджа (AdStatusBadge в приложении). */
function statusMeta(s: string, ru: boolean): { label: string; cls: string } {
  switch (s) {
    case "active":
      return { label: ru ? "Активно" : "Актив", cls: "ad-badge ad-badge--mint" };
    case "pending_review":
      return { label: ru ? "На модерации" : "Тикшереүҙә", cls: "ad-badge ad-badge--warn" };
    case "rejected":
      return { label: ru ? "Отклонено" : "Кире ҡағылды", cls: "ad-badge ad-badge--danger" };
    case "paused":
      return { label: ru ? "На паузе" : "Туҡталышта", cls: "ad-badge" };
    case "draft":
      return { label: ru ? "Черновик" : "Ҡаралама", cls: "ad-badge" };
    default:
      return { label: ru ? "Завершено" : "Тамамланды", cls: "ad-badge" };
  }
}

export default function AdsCabinetScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [status, setStatus] = useState<Status>("loading");
  const [ads, setAds] = useState<AdMine[]>([]);
  const [stats, setStats] = useState<Record<string, AdStat>>({});

  const [payBusy, setPayBusy] = useState<string | null>(null);
  const [payError, setPayError] = useState<string | null>(null);
  const [paid, setPaid] = useState<{ amount: number } | null>(null);
  // Тарифы — для витрины, пока объявлений нет. Их сбой витрину не ломает: список просто пуст.
  const [packages, setPackages] = useState<AdPackage[] | null>(null);
  const [submitBusy, setSubmitBusy] = useState<string | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    fetchAdPackages(signal)
      .then(setPackages)
      .catch(() => setPackages([]));
    Promise.all([
      fetchAdsMine(signal),
      fetchAdsMineStats(signal).catch(() => [] as AdStat[]),
    ])
      .then(([mine, st]) => {
        setAds(mine);
        const map: Record<string, AdStat> = {};
        st.forEach((s) => (map[s.ad_id] = s));
        setStats(map);
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
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  /**
   * Продлить показ. Заявку создаёт СЕРВЕР, и только после этого показываем реквизиты.
   *
   * Раньше кнопка «Продлить» вела в редактор объявления — то есть не продлевала ничего.
   * В приложении было хуже: она сразу рисовала QR, ничего не сказав серверу, человек
   * переводил деньги, а заявки не появлялось — и через несколько дней реклама гасла.
   */
  async function onRenew(ad: AdMine) {
    setPayBusy(ad.id);
    setPayError(null);
    try {
      const res = await renewAd(ad.id);
      setPaid({ amount: Math.round(res.amount_kop / 100) });
    } catch (e) {
      setPayError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось продлить. Попробуй снова.", "Оҙайтып булманы. Ҡабат ҡара.")
      );
    } finally {
      setPayBusy(null);
    }
  }

  /** Черновик или отклонённое — на модерацию; статус меняется на «На модерации» без перезагрузки. */
  async function onSubmit(ad: AdMine) {
    setSubmitBusy(ad.id);
    setPayError(null);
    try {
      const next = await submitAd(ad.id);
      setAds((prev) => prev.map((a) => (a.id === ad.id ? next : a)));
    } catch (e) {
      setPayError(
        e instanceof ApiError && e.message ? e.message : appText("Не отправилось. Повтори.", "Ебәрелмәне. Ҡабатла.")
      );
    } finally {
      setSubmitBusy(null);
    }
  }

  async function onPay(ad: AdMine) {
    setPayBusy(ad.id);
    setPayError(null);
    try {
      const res = await payAd(ad.id);
      setPaid({ amount: Math.round(res.amount_kop / 100) });
    } catch (e) {
      setPayError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось создать оплату. Попробуй снова.", "Түләү булдырырға булманы. Ҡабат ҡара.")
      );
    } finally {
      setPayBusy(null);
    }
  }

  // ---- Заявка на оплату создана (СБП «на доверии») ----
  if (paid) {
    return (
      <>
        <SubHeader title={appText("Оплата размещения", "Урынлаштырыу түләүе")} onBack={() => { setPaid(null); load(); }} />
        <div className="state" style={{ paddingTop: 20 }}>
          <div className="state__icon"><IconReceipt size={34} /></div>
          <h2>{appText("Заявка на оплату создана", "Түләү заявкаһы булдырылды")}</h2>
          <p>
            {appText(
              `Переведи ${paid.amount.toLocaleString("ru-RU")} ₽ по СБП «на доверии». Как получим — объявление уйдёт в показ. Это по-соседски, вручную.`,
              `${paid.amount.toLocaleString("ru-RU")} ₽ СБП аша «ышаныс менән» күсер. Алғас — иғлан күрһәтелә башлай. Был күршеләрсә, ҡулдан.`
            )}
          </p>
          <button type="button" className="btn-soft" style={{ minHeight: 48 }} onClick={() => navigate("/payment-info")}>
            {appText("Как оплатить", "Нисек түләргә")}
          </button>
          <button type="button" className="btn-primary" style={{ marginTop: 10 }} onClick={() => { setPaid(null); load(); }}>
            {appText("Готово", "Әҙер")}
          </button>
        </div>
      </>
    );
  }

  const rub = (kop: number) => `${Math.round(kop / 100).toLocaleString("ru-RU")} ₽`;

  return (
    <>
      <SubHeader title={appText("Кабинет рекламы", "Реклама кабинеты")} onBack={() => navigate(-1)} />
      <div className="cabinet">
        {status === "loading" && <LoadingList count={2} />}
        {status === "soon" && (
          <EmptyStateCard
            icon={<IconMegaphone size={30} />}
            title={appText("Реклама скоро", "Реклама тиҙҙән")}
            text={appText("Кабинет рекламы включится после обновления сервиса.", "Реклама кабинеты яңыртыуҙан һуң эшләй башлар.")}
          />
        )}
        {status === "error" && (
          <ErrorState
            onRetry={() => load()}
            title={appText("Не удалось загрузить кабинет", "Кабинетты йөкләп булманы")}
          />
        )}

        {status === "ready" && payError && (
          <div className="docs-notice is-error" role="alert">
            <IconWarn size={20} />
            <span>{payError}</span>
          </div>
        )}

        {/* AdsShowcase: пока объявлений нет — что это и почём, и одна кнопка. */}
        {status === "ready" && ads.length === 0 && (
          <>
            <div className="cabinet__intro">
              <h2>{appText("Реклама в Юлдаше", "Юлдашта реклама")}</h2>
              <p>
                {appText(
                  "Покажи своё дело землякам по маршрутам и городам. Создай объявление, пройди модерацию и оплати размещение.",
                  "Эшеңде маршруттар һәм ҡалалар буйынса яҡташтарға күрһәт. Иғлан булдыр, модерация үт һәм урынлаштырыуҙы түлә."
                )}
              </p>
            </div>
            <strong className="ads-label">{appText("Тарифы", "Тарифтар")}</strong>
            {packages && packages.length === 0 && (
              <div className="ads-note">
                <strong>{appText("Тарифы подгружаются", "Тарифтар йөкләнә")}</strong>
                <small>{appText("Создать объявление можно уже сейчас — тариф выберешь в редакторе.", "Иғланды хәҙер үк булдырып була — тарифты мөхәррирҙә һайларһың.")}</small>
              </div>
            )}
            {(packages ?? []).map((p) => (
              <div key={p.code} className="ads-package">
                <span className="ads-package__text">
                  <strong>{appText(p.title, p.title_ba || p.title)}</strong>
                  <small>{appText(`${p.period_days} дней показов`, `${p.period_days} көн күрһәтеү`)}</small>
                </span>
                <b>{rub(p.amount_kop)}</b>
              </div>
            ))}
            <p className="dl-hint">{appText("Цены — стартовая гипотеза, обсуждаемо.", "Хаҡтар — башланғыс фараз, һөйләшеп була.")}</p>
            <button type="button" className="btn-primary submit-btn" onClick={() => navigate("/ads/new")}>
              {appText("Разместить рекламу", "Реклама урынлаштырырға")}
            </button>
          </>
        )}

        {status === "ready" && ads.length > 0 && (
          <>
            <button type="button" className="btn-primary submit-btn" onClick={() => navigate("/ads/new")}>
              {appText("Новое объявление", "Яңы иғлан")}
            </button>

            {ads.map((ad) => {
              const m = statusMeta(ad.status, ru);
              const st = stats[ad.id];
              const editable = ad.status === "draft" || ad.status === "rejected";
              const needsPay = ad.status === "active" && !ad.paid;
              const live = ad.status === "active" && ad.paid;
              const ending = st?.days_left != null && st.days_left <= 3;
              return (
                <div key={ad.id} className="ad-card">
                  <div className="ad-card__head">
                    <strong>{ad.title || appText("Без названия", "Исемһеҙ")}</strong>
                    <span className={m.cls}>{m.label}</span>
                  </div>
                  {ad.text && <p className="ad-card__text">{ad.text}</p>}
                  {ad.package_title && (
                    <small className="ad-card__meta">
                      {appText(
                        `Тариф: ${ad.package_title} · ${rub(ad.budget_kop)} / ${ad.period_days} дн`,
                        `Тариф: ${ad.package_title} · ${rub(ad.budget_kop)} / ${ad.period_days} көн`
                      )}
                    </small>
                  )}

                  {/* AdStatsTiles: показы · клики / CTR · дней осталось. */}
                  {live && st && (
                    <div className="ad-card__stats">
                      <div className="cab-metrics">
                        <CabinetMetric label={appText("показы", "күрһәтеү")} value={st.impressions.toLocaleString("ru-RU")} />
                        <CabinetMetric label={appText("клики", "баҫыу")} value={st.clicks.toLocaleString("ru-RU")} />
                      </div>
                      <div className="cab-metrics">
                        <CabinetMetric label="CTR" value={`${st.ctr}%`} />
                        <CabinetMetric
                          label={st.days_left != null ? appText("осталось дней", "көн ҡалды") : appText("бессрочно", "сикһеҙ")}
                          value={st.days_left != null ? String(st.days_left) : "∞"}
                        />
                      </div>
                    </div>
                  )}

                  {ad.status === "rejected" && ad.reject_reason && (
                    <div className="ad-card__reject">
                      {appText(`Причина отказа: ${ad.reject_reason}`, `Кире ҡағыу сәбәбе: ${ad.reject_reason}`)}
                    </div>
                  )}

                  {needsPay && (
                    <>
                      <small className="ad-card__meta">
                        {appText("Одобрено! Оплати размещение — и объявление пойдёт в показы.", "Раҫланды! Урынлаштырыуҙы түлә — иғлан күрһәтелә башлай.")}
                      </small>
                      <button type="button" className="btn-primary" onClick={() => onPay(ad)} disabled={payBusy === ad.id}>
                        {payBusy === ad.id
                          ? appText("Готовим…", "Әҙерләйбеҙ…")
                          : appText(`Оплатить размещение · ${rub(ad.budget_kop)}`, `Урынлаштырыуҙы түләү · ${rub(ad.budget_kop)}`)}
                      </button>
                    </>
                  )}

                  {live && (
                    <>
                      <small className="ad-card__meta is-green">{appText("Оплачено · объявление показывается.", "Түләнде · иғлан күрһәтелә.")}</small>
                      {/* Предупреждаем ДО того, как показы встали: партнёр платил за поток людей. */}
                      {ending && (
                        <small className="ad-card__meta is-warn">
                          {appText("Размещение скоро закончится. Продли, чтобы показы не прервались.", "Урынлаштырыу тиҙҙән бөтә. Күрһәтеү өҙөлмәһен өсөн оҙайт.")}
                        </small>
                      )}
                      {/* Продление: период добавляется к остатку, оплаченные дни не сгорают. */}
                      <button type="button" className="btn-soft" onClick={() => onRenew(ad)} disabled={payBusy === ad.id}>
                        {payBusy === ad.id
                          ? appText("Готовим…", "Әҙерләйбеҙ…")
                          : appText(`Продлить размещение · ${rub(ad.budget_kop)}`, `Урынлаштырыуҙы оҙайтыу · ${rub(ad.budget_kop)}`)}
                      </button>
                    </>
                  )}

                  {editable && (
                    <div className="ad-card__actions">
                      <button type="button" className="btn-soft" onClick={() => navigate(`/ads/${ad.id}/edit`)}>
                        {appText("Изменить", "Үҙгәртергә")}
                      </button>
                      <button type="button" className="btn-primary" onClick={() => onSubmit(ad)} disabled={submitBusy === ad.id}>
                        {submitBusy === ad.id ? appText("Отправляем…", "Ебәрәбеҙ…") : appText("На модерацию", "Модерацияға")}
                      </button>
                    </div>
                  )}
                  {ad.status === "expired" && (
                    <button type="button" className="btn-primary" onClick={() => navigate(`/ads/${ad.id}/edit`)}>
                      {appText("Продлить", "Оҙайтыу")}
                    </button>
                  )}
                </div>
              );
            })}
            <p className="dl-hint">
              {appText(
                "Оплата размещения — переводом по СБП «на доверии»: как получим, объявление пойдёт в показ. Реклама помечается по закону (ОРД).",
                "Урынлаштырыу түләүе — СБП аша «ышаныс менән»: алғас, иғлан күрһәтелә башлай. Реклама закон буйынса билдәләнә (ОРД)."
              )}
            </p>
          </>
        )}
      </div>
    </>
  );
}
