// ================================================================
//  🚗 «Позови водителя» — блок на пустой выдаче поиска.
//  Зеркало Android InviteDriverCallout.
//
//  Пусто в ленте — тупик: человек пришёл ехать и уходит ни с чем.
//  Здесь единственное, что он может сделать прямо сейчас, — позвать
//  знакомого за руль. Приглашённый сделает первый рейс → позвавшему
//  бесплатное поднятие поездки.
//
//  Не вошёл в аккаунт → делимся без кода, общей ссылкой: просить
//  логин ради приглашения — терять и приглашение, и человека.
// ================================================================
import { useEffect, useState } from "react";
import { useLang } from "../i18n/lang";
import { fetchReferral } from "../api/referral";
import { YuModeRideshare } from "./BrandIcons";

export default function InviteDriverCallout() {
  const { appText } = useLang();
  const [code, setCode] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  useEffect(() => {
    const ac = new AbortController();
    fetchReferral(ac.signal)
      .then((r) => setCode(r.code))
      .catch(() => setCode(null)); // гость / нет сети → общая ссылка без кода
    return () => ac.abort();
  }, []);

  const text = code
    ? appText(
        `Юлдаш — попутки между своими по Башкортостану. Становись водителем по моему приглашению: сделаешь первый рейс — обоим бонус. Мой код: ${code}. Скачать: https://yulbash.ru`,
        `Юлдаш — Башҡортостан буйлап үҙебеҙ араһында юлдаштар. Мине саҡырыу буйынса водитель бул: тәүге сәфәрҙе яһаһаң — икебеҙгә лә бонус. Кодым: ${code}. Йөкләү: https://yulbash.ru`
      )
    : appText(
        "Юлдаш — попутки между своими по Башкортостану. Становись водителем: публикуй поездки и вози соседей. Скачать: https://yulbash.ru",
        "Юлдаш — Башҡортостан буйлап үҙебеҙ араһында юлдаштар. Йөрөтөүсе бул: сәфәрҙәр ҡуй һәм күршеләрҙе йөрөт. Йөкләү: https://yulbash.ru"
      );

  async function invite() {
    if (navigator.share) {
      try {
        await navigator.share({ title: "Юлдаш", text });
        return;
      } catch {
        /* отменил — не ошибка */
      }
    }
    try {
      await navigator.clipboard.writeText(text);
      setDone(true);
      window.setTimeout(() => setDone(false), 1600);
    } catch {
      /* буфер недоступен — кнопка просто ничего не делает, экран цел */
    }
  }

  return (
    <section className="invite-driver">
      <div className="invite-driver__head">
        <span className="invite-driver__ic" aria-hidden>
          <YuModeRideshare size={20} />
        </span>
        <div>
          <div className="invite-driver__title">
            {appText("Никто не едет? Позови водителя", "Бер кем дә бармаймы? Йөрөтөүсе саҡыр")}
          </div>
          <p className="invite-driver__sub">
            {appText(
              "Пригласи знакомого. Сделает первый рейс — тебе бесплатный Boost.",
              "Танышыңды саҡыр. Тәүге сәфәрен яһаһа — һиңә бушлай Boost."
            )}
          </p>
        </div>
      </div>
      <button type="button" className="btn-primary" onClick={invite}>
        {done
          ? appText("Ссылка скопирована", "Һылтанма күсерелде")
          : appText("Пригласить водителя", "Йөрөтөүсе саҡырыу")}
      </button>
    </section>
  );
}
