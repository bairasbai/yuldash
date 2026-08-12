// ================================================================
//  «Мои поездки такси» (водитель) — GET /driver/taxi-rides.
//  Зеркало Android DriverTaxiRidesScreen.kt.
//
//  Экран отвечает на один вопрос: «Юлдаш говорит 4200, я насчитал
//  4600 — где мои 400?». По каждой поездке видно цепочку
//  цена → комиссия → чистыми, и итог за все поездки сверху.
//  Комиссия — фактическая (из начисленного долга), не пересчёт.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { fetchDriverTaxiRides, type DriverTaxiRides } from "../api/driver";
import { LoadingList } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconCar, IconCheck, IconChevron, IconWallet } from "../components/Icons";
import { kopExactLabel, priceLabel, rubLabel } from "../utils/format";

type Status = "loading" | "error" | "soon" | "ready";

/** Подпись состояния комиссии по поездке (пара цвет/текст — как в приложении). */
function feeBadge(
  status: string,
  appText: (ru: string, ba: string) => string
): { text: string; cls: string } | null {
  switch (status) {
    case "unpaid":
    case "pending":
      return { text: appText("Комиссия не оплачена", "Комиссия түләнмәгән"), cls: "badge--gold" };
    case "declared":
      return { text: appText("Оплату проверяем", "Түләүҙе тикшерәбеҙ"), cls: "badge--gold" };
    case "paid":
      return { text: appText("Комиссия оплачена", "Комиссия түләнгән"), cls: "badge--mint" };
    case "void":
      return { text: appText("Комиссия списана", "Комиссия һүндерелгән"), cls: "badge--mint" };
    default:
      return null;
  }
}

/** ISO → «03.08, 23:10». Пусто → прочерк, чтобы строка не «прыгала». */
function shortWhen(iso: string | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (isNaN(d.getTime())) return "—";
  const date = d.toLocaleDateString("ru-RU", { day: "2-digit", month: "2-digit" });
  const time = d.toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" });
  return `${date}, ${time}`;
}

