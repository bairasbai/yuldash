// ================================================================
//  «Посылки» (M3). RequireAuth → /parcels. Три вкладки:
//   • Отправить — форма → POST /parcels → крупный код вручения + сбор;
//   • Мои — GET /parcels/mine (статус, код, курьер, «Отменить»);
//   • Возить — «по пути» доставка: Доступные (GET /parcels/available,
//     «Взять») и Везу (GET /parcels/carrying, телефон + статусы).
//
//  Приватность: телефон получателя — только у отправителя (его данные)
//  и у принявшего курьера. Код вручения видит только отправитель.
//  Эндпоинты /parcels/* уже на проде.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  createParcel,
  fetchMyParcels,
  cancelParcel,
  parcelRedeliverRequest,
  fetchAvailableParcels,
  acceptParcel,
  setParcelStatus,
  fetchCarrying,
  type Parcel,
  type ParcelSize,
} from "../api/parcels";
import { rubLabel } from "../utils/format";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList, ErrorState } from "../components/States";
import {
  StatusPillParcel,
  sizeLabel,
  AvailableParcelCard,
  CarryParcelCard,
  CodeDialog,
} from "../components/parcelUi";
import ParcelRate from "../components/ParcelRate";
import ParcelProblemActions from "../components/ParcelProblemActions";
import { IconBox, IconCheck, IconChat, IconCopy, IconGift, IconRoute, IconShield, IconStar } from "../components/Icons";

type Tab = "send" | "mine" | "carry";


/** Разбор компенсации за отмену по строкам: «(100 ₽ — отмена + 300 ₽ — дорога курьера)».
 *  Пусто, если сервер старый и разбора не прислал: тогда человек видит только итог. */
function cancelParts(p: Parcel, подписи: [string, string, string]): string {
  const ч = p.cancel_fee_parts;
  if (!ч) return "";
  const строки = [
    [ч.base_kop, подписи[0]] as const,
    [ч.pickup_kop, подписи[1]] as const,
    [ч.waiting_kop, подписи[2]] as const,
  ]
    .filter(([kop]) => kop > 0)
    .map(([kop, имя]) => `${Math.round(kop / 100)} ₽ — ${имя}`);
  return строки.length ? ` (${строки.join(" + ")})` : "";
}

const cancelPartsRu = (p: Parcel) => cancelParts(p, ["отмена", "дорога курьера", "ожидание"]);
const cancelPartsBa = (p: Parcel) => cancelParts(p, ["кире алыу", "курьер юлы", "көтөү"]);

export default function ParcelsScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();
  const [tab, setTab] = useState<Tab>("send");

  return (
    <>
      <SubHeader
        title={appText("Посылки", "Бандеролдәр")}
        subtitle={appText("Доставка между сёлами «между своими»", "Ауылдар араһында «үҙебеҙ» доставка")}
        onBack={() => navigate(-1)}
      />

      <div className="taxi-when parcel-tabs">
        <button type="button" className={"taxi-when__tab" + (tab === "send" ? " is-active" : "")} onClick={() => setTab("send")}>
          {appText("Отправить", "Ебәреү")}
        </button>
        <button type="button" className={"taxi-when__tab" + (tab === "mine" ? " is-active" : "")} onClick={() => setTab("mine")}>
          {appText("Мои", "Минеке")}
        </button>
        <button type="button" className={"taxi-when__tab" + (tab === "carry" ? " is-active" : "")} onClick={() => setTab("carry")}>
          {appText("Возить", "Йөрөтөү")}
        </button>
      </div>

      {tab === "send" && <SendTab onSent={() => setTab("mine")} />}
      {tab === "mine" && <MineTab />}
      {tab === "carry" && <CarryTab />}
    </>
  );
}

