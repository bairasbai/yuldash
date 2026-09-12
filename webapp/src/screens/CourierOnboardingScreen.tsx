// ================================================================
//  «Стать курьером Юлдаша» (C1). RequireAuth → /courier-onboarding
//  (зеркало backend routers/courier.py: GET /courier/application,
//  POST /courier/apply).
//
//  Правила + выбор транспорта (авто/грузовой) + селфи с документом
//  (аплоад через POST /upload/photo) + статус заявки
//  (pending/approved/rejected + причина).
//
//  Мягкая деградация: courier/* появятся на проде после мержа release
//  → 404/403 ловим спокойным состоянием без краша.
// ================================================================
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { uploadDoc } from "../api/driver";
import {
  fetchCourierApplication,
  applyCourier,
  type CourierApplication,
  type CourierTransport,
} from "../api/courier";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList, ErrorState } from "../components/States";
import { IconCheck, IconCamera, IconBox, IconCar, IconRoute } from "../components/Icons";
import { DeliveryErrorCard, DeliveryHint, DeliverySectionTitle } from "../components/parcelForm";
import { useDraftSync, clearDraft } from "../utils/formDraft";
import { track } from "../analytics";

type Boot = "loading" | "error" | "ready";

/** Ключ черновика анкеты курьера. */
const COURIER_DRAFT = "courier-application";

