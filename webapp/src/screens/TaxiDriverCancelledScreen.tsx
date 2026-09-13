// ================================================================
//  Закрытый без поездки заказ глазами таксиста — зеркало android/TaxiDriverCancelledScreen.kt
//  (связка C + A): нейтральная шапка, маршрут, факты 2×2, честный денежный блок (только
//  зафиксированная сервером плата), «Проблема с заказом» → «Что случилось?» → жалоба / разбор.
//  Снизу закреплено «Вернуться на линию» + текстовая «Завершить смену».
// ================================================================
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { type InstantOrder } from "../api/instant";
import { setDriverOnline } from "../api/driver";
import RouteTimeline from "../components/RouteTimeline";
import FileIncidentCard from "../components/FileIncidentCard";
import AlertDialog from "../components/AlertDialog";
import {
  IconCar,
  IconCheckCircle,
  IconChevron,
  IconClock,
  IconEventBusy,
  IconFlag,
  IconHandshake,
  IconInfo,
  IconShield,
  IconSwap,
  IconWallet,
  IconWarn,
} from "../components/Icons";
import { hhmm, kopExactLabel } from "../utils/format";
import { serverDate, serverMs } from "../utils/serverTime";
import { payMethodShortLabel } from "./TaxiDriverCompletedScreen";

function categoryLabel(cat: string, appText: (ru: string, ba: string) => string): string {
  switch (cat) {
    case "comfort":
      return appText("Комфорт", "Комфорт");
    case "business":
      return appText("Бизнес", "Бизнес");
    case "minivan":
      return appText("Минивэн", "Минивэн");
    default:
      return appText("Эконом", "Эконом");
  }
}

/** Иконка способа оплаты (PayMethods.icon). */
export function PayMethodIcon({ method, size = 21 }: { method?: string; size?: number }) {
  switch (method) {
    case "cash":
      return <IconWallet size={size} />;
    case "sbp":
      return <IconSwap size={size} />;
    default:
      return <IconHandshake size={size} />;
  }
}

function Fact({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <div className="tdx-fact">
      <span className="tdx-fact__icon" aria-hidden>{icon}</span>
      <span className="tdx-fact__text">
        <small>{label}</small>
        <strong>{value}</strong>
      </span>
    </div>
  );
}