export default function DriverTaxiRidesScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [status, setStatus] = useState<Status>("loading");
  const [data, setData] = useState<DriverTaxiRides | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    fetchDriverTaxiRides(100, signal)
      .then((d) => {
        setData(d);
        setStatus("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        if (e instanceof ApiError && (e.status === 404 || e.status === 403)) {
          return setStatus("soon");
        }
        setStatus("error");
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
        title={appText("Мои поездки такси", "Такси сәфәрҙәрем")}
        subtitle={appText("Цена → комиссия → чистыми", "Хаҡ → комиссия → таҙа")}
        onBack={() => navigate(-1)}
      />

      {status === "loading" && <LoadingList count={3} />}

      {status === "soon" && (
        <div className="state" style={{ paddingTop: 32 }}>
          <div className="state__icon">
            <IconWallet size={34} />
          </div>
          <h2>{appText("Скоро здесь", "Тиҙҙән бында")}</h2>
          <p>
            {appText(
              "Расшифровка по поездкам включится с ближайшим обновлением.",
              "Сәфәрҙәр буйынса тарҡатыу яҡын яңыртыуҙа тоташа."
            )}
          </p>
        </div>
      )}

      {status === "error" && (
        <div className="state" style={{ paddingTop: 32 }}>
          <div className="state__icon state__icon--warn">
            <IconWallet size={34} />
          </div>
          <h2>{appText("Не получилось загрузить", "Йөкләргә булманы")}</h2>
          <button type="button" className="btn-primary" onClick={() => load()}>
            {appText("Повторить", "Ҡабатларға")}
          </button>
        </div>
      )}

      {status === "ready" && data && data.rides.length === 0 && (
        <div className="state" style={{ paddingTop: 32 }}>
          <div className="state__icon">
            <IconCar size={34} />
          </div>
          <h2>{appText("Поездок пока нет", "Әлегә сәфәр юҡ")}</h2>
          <p>
            {appText(
              "Как только завершишь первый заказ — здесь появится расчёт до копейки.",
              "Беренсе заказды тамамлағас — бында тин-тингә тиклем иҫәп күренәсәк."
            )}
          </p>
        </div>
      )}

      {status === "ready" && data && data.rides.length > 0 && (
        <>
          <div className="money-total">
            <div className="money-total__label">
              {appText("Чистыми за последние поездки", "Һуңғы сәфәрҙәр өсөн таҙа")}
            </div>
            <div className="money-total__value">{rubLabel(data.total_net_kop)}</div>
            <div className="money-total__rows">
              <div className="info-row">
                <span className="info-row__k">{appText("Пассажиры заплатили", "Юлаусылар түләне")}</span>
                <span className="info-row__v">{priceLabel(data.total_price, ru)}</span>
              </div>
              <div className="info-row">
                <span className="info-row__k">{appText("Комиссия Юлдаша", "Юлдаш комиссияһы")}</span>
                <span className="info-row__v">−{rubLabel(data.total_fee_kop)}</span>
              </div>
              <div className="info-row">
                <span className="info-row__k">{appText("Осталось тебе", "Һиңә ҡалды")}</span>
                <span className="info-row__v">{rubLabel(data.total_net_kop)}</span>
              </div>
            </div>
          </div>

          <h2 className="section-title">{appText("Каждая поездка", "Һәр сәфәр")}</h2>
          <p className="demand__quiet">
            {appText(
              "Комиссия берётся фактическая — та, что начислена по этой поездке.",
              "Комиссия факт буйынса алына — ошо сәфәргә иҫәпләнгәне."
            )}
          </p>

          <div className="list" style={{ marginTop: 8 }}>
            {data.rides.map((r) => {
              const badge = feeBadge(r.fee_status, appText);
              return (
                <button
                  key={r.order_id}
                  type="button"
                  className="money-row"
                  style={{ width: "100%", textAlign: "left" }}
                  onClick={() => navigate(`/taxi-receipt/${r.order_id}`)}
                  aria-label={appText("Открыть чек поездки", "Сәфәр чеген асыу")}
                >
                  <div className="money-row__head">
                    <span className="money-row__route">
                      {r.from || appText("Точка А", "А нөктә")}
                      <span className="money-row__op"> → </span>
                      {r.to || appText("Точка Б", "Б нөктә")}
                    </span>
                    <span className="money-row__net">{kopExactLabel(r.net_kop)}</span>
                  </div>
                  <div className="money-row__date">
                    {shortWhen(r.done_at)} · {appText("тебе", "һиңә")}
                  </div>

                  <div className="money-row__calc">
                    <span>
                      {appText("Цена", "Хаҡ")}: <b>{priceLabel(r.price, ru)}</b>
                    </span>
                    <span className="money-row__op">−</span>
                    <span>
                      {appText("Комиссия", "Комиссия")}: <b>{kopExactLabel(r.fee_kop)}</b>
                    </span>
                    {r.promo_discount_kop > 0 && (
                      <>
                        <span className="money-row__op">·</span>
                        <span>
                          {appText("Промокод", "Промокод")}: −{kopExactLabel(r.promo_discount_kop)}
                        </span>
                      </>
                    )}
                    {r.promo_comp_kop > 0 && (
                      <>
                        <span className="money-row__op">+</span>
                        <span>
                          {appText("Возврат Юлдаша", "Юлдаш ҡайтарыуы")}: {kopExactLabel(r.promo_comp_kop)}
                        </span>
                      </>
                    )}
                  </div>

                  <div className="money-row__foot">
                    <span className={"badge " + (r.paid ? "badge--mint" : "badge--gold")}>
                      {r.paid ? <IconCheck size={12} /> : null}{" "}
                      {r.paid ? appText("Оплачено", "Түләнгән") : appText("Не отмечено", "Билдәләнмәгән")}
                    </span>
                    {badge && <span className={"badge " + badge.cls}>{badge.text}</span>}
                    <span style={{ marginLeft: "auto", display: "inline-flex" }}>
                      <IconChevron size={18} />
                    </span>
                  </div>
                </button>
              );
            })}
          </div>

          <p className="receipt__foot">
            {appText(
              "Деньги идут напрямую тебе — Юлдаш только показывает счёт и комиссию.",
              "Аҡса тура һиңә бара — Юлдаш иҫәпте һәм комиссияны ғына күрһәтә."
            )}
          </p>
        </>
      )}
    </>
  );
}
