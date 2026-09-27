// ================================================================
//  /pay/done — сюда банк возвращает человека после оплаты картой.
//
//  Раньше этого экрана не было: ЮKassa возвращала на /pay/done, роут не
//  находился, и человек оказывался на стартовой заставке. Он заплатил деньги
//  и не узнал, прошёл платёж или нет.
//
//  Банк подтверждает оплату серверу не мгновенно, поэтому статус спрашиваем
//  несколько раз с паузой, а не один раз — и только потом говорим «пока не видно».
// ================================================================
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { fetchPaymentStatus } from "../api/boost";
import { readPendingPayment, forgetPayment } from "../utils/pendingPayment";
import { IconCheck, IconClock, IconWarn } from "../components/Icons";
import { SubHeader } from "./ConsentsScreen";
import { getSessionGeneration } from "../api/client";

type State = "checking" | "paid" | "waiting" | "unknown";

/** Сколько раз спросить сервер и с какой паузой: банк отвечает не сразу. */
const TRIES = 5;
const PAUSE_MS = 2000;

export default function PayDoneScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const pending = useRef(readPendingPayment());
  const owner = useRef(getSessionGeneration()).current;
  const [state, setState] = useState<State>(pending.current ? "checking" : "unknown");
  const [tries, setTries] = useState(0);

  const backTo = pending.current?.backTo ?? "/map";

  /** За что платили — словами, чтобы человек узнал свой платёж. */
  const whatLabel = (() => {
    switch (pending.current?.what) {
      case "boost":
        return appText("за поднятие поездки", "сәфәрҙе күтәреү өсөн");
      case "commission":
        return appText("комиссию", "комиссияны");
      case "debt":
        return appText("задолженность", "бурысты");
      case "trip":
        return appText("за поездку", "сәфәр өсөн");
      case "ad":
        return appText("за размещение", "урынлаштырыу өсөн");
      case "donate":
        return appText("поддержку Юлдаша", "Юлдашҡа ярҙамды");
      default:
        return appText("платёж", "түләүҙе");
    }
  })();

  const check = useCallback(async () => {
    const p = pending.current;
    if (!p) return;
    try {
      const s = await fetchPaymentStatus(p.paymentId);
      if (s.status === "succeeded") {
        forgetPayment(owner);
        setState("paid");
        return;
      }
      if (s.status === "canceled") {
        forgetPayment(owner);
        setState("unknown");
        return;
      }
    } catch {
      /* нет сети / сервер молчит — попробуем ещё раз ниже */
    }
    setTries((n) => n + 1);
  }, []);

  useEffect(() => {
    if (state !== "checking") return;
    if (tries === 0) {
      void check();
      return;
    }
    if (tries >= TRIES) {
      setState("waiting");
      return;
    }
    const id = window.setTimeout(() => void check(), PAUSE_MS);
    return () => window.clearTimeout(id);
  }, [state, tries, check]);

  // ---- Оплачено ----
  if (state === "paid") {
    return (
      <>
        <SubHeader title={appText("Оплата прошла", "Түләү үтте")} onBack={() => navigate(backTo)} />
        <div className="state" style={{ paddingTop: 40 }}>
          {/* базовый значок уже зелёный (mint) — отдельный модификатор не нужен */}
          <div className="state__icon">
            <IconCheck size={34} />
          </div>
          <h2>{appText("Спасибо, оплата прошла 💚", "Рәхмәт, түләү үтте 💚")}</h2>
          <p>{appText(`Мы получили ${whatLabel}.`, `${whatLabel} алдыҡ.`)}</p>
          <button type="button" className="btn-primary" onClick={() => navigate(backTo, { replace: true })}>
            {appText("Продолжить", "Дауам итергә")}
          </button>
        </div>
      </>
    );
  }

  // ---- Банк ещё не подтвердил ----
  if (state === "waiting") {
    return (
      <>
        <SubHeader title={appText("Проверяем оплату", "Түләүҙе тикшерәбеҙ")} onBack={() => navigate(backTo)} />
        <div className="state" style={{ paddingTop: 40 }}>
          <div className="state__icon">
            <IconClock size={34} />
          </div>
          <h2>{appText("Банк ещё подтверждает", "Банк әле раҫлай")}</h2>
          <p>
            {appText(
              "Деньги списаны, но подтверждение банка идёт до нескольких минут. Загляни чуть позже — если не появится, напиши нам, разберёмся.",
              "Аҡса алынды, әммә банк раҫлауы бер нисә минутҡа һуҙыла. Аҙыраҡтан ҡара — күренмәһә, беҙгә яҙ, асыҡлайбыҙ."
            )}
          </p>
          <button
            type="button"
            className="btn-primary"
            onClick={() => {
              setTries(0);
              setState("checking");
            }}
          >
            {appText("Проверить ещё раз", "Тағы тикшерергә")}
          </button>
          <button type="button" className="btn-ghost" onClick={() => navigate(backTo, { replace: true })}>
            {appText("Позже", "Һуңыраҡ")}
          </button>
        </div>
      </>
    );
  }

  // ---- Про этот платёж мы ничего не знаем ----
  if (state === "unknown") {
    return (
      <>
        <SubHeader title={appText("Оплата", "Түләү")} onBack={() => navigate(backTo)} />
        <div className="state" style={{ paddingTop: 40 }}>
          <div className="state__icon state__icon--warn">
            <IconWarn size={34} />
          </div>
          <h2>{appText("Оплата не завершилась", "Түләү тамамланманы")}</h2>
          <p>
            {appText(
              "Похоже, платёж отменён или не начинался. Деньги не списаны — можно попробовать снова.",
              "Түләү кире алынған йәки башланмаған, оҡшап тора. Аҡса алынманы — ҡабат ҡарап була."
            )}
          </p>
          <button type="button" className="btn-primary" onClick={() => navigate(backTo, { replace: true })}>
            {appText("Вернуться", "Кире ҡайтырға")}
          </button>
        </div>
      </>
    );
  }

  // ---- Спрашиваем сервер ----
  return (
    <>
      <SubHeader title={appText("Проверяем оплату", "Түләүҙе тикшерәбеҙ")} onBack={() => navigate(backTo)} />
      <div className="state" style={{ paddingTop: 40 }}>
        <div className="state__icon">
          <IconClock size={34} />
        </div>
        <h2>{appText("Проверяем оплату…", "Түләүҙе тикшерәбеҙ…")}</h2>
        <p>
          {appText(
            "Это занимает пару секунд — не закрывай экран.",
            "Был бер-ике секунд ала — экранды ябма."
          )}
        </p>
      </div>
    </>
  );
}
