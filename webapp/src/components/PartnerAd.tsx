// ================================================================
//  📣 Реклама партнёра — то, за что бизнес заплатил деньги.
//  Зеркало Android InlinePartnerAdCard / PartnerAdCard.
//
//  Показ считается один раз на объявление за жизнь экрана: сервер режет
//  повторы, но слать их без нужды — портить CTR в кабинете партнёра.
//
//  Место показа (placement) приходит с сервера и НЕ подменяется на клиенте:
//  партнёр платит за конкретное место, показать «где-нибудь ещё» — обмануть
//  его дважды (там, где платил, пусто; там, где не платил, крутится).
//
//  Маркировка erid видна всегда — это требование закона о рекламе.
//  Нет ни ссылки, ни телефона → карточка не кликается вовсе: лучше
//  показать без действия, чем вести в никуда.
// ================================================================
import { useEffect, useRef, useState } from "react";
import { useLang } from "../i18n/lang";
import { fetchAds, sendAdEvent, type AdPlacement, type PartnerAd } from "../api/ads";
import { isOwnApiUrl } from "../api/client";

/**
 * Реклама под место показа. Молчит при любой неудаче: нет сети, ручки
 * ещё нет на проде, пусто — экран просто без рекламы.
 */
export function usePartnerAds(placement: AdPlacement, city?: string): PartnerAd[] {
  const [ads, setAds] = useState<PartnerAd[]>([]);

  useEffect(() => {
    const ac = new AbortController();
    fetchAds({ placement, city: city?.trim() || undefined, signal: ac.signal })
      .then((list) => setAds(Array.isArray(list) ? list : []))
      .catch(() => setAds([])); // 404 / нет сети → тихо без рекламы
    return () => ac.abort();
  }, [placement, city]);

  return ads;
}

/** Телефон для звонка: оставляем только цифры и плюс. Меньше 10 цифр — не номер. */
function telHref(contact: string): string | null {
  const digits = contact.replace(/[^\d+]/g, "");
  return digits.replace(/\D/g, "").length >= 10 ? `tel:${digits}` : null;
}

/** Куда ведёт карточка: сайт партнёра → иначе звонок → иначе никуда. */
function adHref(ad: PartnerAd): string | null {
  const link = ad.target.trim();
  if (link.startsWith("http://") || link.startsWith("https://")) return link;
  return telHref(ad.contact);
}

export default function PartnerAdCard({
  ad,
  label,
}: {
  ad: PartnerAd;
  /** Подпись места: «Партнёр по маршруту», «Совет партнёра», «Партнёр рядом». */
  label?: string;
}) {
  const { appText } = useLang();
  const counted = useRef(false);
  const href = adHref(ad);

  useEffect(() => {
    if (counted.current) return;
    counted.current = true;
    void sendAdEvent(ad.id, "impression");
  }, [ad.id]);

  const labelText = label ?? appText("Партнёр рядом", "Яҡындағы партнёр");
  const buttonText = ad.button.trim() || appText("Открыть", "Асыу");

  return (
    <article className="partner-ad">
      <div className="partner-ad__head">
        <span className="partner-ad__erid">
          {appText("Реклама", "Реклама")}
          {ad.erid ? ` · erid: ${ad.erid}` : ""}
        </span>
        <span className="badge badge--mint partner-ad__label">{labelText}</span>
      </div>

      <div className="partner-ad__body">
        {/* Логотип грузим ТОЛЬКО со своего сервера. Картинка с чужого хоста
            раскрывала бы партнёру IP каждого, кто просто листал ленту, — человек
            рекламу не заказывал и на такой обмен не соглашался. Приложение внешние
            картинки тоже не грузит: там иконка берётся из своего набора. */}
        {isOwnApiUrl(ad.image) ? (
          <img className="partner-ad__logo" src={ad.image} alt="" loading="lazy" />
        ) : (
          <span className="partner-ad__logo partner-ad__logo--letter" aria-hidden>
            {(ad.partner || ad.title).trim().charAt(0).toUpperCase() || "•"}
          </span>
        )}
        <div className="partner-ad__text">
          <div className="partner-ad__title">{ad.title}</div>
          {ad.text && <div className="partner-ad__sub">{ad.text}</div>}
        </div>
        {href && (
          <a
            className="partner-ad__btn"
            href={href}
            target={href.startsWith("tel:") ? undefined : "_blank"}
            rel="noreferrer"
            onClick={() => void sendAdEvent(ad.id, "click")}
          >
            {buttonText}
          </a>
        )}
      </div>
    </article>
  );
}

/**
 * Одно объявление под место показа — самое приоритетное (сервер уже отсортировал).
 * Нет рекламы — нет и пустого места на экране.
 */
export function PartnerAdSlot({
  placement,
  city,
  label,
}: {
  placement: AdPlacement;
  city?: string;
  label?: string;
}) {
  const ads = usePartnerAds(placement, city);
  if (ads.length === 0) return null;
  return <PartnerAdCard ad={ads[0]} label={label} />;
}
