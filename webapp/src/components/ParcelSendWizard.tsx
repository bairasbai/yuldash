// ================================================================
//  Вкладка «Отправить» — мастер в три шага, зеркало Android SendParcelTab
//  (ParcelsScreen.kt): «Куда и как» → «Что за посылка» → «Получатель».
//
//  Три способа доставки в одном мастере:
//   • «По пути»     — POST /parcels (попутчик, деньги напрямую, цену пишет отправитель);
//   • «Курьером» и «Купи и привези» — геокод городов → GET /courier/estimate
//     («Рассчитать доставку», карточка цены) → POST /courier/orders («Заказать доставку»).
//  Валидация пошаговая: дальше не пускаем, пока шаг не заполнен, а под погасшей кнопкой
//  прямо пишем, чего не хватает. Успех — крупный код вручения (ParcelCreatedView).
// ================================================================
import { useState } from "react";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { createParcel, type ParcelSize } from "../api/parcels";
import {
  createCourierOrder,
  estimateCourier,
  type CourierEstimate,
  type CourierUrgency,
} from "../api/courier";
import { searchSettlements } from "../api/geo";
import { geocode } from "../api/discovery";
import { track } from "../analytics";
import { IconBox, IconCar, IconCheck, IconCopy, IconRoute, IconStore } from "./Icons";
import {
  DeliveryBlockedHint,
  DeliveryErrorCard,
  DeliveryHint,
  DeliverySectionTitle,
  DeliveryTypeCard,
  DeliveryWaitNote,
  EstimateCard,
  MobilityScreenIntro,
  PARCEL_ADDRESS_MAX_LEN,
  PARCEL_DESC_MAX,
  PARCEL_MAX_WEIGHT_KG,
  ParcelCargoTypePicker,
  ParcelDeadlinePicker,
  ParcelFragileSwitch,
  ParcelRouteRow,
  ParcelRouteSummary,
  ParcelSizeCard,
  ParcelStepProgress,
  RulesCheckbox,
  UrgencyChip,
} from "./parcelForm";

type DeliveryType = "poputka" | "courier" | "buy_bring";
type Step = 0 | 1 | 2;
const STEP_ROUTE: Step = 0; // способ доставки + откуда/куда
const STEP_PARCEL: Step = 1; // что за посылка: размер, груз, срок, описание, цена, ценность
const STEP_RECEIVER: Step = 2; // получатель, правила, отправка
const STEP_TOTAL = 3;
/** Потолок денег как на сервере (100 000 ₽): без него шесть цифр в поле давали 422. */
const MONEY_CAP_KOP = 100_000_00;

interface GeoPoint {
  title: string;
  lat: number;
  lng: number;
}

/**
 * Сначала наш справочник (там все сёла РБ и приграничья с координатами), и только потом
 * геокодер: «Кузяново (Ишимбайский р-н)» он ищет хуже. Возвращает РАСПОЗНАННОЕ название —
 * его подставляем в поле, чтобы опечатка не ушла тихо в другой населённый пункт.
 */
async function resolveCity(city: string): Promise<GeoPoint | null> {
  const q = city.trim();
  if (!q) return null;
  try {
    const s = (await searchSettlements(q, 1)).find((x) => x.lat != null && x.lng != null);
    if (s && s.lat != null && s.lng != null) {
      const d = s.district?.trim() ?? "";
      const title = s.kind === "village" && d ? `${s.name_ru} (${d})` : s.name_ru;
      return { title, lat: s.lat, lng: s.lng };
    }
  } catch {
    /* справочник недоступен — пробуем геокодер */
  }
  try {
    const hit = (await geocode(q)).items?.[0];
    if (hit) return { title: hit.title, lat: hit.lat, lng: hit.lon };
  } catch {
    /* ответ ниже — «не удалось определить города» */
  }
  return null;
}

const digits = (s: string, max: number) => s.replace(/\D/g, "").slice(0, max);

interface Created {
  code: string;
  from: string;
  to: string;
  receiver: string;
}