// ============================ Вкладка «Отправить» ============================
function SendTab({ onSent }: { onSent: () => void }) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";

  const [fromCity, setFromCity] = useState("");
  const [toCity, setToCity] = useState("");
  const [size, setSize] = useState<ParcelSize>("small");
  const [desc, setDesc] = useState("");
  const [receiver, setReceiver] = useState("");
  const [phone, setPhone] = useState("");
  const [rules, setRules] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [created, setCreated] = useState<Parcel | null>(null);
  const [copied, setCopied] = useState(false);

  // «Что и за сколько везём» — без этого курьер видел маршрут и размер, а решить,
  // браться или нет, было не по чему. Блок сворачиваемый: обязательного тут ничего нет.
  const [more, setMore] = useState(false);
  const [fromAddr, setFromAddr] = useState("");
  const [toAddr, setToAddr] = useState("");
  const [price, setPrice] = useState("");
  const [value, setValue] = useState("");
  const [weight, setWeight] = useState("");
  const [fragile, setFragile] = useState(false);
  const [deliverBy, setDeliverBy] = useState("");

  const weightNum = Number(weight.replace(",", "."));
  const weightOk = !weight.trim() || (Number.isFinite(weightNum) && weightNum <= 100);

  const canSubmit =
    fromCity.trim() && toCity.trim() && receiver.trim() && rules && weightOk && !busy;

  async function submit() {
    if (!canSubmit) return;
    setBusy(true);
    setError(null);
    try {
      const p = await createParcel({
        from_city: fromCity.trim(),
        to_city: toCity.trim(),
        size,
        description: desc.trim(),
        receiver_name: receiver.trim(),
        receiver_phone: phone.trim(),
        rules_accepted: rules,
        // Деньги — в копейках: сервер везде считает целыми, чтобы не терять на округлении.
        price_kop: price.trim() ? Math.max(0, Math.round(Number(price) * 100)) : 0,
        declared_value_kop: value.trim() ? Math.max(0, Math.round(Number(value) * 100)) : 0,
        from_address: fromAddr.trim(),
        to_address: toAddr.trim(),
        weight_kg: weight.trim() ? Math.max(0, weightNum) : 0,
        fragile,
        deliver_by: deliverBy || null,
      });
      setCreated(p);
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось создать заявку. Попробуй снова.", "Заявка яһап булманы. Ҡабат ҡара.")
      );
    } finally {
      setBusy(false);
    }
  }

  function copyCode(code: string) {
    navigator.clipboard?.writeText(code).then(
      () => {
        setCopied(true);
        setTimeout(() => setCopied(false), 1800);
      },
      () => {}
    );
  }

  // Успех: крупный код вручения + сбор.
  if (created) {
    const price = created.price_kop > 0 ? created.price_kop : created.fee_kop;
    return (
      <div className="parcel-done">
        <div className="parcel-done__emoji"><IconBox size={34} /></div>
        <h2>{appText("Заявка создана!", "Заявка яһалды!")}</h2>
        <p className="parcel-card__desc" style={{ textAlign: "center" }}>
          {appText(
            "Назови этот код получателю. Он скажет его курьеру при получении — так подтвердится вручение.",
            "Был кодты алыусыға әйт. Ул уны курьерға тапшырғанда әйтә — тапшырыу шулай раҫлана."
          )}
        </p>
        <div className="parcel-code">
          <div className="parcel-code__label">{appText("Код вручения", "Тапшырыу коды")}</div>
          <div className="parcel-code__value">{created.confirm_code}</div>
          <button type="button" className="btn-soft" onClick={() => copyCode(created.confirm_code || "")}>
            {copied ? <IconCheck size={18} /> : <IconCopy size={18} />}
            {copied ? appText("Скопировано", "Күсерелде") : appText("Копировать", "Күсереү")}
          </button>
        </div>
        <div className="info-list">
          <div className="info-row">
            <span className="info-row__k">{appText("Маршрут", "Юл")}</span>
            <span className="info-row__v">{created.from_city} → {created.to_city}</span>
          </div>
          <div className="info-row">
            <span className="info-row__k">{appText("Размер", "Үлсәм")}</span>
            <span className="info-row__v">{sizeLabel(created.size, ru)}</span>
          </div>
          <div className="info-row">
            <span className="info-row__k">{appText("Сбор Юлдаша", "Юлдаш сбыры")}</span>
            <span className="info-row__v">{rubLabel(price)}</span>
          </div>
        </div>
        <button type="button" className="btn-primary submit-btn" style={{ marginTop: 14 }} onClick={onSent}>
          {appText("К моим посылкам", "Минең бандеролдәргә")}
        </button>
      </div>
    );
  }

  return (
    <>
      <div className="field-row" style={{ marginTop: 14 }}>
        <label className="field" style={{ flex: 1 }}>
          <span className="field__label">{appText("Откуда", "Ҡайҙан")}</span>
          <input className="field__input" value={fromCity} onChange={(e) => setFromCity(e.target.value)} placeholder={appText("Село/город", "Ауыл/ҡала")} />
        </label>
        <label className="field" style={{ flex: 1 }}>
          <span className="field__label">{appText("Куда", "Ҡайҙа")}</span>
          <input className="field__input" value={toCity} onChange={(e) => setToCity(e.target.value)} placeholder={appText("Село/город", "Ауыл/ҡала")} />
        </label>
      </div>

      {/* Размер */}
      <span className="field__label" style={{ marginTop: 14, display: "block" }}>
        {appText("Размер посылки", "Бандероль үлсәме")}
      </span>
      <div className="seg" style={{ marginTop: 6 }}>
        {(["small", "medium", "large"] as ParcelSize[]).map((s) => (
          <button key={s} type="button" className={"seg__item" + (size === s ? " is-active" : "")} onClick={() => setSize(s)}>
            <span>{s === "small" ? <IconBox size={20} /> : s === "medium" ? <IconGift size={20} /> : <IconBox size={20} />}</span>
            {sizeLabel(s, ru)}
          </button>
        ))}
      </div>

      <label className="field" style={{ marginTop: 14 }}>
        <span className="field__label">{appText("Что за посылка", "Ниндәй бандероль")}</span>
        <textarea
          className="field__area"
          value={desc}
          onChange={(e) => setDesc(e.target.value)}
          placeholder={appText("Например: документы, книга, гостинец", "Мәҫәлән: документтар, дарыу, күстәнәс")}
          rows={2}
        />
      </label>

      <div className="field-row" style={{ marginTop: 12 }}>
        <label className="field" style={{ flex: 1 }}>
          <span className="field__label">{appText("Имя получателя", "Алыусы исеме")}</span>
          <input className="field__input" value={receiver} onChange={(e) => setReceiver(e.target.value)} placeholder={appText("Кто встретит", "Кем ҡаршы ала")} />
        </label>
        <label className="field" style={{ flex: 1 }}>
          <span className="field__label">{appText("Телефон", "Телефон")}</span>
          <input className="field__input" value={phone} onChange={(e) => setPhone(e.target.value)} inputMode="tel" placeholder="+7 900 000-00-00" />
        </label>
      </div>

      {/* Цена доставки — не в «дополнительно»: без неё курьеру нечем решить, браться или нет */}
      <label className="field" style={{ marginTop: 12 }}>
        <span className="field__label">{appText("Сколько платишь за доставку, ₽", "Илтеү өсөн күпме түләйһең, ₽")}</span>
        <input
          className="field__input"
          type="number"
          inputMode="numeric"
          min={0}
          value={price}
          onChange={(e) => setPrice(e.target.value)}
          placeholder={appText("0 — по-соседски, бесплатно", "0 — күршеләрсә, бушлай")}
        />
        <span className="field__hint">
          {appText(
            "Курьер видит сумму до того, как возьмёт посылку. Деньги отдаёшь напрямую ему.",
            "Курьер аҫылманы алғанға тиклем сумманы күрә. Аҡсаны тура уға бирәһең."
          )}
        </span>
      </label>

      {/* Остальное — по желанию, но каждое поле снимает по одному спору потом */}
      <button
        type="button"
        className="more-toggle"
        onClick={() => setMore((v) => !v)}
        aria-expanded={more}
      >
        {appText("Дополнительно", "Өҫтәмә")}
        <span className="more-toggle__chev">{more ? "▴" : "▾"}</span>
      </button>

      {more && (
        <div className="more-body">
          <div className="field-row">
            <label className="field" style={{ flex: 1 }}>
              <span className="field__label">{appText("Откуда забрать", "Ҡайҙан алырға")}</span>
              <input
                className="field__input"
                value={fromAddr}
                onChange={(e) => setFromAddr(e.target.value)}
                maxLength={200}
                placeholder={appText("Дом, квартира или ориентир", "Йорт, фатир йәки билдә")}
              />
            </label>
            <label className="field" style={{ flex: 1 }}>
              <span className="field__label">{appText("Куда привезти", "Ҡайҙа килтерергә")}</span>
              <input
                className="field__input"
                value={toAddr}
                onChange={(e) => setToAddr(e.target.value)}
                maxLength={200}
                placeholder={appText("«У мечети», «синие ворота»", "«Мәсет янында», «зәңгәр ҡапҡа»")}
              />
            </label>
          </div>

          <div className="field-row" style={{ marginTop: 12 }}>
            <label className="field" style={{ flex: 1 }}>
              <span className="field__label">{appText("Вес, кг", "Ауырлыҡ, кг")}</span>
              <input
                className="field__input"
                type="number"
                inputMode="decimal"
                min={0}
                max={100}
                value={weight}
                onChange={(e) => setWeight(e.target.value)}
                placeholder={appText("Примерно", "Яҡынса")}
              />
              {!weightOk && (
                <span className="field__hint" style={{ color: "var(--danger)" }}>
                  {appText(
                    "Вес больше 100 кг — это уже грузоперевозка",
                    "Ауырлыҡ 100 кг-дан артыҡ — был инде йөк ташыу"
                  )}
                </span>
              )}
            </label>
            <label className="field" style={{ flex: 1 }}>
              <span className="field__label">{appText("Ценность, ₽", "Хаҡы, ₽")}</span>
              <input
                className="field__input"
                type="number"
                inputMode="numeric"
                min={0}
                value={value}
                onChange={(e) => setValue(e.target.value)}
                placeholder={appText("Необязательно", "Мотлаҡ түгел")}
              />
              {/* Честно про границы: это не страховка. Но и без цифры разбор
                  упирается в «стоило дорого» против «ничего не стоило». */}
              <span className="field__hint">
                {appText(
                  "Если что-то случится, это будет ориентиром при разборе. Не страховка — но без цифры спорить не о чем.",
                  "Берәй хәл булһа, был тикшереүҙә ориентир булыр. Иминләштереү түгел — әммә һанһыҙ бәхәсләшеп булмай."
                )}
              </span>
            </label>
          </div>

          <label className="field" style={{ marginTop: 12 }}>
            <span className="field__label">{appText("Нужно доставить не позже", "Ошо көндән һуң түгел")}</span>
            <input
              className="field__input"
              type="date"
              value={deliverBy}
              onChange={(e) => setDeliverBy(e.target.value)}
            />
            <span className="field__hint">
              {appText("Пусто — не срочно, когда получится.", "Буш — ашығыс түгел, ҡасан килеп сыға.")}
            </span>
          </label>

          <label className="list-row list-row--check" style={{ marginTop: 12 }}>
            <div className="list-row__main">
              <div className="list-row__title">{appText("Хрупкое", "Ватыла торған")}</div>
              <div className="list-row__sub">
                {appText(
                  "Курьер повезёт аккуратнее и не поставит сверху тяжёлое.",
                  "Курьер һаҡсылыраҡ алып барыр, өҫтөнә ауырҙы ҡуймаҫ."
                )}
              </div>
            </div>
            <input
              type="checkbox"
              className="checkbox"
              checked={fragile}
              onChange={() => setFragile((v) => !v)}
              aria-label={appText("Хрупкое", "Ватыла торған")}
            />
          </label>
        </div>
      )}

      {/* Правила — обязательный чекбокс */}
      <label className="list-row list-row--check" style={{ marginTop: 14 }}>
        <div className="list-row__main">
          <div className="list-row__title">{appText("Соглашаюсь с правилами", "Ҡағиҙәләр менән ризамын")}</div>
          <div className="list-row__sub">
            {appText(
              "Не отправляю запрещённое, деньги, документы без описи. Мы возим «между своими».",
              "Тыйылған, аҡса, теркәүһеҙ документ ебәрмәйем. Беҙ «үҙебеҙ араһында» йөрөтәбеҙ."
            )}
          </div>
        </div>
        <input type="checkbox" className="checkbox" checked={rules} onChange={() => setRules((v) => !v)} aria-label={appText("Правила", "Ҡағиҙәләр")} />
      </label>

      {/* Главное про ожидания: везёт сосед по пути, а не курьерская служба
          со страховкой. Сказать это надо до отправки, а не при разборе спора. */}
      <p className="parcel-expect">
        {appText(
          "Курьер — обычный попутчик, а не служба доставки. Не клади ценное, хрупкое или запрещённое. Ответственность за содержимое — на тебе.",
          "Курьер — ябай юлдаш, доставка хеҙмәте түгел. Ҡиммәтле, ватыҡ йәки тыйылған әйберҙе һалма. Эстәлеге өсөн яуаплылыҡ — һиндә."
        )}
      </p>

      {error && <div className="auth__error">{error}</div>}

      <button type="button" className="btn-primary submit-btn" style={{ marginTop: 14 }} onClick={submit} disabled={!canSubmit}>
        {busy ? appText("Создаём…", "Яһайбыҙ…") : <><IconBox size={18} /> {appText("Создать заявку", "Заявка яһау")}</>}
      </button>
    </>
  );
}