export default function TaxiDriverCancelled({
  order,
  onReturnToLine,
  onShiftFinished,
}: {
  order: InstantOrder;
  onReturnToLine: () => void;
  onShiftFinished: () => void;
}) {
  const { appText } = useLang();
  const navigate = useNavigate();
  const [shiftEnding, setShiftEnding] = useState(false);
  const [choice, setChoice] = useState(false);
  const [dispute, setDispute] = useState(false);
  const [disputeFiled, setDisputeFiled] = useState(false);
  const [successText, setSuccessText] = useState("");
  const [errorText, setErrorText] = useState("");

  const noShow = order.no_show;
  const title = noShow
    ? appText("Пассажир не вышел", "Пассажир сыҡманы")
    : order.cancel_by === "passenger"
      ? appText("Пассажир отменил заказ", "Пассажир заказды кире алды")
      : appText("Заказ отменён", "Заказ кире алынды");
  const status = noShow
    ? appText("Заказ закрыт", "Заказ ябылды")
    : order.cancel_by === "passenger" && order.cancel_fee_kop > 0
      ? appText("Поздняя отмена", "Һуң кире алыу")
      : order.cancel_by === "passenger"
        ? appText("Отмена без платы", "Түләүһеҙ кире алыу")
        : order.cancel_by === "driver"
          ? appText("Отменено тобой", "Һин кире алдың")
          : appText("Заказ закрыт", "Заказ ябылды");

  // Факты: прибыл (HH:mm) / заказ №; ожидание (мин) / кто отменил; тариф; оплата.
  const arrivedAt = serverDate(order.waiting_started_at);
  const arrival = arrivedAt ? hhmm(arrivedAt) : null;
  const waitMinutes = (() => {
    const start = serverMs(order.waiting_started_at);
    const end = serverMs(order.no_show_at);
    if (Number.isNaN(start) || Number.isNaN(end)) return null;
    const sec = (end - start) / 1000;
    return sec > 0 ? Math.max(1, Math.ceil(sec / 60)) : null;
  })();
  const hasFee = order.cancel_fee_kop > 0;
  const moneyLabel = hasFee ? appText("Плата за подачу зафиксирована", "Килеү хаҡы теркәлде") : appText("Без компенсации", "Компенсацияһыҙ");
  const moneyNote = hasFee
    ? appText("Пассажиру выставлена эта сумма. Деньги не списываются автоматически.", "Пассажирға был сумма ҡуйылды. Аҡса автоматик рәүештә алынмай.")
    : noShow
      ? appText("Сервер не указал плату за подачу. Если это ошибка — открой проблему с заказом.", "Сервер килеү хаҡын күрһәтмәне. Был хата булһа — заказ буйынса проблема ас.")
      : order.cancel_by === "passenger"
        ? appText("Отмена произошла до платного окна.", "Заказ түләүле ваҡыт башланғансы кире алынды.")
        : order.cancel_by === "driver"
          ? appText("Заказ отменён тобой — плата пассажиру не выставляется.", "Заказды һин кире алдың — пассажирға түләү ҡуйылмай.")
          : appText("Плата за отмену не зафиксирована.", "Кире алыу өсөн түләү теркәлмәгән.");
  const passengerId = order.passenger_id ?? 0;

  async function finishShift() {
    if (shiftEnding) return;
    setShiftEnding(true);
    setErrorText("");
    try {
      await setDriverOnline(false);
      onShiftFinished();
    } catch (e) {
      setErrorText(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось завершить смену. Проверь сеть и повтори.", "Сменаны тамамлап булманы. Селтәрҙе тикшереп ҡабатла.")
      );
    } finally {
      setShiftEnding(false);
    }
  }

  return (
    <div className="tdc">
      <div className="tdc__scroll">
        {/* Шапка: нейтральный круг 64 (не зелёная галочка), заголовок, статус · № заказа. */}
        <header className="tdx-head appear" style={{ "--i": 0 } as React.CSSProperties}>
          <span className="tdx-head__icon" aria-hidden>{noShow ? <IconEventBusy size={34} /> : <IconInfo size={34} />}</span>
          <h1>{title}</h1>
          <p>
            {status} · {appText(`Заказ № ${order.id}`, `Заказ № ${order.id}`)}
          </p>
        </header>

        <section className="tdx-route appear" style={{ "--i": 1 } as React.CSSProperties}>
          <RouteTimeline
            from={order.from_text}
            to={order.to_text}
            fromLabel={appText("Точка подачи", "Килеү нөктәһе")}
            toLabel={appText("Куда ехали", "Ҡайҙа бара инегеҙ")}
          />
        </section>

        <section className="tdx-facts appear" style={{ "--i": 2 } as React.CSSProperties}>
          <div className="tdx-facts__row">
            <Fact
              icon={arrival ? <IconClock size={21} /> : <IconCheckCircle size={21} />}
              label={arrival ? appText("Прибыл", "Килде") : appText("Заказ", "Заказ")}
              value={arrival ?? `№ ${order.id}`}
            />
            <Fact
              icon={noShow ? <IconEventBusy size={21} /> : <IconWarn size={21} />}
              label={noShow ? appText("Ожидание", "Көтөү") : appText("Отменил", "Кире алды")}
              value={
                noShow
                  ? waitMinutes != null
                    ? appText(`${waitMinutes}+ мин`, `${waitMinutes}+ мин`)
                    : appText("Завершено", "Тамамланған")
                  : order.cancel_by === "passenger"
                    ? appText("Пассажир", "Пассажир")
                    : order.cancel_by === "driver"
                      ? appText("Ты", "Һин")
                      : appText("Система", "Система")
              }
            />
          </div>
          <div className="tdx-facts__row">
            <Fact icon={<IconCar size={21} />} label={appText("Тариф", "Тариф")} value={categoryLabel(order.category, appText)} />
            <Fact icon={<PayMethodIcon method={order.payment_method} />} label={appText("Оплата", "Түләү")} value={payMethodShortLabel(order.payment_method, appText)} />
          </div>
        </section>

        {/* Деньги: зелёным празднуем только реальные деньги; ноль — нейтральная карточка. */}
        <section className={"tdx-money appear" + (hasFee ? " is-fee" : "")} style={{ "--i": 3 } as React.CSSProperties}>
          <span className="tdx-money__icon" aria-hidden>{hasFee ? <IconWallet size={28} /> : <IconInfo size={28} />}</span>
          <span className="tdx-money__text">
            <small>{moneyLabel}</small>
            <strong>{kopExactLabel(order.cancel_fee_kop)}</strong>
            <span>{moneyNote}</span>
          </span>
        </section>

        {successText && <p className="rcpt-msg rcpt-msg--ok">{successText}</p>}
        {errorText && <p className="rcpt-msg rcpt-msg--err">{errorText}</p>}

        {dispute && passengerId > 0 ? (
          <FileIncidentCard
            respondentId={passengerId}
            respondentName={order.passenger_name || appText("Пассажир", "Пассажир")}
            orderId={order.id}
            onCancel={() => setDispute(false)}
            onFiled={() => {
              setDispute(false);
              setDisputeFiled(true);
              setErrorText("");
              setSuccessText(appText("Разбор открыт. Мы сообщим о решении.", "Ҡарау асылды. Ҡарар тураһында хәбәр итербеҙ."));
            }}
            onError={setErrorText}
          />
        ) : (
          <button type="button" className="rcpt-act rcpt-act--lg tdx-problem appear" style={{ "--i": 4 } as React.CSSProperties} onClick={() => setChoice(true)}>
            <span className="rcpt-act__icon" aria-hidden><IconWarn size={22} /></span>
            <span className="rcpt-act__text">
              <strong>{appText("Проблема с заказом", "Заказ менән проблема")}</strong>
              <small>{disputeFiled ? appText("Разбор уже открыт", "Ҡарау асылған") : appText("Сообщить или открыть разбор", "Хәбәр итеү йәки ҡарау асыу")}</small>
            </span>
            <span className="rcpt-act__chev" aria-hidden><IconChevron size={22} /></span>
          </button>
        )}
      </div>

      <div className="tdc__bar">
        <button type="button" className="btn-primary tdc__primary" onClick={onReturnToLine} disabled={shiftEnding}>
          {appText("Вернуться на линию", "Линияға ҡайтыу")}
        </button>
        <button type="button" className="tdc__text-btn" onClick={() => void finishShift()} disabled={shiftEnding}>
          {shiftEnding ? <span className="spinner spinner--sm" aria-hidden /> : appText("Завершить смену", "Сменаны тамамлау")}
        </button>
      </div>

      {choice && (
        <AlertDialog title={appText("Что случилось?", "Нимә булды?")} onClose={() => setChoice(false)} dismiss={{ label: appText("Закрыть", "Ябыу"), onClick: () => setChoice(false) }}>
          <div className="adlg__choices">
            <button
              type="button"
              className="adlg__choice"
              onClick={() => {
                setChoice(false);
                navigate(`/report?order=${order.id}`);
              }}
            >
              <IconFlag size={24} />
              <span>
                <strong>{appText("Сообщить о нарушении", "Боҙоу тураһында хәбәр итеү")}</strong>
                <small>{appText("Анонимно, проверит человек", "Аноним, кеше тикшерәсәк")}</small>
              </span>
            </button>
            {passengerId > 0 && (
              <button
                type="button"
                className="adlg__choice"
                onClick={() => {
                  setChoice(false);
                  setDispute(true);
                }}
              >
                <IconShield size={24} />
                <span>
                  <strong>{appText("Открыть разбор", "Ҡарауҙы асыу")}</strong>
                  <small>{appText("Выслушаем обе стороны", "Ике яҡты ла тыңлаясаҡбыҙ")}</small>
                </span>
              </button>
            )}
          </div>
        </AlertDialog>
      )}
    </div>
  );
}