export default function ParcelSendWizard({ onSent }: { onSent: () => void }) {
  const { appText } = useLang();

  const [created, setCreated] = useState<Created | null>(null);

  const [deliveryType, setDeliveryType] = useState<DeliveryType>("poputka");
  const [fromCity, setFromCity] = useState("");
  const [toCity, setToCity] = useState("");
  const [fromAddress, setFromAddress] = useState("");
  const [toAddress, setToAddress] = useState("");
  const [size, setSize] = useState<ParcelSize | "">("");
  const [weightInput, setWeightInput] = useState("");
  const [cargoType, setCargoType] = useState("");
  const [fragile, setFragile] = useState(false);
  const [description, setDescription] = useState("");
  const [receiverName, setReceiverName] = useState("");
  const [receiverPhone, setReceiverPhone] = useState("");
  const [rulesAccepted, setRulesAccepted] = useState(false);
  const [urgency, setUrgency] = useState<CourierUrgency>("bypath");
  const [deliverBy, setDeliverBy] = useState("");
  const [shoppingList, setShoppingList] = useState("");
  const [declaredRub, setDeclaredRub] = useState("");
  const [priceRub, setPriceRub] = useState("");
  const [productRub, setProductRub] = useState("");
  const [estimate, setEstimate] = useState<CourierEstimate | null>(null);
  const [fromPt, setFromPt] = useState<GeoPoint | null>(null);
  const [toPt, setToPt] = useState<GeoPoint | null>(null);
  const [working, setWorking] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [step, setStep] = useState<Step>(STEP_ROUTE);

  const isCourier = deliveryType !== "poputka";
  const productRubInt = productRub ? Number(productRub) : null;
  const buyBringOk =
    deliveryType !== "buy_bring" ||
    (shoppingList.trim().length > 0 && productRubInt != null && productRubInt >= 1 && productRubInt <= 5000);
  const baseFilled = fromCity.trim().length > 0 && toCity.trim().length > 0 && size !== "";
  const receiverOk = receiverName.trim().length > 0 && receiverPhone.trim().length > 0;
  // Вес: пусто = «не указан», и это нормально. Перебор не «подрезаем» втихую до 100.
  const weightKgInt = weightInput ? Number(weightInput) : null;
  const weightOk = !weightInput || (weightKgInt != null && weightKgInt <= PARCEL_MAX_WEIGHT_KG);
  const overWeight = !!weightInput && !weightOk;
  const routeOk = fromCity.trim().length > 0 && toCity.trim().length > 0;
  const parcelOk = size !== "" && buyBringOk && weightOk;

  const stepTitle =
    step === STEP_ROUTE
      ? appText("Куда и как", "Ҡайҙа һәм нисек")
      : step === STEP_PARCEL
        ? appText("Что за посылка", "Ниндәй бандероль")
        : appText("Получатель", "Алыусы");

  const sendErr = appText("Не получилось отправить. Проверь сеть и повтори.", "Ебәреп булманы. Селтәрҙе тикшереп ҡабатла.");
  const geoErr = appText("Не удалось определить города. Проверь названия.", "Ҡалаларҙы билдәләп булманы. Атамаларҙы тикшер.");
  const estErr = appText("Не удалось рассчитать. Проверь сеть.", "Иҫәпләп булманы. Селтәрҙе тикшер.");
  const apiMessage = (e: unknown, fallback: string) => (e instanceof ApiError && e.message ? e.message : fallback);

  /** Успех: показываем код и начинаем чистый черновик — повторный заказ по ошибке исключён. */
  function showCreatedReceipt(code: string, from: string, to: string, receiver: string) {
    setCreated({ code, from, to, receiver });
    setDeliveryType("poputka");
    setFromCity(""); setToCity(""); setFromAddress(""); setToAddress(""); setSize(""); setDescription("");
    setWeightInput(""); setCargoType(""); setFragile(false);
    setReceiverName(""); setReceiverPhone(""); setRulesAccepted(false);
    setUrgency("bypath"); setShoppingList(""); setDeclaredRub(""); setDeliverBy("");
    setPriceRub(""); setProductRub(""); setEstimate(null);
    setFromPt(null); setToPt(null);
    setError(null);
    setStep(STEP_ROUTE);
  }

  const weightKg = Math.min(PARCEL_MAX_WEIGHT_KG, Math.max(0, weightKgInt ?? 0));
  const declaredKop = Math.min(MONEY_CAP_KOP, Math.max(0, (Number(declaredRub) || 0) * 100));

  async function sendPoputka() {
    if (working || size === "" || !(baseFilled && receiverOk && rulesAccepted)) return;
    setWorking(true);
    setError(null);
    try {
      const p = await createParcel({
        from_city: fromCity.trim(),
        to_city: toCity.trim(),
        size,
        description: description.trim(),
        receiver_name: receiverName.trim(),
        receiver_phone: receiverPhone.trim(),
        rules_accepted: rulesAccepted,
        price_kop: Math.min(MONEY_CAP_KOP, Math.max(0, (Number(priceRub) || 0) * 100)),
        declared_value_kop: declaredKop,
        from_address: fromAddress.trim(),
        to_address: toAddress.trim(),
        deliver_by: deliverBy || null,
        weight_kg: weightKg,
        cargo_type: cargoType,
        fragile,
      });
      track("parcel_create");
      showCreatedReceipt(p.confirm_code ?? "", p.from_city, p.to_city, p.receiver_name);
    } catch (e) {
      setError(apiMessage(e, sendErr));
    } finally {
      setWorking(false);
    }
  }

  /** Курьер / купи-привези, шаг 1 — рассчитать цену (геокод городов + оценка сервера). */
  async function calcEstimate() {
    if (working || size === "" || !(baseFilled && buyBringOk)) return;
    setWorking(true);
    setError(null);
    try {
      const [f, t] = await Promise.all([resolveCity(fromCity), resolveCity(toCity)]);
      if (!f || !t) {
        setError(geoErr);
        return;
      }
      setFromCity(f.title);
      setToCity(t.title);
      setFromPt(f);
      setToPt(t);
      try {
        const est = await estimateCourier({
          from_lat: f.lat,
          from_lng: f.lng,
          to_lat: t.lat,
          to_lng: t.lng,
          size,
          urgency,
          delivery_type: deliveryType === "buy_bring" ? "buy_bring" : "courier",
        });
        setEstimate(est);
      } catch (e) {
        setError(apiMessage(e, estErr));
      }
    } finally {
      setWorking(false);
    }
  }

  /** Курьер / купи-привези, шаг 2 — оформить заказ. */
  async function orderCourier() {
    if (working || size === "" || !(receiverOk && rulesAccepted) || !fromPt || !toPt) return;
    setWorking(true);
    setError(null);
    try {
      const r = await createCourierOrder({
        from_city: fromCity.trim(),
        to_city: toCity.trim(),
        from_lat: fromPt.lat,
        from_lng: fromPt.lng,
        to_lat: toPt.lat,
        to_lng: toPt.lng,
        size,
        description: description.trim(),
        receiver_name: receiverName.trim(),
        receiver_phone: receiverPhone.trim(),
        rules_accepted: rulesAccepted,
        delivery_type: deliveryType === "buy_bring" ? "buy_bring" : "courier",
        urgency,
        cod_amount_kop: deliveryType === "buy_bring" ? (productRubInt ?? 0) * 100 : undefined,
        shopping_list: deliveryType === "buy_bring" ? shoppingList.trim() : undefined,
        declared_value_kop: declaredKop > 0 ? declaredKop : undefined,
        from_address: fromAddress.trim(),
        to_address: toAddress.trim(),
        deliver_by: deliverBy || null,
        weight_kg: weightKg,
        cargo_type: cargoType,
        fragile,
      });
      track("parcel_create");
      showCreatedReceipt(String(r.confirm_code ?? ""), fromCity.trim(), toCity.trim(), receiverName.trim());
    } catch (e) {
      setError(apiMessage(e, sendErr));
    } finally {
      setWorking(false);
    }
  }

  if (created) {
    return (
      <ParcelCreatedView
        confirmCode={created.code}
        fromCity={created.from}
        toCity={created.to}
        receiverName={created.receiver}
        onDone={() => {
          setCreated(null);
          onSent();
        }}
      />
    );
  }

  // Смена типа/города/размера/срочности сбрасывает расчёт: цена считалась для других условий.
  const pickType = (t: DeliveryType) => { setDeliveryType(t); setEstimate(null); };
  const addrAtLimit = fromAddress.length >= PARCEL_ADDRESS_MAX_LEN || toAddress.length >= PARCEL_ADDRESS_MAX_LEN;

  const nextHint =
    step === STEP_ROUTE && !fromCity.trim()
      ? appText("Укажи, откуда забрать посылку", "Бандерольде ҡайҙан алырға икәнен күрһәт")
      : step === STEP_ROUTE && !toCity.trim()
        ? appText("Укажи, куда её привезти", "Уны ҡайҙа илтергә икәнен күрһәт")
        : step === STEP_PARCEL && size === ""
          ? appText("Выбери размер посылки — он выше", "Бандероль үлсәмен һайла — ул юғарыраҡ")
          : step === STEP_PARCEL && !weightOk
            ? appText("Вес больше 100 кг — это уже грузоперевозка", "Ауырлыҡ 100 кг-дан артыҡ — был инде йөк ташыу")
            : step === STEP_PARCEL && !buyBringOk
              ? appText("Укажи стоимость покупки", "Һатып алыу хаҡын күрһәт")
              : null;
  const sendHint = !receiverName.trim()
    ? appText("Впиши имя получателя", "Алыусының исемен яҙ")
    : !receiverPhone.trim()
      ? appText("Впиши телефон получателя", "Алыусының телефонын яҙ")
      : !rulesAccepted
        ? appText("Отметь галочку с правилами доставки", "Доставка ҡағиҙәләре янындағы билдәне ҡуй")
        : null;
  const blocked = step === STEP_ROUTE ? !routeOk : !parcelOk;

  return (
    <div className="dl-form">
      <MobilityScreenIntro
        mode="courier"
        title={appText("Что доставим?", "Нимә илтәбеҙ?")}
        subtitle={appText(
          "Выбери способ — маршрут, цена и условия будут видны до заказа.",
          "Ысулды һайла — маршрут, хаҡ һәм шарттар заказға тиклем күренә."
        )}
        badge={appText("Доставка", "Илтеү")}
      />
      <ParcelStepProgress step={step} total={STEP_TOTAL} title={stepTitle} />

      {/* Смена шага — с затуханием (AnimatedContent в Android), а не одним кадром. */}
      <div className="dl-step" key={step}>
      {step !== STEP_ROUTE && (
        <ParcelRouteSummary from={fromCity} to={toCity} onEdit={() => { if (!working) setStep(STEP_ROUTE); }} />
      )}

      {/* ── Шаг 1: способ доставки + маршрут ─────────────────────────────── */}
      {step === STEP_ROUTE && (
        <>
          <div className="dl-stack" role="radiogroup" aria-label={appText("Способ доставки", "Илтеү ысулы")}>
            <DeliveryTypeCard
              selected={deliveryType === "poputka"}
              title={appText("По пути", "Юл ыңғайы")}
              subtitle={appText("Попутчик, который и так едет. Дёшево, по-соседски.", "Юл ыңғайы бараған юлдаш. Арзан, күршеләрсә.")}
              icon={<IconCar size={20} />}
              onClick={() => pickType("poputka")}
            />
            <DeliveryTypeCard
              selected={deliveryType === "courier"}
              title={appText("Заказать курьера", "Курьер заказлау")}
              subtitle={appText("Проверенный курьер Юлдаша заберёт и доставит. Цена — сразу.", "Юлдаштың тикшерелгән курьеры алып илтер. Хаҡы — шунда уҡ.")}
              icon={<IconRoute size={20} />}
              onClick={() => pickType("courier")}
            />
            <DeliveryTypeCard
              selected={deliveryType === "buy_bring"}
              title={appText("Купи и привези", "Ал да килтер")}
              subtitle={appText("Курьер купит товар за тебя и привезёт. До 5000 ₽.", "Курьер һинең өсөн тауар алып килтерер. 5000 ₽-ға тиклем.")}
              icon={<IconStore size={20} />}
              onClick={() => pickType("buy_bring")}
            />
            <DeliveryWaitNote type={deliveryType} />
          </div>

          {/* Каждый город идёт в паре со своим ориентиром — как рассказывал бы дорогу вслух. */}
          <ParcelField
            value={fromCity}
            onValue={(v) => { setFromCity(v); setEstimate(null); }}
            label={appText("Откуда", "Ҡайҙан")}
            placeholder={appText("Город отправления", "Ебәреү ҡалаһы")}
            hint={appText("Город или село", "Ҡала йәки ауыл")}
            autoCapitalize="words"
          />
          <ParcelField
            value={fromAddress}
            onValue={(v) => setFromAddress(v.slice(0, PARCEL_ADDRESS_MAX_LEN))}
            label={appText("Где забрать", "Ҡайҙан алырға")}
            placeholder={appText("У мечети, синие ворота", "Мәсет янында, зәңгәр ҡапҡа")}
            hint={appText("Место в этом селе: «у мечети», «синие ворота»", "Ошо ауылдағы урын: «мәсет янында», «зәңгәр ҡапҡа»")}
            multiline
          />
          <ParcelField
            value={toCity}
            onValue={(v) => { setToCity(v); setEstimate(null); }}
            label={appText("Куда", "Ҡайҙа")}
            placeholder={appText("Город получения", "Алыу ҡалаһы")}
            hint={appText("Город или село", "Ҡала йәки ауыл")}
            autoCapitalize="words"
          />
          <ParcelField
            value={toAddress}
            onValue={(v) => setToAddress(v.slice(0, PARCEL_ADDRESS_MAX_LEN))}
            label={appText("Куда привезти", "Ҡайҙа илтергә")}
            placeholder={appText("За школой, белый дом с зелёной крышей", "Мәктәп артында, йәшел түбәле аҡ йорт")}
            hint={appText("Место в том селе: «за школой», «белый дом»", "Теге ауылдағы урын: «мәктәп артында», «аҡ йорт»")}
            multiline
          />
          <DeliveryHint tone={addrAtLimit ? "danger" : "muted"}>
            {addrAtLimit
              ? appText(
                  `Больше ${PARCEL_ADDRESS_MAX_LEN} символов не влезет — оставь самое главное.`,
                  `${PARCEL_ADDRESS_MAX_LEN} символдан артыҡ һыймай — иң мөһимен ҡалдыр.`
                )
              : appText(
                  "Пиши не улицу с табличкой, а как объясняешь соседу. Курьер увидит эти ориентиры, когда возьмёт посылку.",
                  "Таблицалы урамды түгел, ә күршегә аңлатҡан кеүек яҙ. Курьер был билдәләрҙе бандеролде алғас күрер."
                )}
          </DeliveryHint>
        </>
      )}

      {/* ── Шаг 2: сама посылка ───────────────────────────────────────────── */}
      {step === STEP_PARCEL && (
        <>
          <DeliverySectionTitle>{appText("Размер посылки", "Бандероль ҙурлығы")}</DeliverySectionTitle>
          <div className="dl-stack" role="radiogroup" aria-label={appText("Размер посылки", "Бандероль ҙурлығы")}>
            {(["small", "medium", "large"] as ParcelSize[]).map((s) => (
              <ParcelSizeCard key={s} size={s} selected={size === s} onClick={() => { setSize(s); setEstimate(null); }} />
            ))}
          </div>

          {/* Что за груз — сразу за размером: «унесу ли», «возьмусь ли», «как положить». */}
          <DeliverySectionTitle>{appText("Что за груз", "Ниндәй йөк")}</DeliverySectionTitle>
          <ParcelField
            value={weightInput}
            onValue={(v) => setWeightInput(digits(v, 3))}
            label={appText("Вес", "Ауырлыҡ")}
            placeholder={appText("Примерно, в килограммах", "Яҡынса, килограммда")}
            inputMode="numeric"
          />
          <DeliveryHint tone={overWeight ? "danger" : "muted"}>
            {overWeight
              ? appText(
                  `Больше ${PARCEL_MAX_WEIGHT_KG} кг — это уже грузоперевозка, а не посылка. Уменьши вес.`,
                  `${PARCEL_MAX_WEIGHT_KG} кг-дан артыҡ — был инде йөк ташыу, бандероль түгел. Ауырлыҡты кәметер.`
                )
              : appText(
                  "Необязательно, но курьер сразу поймёт, унесёт ли один. Точность до грамма не нужна — хватит «на глаз».",
                  "Мотлаҡ түгел, әммә курьер шунда уҡ яңғыҙы күтәрә аламы-юҡмы аңлар. Граммға тиклем теүәллек кәрәкмәй — «күҙ менән» етә."
                )}
          </DeliveryHint>
          <ParcelCargoTypePicker value={cargoType} onValue={setCargoType} />
          <ParcelFragileSwitch checked={fragile} onToggle={() => setFragile((v) => !v)} />

          {/* Срок — у ВСЕХ типов, включая «по пути»: именно там непонятно, сегодня или через неделю. */}
          <DeliverySectionTitle>{appText("Когда нужно", "Ҡасан кәрәк")}</DeliverySectionTitle>
          <ParcelDeadlinePicker value={deliverBy} onValue={setDeliverBy} />

          {isCourier && (
            <>
              <DeliverySectionTitle>{appText("Как везти", "Нисек илтергә")}</DeliverySectionTitle>
              <div className="deadline__row" role="radiogroup" aria-label={appText("Как везти", "Нисек илтергә")}>
                <UrgencyChip
                  title={appText("По пути", "Юл ыңғайы")}
                  subtitle={appText("дешевле", "арзаныраҡ")}
                  selected={urgency === "bypath"}
                  onClick={() => { setUrgency("bypath"); setEstimate(null); }}
                />
                <UrgencyChip
                  title={appText("Срочно", "Ашығыс")}
                  subtitle={appText("сейчас", "хәҙер")}
                  selected={urgency === "now"}
                  onClick={() => { setUrgency("now"); setEstimate(null); }}
                />
              </div>
            </>
          )}

          {deliveryType === "buy_bring" && (
            <>
              <ParcelField
                value={shoppingList}
                onValue={setShoppingList}
                label={appText("Что купить", "Нимә алырға")}
                placeholder={appText("Например: хлеб, молоко, лекарство из аптеки", "Мәҫәлән: икмәк, һөт, дарыуханан дарыу")}
                multiline
              />
              <ParcelField
                value={productRub}
                onValue={(v) => { setProductRub(digits(v, 5)); setEstimate(null); }}
                label={appText("Сумма покупки, ₽", "Һатып алыу суммаһы, ₽")}
                placeholder="0"
                inputMode="numeric"
              />
              <DeliveryHint tone={productRubInt != null && productRubInt > 5000 ? "danger" : "muted"}>
                {productRubInt != null && productRubInt > 5000
                  ? appText("Лимит покупки — 5000 ₽. Уменьши сумму.", "Һатып алыу лимиты — 5000 ₽. Сумманы кәметер.")
                  : appText("Курьер купит на эту сумму, а получатель вернёт её при вручении.", "Курьер шул суммаға алыр, алыусы тапшырғанда кире ҡайтарыр.")}
              </DeliveryHint>
            </>
          )}

          <ParcelField
            value={description}
            onValue={(v) => setDescription(v.slice(0, PARCEL_DESC_MAX))}
            label={appText("Что за посылка", "Нимә бул")}
            placeholder={appText("Например: документы, книга, гостинец", "Мәҫәлән: документтар, китап, күстәнәс")}
            multiline
          />

          {/* Сколько заплатишь попутчику — только «по пути»: у курьерских цену считает сервер. */}
          {!isCourier && (
            <>
              <ParcelField
                value={priceRub}
                onValue={(v) => setPriceRub(digits(v, 6))}
                label={appText("Сколько заплатишь попутчику, ₽", "Юлдашҡа күпме түләйһең, ₽")}
                placeholder="0"
                inputMode="numeric"
              />
              <DeliveryHint>
                {(Number(priceRub) || 0) > 0
                  ? appText("Отдашь эти деньги попутчику лично — Юлдаш к ним не прикасается.", "Был аҡсаны юлдашҡа үҙең бирәһең — Юлдаш уға ҡағылмай.")
                  : appText("Оставь пусто — значит по-соседски, бесплатно. Так и увидит попутчик.", "Буш ҡалдыр — тимәк күрше хаҡы, бушлай. Юлдаш шулай күрер.")}
              </DeliveryHint>
            </>
          )}

          <ParcelField
            value={declaredRub}
            onValue={(v) => setDeclaredRub(digits(v, 6))}
            label={appText("Ценность посылки, ₽ (необязательно)", "Бандероль хаҡы, ₽ (мотлаҡ түгел)")}
            placeholder="0"
            inputMode="numeric"
          />
          <DeliveryHint>
            {appText(
              "Если что-то случится, это будет ориентиром при разборе. Не страховка — но без цифры спорить не о чем.",
              "Берәй хәл булһа, был ҡарағанда ориентир булыр. Страховка түгел — әммә һанһыҙ бәхәсләшер нәмә юҡ."
            )}
          </DeliveryHint>
        </>
      )}

      {/* ── Шаг 3: получатель, правила, отправка ─────────────────────────── */}
      {step === STEP_RECEIVER && (
        <>
          <DeliverySectionTitle>{appText("Получатель", "Алыусы")}</DeliverySectionTitle>
          <ParcelField
            value={receiverName}
            onValue={setReceiverName}
            label={appText("Имя получателя", "Алыусы исеме")}
            placeholder={appText("Кто встретит курьера", "Курьерҙы кем ҡаршылай")}
            autoCapitalize="words"
          />
          <ParcelField
            value={receiverPhone}
            onValue={setReceiverPhone}
            label={appText("Телефон получателя", "Алыусы телефоны")}
            placeholder="+7 …"
            inputMode="tel"
          />
          {isCourier && estimate && <EstimateCard est={estimate} />}
          <RulesCheckbox checked={rulesAccepted} onToggle={() => setRulesAccepted((v) => !v)} />
          <p className="dl-note">
            {isCourier
              ? appText(
                  "Курьер отвечает за сохранность. Не отправляй запрещённое: деньги, документы на предъявителя, лекарства без рецепта, скоропорт, оружие.",
                  "Курьер һаҡлыҡ өсөн яуаплы. Тыйылғанды ебәрмә: аҡса, күрһәтеүсегә документтар, рецептһыҙ дарыу, тиҙ боҙолған аҙыҡ, ҡорал."
                )
              : appText(
                  "Курьер — обычный попутчик, а не служба доставки. Не клади ценное, хрупкое или запрещённое. Ответственность за содержимое — на тебе.",
                  "Курьер — ябай юлдаш, доставка хеҙмәте түгел. Ҡиммәтле, ватыҡ йәки тыйылған әйберҙе һалма. Эстәлеге өсөн яуаплылыҡ — һиндә."
                )}
          </p>
        </>
      )}

      <DeliveryErrorCard message={error} />

      {/* Шаги 1–2: «Далее». Кнопка гаснет, пока шаг не заполнен, и под ней прямо сказано почему. */}
      {step !== STEP_RECEIVER && (
        <div className="dl-actions">
          <button
            type="button"
            className="btn-primary btn-accent submit-btn"
            disabled={blocked}
            onClick={() => {
              if (step === STEP_ROUTE && routeOk) setStep(STEP_PARCEL);
              else if (step === STEP_PARCEL && parcelOk) setStep(STEP_RECEIVER);
            }}
          >
            {appText("Далее", "Артабан")}
          </button>
          {blocked && nextHint && <DeliveryBlockedHint>{nextHint}</DeliveryBlockedHint>}
        </div>
      )}

      {step === STEP_RECEIVER && (
        <div className="dl-actions">
          {sendHint && <DeliveryBlockedHint>{sendHint}</DeliveryBlockedHint>}
          {!isCourier ? (
            <button
              type="button"
              className="btn-primary btn-accent submit-btn"
              disabled={working || !(baseFilled && receiverOk && rulesAccepted)}
              onClick={sendPoputka}
            >
              {working ? appText("Отправляем…", "Ебәрәбеҙ…") : <><IconBox size={18} /> {appText("Отправить посылку", "Бандероль ебәреү")}</>}
            </button>
          ) : estimate == null ? (
            <button
              type="button"
              className="btn-primary submit-btn"
              disabled={working || !(baseFilled && buyBringOk)}
              onClick={calcEstimate}
            >
              {working ? appText("Считаем…", "Иҫәпләйбеҙ…") : appText("Рассчитать доставку", "Илтеүҙе иҫәпләү")}
            </button>
          ) : (
            <button
              type="button"
              className="btn-primary btn-accent submit-btn"
              disabled={working || !(receiverOk && rulesAccepted)}
              onClick={orderCourier}
            >
              {working ? appText("Отправляем…", "Ебәрәбеҙ…") : <><IconRoute size={18} /> {appText("Заказать доставку", "Илтеүҙе заказлау")}</>}
            </button>
          )}
        </div>
      )}

      {/* «Назад» — на любом шаге кроме первого; черновик при этом не теряется. */}
      {step !== STEP_ROUTE && (
        <button
          type="button"
          className="btn-soft dl-back"
          disabled={working}
          onClick={() => { if (!working) setStep((step - 1) as Step); }}
        >
          {appText("Назад", "Артҡа")}
        </button>
      )}
      </div>
    </div>
  );
}

