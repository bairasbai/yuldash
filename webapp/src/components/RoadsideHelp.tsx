// ================================================================
//  «Застряли на трассе» — помощь мягче красной кнопки SOS.
//  Зеркало Android RoadsideHelp.kt.
//
//  Разница с SOS: там человек в опасности и зовёт всех сразу, тут —
//  машина не едет, и нужны координаты близким плюс запись у поддержки.
//  Зимой между сёлами это самая частая беда, и отправлять её через
//  «SOS» люди стесняются: кнопка выглядит слишком громкой.
//
//  В вебе кнопки не было ни у попутки, ни у такси, ни у доставки —
//  хотя сервер принимал сигнал от всех трёх (сверка с Android,
//  2026-08-30).
//
//  Числа в ответе честные. Экран, который писал «близкие получили
//  твои координаты» всегда, врал тому, кто доверенных не добавлял:
//  человек на морозе читал это и переставал звонить сам.
//
//  Перед отправкой — окно «Позвать помощь?», как в Android (паритет 16.10.2026): раньше
//  сигнал уходил с первого касания, и случайный тап в кармане поднимал дежурного.
// ================================================================
import { useEffect, useRef, useState } from "react";
import { useLang } from "../i18n/lang";
import { ApiError, getSessionGeneration } from "../api/client";
import {
  roadsideHelpBooking,
  roadsideHelpParcel,
  type RoadsideResult,
} from "../api/safety";
import { IconWarn } from "./Icons";
import { track } from "../analytics";
import AlertDialog from "./AlertDialog";

type Where = { kind: "booking"; id: number } | { kind: "parcel"; id: number };

/**
 * Кнопка «застряли». Координаты берём, если браузер даёт: без них сигнал всё равно
 * уходит — сигнал без места лучше, чем ничего.
 */