export default function CourierOnboardingScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [boot, setBoot] = useState<Boot>("loading");
  const [app, setApp] = useState<CourierApplication | null>(null);
  const [editing, setEditing] = useState(false);

  // Форма.
  const [transport, setTransport] = useState<CourierTransport>("car");
  const [selfieUrl, setSelfieUrl] = useState("");
  // Кто и на чём везёт. Сервер пока принимает мягко, но спрашиваем сразу:
  // человеку доверяют чужую посылку, а по госномеру его узнают у подъезда.
  const [fullName, setFullName] = useState("");
  const [carPlate, setCarPlate] = useState("");
  const [rulesOk, setRulesOk] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // За селфи человек уходит в камеру, и вкладка на айфоне может выгрузиться.
  // Черновик держит анкету на месте, пока она не отправлена.
  useDraftSync(
    COURIER_DRAFT,
    { transport, selfieUrl, fullName, carPlate, rulesOk },
    (d) => {
      if (d.transport) setTransport(d.transport);
      if (d.selfieUrl) setSelfieUrl(d.selfieUrl);
      if (d.fullName) setFullName(d.fullName);
      if (d.carPlate) setCarPlate(d.carPlate);
      if (d.rulesOk) setRulesOk(d.rulesOk);
    }
  );
  const fileRef = useRef<HTMLInputElement>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setBoot("loading");
    fetchCourierApplication(signal)
      .then((r) => {
        setApp(r.application);
        setBoot("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        // Эндпоинта ещё нет на проде (404) → пускаем к форме (мягко).
        if (e instanceof ApiError && e.status === 404) {
          setApp(null);
          setBoot("ready");
        } else setBoot("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  async function pickSelfie(f: File) {
    setUploading(true);
    setError(null);
    try {
      const { url } = await uploadDoc(f);
      setSelfieUrl(url);
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не удалось загрузить фото, попробуй ещё раз", "Фотоны йөкләргә булманы. Ҡабат ҡара.")
      );
    } finally {
      setUploading(false);
    }
  }

  const canSubmit =
    !!selfieUrl && fullName.trim().split(/\s+/).length >= 2 && rulesOk && !busy && !uploading;

  async function submit() {
    if (!canSubmit) return;
    setBusy(true);
    setError(null);
    try {
      const a = await applyCourier({
        transport,
        selfie_url: selfieUrl,
        full_name: fullName.trim(),
        car_plate: carPlate.trim().toUpperCase(),
        rules_accepted: rulesOk,
      });
      track("courier_apply");
      setApp(a);
      setEditing(false);
      clearDraft(COURIER_DRAFT); // отправлено — черновик больше не нужен
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось отправить заявку. Попробуй снова.", "Ғаризаны ебәрергә булманы. Ҡабат ҡара.")
      );
    } finally {
      setBusy(false);
    }
  }

  // ---------------- Состояния ----------------
  if (boot === "loading") {
    return (
      <>
        <SubHeader title={appText("Курьер Юлдаш", "Юлдаш курьеры")} onBack={() => navigate(-1)} />
        <LoadingList count={2} />
      </>
    );
  }

  if (boot === "error") {
    return (
      <>
        <SubHeader title={appText("Курьер Юлдаш", "Юлдаш курьеры")} onBack={() => navigate(-1)} />
        <ErrorState onRetry={() => load()} />
      </>
    );
  }

  // Статус: заявка уже подана и не в режиме редактирования (CourierStatusScaffold в Android).
  const st = app?.status;
  if (app && !editing && (st === "pending" || st === "approved" || st === "rejected")) {
    const view =
      st === "approved"
        ? {
            emoji: "🎉",
            tone: "mint",
            title: appText("Поздравляем — ты курьер Юлдаша!", "Ҡотлайбыҙ — һин Юлдаш курьеры!"),
            body: appText(
              "Заявка одобрена. Открывай «Режим курьера», включай «На линии» — и заказы начнут приходить.",
              "Заявка раҫланды. «Курьер режимын» ас, «Линияла» тумблерын ҡабыҙ — заказдар килә башлар."
            ),
            summary: false,
            primary: appText("В режим курьера", "Курьер режимына"),
            onPrimary: () => navigate("/courier"),
            secondary: null as string | null,
            onSecondary: undefined as (() => void) | undefined,
          }
        : st === "rejected"
          ? {
              emoji: "✋",
              tone: "danger",
              title: appText("Заявку пока отклонили", "Заявка әлегә кире ҡағылды"),
              body: app.reject_reason
                ? appText("Причина: ", "Сәбәбе: ") + app.reject_reason
                : appText(
                    "Проверь селфи и транспорт — и подай снова. Мы поможем разобраться.",
                    "Селфи менән транспортты тикшер ҙә ҡабат бир. Аңларға ярҙам итәбеҙ."
                  ),
              summary: true,
              primary: appText("Подать снова", "Ҡабат биреү"),
              onPrimary: () => {
                setTransport((app.transport as CourierTransport) || "car");
                setEditing(true);
              },
              secondary: appText("Позже", "Һуңыраҡ"),
              onSecondary: () => navigate(-1),
            }
          : {
              emoji: "⏳",
              tone: "warn",
              title: appText("Заявка на проверке", "Заявка тикшереүҙә"),
              body: appText(
                "Мы уже смотрим твою заявку. Обычно это занимает меньше дня — пришлём уведомление, как только всё готово.",
                "Заявкаңды ҡарайбыҙ инде. Ғәҙәттә был бер көндән дә әҙерәк ваҡыт ала — әҙер булыу менән хәбәр ебәрәбеҙ."
              ),
              summary: true,
              primary: appText("Обновить статус", "Статусты яңыртыу"),
              onPrimary: () => load(),
              secondary: appText("Понятно", "Аңлашылды"),
              onSecondary: () => navigate(-1),
            };

    return (
      <>
        <SubHeader title={appText("Курьер Юлдаш", "Юлдаш курьеры")} onBack={() => navigate(-1)} />
        <div className="gate">
          <span className={"gate__badge gate__badge--" + view.tone} aria-hidden>{view.emoji}</span>
          <h2 className="gate__title">{view.title}</h2>
          <p className="gate__body">{view.body}</p>
          {view.summary && (
            <div className="gate__summary">
              <div className="gate__row">
                <span>{appText("Транспорт", "Транспорт")}</span>
                <b>{courierTransportLabel(app.transport, appText)}</b>
              </div>
              <div className="gate__row">
                <span>{appText("Селфи", "Селфи")}</span>
                <b>{app.selfie_url ? appText("загружено", "йөкләнгән") : appText("нет", "юҡ")}</b>
              </div>
              {app.invited_by != null && (
                <div className="gate__row">
                  <span>{appText("Пригласил", "Саҡырҙы")}</span>
                  <b>#{app.invited_by}</b>
                </div>
              )}
            </div>
          )}
          <button type="button" className="btn-primary gate__primary" onClick={view.onPrimary}>
            {view.primary}
          </button>
          {view.secondary && view.onSecondary && (
            <button type="button" className="btn-ghost gate__secondary" onClick={view.onSecondary}>
              {view.secondary}
            </button>
          )}
        </div>
      </>
    );
  }

  // Форма заявки — CourierApplyFormContent.
  return (
    <>
      <SubHeader title={appText("Курьер Юлдаш", "Юлдаш курьеры")} onBack={() => navigate(-1)} />
      <div className="dl-form">
        {/* Герой: мятная карточка, зелёный круг с иконкой, заголовок 19 Bold, текст. */}
        <section className="apply-hero">
          <div className="apply-hero__head">
            <span className="apply-hero__icon" aria-hidden><IconRoute size={24} /></span>
            <h2>{appText("Стать курьером Юлдаша", "Юлдаш курьеры булыу")}</h2>
          </div>
          <p>
            {appText(
              "Развози посылки своим — по-соседски и без жадных процентов. Оставь заявку, мы проверим тебя и подключим к заказам.",
              "Үҙебеҙҙекеләргә бандеролдәр илт — күршеләрсә һәм йыртҡыс процентһыҙ. Заявка ҡалдыр, беҙ һине тикшереп заказдарға тоташтырабыҙ."
            )}
          </p>
        </section>

        <SectionHeader title={appText("Наши условия", "Беҙҙең шарттар")} subtitle={appText("Просто и честно", "Ябай һәм намыҫлы")} />
        <div className="rules-list">
          <RuleRow
            emoji="💚"
            title={appText("Комиссия — по ступени, прозрачно", "Комиссия — баҫҡыс буйынса, асыҡ")}
            body={appText(
              "Текущая ставка комиссии — в кабинете курьера, сумма по доставке — в её расчёте.",
              "Хәҙерге комиссия ставкаһы — курьер кабинетында, илтеү өсөн сумма — уның иҫәбендә."
            )}
          />
          <RuleRow
            emoji="🚫"
            title={appText("Что нельзя возить", "Нимә илтергә ярамай")}
            body={appText(
              "Деньги, документы на предъявителя, лекарства без рецепта, скоропорт, оружие, запрещённое законом.",
              "Аҡса, күрһәтеүсегә документтар, рецептһыҙ дарыу, тиҙ боҙолған аҙыҡ, ҡорал, закон менән тыйылған."
            )}
          />
          <RuleRow
            emoji="🤝"
            title={appText("Ответственность на курьере", "Яуаплылыҡ курьерҙа")}
            body={appText(
              "Ты отвечаешь за сохранность посылки от приёма до вручения по коду. Береги чужое как своё.",
              "Бандеролде алғандан код буйынса тапшырғанға тиклем һаҡлау — һинең өҫтөңдә. Кеше әйберен үҙеңдеке кеүек һаҡла."
            )}
          />
          <RuleRow
            emoji="🛒"
            title={appText("«Купи и привези» — до 5000 ₽", "«Ал да килтер» — 5000 ₽-ға тиклем")}
            body={appText(
              "Можешь купить товар за клиента и привезти. Лимит суммы покупки — 5000 ₽, чтобы ты не рисковал крупным.",
              "Клиент өсөн тауар алып килтерә алаһың. Һатып алыу лимиты — 5000 ₽, ҙур аҡса менән тәүәккәлләмәҫ өсөн."
            )}
          />
        </div>

        {editing && app?.reject_reason && (
          <div className="dl-error" role="alert">
            <span>
              <strong className="dl-error__title">{appText("Причина отказа", "Кире ҡағыу сәбәбе")}</strong>
              {app.reject_reason}
            </span>
          </div>
        )}

        <SectionHeader title={appText("Транспорт", "Транспорт")} subtitle={appText("На чём возишь", "Нимәлә йөрөтәһең")} />
        <div className="deadline__row" role="radiogroup" aria-label={appText("Транспорт", "Транспорт")}>
          <TransportChip
            title={appText("Легковой", "Еңел машина")}
            subtitle={appText("посылки, покупки", "бандероль, һатып алыу")}
            icon={<IconCar size={24} />}
            selected={transport === "car"}
            onClick={() => setTransport("car")}
          />
          <TransportChip
            title={appText("Грузовой", "Йөк машинаһы")}
            subtitle={appText("крупное, мебель", "ҙур, мебель")}
            icon={<IconBox size={24} />}
            selected={transport === "cargo"}
            onClick={() => setTransport("cargo")}
          />
        </div>

        {/* Кто пригласил — берём из реферального кода, отдельного поля нет. */}
        <div className="invite-note">
          <span className="invite-note__emoji" aria-hidden>🤝</span>
          <span className="invite-note__text">
            <strong>{appText("Кто пригласил", "Кем саҡырҙы")}</strong>
            <DeliveryHint>
              {app?.invited_by != null
                ? appText("Тебя пригласил: ", "Һине саҡырҙы: ") + `#${app.invited_by}`
                : appText(
                    "Возьмём из твоего реферального кода — так админ видит, кто за тебя поручился.",
                    "Реферал кодыңдан алабыҙ — админ кем һинең өсөн яуаплы икәнен күрер."
                  )}
            </DeliveryHint>
          </span>
        </div>

        <SectionHeader title={appText("Селфи с документом", "Документ менән селфи")} subtitle={appText("Чтобы возил именно ты", "Нәҡ һин йөрөтөүең өсөн")} />
        <DeliveryHint>
          {appText(
            "Сделай селфи с паспортом или правами в руках — так соседи знают, кому доверяют посылку (как в Яндекс.Доставке).",
            "Ҡулыңда паспорт йәки права менән селфи яһа — шулай күршеләр бандеролде кемгә ышанғандарын белә (Яндекс.Доставка кеүек)."
          )}
        </DeliveryHint>
        <button
          type="button"
          className={"photo-slot" + (selfieUrl ? " is-done" : "")}
          onClick={() => fileRef.current?.click()}
          disabled={uploading}
        >
          <input
            ref={fileRef}
            type="file"
            accept="image/*"
            hidden
            onChange={(e) => {
              const f = e.target.files?.[0];
              if (f) pickSelfie(f);
              e.target.value = "";
            }}
          />
          <span className="photo-slot__icon">
            {selfieUrl ? <IconCheck size={24} /> : <IconCamera size={24} />}
          </span>
          <span className="photo-slot__text">
            <b>{appText("Селфи с документом в руках", "Ҡулыңда документ менән селфи")}</b>
            <span>
              {uploading
                ? appText("Загружаем…", "Йөкләйбеҙ…")
                : selfieUrl
                  ? appText("Фото загружено", "Фото йөкләнде")
                  : appText("Лицо и паспорт/права в кадре", "Йөҙ һәм паспорт/права кадрҙа")}
            </span>
          </span>
        </button>

        <DeliverySectionTitle>{appText("О себе", "Үҙең тураһында")}</DeliverySectionTitle>
        {/* Имя — как в документе на селфи: модератор сверяет одно с другим. */}
        <label className="field dl-field">
          <span className="field__label">{appText("Фамилия и имя как в документе", "Документтағыса фамилия һәм исем")}</span>
          <input
            className="field__input"
            value={fullName}
            maxLength={120}
            onChange={(e) => setFullName(e.target.value)}
            placeholder={appText("Иванов Ринат", "Иванов Ринат")}
            autoComplete="name"
          />
        </label>
        <label className="field dl-field">
          <span className="field__label">{appText("Госномер машины", "Машинаның дәүләт номеры")}</span>
          <input
            className="field__input"
            value={carPlate}
            maxLength={16}
            onChange={(e) => setCarPlate(e.target.value.toUpperCase())}
            placeholder="Х123УХ102"
            autoComplete="off"
          />
          <span className="field__hint">
            {appText("По нему тебя узнают у подъезда", "Уның буйынса подъезд янында һине таныйҙар")}
          </span>
        </label>

        {/* Согласие — карточка-переключатель, как в приложении: круг-галочка, заголовок, что обещаешь. */}
        <button
          type="button"
          role="checkbox"
          aria-checked={rulesOk}
          className={"agree-card" + (rulesOk ? " is-on" : "")}
          onClick={() => setRulesOk((v) => !v)}
        >
          <span className="agree-card__mark" aria-hidden>{rulesOk && <IconCheck size={14} />}</span>
          <span className="agree-card__text">
            <strong>{appText("Согласен с правилами доставки", "Илтеү ҡағиҙәләре менән килешәм")}</strong>
            <DeliveryHint>
              {appText(
                "Везу бережно, не вскрываю, запрещённое не беру. Если что-то пошло не так — говорю сразу, а не молчу.",
                "Һаҡ илтәм, асмайым, тыйылғанды алмайым. Берәй хәл булһа — шунда уҡ әйтәм, өндәшмәй ҡалмайым."
              )}
            </DeliveryHint>
          </span>
        </button>

        <DeliveryErrorCard message={error} />

        <button type="button" className="btn-primary btn-accent submit-btn" onClick={submit} disabled={!canSubmit}>
          {busy ? (
            appText("Отправляем…", "Ебәрәбеҙ…")
          ) : (
            <>
              <IconRoute size={18} /> {app ? appText("Подать снова", "Ҡабат биреү") : appText("Отправить заявку", "Заявка ебәреү")}
            </>
          )}
        </button>
        <DeliveryHint>
          {appText("Фото нужно только для проверки и не видно другим пользователям.", "Фото тик тикшереү өсөн, башҡаларға күренмәй.")}
        </DeliveryHint>
      </div>
    </>
  );
}

