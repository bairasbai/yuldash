// ================================================================
//  📷 Фото посылки: «взял целой» и «отдал целой».
//  Зеркало Android-снимков в ParcelsScreen.
//
//  Это две границы ответственности. Без них спор «привёз битой»
//  упирается в слово против слова, и разбирать нечего: ни курьер
//  не докажет, что взял уже мятой, ни отправитель — что отдавал целой.
//
//  Снимок уходит в ПРИВАТНУЮ область (/upload/evidence), а не в общую:
//  на фото бывают лица, номера квартир и содержимое коробки. Видят его
//  только двое и тот, кто разбирает спор.
//
//  Фото необязательное: заставлять курьера снимать на морозе — терять
//  курьера. Но кнопка всегда на виду, и в споре её отсутствие говорит само.
// ================================================================
import { useRef, useState } from "react";
import { useLang } from "../i18n/lang";
import { uploadEvidence } from "../api/parcels";
import { IconCheck, IconCamera } from "./Icons";

export default function ParcelPhoto({
  kind,
  url,
  onReady,
}: {
  /** pickup — «взял целой», delivery — «отдал целой». Меняет только подпись. */
  kind: "pickup" | "delivery";
  url: string | null;
  onReady: (url: string) => void;
}) {
  const { appText } = useLang();
  const input = useRef<HTMLInputElement | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function pick(file: File | undefined) {
    if (!file || busy) return;
    setBusy(true);
    setError(null);
    try {
      const r = await uploadEvidence(file);
      onReady(r.url);
    } catch {
      setError(appText("Фото не загрузилось, попробуй ещё раз", "Фото йөкләнмәне, ҡабат ҡара"));
    } finally {
      setBusy(false);
      if (input.current) input.current.value = ""; // тот же файл можно выбрать снова
    }
  }

  const label =
    kind === "pickup"
      ? appText("Снять «взял целой»", "«Бөтөн алдым» фотоһы")
      : appText("Снять «отдал целой»", "«Бөтөн бирҙем» фотоһы");

  return (
    <div className="parcel-photo">
      <input
        ref={input}
        type="file"
        accept="image/*"
        capture="environment"
        hidden
        onChange={(e) => void pick(e.target.files?.[0])}
      />
      <button
        type="button"
        className={"btn-soft parcel-photo__btn" + (url ? " is-done" : "")}
        onClick={() => input.current?.click()}
        disabled={busy}
      >
        {url ? <IconCheck size={18} /> : <IconCamera size={18} />}
        {busy
          ? appText("Загружаем фото…", "Фото йөкләнә…")
          : url
            ? appText("Фото приложено", "Фото ҡуйылды")
            : label}
      </button>
      {error && <div className="auth__error">{error}</div>}
      <p className="parcel-photo__note">
        {appText(
          "Фото видят только вы двое и тот, кто разбирает спор.",
          "Фотоны тик икегеҙ һәм бәхәсте хәл итеүсе күрә."
        )}
      </p>
    </div>
  );
}
