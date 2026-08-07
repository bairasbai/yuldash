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
  fetchAdsMine,
  fetchAdsMineStats,
  payAd,
  type AdMine,
  type AdStat,
} from "../api/ads";
import { LoadingList } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconReceipt, IconMegaphone, IconWarn } from "../components/Icons";

type Status = "loading" | "error" | "soon" | "ready";

/** Двуязычная подпись статуса объявления + класс бейджа. */
function statusMeta(s: string, ru: boolean): { label: string; cls: string } {
  switch (s) {
    case "draft":
      return { label: ru ? "Черновик" : "Ҡаралама", cls: "badge" };
    case "pending_review":
      return { label: ru ? "На модерации" : "Модерацияла", cls: "badge badge--gold" };
    case "rejected":
      return { label: ru ? "Отклонено" : "Кире ҡағылған", cls: "badge badge--danger" };
    case "active":
      return { label: ru ? "Одобрено" : "Раҫланған", cls: "badge badge--mint" };
    case "paused":
      return { label: ru ? "На паузе" : "Туҡтатылған", cls: "badge" };
    case "expired":
      return { label: ru ? "Срок вышел" : "Ваҡыт үтте", cls: "badge" };
    case "archived":
      return { label: ru ? "В архиве" : "Архивта", cls: "badge" };
    default:
      return { label: s, cls: "badge" };
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

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
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

  return (
    <>
      <SubHeader
        title={appText("Кабинет рекламы", "Реклама кабинеты")}
        subtitle={appText("Объявления, показы и оплата", "Иғландар, күрһәтеүҙәр һәм түләү")}
        onBack={() => navigate(-1)}
      />

      {status === "loading" && <LoadingList count={2} />}

      {status === "soon" && (
        <div className="state" style={{ paddingTop: 28 }}>
          <div className="state__icon"><IconMegaphone size={34} /></div>
          <h2>{appText("Реклама скоро", "Реклама тиҙҙән")}</h2>
          <p>{appText("Кабинет рекламы включится после обновления сервиса.", "Реклама кабинеты яңыртыуҙан һуң эшләй башлар.")}</p>
        </div>
      )}

      {status === "error" && (
        <div className="state" style={{ paddingTop: 28 }}>
          <div className="state__icon state__icon--warn"><IconWarn size={34} /></div>
          <h2>{appText("Не получилось загрузить", "Йөкләргә булманы")}</h2>
          <button type="button" className="btn-primary" onClick={() => load()}>
            {appText("Повторить", "Ҡабатларға")}
          </button>
        </div>
      )}

      {status === "ready" && (
        <>
          {payError && <div className="auth__error">{payError}</div>}

          {ads.length === 0 ? (
            <div className="state" style={{ paddingTop: 24 }}>
              <div className="state__icon"><IconMegaphone size={34} /></div>
              <h2>{appText("Пока нет объявлений", "Әле иғландар юҡ")}</h2>
              <p>
                {appText(
                  "Расскажи о своём деле попутчикам — создай первое объявление.",
                  "Юлдаштарға эшең тураһында һөйлә — беренсе иғланды булдыр."
                )}
              </p>
              <button type="button" className="btn-primary" onClick={() => navigate("/ads/new")}>
                {appText("Создать объявление", "Иғлан булдырыу")}
              </button>
            </div>
          ) : (
            <>
              <button
                type="button"
                className="btn-primary submit-btn"
                style={{ marginTop: 14 }}
                onClick={() => navigate("/ads/new")}
              >
                {appText("Создать объявление", "Иғлан булдырыу")}
              </button>

              {ads.map((ad) => {
                const m = statusMeta(ad.status, ru);
                const st = stats[ad.id];
                const editable = ad.status === "draft" || ad.status === "rejected";
                const needsPay = ad.status === "active" && !ad.paid;
                const live = ad.status === "active" && ad.paid;
                return (
                  <div key={ad.id} className="biz-item">
                    <div className="biz-item__head">
                      <div>
                        <div className="biz-item__title">{ad.title || appText("Без названия", "Исемһеҙ")}</div>
                        <div className="biz-item__meta">
                          {(ad.package_title || appText("Пакет не выбран", "Пакет һайланмаған")) +
                            (ad.budget_kop > 0 ? ` · ${(ad.budget_kop / 100).toLocaleString("ru-RU")} ₽` : "")}
                        </div>
                      </div>
                      <span className={m.cls}>{m.label}</span>
                    </div>

                    {ad.status === "rejected" && ad.reject_reason && (
                      <p className="biz-item__meta" style={{ color: "var(--danger)" }}>
                        {ad.reject_reason}
                      </p>
                    )}

                    {live && st && (
                      <div className="biz-item__stats">
                        <div className="biz-item__stat">
                          <b>{st.impressions.toLocaleString("ru-RU")}</b>
                          <span>{appText("Показы", "Күрһәтеүҙәр")}</span>
                        </div>
                        <div className="biz-item__stat">
                          <b>{st.clicks.toLocaleString("ru-RU")}</b>
                          <span>{appText("Клики", "Клик")}</span>
                        </div>
                        <div className="biz-item__stat">
                          <b>{st.ctr}%</b>
                          <span>CTR</span>
                        </div>
                        {st.days_left != null && (
                          <div className="biz-item__stat">
                            <b>{st.days_left}</b>
                            <span>{appText("дней осталось", "көн ҡалды")}</span>
                          </div>
                        )}
                      </div>
                    )}

                    <div className="biz-item__actions">
                      {editable && (
                        <button type="button" className="btn-soft" onClick={() => navigate(`/ads/${ad.id}/edit`)}>
                          {appText("Редактировать", "Төҙәтеү")}
                        </button>
                      )}
                      {needsPay && (
                        <button
                          type="button"
                          className="btn-primary"
                          onClick={() => onPay(ad)}
                          disabled={payBusy === ad.id}
                        >
                          {payBusy === ad.id
                            ? appText("Готовим…", "Әҙерләйбеҙ…")
                            : appText("Оплатить размещение", "Урынлаштырыуҙы түләү")}
                        </button>
                      )}
                      {ad.status === "expired" && (
                        <button type="button" className="btn-primary" onClick={() => navigate(`/ads/${ad.id}/edit`)}>
                          {appText("Продлить", "Оҙайтыу")}
                        </button>
                      )}
                    </div>
                  </div>
                );
              })}
            </>
          )}

          <p className="receipt__foot">
            {appText(
              "Оплата размещения — переводом по СБП «на доверии»: как получим, объявление пойдёт в показ. Реклама помечается по закону (ОРД).",
              "Урынлаштырыу түләүе — СБП аша «ышаныс менән»: алғас, иғлан күрһәтелә башлай. Реклама закон буйынса билдәләнә (ОРД)."
            )}
          </p>
        </>
      )}
    </>
  );
}
