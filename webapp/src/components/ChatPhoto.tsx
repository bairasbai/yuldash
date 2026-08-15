// ================================================================
//  Фото в чате: кнопка отправки и рендер картинки в пузыре.
//  Зеркало Android (ApiClient.uploadChatPhoto + префикс «[img]»).
//
//  Зачем: «стою у второго подъезда, где синяя дверь» одной фоткой
//  объясняется лучше, чем десятью сообщениями. Особенно там, где
//  адреса нет — а в сёлах его часто нет.
//
//  Фото чата лежит в публичной папке media (его видит собеседник),
//  отдельно от документов водителя — те приватны.
// ================================================================
import { useRef, useState } from "react";
import { useLang } from "../i18n/lang";
import { uploadChatPhoto, IMG_PREFIX, imageUrlOf } from "../api/chat";
import { ApiError } from "../api/client";
import { IconCamera } from "./Icons";

/** Кнопка «приложить фото»: грузим файл → отдаём готовый текст сообщения. */
export function ChatPhotoButton({
  onReady,
  onProblem,
}: {
  onReady: (text: string) => void;
  /** Экран покажет это человеку: выбрал фото, а его не отправили — надо сказать. */
  onProblem?: (message: string) => void;
}) {
  const { appText } = useLang();
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [busy, setBusy] = useState(false);

  async function pick(file: File | undefined) {
    if (!file || busy) return;
    setBusy(true);
    onProblem?.("");
    try {
      const { url } = await uploadChatPhoto(file);
      if (url) onReady(`${IMG_PREFIX}${url}`);
    } catch (e) {
      // Выбрал фото, оно не ушло — и раньше об этом никто не сообщал.
      // Отдельно про размер: сервер отвергает большие файлы, а человек снимает
      // на телефон, где кадр легко весит десяток мегабайт.
      const tooBig = e instanceof ApiError && (e.status === 413 || e.status === 422);
      onProblem?.(
        tooBig
          ? appText(
              "Фото слишком большое — больше 10 МБ. Сфотографируй заново камерой телефона: такой снимок весит меньше.",
              "Фото артыҡ ҙур — 10 МБ-тан күберәк. Телефон камераһы менән яңынан төшөр: ундай һүрәт еңелерәк."
            )
          : appText("Фото не отправилось. Попробуй ещё раз.", "Фото китмәне. Тағы ҡабатла.")
      );
    } finally {
      setBusy(false);
      if (inputRef.current) inputRef.current.value = "";
    }
  }

  return (
    <>
      <input
        ref={inputRef}
        type="file"
        accept="image/*"
        hidden
        onChange={(e) => void pick(e.target.files?.[0])}
      />
      <button
        type="button"
        onClick={() => inputRef.current?.click()}
        disabled={busy}
        aria-label={appText("Приложить фото", "Фото тағыу")}
      >
        <IconCamera size={20} />
      </button>
    </>
  );
}

/** Содержимое пузыря: картинка, если сообщение — фото; иначе обычный текст. */
export function ChatMessageBody({ text }: { text: string }) {
  const { appText } = useLang();
  const url = imageUrlOf(text);
  if (!url) return <>{text}</>;
  return (
    <a href={url} target="_blank" rel="noreferrer">
      <img
        className="chat-photo"
        src={url}
        alt={appText("Фото в чате — открыть", "Чаттағы фото — асыу")}
        loading="lazy"
      />
    </a>
  );
}
