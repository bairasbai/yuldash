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
import { IconCamera } from "./Icons";

/** Кнопка «приложить фото»: грузим файл → отдаём готовый текст сообщения. */
export function ChatPhotoButton({ onReady }: { onReady: (text: string) => void }) {
  const { appText } = useLang();
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [busy, setBusy] = useState(false);

  async function pick(file: File | undefined) {
    if (!file || busy) return;
    setBusy(true);
    try {
      const { url } = await uploadChatPhoto(file);
      if (url) onReady(`${IMG_PREFIX}${url}`);
    } catch {
      /* не загрузилось — человек попробует ещё раз, чат не ломаем */
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
