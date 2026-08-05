/**
 * Телеграм-почтальон.
 *
 * Бот не умеет писать человеку первым — пока Илиза сама не нажмёт
 * у него «Начать», отправка ей будет молча отваливаться. Это не баг,
 * так устроен телеграм.
 */

const API = "https://api.telegram.org";

type SendResult = { ok: boolean; error?: string };

async function send(
  chatId: string | undefined,
  text: string,
  buttons?: { text: string; url: string }[],
): Promise<SendResult> {
  const token = process.env.TELEGRAM_BOT_TOKEN;
  if (!token) return { ok: false, error: "нет TELEGRAM_BOT_TOKEN" };
  if (!chatId) return { ok: false, error: "не задан адресат" };

  try {
    const res = await fetch(`${API}/bot${token}/sendMessage`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        chat_id: chatId,
        text,
        parse_mode: "HTML",
        link_preview_options: { is_disabled: true },
        ...(buttons?.length
          ? { reply_markup: { inline_keyboard: [buttons.map((b) => b)] } }
          : {}),
      }),
    });

    const data = (await res.json()) as { ok: boolean; description?: string };
    return data.ok ? { ok: true } : { ok: false, error: data.description };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : "сеть" };
  }
}

/** Утреннее приглашение Илизе. Текста письма в нём нет — только зов на сайт. */
export function inviteHer(n: number, daysLeft: number): Promise<SendResult> {
  const url = process.env.SITE_URL ?? "";
  const tail =
    daysLeft === 0
      ? "Сегодня."
      : `До встречи ${daysLeft} ${daysLeft === 1 ? "день" : daysLeft < 5 ? "дня" : "дней"}.`;

  return send(
    process.env.TELEGRAM_CHAT_ID_HER,
    `<b>Письмо №${n}</b> ждёт тебя.\n\n${tail}`,
    url ? [{ text: "Открыть конверт", url }] : undefined,
  );
}

/** Всё, что сайт хочет сказать Байрасу: ответы, нажатия, сбои. */
export function notifyHim(text: string): Promise<SendResult> {
  return send(process.env.TELEGRAM_CHAT_ID_HIM, text);
}

export function notifyHer(text: string): Promise<SendResult> {
  return send(process.env.TELEGRAM_CHAT_ID_HER, text);
}

/**
 * Сообщение второму: сделала она — уходит ему, сделал он — уходит ей.
 * Так список желаний и вопрос дня оживают без дёрганья вручную.
 */
export function notifyOther(actor: "her" | "him", text: string) {
  return actor === "her" ? notifyHim(text) : notifyHer(text);
}
