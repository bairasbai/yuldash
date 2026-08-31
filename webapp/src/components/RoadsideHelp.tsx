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
// ================================================================
import { useState } from "react";
import { useLang } from "../i18n/lang";
import {
  roadsideHelpBooking,
  roadsideHelpParcel,
  type RoadsideResult,
} from "../api/safety";
import { IconWarn } from "./Icons";
import { track } from "../analytics";

type Where = { kind: "booking"; id: number } | { kind: "parcel"; id: number };

/**
 * Кнопка «застряли». Координаты берём, если браузер даёт: без них сигнал всё равно
 * уходит — сигнал без места лучше, чем ничего.
 */
export default function RoadsideHelp({ target }: { target: Where }) {
  const { appText } = useLang();
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState("");

  function describe(r: RoadsideResult): string {
    if (r.contacts_total === 0) {
      return appText(
        "Поддержка получила сигнал. Близких у тебя в приложении нет — добавь их в «Доверенных», чтобы в следующий раз им ушло сообщение.",
        "Ярҙам хеҙмәте сигналды алды. Ҡәҙерлеләрең өҫтәлмәгән — киләһе юлы хәбәр китһен өсөн уларҙы «Ышаныслы кешеләр»гә өҫтә."
      );
    }
    if (r.contacts_notified === 0) {
      return appText(
        "Поддержка получила сигнал. Сообщение близким сейчас не уходит — позвони им сам.",
        "Ярҙам хеҙмәте сигналды алды. Хәбәр ҡәҙерлеләргә китмәй — уларға үҙең шылтырат."
      );
    }
    return appText(
      `Помощь вызвана. Близким отправлено: ${r.contacts_notified}.`,
      `Ярҙам саҡырылды. Ҡәҙерлеләргә ебәрелде: ${r.contacts_notified}.`
    );
  }

  function call() {
    if (busy) return;
    setBusy(true);
    setNote("");
    const send = (lat?: number, lng?: number) => {
      const req =
        target.kind === "booking"
          ? roadsideHelpBooking(target.id, { lat, lng })
          : roadsideHelpParcel(target.id, { lat, lng });
      req
        .then((r) => {
          setNote(describe(r));
          // Считаем ФАКТ, а не намерение: сигнал, который не ушёл из-за связи, в воронке
          // помощи выглядел бы как оказанная помощь. Имя события — как в приложении,
          // вид беды различаем именем: у попутки и у доставки это разные истории.
          track(target.kind === "parcel" ? "roadside_help_parcel" : "roadside_help");
        })
        .catch(() =>
          setNote(
            appText(
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
      <button type="button" className="btn-soft trip-btn trip-btn--warn" onClick={call} disabled={busy}>
        <IconWarn size={18} /> {appText("Застряли на трассе", "Юлда ҡалдыҡ")}
      </button>
      {note && (
        <div className="notice" role="status">
          {note}
        </div>
      )}
    </>
  );
}