/** Заголовок раздела с подписью — SectionHeader из Android UiKit. */
function SectionHeader({ title, subtitle }: { title: string; subtitle: string }) {
  return (
    <div className="section-head">
      <h3 className="section-head__title">{title}</h3>
      <p className="section-head__sub">{subtitle}</p>
    </div>
  );
}

/** Строка правила: плитка 40 с эмодзи, заголовок 16 Bold, пояснение (CourierRuleRow). */
function RuleRow({ emoji, title, body }: { emoji: string; title: string; body: string }) {
  return (
    <div className="rule-row">
      <span className="rule-row__tile" aria-hidden>{emoji}</span>
      <span className="rule-row__text">
        <strong>{title}</strong>
        <DeliveryHint>{body}</DeliveryHint>
      </span>
    </div>
  );
}

/** Чип транспорта (CourierTransportChip): иконка 24, заголовок, подпись, мин. 96, выделение рамкой. */
function TransportChip({
  title,
  subtitle,
  icon,
  selected,
  onClick,
}: {
  title: string;
  subtitle: string;
  icon: React.ReactNode;
  selected: boolean;
  onClick: () => void;
}) {
  return (
    <button type="button" role="radio" aria-checked={selected} className={"transport-chip" + (selected ? " is-selected" : "")} onClick={onClick}>
      <span className="transport-chip__icon" aria-hidden>{icon}</span>
      <strong>{title}</strong>
      <small>{subtitle}</small>
    </button>
  );
}

function courierTransportLabel(transport: string, appText: (ru: string, ba: string) => string): string {
  if (transport === "car") return appText("Легковой", "Еңел машина");
  if (transport === "cargo") return appText("Грузовой", "Йөк машинаһы");
  return transport;
}