export default function RoadsideHelp({ target }: { target: Where }) {
  const { appText } = useLang();
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState("");
  /** Открыто окно «Позвать помощь?». */
  const [confirm, setConfirm] = useState(false);
  const generation = target.kind === "booking" ? getSessionGeneration() : "";
  const targetKey = `${target.kind}:${target.id}`;
  const flight = useRef<object | null>(null);
  const scope = useRef({ generation, targetKey, version: 0, epoch: 0, mounted: false });
  if (scope.current.generation !== generation || scope.current.targetKey !== targetKey) {
    scope.current.epoch++;
    scope.current.version++;
    flight.current = null;
  }
  scope.current.generation = generation;
  scope.current.targetKey = targetKey;
  const renderVersion = scope.current.version;
  useEffect(() => {
    scope.current.mounted = true;
    if (target.kind === "booking") { setBusy(false); setNote(""); setConfirm(false); }
    return () => { scope.current.mounted = false; scope.current.epoch++; flight.current = null; };
  }, [generation, targetKey, target.kind]);

  /** 429 — не «нет связи», а «слишком часто»: связь есть, сервер просит подождать. Срочное — 112 и близкие. */
  const tooOften = () => appText(
    "Слишком часто — подожди минуту и нажми снова. Если срочно, позвони близким или 112.",
    "Артыҡ йыш — бер минут көт һәм ҡабат баҫ. Ашығыс булһа, яҡындарыңа йәки 112-гә шылтырат." // DRAFT
  );

  function describe(r: RoadsideResult): string {
    if (r.delivery_unconfirmed) {
      return appText(
        r.hint_ru || "Сигнал сохранён в Юлдаш. Доставка сообщений не подтверждена. Позвони близким или 112.",
        r.hint_ba || r.hint_ru || "Сигнал Юлдашта һаҡланды. Хәбәрҙәрҙең барып етеүе раҫланманы. Яҡындарыңа йәки 112-гә шылтырат."
      );
    }
    if (r.contacts_total === 0) {
      return appText(
        "Сигнал сохранён в Юлдаш. Близких у тебя в приложении нет — добавь их в «Доверенных», чтобы в следующий раз им ушло сообщение.",
        "Сигнал Юлдашта һаҡланды. Ҡәҙерлеләрең өҫтәлмәгән — киләһе юлы хәбәр китһен өсөн уларҙы «Ышаныслы кешеләр»гә өҫтә."
      );
    }
    if (r.contacts_notified === 0) {
      return appText(
        "Сигнал сохранён в Юлдаш. Сообщение близким сейчас не уходит — позвони им сам.",
        "Сигнал Юлдашта һаҡланды. Хәбәр ҡәҙерлеләргә китмәй — уларға үҙең шылтырат."
      );
    }
    return appText(
      `Сигнал сохранён в Юлдаш. Сообщений близким подготовлено: ${r.contacts_notified}. Доставка не подтверждена. Если можешь, позвони им и 112 сам.`,
      `Сигнал Юлдашта һаҡланды. Яҡындарға әҙерләнгән хәбәрҙәр: ${r.contacts_notified}. Барып етеүе раҫланманы. Мөмкин булһа, уларға һәм 112-гә үҙең шылтырат.`
    );
  }

  function callBooking() {
    if (target.kind !== "booking" || flight.current || !scope.current.mounted
      || renderVersion !== scope.current.version || generation !== getSessionGeneration()) return;
    const epoch = scope.current.epoch;
    const ticket = {};
    const current = () => scope.current.mounted && scope.current.epoch === epoch
      && scope.current.targetKey === targetKey && generation === getSessionGeneration();
    flight.current = ticket;
    setConfirm(false); setBusy(true); setNote("");
    let fix: { lat: number; lng: number; timestamp: number } | null = null;
    let permission: PermissionStatus | null = null;
    const readWhere = () => {
      if (!current()) throw new ApiError(409, "");
      const point = fix;
      const age = point ? Date.now() - point.timestamp : -1;
      const fresh = point && age >= 0 && age < 60_000 && permission?.state !== "denied" && permission?.state !== "prompt" ? point : null;
      return { lat: fresh?.lat, lng: fresh?.lng };
    };
    const send = () => {
      if (!current()) return;
      roadsideHelpBooking(target.id, readWhere(), { _generation: generation, readWhere })
        .then(r => {
          if (!current()) return;
          setNote(describe(r)); track("roadside_help");
        })
        .catch(error => {
          if (current()) setNote(error instanceof ApiError && error.status === 429
            ? tooOften()
            : appText("Не удалось подтвердить отправку. Проверь связь, повтори сигнал или позвони 112 и близким, если можешь.",
              "Ебәреүҙе раҫлап булманы. Бәйләнеште тикшер, сигналды ҡабатла йәки, мөмкин булһа, 112-гә һәм яҡындарыңа шылтырат."));
        })
        .finally(() => {
          if (flight.current === ticket) flight.current = null;
          if (current()) setBusy(false);
        });
    };
    try {
      void navigator.permissions?.query({ name: "geolocation" }).then(status => {
        if (current()) permission = status;
      }).catch(() => {});
    } catch { /* Permissions API is optional; a confirmed geolocation fix can still work. */ }
    if (!navigator.geolocation) { send(); return; }
    navigator.geolocation.getCurrentPosition(p => {
      if (!current()) return;
      if (Number.isFinite(p.timestamp) && Number.isFinite(p.coords.latitude) && Number.isFinite(p.coords.longitude)) {
        fix = { lat: p.coords.latitude, lng: p.coords.longitude, timestamp: p.timestamp };
      }
      send();
    }, () => { if (current()) send(); }, { timeout: 8000 });
  }

  function call() {
    if (target.kind === "booking") { callBooking(); return; }
    if (busy) return;
    setConfirm(false);
    setBusy(true);
    setNote("");
    const send = (lat?: number, lng?: number) => {
      const req = roadsideHelpParcel(target.id, { lat, lng });
      req
        .then((r) => {
          setNote(describe(r));
          // Считаем ФАКТ, а не намерение: сигнал, который не ушёл из-за связи, в воронке
          // помощи выглядел бы как оказанная помощь. Имя события — как в приложении,
          // вид беды различаем именем: у попутки и у доставки это разные истории.
          track(target.kind === "parcel" ? "roadside_help_parcel" : "roadside_help");
        })
        .catch((error) =>
          setNote(
            error instanceof ApiError && error.status === 429
              ? tooOften()
              : appText(
                "Сигнал не ушёл — нет связи. Позвони близким или 112.",
                "Сигнал китмәне — бәйләнеш юҡ. Ҡәҙерлеләреңә йәки 112-гә шылтырат."
              )
          )
        )
        .finally(() => setBusy(false));
    };

    if (!navigator.geolocation) {
      send();
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (p) => send(p.coords.latitude, p.coords.longitude),
      () => send(),
      { timeout: 8000 }
    );
  }

  return (
    <>
      <button type="button" className="btn-soft trip-btn trip-btn--warn" onClick={() => setConfirm(true)} disabled={busy}>
        <IconWarn size={18} /> {appText("Застряли на трассе", "Юлда ҡалдыҡ")}
      </button>
      {/* Текст окна — из Android RoadsideHelp.kt, кроме одного слова: там «Кому из близких ушло
          SMS — покажем сразу после». SMS на проде выключены, поэтому здесь «сообщение» — то же
          слово, что в ответе после отправки («Близким отправлено: N»). */}
      {confirm && (
        <AlertDialog
          title={appText("Позвать помощь?", "Ярҙам саҡырырғамы?")}
          text={appText(
            "Отправим сигнал в Юлдаш. Если место доступно, приложим его. Если угрожает опасность — звони 112.",
            "Юлдашҡа сигнал ебәрәбеҙ. Урын билдәле булһа, уны ла өҫтәйбеҙ. Хәүеф янаһа — 112-гә шылтырат."
          )}
          onClose={() => setConfirm(false)}
          confirm={{ label: appText("Позвать помощь", "Ярҙам саҡырыу"), tone: "warn", onClick: call }}
          dismiss={{ label: appText("Отмена", "Кире алыу"), tone: "muted", onClick: () => setConfirm(false) }}
        />
      )}
      {note && (
        <div className="notice" role="status">
          {note}
        </div>
      )}
    </>
  );
}