// ============================ Вкладка «Мои» ============================
type Boot = "loading" | "error" | "ready";

function MineTab() {
  const { appText } = useLang();
  const navigate = useNavigate();
  const [boot, setBoot] = useState<Boot>("loading");
  const [items, setItems] = useState<Parcel[]>([]);
  const [busyId, setBusyId] = useState<number | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setBoot("loading");
    fetchMyParcels(signal)
      .then((rows) => {
        setItems(rows);
        setBoot("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        if (e instanceof ApiError && e.status === 404) {
          setItems([]);
          setBoot("ready");
        } else setBoot("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  async function onCancel(id: number) {
    setBusyId(id);
    try {
      const p = await cancelParcel(id);
      setItems((prev) => prev.map((x) => (x.id === id ? p : x)));
    } catch {
      /* тихо — статус не изменился */
    } finally {
      setBusyId(null);
    }
  }

  // «Курьер не застал получателя» → попросить заехать ещё раз. Ступенька между неудачей
  // и возвратом: раньше её не было и отправитель платил за возврат почти полную доставку.
  async function onRedeliver(id: number) {
    setBusyId(id);
    try {
      const p = await parcelRedeliverRequest(id);
      setItems((prev) => prev.map((x) => (x.id === id ? p : x)));
    } catch {
      /* тихо — сервер сам решает, открыта ли просьба; карточка не изменилась */
    } finally {
      setBusyId(null);
    }
  }

  if (boot === "loading") return <LoadingList count={3} />;
  if (boot === "error") return <ErrorState onRetry={() => load()} />;
  if (items.length === 0) {
    return (
      <div className="state" style={{ paddingTop: 28 }}>
        <div className="state__icon"><IconBox size={34} /></div>
        <h2>{appText("Пока нет посылок", "Әле бандеролдәр юҡ")}</h2>
        <p>{appText("Создай заявку — попутный курьер довезёт её «между своими».", "Заявка яһа — юл ыңғайы курьер уны «үҙебеҙ» еткерә.")}</p>
      </div>
    );
  }

  return (
    <div style={{ marginTop: 14 }}>
      {items.map((p) => {
        const active = p.status !== "delivered" && p.status !== "canceled";
        return (
          <div key={p.id} className="parcel-card">
            <div className="parcel-card__head">
              <div className="parcel-card__to">{appText("Кому", "Кемгә")}: {p.receiver_name || "—"}</div>
              <StatusPillParcel status={p.status} />
            </div>
            <div className="repeat-route">
              <span>{p.from_city}</span>
              <span className="repeat-route__arrow">→</span>
              <span>{p.to_city}</span>
            </div>
            {/* Код вручения — свой, показываем отправителю */}
            {p.confirm_code && active && (
              <div className="parcel-card__code">
                <span>{appText("Код вручения", "Тапшырыу коды")}</span>
                <b>{p.confirm_code}</b>
              </div>
            )}
            {/* Курьер, если принята */}
            {p.courier && (
              <div className="parcel-card__courier">
                <div className="parcel-card__courier-name">
                  <IconRoute size={15} /> {p.courier.name || appText("Курьер", "Курьер")}
                  {p.courier.rating != null && (
                    <span className="taxi-driver__rating"><IconStar size={13} /> {p.courier.rating.toFixed(1)}</span>
                  )}
                </div>
                {p.courier.phone && (
                  <a className="btn-soft" href={`tel:${p.courier.phone}`}>{appText("Позвонить", "Шылтыратыу")}</a>
                )}
                {/* Чат: где оставить, кому отдать, когда будут дома — письменно, а не в звонке. */}
                <button
                  type="button"
                  className="btn-soft"
                  onClick={() => navigate(`/parcel-chat/${p.id}`)}
                >
                  <IconChat size={18} /> {appText("Чат", "Чат")}
                </button>
              </div>
            )}
            {/* Курьер уже потратил свои деньги на товар — просто «отменить» тут
                нечестно по отношению к нему. Разбираться нужно через спор, где
                слышны обе стороны. */}
            {active && (p.settlement?.goods_actual_kop ?? 0) > 0 ? (
              <div className="parcel-card__warn">
                {appText(
                  "Курьер уже купил товар. Обычная отмена недоступна — если что-то пошло не так, открой спор.",
                  "Курьер тауарҙы һатып алған инде. Ғәҙәти кире алыу мөмкин түгел — проблема булһа, бәхәс ас."
                )}
              </div>
            ) : (
              active && (
                <>
                  {/* Курьер уже в пути — отмена стоит ему времени и бензина.
                      Сумму называем ДО нажатия, а не после. */}
                  {p.courier && (
                    <div className="parcel-card__warn">
                      {(p.cancel_fee_preview_kop ?? 0) > 0
                        ? appText(
                            `Курьер уже принял заказ. Отмена сейчас — компенсация курьеру ${Math.round((p.cancel_fee_preview_kop ?? 0) / 100)} ₽${cancelPartsRu(p)} за потраченное время и дорогу. Расчёт напрямую с курьером.`,
                            `Курьер заказды алған инде. Хәҙер кире алһаң — курьерға ваҡыт һәм юл өсөн ${Math.round((p.cancel_fee_preview_kop ?? 0) / 100)} һум${cancelPartsBa(p)} компенсация. Иҫәпләшеү курьер менән туранан-тура.`
                          )
                        : appText(
                            "Курьер уже принял заказ. После отмены сервис зафиксирует компенсацию за потраченное время и дорогу; сумма появится в карточке, расчёт — напрямую.",
                            "Курьер заказды алған инде. Кире алғандан һуң сервис ваҡыт һәм юл өсөн компенсацияны теркәр; сумма карточкала күренер, иҫәпләшеү — туранан-тура."
                          )}
                    </div>
                  )}
                  {/* Курьер приехал и не застал получателя. Раньше у отправителя тут не
                      было ничего, кроме чата: дозвонись как-нибудь сам, а нет — плати за
                      возврат почти полную доставку. Теперь есть дешёвый выход. Цену заезда
                      называем ДО нажатия и считаем на сервере. */}
                  {p.can_request_redelivery && (
                    <div className="parcel-card__warn" style={{ marginTop: 10 }}>
                      <strong>{appText("Курьер не застал получателя", "Курьер алыусыны тапманы")}</strong>
                      <div style={{ marginTop: 4 }}>
                        {(p.return_fee_parts?.next_redeliver_kop ?? 0) > 0
                          ? appText(
                              `Свяжись с ним и попроси курьера заехать ещё раз — заезд стоит ${Math.round((p.return_fee_parts?.next_redeliver_kop ?? 0) / 100)} ₽, это дешевле возврата (${Math.round((p.return_fee_parts?.total_kop ?? 0) / 100)} ₽).`,
                              `Уның менән бәйләнеш тот һәм курьерҙан ҡабат инеүҙе һора — инеү ${Math.round((p.return_fee_parts?.next_redeliver_kop ?? 0) / 100)} һум тора, был кире ҡайтарыуҙан (${Math.round((p.return_fee_parts?.total_kop ?? 0) / 100)} һум) арзаныраҡ.`
                            )
                          : appText(
                              "Свяжись с ним и попроси курьера заехать ещё раз — доплаты за этот заезд не будет.",
                              "Уның менән бәйләнеш тот һәм курьерҙан ҡабат инеүҙе һора — был инеү өсөн өҫтәмә түләү булмаясаҡ."
                            )}
                      </div>
                      <button
                        type="button"
                        className="btn-ghost"
                        style={{ marginTop: 10 }}
                        onClick={() => onRedeliver(p.id)}
                        disabled={busyId === p.id}
                      >
                        {busyId === p.id
                          ? appText("Просим…", "Һорайбыҙ…")
                          : appText("Попросить заехать ещё раз", "Ҡабат инеүҙе һорау")}
                      </button>
                    </div>
                  )}
                  <button type="button" className="btn-ghost" style={{ marginTop: 10 }} onClick={() => onCancel(p.id)} disabled={busyId === p.id}>
                    {busyId === p.id ? appText("Отменяем…", "Кире алабыҙ…") : appText("Отменить", "Кире алыу")}
                  </button>
                </>
              )
            )}

            {/* Доставлено — оцениваем курьера. Без истории оценок он для
                следующего отправителя просто незнакомый человек с коробкой. */}
            {p.status === "delivered" && p.courier && (
              <ParcelRate parcelId={p.id} role="courier" />
            )}
          </div>
        );
      })}
    </div>
  );
}

// ============================ Вкладка «Возить» («по пути») ============================
function CarryTab() {
  const { appText } = useLang();
  const [sub, setSub] = useState<"available" | "carrying">("available");
  return (
    <>
      <div className="chips" style={{ marginTop: 14 }}>
        <button type="button" className={"chip" + (sub === "available" ? " chip--on" : "")} onClick={() => setSub("available")}>
          {appText("Доступные", "Асыҡ")}
        </button>
        <button type="button" className={"chip" + (sub === "carrying" ? " chip--on" : "")} onClick={() => setSub("carrying")}>
          {appText("Везу", "Йөрөтәм")}
        </button>
      </div>
      {sub === "available" ? <AvailableList /> : <CarryingList />}
    </>
  );
}

function AvailableList() {
  const { appText } = useLang();
  const [boot, setBoot] = useState<Boot>("loading");
  const [items, setItems] = useState<Parcel[]>([]);
  const [busyId, setBusyId] = useState<number | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setBoot("loading");
    fetchAvailableParcels({}, signal)
      .then((rows) => {
        setItems(rows);
        setBoot("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        if (e instanceof ApiError && e.status === 404) {
          setItems([]);
          setBoot("ready");
        } else setBoot("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  async function onTake(id: number) {
    setBusyId(id);
    try {
      await acceptParcel(id);
      setItems((prev) => prev.filter((x) => x.id !== id));
    } catch {
      // 409 — уже взяли; просто убираем из списка.
      setItems((prev) => prev.filter((x) => x.id !== id));
    } finally {
      setBusyId(null);
    }
  }

  if (boot === "loading") return <LoadingList count={3} />;
  if (boot === "error") return <ErrorState onRetry={() => load()} />;
  if (items.length === 0) {
    return (
      <div className="state" style={{ paddingTop: 28 }}>
        <div className="state__icon"><IconShield size={34} /></div>
        <h2>{appText("Пока нет посылок «по пути»", "Әле «юл ыңғайы» бандеролдәр юҡ")}</h2>
        <p>{appText("Как появятся заявки на твоём маршруте — покажем здесь. Помоги соседу по пути.", "Маршрутыңда заявка сыҡһа — бында күрһәтәбеҙ. Юл ыңғайы күршегә ярҙам ит.")}</p>
      </div>
    );
  }

  return (
    <div style={{ marginTop: 4 }}>
      {items.map((p) => (
        <AvailableParcelCard key={p.id} p={p} busy={busyId === p.id} onTake={() => onTake(p.id)} />
      ))}
    </div>
  );
}

function CarryingList() {
  const { appText } = useLang();
  const [boot, setBoot] = useState<Boot>("loading");
  const [items, setItems] = useState<Parcel[]>([]);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [codeFor, setCodeFor] = useState<Parcel | null>(null);
  const [codeBusy, setCodeBusy] = useState(false);
  const [codeErr, setCodeErr] = useState<string | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setBoot("loading");
    fetchCarrying(signal)
      .then((rows) => {
        setItems(rows);
        setBoot("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        if (e instanceof ApiError && e.status === 404) {
          setItems([]);
          setBoot("ready");
        } else setBoot("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  async function onDepart(id: number) {
    setBusyId(id);
    try {
      const p = await setParcelStatus(id, "in_transit");
      setItems((prev) => prev.map((x) => (x.id === id ? p : x)));
    } catch {
      /* тихо */
    } finally {
      setBusyId(null);
    }
  }

  async function onDeliver(code: string) {
    if (!codeFor) return;
    setCodeBusy(true);
    setCodeErr(null);
    try {
      await setParcelStatus(codeFor.id, "delivered", code.trim());
      setItems((prev) => prev.filter((x) => x.id !== codeFor.id));
      setCodeFor(null);
    } catch (e) {
      setCodeErr(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Неверный код. Проверь и введи снова.", "Код дөрөҫ түгел. Тикшереп ҡабат ҡара.")
      );
    } finally {
      setCodeBusy(false);
    }
  }

  if (boot === "loading") return <LoadingList count={2} />;
  if (boot === "error") return <ErrorState onRetry={() => load()} />;
  if (items.length === 0) {
    return (
      <div className="state" style={{ paddingTop: 28 }}>
        <div className="state__icon"><IconCheck size={34} /></div>
        <h2>{appText("Ты пока ничего не везёшь", "Һин бер нәмә лә йөрөтмәйһең")}</h2>
        <p>{appText("Возьми заявку во вкладке «Доступные» — она появится здесь.", "«Асыҡ» бүлегендә заявка ал — ул бында күренер.")}</p>
      </div>
    );
  }

  return (
    <div style={{ marginTop: 4 }}>
      {items.map((p) => (
        <div key={p.id}>
          <CarryParcelCard
            p={p}
            busy={busyId === p.id}
            onDepart={() => onDepart(p.id)}
            onDeliver={() => { setCodeErr(null); setCodeFor(p); }}
          />
          <ParcelProblemActions parcel={p} role="courier" onChanged={() => load()} />
        </div>
      ))}
      {codeFor && (
        <CodeDialog busy={codeBusy} error={codeErr} onSubmit={onDeliver} onClose={() => setCodeFor(null)} />
      )}
    </div>
  );
}
