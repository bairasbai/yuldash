// ================================================================
//  Правка и снятие своей поездки (rides.py: /rides/{id}/edit,
//  /rides/{id}/cancel). Зеркало Android RidesRequestsChatScreens.kt.
//
//  Чего в вебе не было. Опубликованную поездку нельзя было ни
//  исправить, ни снять: опечатка в цене («300» вместо «30») или
//  в часе выезда оставалась навсегда, а сломавшийся водитель просто
//  не приезжал — пассажиры узнавали об этом у обочины
//  (сверка с Android, 2026-08-30).
//
//  Честность перед пассажирами держит сервер, и мы говорим о ней
//  вслух: пока броней нет — меняется всё; когда есть — только
//  комментарий и цена ВНИЗ. Условия «купленного» не ухудшают.
// ================================================================
import { useState } from "react";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { cancelRide, editRide, type Ride } from "../api/rides";
import { IconWarn } from "./Icons";
import { SeatOptionsFields } from "./SeatOptions";

export default function RideEditActions({
  ride,
  onChanged,
  editOnly = false,
}: {
  ride: Ride;
  /** Поездка изменилась — родителю надо перечитать список. */
  onChanged: (r?: Ride) => void;
  /** Кабинет водителя: снятие рейса рисует родитель, здесь — только «Изменить цену и комментарий». */
  editOnly?: boolean;
}) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const [sheet, setSheet] = useState<"none" | "edit" | "cancel">("none");
  const [price, setPrice] = useState(String(ride.price ?? ""));
  const [comment, setComment] = useState("");
  // Опции мест — те же, что при публикации. Обещанное пассажирам сервер забрать не даст.
  const [backMaxTwo, setBackMaxTwo] = useState(Boolean(ride.back_max_two));
  const [frontSeatAvailable, setFrontSeatAvailable] = useState(ride.front_seat_available !== false);
  // RS-02 Б: цены остановок (включены, если были) — правятся тут же, идут отдельным полем.
  const [stopPricesOn, setStopPricesOn] = useState((ride.stop_prices?.length ?? 0) > 0);
  const [stopPriceValues, setStopPriceValues] = useState<string[]>((ride.stop_prices ?? []).map((s) => String(s.price)));
  const [stopPriceError, setStopPriceError] = useState(false);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState("");

  /** Есть ли уже забронированные места. По ним и меняются правила правки.
   *  RS-02 А: считаем из серверного seats_booked (правда живых броней), а не «всего − остаток». */
  const booked = Math.max(0, ride.seats_booked ?? ((ride.seats_total ?? 0) - (ride.seats_left ?? 0)));
  const stops = (ride.waypoints ?? "").split("|").map((s) => s.trim()).filter(Boolean);

  async function save() {
    if (busy) return;
    setBusy(true);
    setNote("");
    try {
      const body: { price?: number; comment?: string; back_max_two?: boolean; front_seat_available?: boolean; stop_prices?: number[]; expected_terms_version?: number } = {};
      const p = Math.round(Number(price));
      if (price.trim() && Number.isFinite(p) && p !== ride.price) body.price = Math.max(0, p);
      if (comment.trim()) body.comment = comment.trim().slice(0, 2000);
      if (backMaxTwo !== Boolean(ride.back_max_two)) body.back_max_two = backMaxTwo;
      if (frontSeatAvailable !== (ride.front_seat_available !== false)) body.front_seat_available = frontSeatAvailable;
      // RS-02 Б: цены остановок — только если изменились; выключены при включённых — одна цена ([]).
      if (stops.length) {
        const current = (ride.stop_prices ?? []).map((s) => s.price);
        if (!stopPricesOn) {
          if (current.length) body.stop_prices = [];
        } else {
          const nums = stopPriceValues.filter((v) => v.trim()).map((v) => parseInt(v, 10));
          const total = body.price ?? ride.price;
          const ok = nums.length === stops.length && nums.every((v, i) => Number.isInteger(v) && v > (i ? nums[i - 1] : 0) && v < total);
          if (!ok) {
            setStopPriceError(true);
            setNote(appText("Цены остановок: каждая больше предыдущей и меньше цены маршрута.",
                            "Туҡталыш хаҡтары: һәр береһе алдағыһынан ҙур һәм юл хаҡынан бәләкәй."));
            setBusy(false);
            return;
          }
          if (nums.join() !== current.join()) body.stop_prices = nums;
        }
      }
      if (!body.price && !body.comment && body.back_max_two === undefined && body.front_seat_available === undefined && body.stop_prices === undefined) {
        setNote(appText("Нечего менять", "Үҙгәртер нәмә юҡ"));
        setBusy(false);
        return;
      }
      // RS-02 А/Б: версия условий, которую видел водитель (stale → 409 ride_terms_changed).
      body.expected_terms_version = ride.terms_version;
      const r = await editRide(ride.id, body);
      setSheet("none");
      onChanged(r);
    } catch (e) {
      setNote(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не сохранилось. Проверь сеть.", "Һаҡланманы. Селтәрҙе тикшер.")
      );
    } finally {
      setBusy(false);
    }
  }

  async function drop() {
    if (busy) return;
    setBusy(true);
    setNote("");
    try {
      const r = await cancelRide(ride.id);
      setSheet("none");
      onChanged(r);
    } catch (e) {
      setNote(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось снять. Проверь сеть.", "Алып ташлап булманы. Селтәрҙе тикшер.")
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      {sheet === "none" && editOnly && (
        <button type="button" className="driver-ride__outlined driver-ride__outlined--wide" onClick={() => setSheet("edit")}>
          {appText("Изменить цену и комментарий", "Хаҡ һәм аңлатма үҙгәртеү")}
        </button>
      )}
      {sheet === "none" && !editOnly && (
        <div className="act-card__actions" style={{ marginTop: 8, flexWrap: "wrap" }}>
          <button type="button" className="btn-soft btn-soft--sm" onClick={() => setSheet("edit")}>
            {appText("Исправить", "Төҙәтеү")}
          </button>
          <button type="button" className="btn-soft btn-soft--sm" onClick={() => setSheet("cancel")}>
            {appText("Снять поездку", "Сәфәрҙе алып ташлау")}
          </button>
        </div>
      )}

      {/* Правка. Время и места при живых бронях сервер не отдаст — не обещаем их и мы. */}
      {sheet === "edit" && (
        <div className="act-card">
          <div className="act-card__title">{appText("Исправить поездку", "Сәфәрҙе төҙәтеү")}</div>
          <p className="act-card__text">
            {booked > 0
              ? appText(
                  `Места уже забронированы (${booked}). Поменять можно комментарий и цену — только вниз: то, на что человек согласился, ухудшать нельзя. Нужно другое время — сними поездку и создай новую.`,
                  `Урындар броньланған (${booked}). Комментарийҙы һәм хаҡты ғына үҙгәртеп була — түбәнгә генә: кеше риза булған шарттарҙы насарайтырға ярамай. Башҡа ваҡыт кәрәкһә — сәфәрҙе алып ташла ла яңыны яһа.`
                )
              : appText(
                  "Броней ещё нет — меняй свободно. Пассажирам с бронью потом уйдёт уведомление.",
                  "Броньдар юҡ әле — иркен үҙгәрт. Броньлағандарға һуңынан хәбәр китә."
                )}
          </p>
          <label className="field">
            <span className="field__label">{appText("Цена, ₽", "Хаҡ, һум")}</span>
            <input
              className="field__input"
              inputMode="numeric"
              value={price}
              onChange={(e) => setPrice(e.target.value.replace(/[^\d]/g, ""))}
              maxLength={6}
            />
          </label>
          {stops.length > 0 && (
            <div style={{ marginTop: 4 }}>
              <label style={{ display: "flex", alignItems: "center", gap: 8, minHeight: 48 }}>
                <input
                  type="checkbox"
                  className="checkbox"
                  checked={stopPricesOn}
                  onChange={(e) => { setStopPricesOn(e.target.checked); setStopPriceError(false); }}
                />
                <span>
                  <b>{appText("Цена до каждой остановки", "Һәр туҡталышҡа тиклем хаҡ")}</b>
                  <div className="sheet__note">{appText("Попутчик платит только за свой путь.", "Юлдаш тик үҙ юлы өсөн түләй.")}</div>
                </span>
              </label>
              {stopPricesOn && stops.map((stop, i) => (
                <label className="field" key={`esp-${i}`} style={{ marginTop: 6 }}>
                  <span className="field__label">{appText(`до ${stop}, ₽`, `${stop} тиклем, ₽`)}</span>
                  <input
                    className="field__input"
                    inputMode="numeric"
                    value={stopPriceValues[i] ?? ""}
                    onChange={(e) => {
                      const v = e.target.value.replace(/\D/g, "").slice(0, 5);
                      setStopPriceError(false);
                      setStopPriceValues((prev) => {
                        const next = [...prev];
                        while (next.length <= i) next.push("");
                        next[i] = v;
                        return next;
                      });
                    }}
                  />
                </label>
              ))}
              {stopPriceError && (
                <p className="sheet__note" role="alert" style={{ color: "var(--danger)" }}>
                  {appText("Цены остановок: каждая больше предыдущей и меньше цены маршрута.",
                           "Туҡталыш хаҡтары: һәр береһе алдағыһынан ҙур һәм юл хаҡынан бәләкәй.")}
                </p>
              )}
            </div>
          )}
          <label className="field">
            <span className="field__label">{appText("Комментарий", "Комментарий")}</span>
            <input
              className="field__input"
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              maxLength={200}
              placeholder={appText("«Выезжаю от автовокзала»", "«Автовокзалдан сығам»")}
            />
          </label>
          <SeatOptionsFields
            backMaxTwo={backMaxTwo}
            frontSeatAvailable={frontSeatAvailable}
            onBackMaxTwo={setBackMaxTwo}
            onFrontSeatAvailable={setFrontSeatAvailable}
          />
          <div className="act-card__actions">
            <button type="button" className="btn-primary" onClick={() => void save()} disabled={busy}>
              {appText("Сохранить", "Һаҡлау")}
            </button>
            <button type="button" className="btn-ghost" onClick={() => setSheet("none")} disabled={busy}>
              {appText("Отмена", "Кире алыу")}
            </button>
          </div>
          {note && (
            <div className="notice" role="status">
              {note}
            </div>
          )}
        </div>
      )}

      {/* Снятие рейса. Говорим прямо, что случится с людьми, которые уже забронировали. */}
      {sheet === "cancel" && (
        <div className="act-card act-card--warn">
          <div className="act-card__title">
            <IconWarn size={18} /> {appText("Снять поездку?", "Сәфәрҙе алып ташларғамы?")}
          </div>
          <p className="act-card__text">
            {booked > 0
              ? appText(
                  `Забронировано мест: ${booked}. Все брони отменятся, каждому пассажиру уйдёт уведомление — они успеют найти другую машину.`,
                  `Броньланған урын: ${booked}. Бөтә броньдар кире алына, һәр юлаусыға хәбәр китә — улар башҡа машина табып өлгөрә.`
                )
              : appText(
                  "Броней нет — поездка просто исчезнет из ленты.",
                  "Броньдар юҡ — сәфәр таҫманан юғала ла ҡуя."
                )}
          </p>
          <div className="act-card__actions">
            <button type="button" className="btn-danger" onClick={() => void drop()} disabled={busy}>
              {ru ? "Снять поездку" : "Алып ташлау"}
            </button>
            <button type="button" className="btn-ghost" onClick={() => setSheet("none")} disabled={busy}>
              {appText("Оставить", "Ҡалдырыу")}
            </button>
          </div>
          {note && (
            <div className="notice" role="status">
              {note}
            </div>
          )}
        </div>
      )}
    </>
  );
}