/** Поле формы доставки: подпись над полем, пример внутри, подсказка под ним — видна всегда. */
function ParcelField({
  value,
  onValue,
  label,
  placeholder,
  hint,
  multiline = false,
  inputMode,
  autoCapitalize,
}: {
  value: string;
  onValue: (v: string) => void;
  label: string;
  placeholder: string;
  hint?: string;
  multiline?: boolean;
  inputMode?: "numeric" | "tel" | "text";
  autoCapitalize?: "words" | "sentences";
}) {
  return (
    <label className="field dl-field">
      <span className="field__label">{label}</span>
      {multiline ? (
        <textarea
          className="field__input field__area dl-field__area"
          value={value}
          onChange={(e) => onValue(e.target.value)}
          placeholder={placeholder}
          rows={2}
          autoCapitalize={autoCapitalize ?? "sentences"}
        />
      ) : (
        <input
          className="field__input"
          value={value}
          onChange={(e) => onValue(e.target.value)}
          placeholder={placeholder}
          inputMode={inputMode}
          autoCapitalize={autoCapitalize ?? "sentences"}
        />
      )}
      {hint && <span className="field__hint">{hint}</span>}
    </label>
  );
}

// ─────────────────────────── Успех: код вручения ───────────────────────────
function ParcelCreatedView({
  confirmCode,
  fromCity,
  toCity,
  receiverName,
  onDone,
}: {
  confirmCode: string;
  fromCity: string;
  toCity: string;
  receiverName: string;
  onDone: () => void;
}) {
  const { appText } = useLang();
  const [copied, setCopied] = useState(false);
  function copyCode() {
    navigator.clipboard?.writeText(confirmCode).then(
      () => {
        setCopied(true);
        setTimeout(() => setCopied(false), 1800);
      },
      () => {}
    );
  }
  return (
    <div className="parcel-created">
      {/* Печать «готово» вырастает после первого кадра — иначе экран успеха просто «появляется». */}
      <span className="parcel-created__seal" aria-hidden><IconCheck size={36} /></span>
      <h2 className="parcel-created__title">{appText("Посылка создана!", "Бандероль булдырылды!")}</h2>
      <p className="parcel-created__sub">
        {appText(
          "Как только попутчик её возьмёт — ты увидишь курьера и его телефон.",
          "Юлдаш уны алыу менән — курьерҙы һәм телефонын күрерһең."
        )}
      </p>
      <div className="parcel-created__code">
        <span className="parcel-created__code-label">{appText("Код вручения", "Тапшырыу коды")}</span>
        <b className="parcel-created__code-value">{confirmCode}</b>
        {confirmCode && (
          <button type="button" className="parcel-created__copy" onClick={copyCode}>
            {copied ? <IconCheck size={20} /> : <IconCopy size={20} />}
            {copied ? appText("Скопировано", "Күсерелде") : appText("Скопировать", "Күсереп алыу")}
          </button>
        )}
      </div>
      <p className="dl-note">
        {appText(
          "Передай этот код получателю (например, в сообщении). Курьер спросит его при вручении — так посылка попадёт в нужные руки.",
          "Был кодты алыусыға тапшыр (мәҫәлән, хәбәрҙә). Курьер уны тапшырғанда һорар — шулай бандероль кәрәкле ҡулға етер."
        )}
      </p>
      <div className="parcel-created__route">
        <ParcelRouteRow from={fromCity} to={toCity} />
        <p className="dl-hint">{appText("Получатель: ", "Алыусы: ")}{receiverName}</p>
      </div>
      <button type="button" className="btn-primary submit-btn" onClick={onDone}>
        {appText("Готово", "Әҙер")}
      </button>
    </div>
  );
}
