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
import { IconBox, IconCheck, IconCopy, IconGift, IconRoute, IconShield, IconStar } from "../components/Icons";

type Tab = "send" | "mine" | "carry";

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

  const canSubmit = fromCity.trim() && toCity.trim() && receiver.trim() && rules && !busy;

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
            <span className="info-row__k">{appText("Маршрут", "Маршрут")}</span>
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
          placeholder={appText("Например: документы, лекарства, гостинцы", "Мәҫәлән: документтар, дарыу, күстәнәс")}
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
              </div>
            )}
            {active && (
              <button type="button" className="btn-ghost" style={{ marginTop: 10 }} onClick={() => onCancel(p.id)} disabled={busyId === p.id}>
                {busyId === p.id ? appText("Отменяем…", "Кире алабыҙ…") : appText("Отменить", "Кире алыу")}
              </button>
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
          : appText("Неверный код. Проверь и попробуй снова.", "Код дөрөҫ түгел. Тикшереп ҡабат ҡара.")
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
        <h2>{appText("Ты ничего не везёшь", "Һин бер нәмә лә йөрөтмәйһең")}</h2>
        <p>{appText("Возьми заявку во вкладке «Доступные» — она появится здесь.", "«Асыҡ» бүлегендә заявка ал — ул бында күренер.")}</p>
      </div>
    );
  }

  return (
    <div style={{ marginTop: 4 }}>
      {items.map((p) => (
        <CarryParcelCard
          key={p.id}
          p={p}
          busy={busyId === p.id}
          onDepart={() => onDepart(p.id)}
          onDeliver={() => { setCodeErr(null); setCodeFor(p); }}
        />
      ))}
      {codeFor && (
        <CodeDialog busy={codeBusy} error={codeErr} onSubmit={onDeliver} onClose={() => setCodeFor(null)} />
      )}
    </div>
  );
}
