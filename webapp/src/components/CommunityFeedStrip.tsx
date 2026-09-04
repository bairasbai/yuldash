// ================================================================
//  Живая лента сообщества под картой.
//  Зеркало Android (MainActivity.kt, mapFeedFrom) + GET /feed.
//
//  Зачем. В райцентре на карте может не быть ни одной машины прямо
//  сейчас — и новый человек решает, что сервисом никто не пользуется.
//  Числа отвечают на это фактом: столько поездок было за день, столько
//  человек за рулём, вот самый частый маршрут недели.
//
//  Числа настоящие, с сервера. Нулей не стесняемся, но и не показываем:
//  пустая строка «0 поездок» хуже, чем её отсутствие. Ничего не пришло
//  — полосы просто нет (сверка с Android, 2026-08-30).
// ================================================================
import { useEffect, useState } from "react";
import { useLang } from "../i18n/lang";
import { fetchCommunityFeed, type CommunityFeed } from "../api/discovery";

/** Русский счёт поездок: 1 поездка / 2 поездки / 5 поездок. */
function ridesRu(n: number): string {
  const mod100 = n % 100;
  const mod10 = n % 10;
  if (mod100 >= 11 && mod100 <= 14) return "поездок";
  if (mod10 === 1) return "поездка";
  if (mod10 >= 2 && mod10 <= 4) return "поездки";
  return "поездок";
}

/** Русский счёт водителей: 1 водитель / 2 водителя / 5 водителей. */
function driversRu(n: number): string {
  const mod100 = n % 100;
  const mod10 = n % 10;
  if (mod100 >= 11 && mod100 <= 14) return "водителей";
  if (mod10 === 1) return "водитель";
  if (mod10 >= 2 && mod10 <= 4) return "водителя";
  return "водителей";
}

interface Card {
  key: string;
  eyebrow: string;
  title: string;
  note: string;
}

export default function CommunityFeedStrip() {
  const { appText } = useLang();
  const [feed, setFeed] = useState<CommunityFeed | null>(null);

  useEffect(() => {
    const ac = new AbortController();
    fetchCommunityFeed(ac.signal)
      .then(setFeed)
      .catch(() => {
        /* нет сети / нет ручки — полосы просто не будет */
      });
    return () => ac.abort();
  }, []);

  if (!feed) return null;

  const cards: Card[] = [];
  if (feed.today > 0) {
    cards.push({
      key: "today",
      eyebrow: appText("Сегодня", "Бөгөн"),
      title: appText(`${feed.today} ${ridesRu(feed.today)}`, `${feed.today} сәфәр`),
      note: appText("Земляки уже в пути", "Яҡташтар юлда"),
    });
  }
  if (feed.top_route && feed.top_route.count > 0) {
    const r = feed.top_route;
    cards.push({
      key: "top",
      eyebrow: appText("Хит недели", "Аҙна хиты"),
      title: `${r.from_city} → ${r.to_city}`,
      note: appText(`${r.count} раз за неделю`, `Аҙнаға ${r.count} тапҡыр`),
    });
  }
  if (feed.month > 0) {
    cards.push({
      key: "month",
      eyebrow: appText("За месяц", "Айға"),
      title: appText(`${feed.month} ${ridesRu(feed.month)}`, `${feed.month} сәфәр`),
      note:
        feed.drivers > 0
          ? appText(`${feed.drivers} ${driversRu(feed.drivers)} за рулём`, `${feed.drivers} водитель юлда`)
          : appText("Спасибо, что вы вместе ❤️", "Бергә булғанға рәхмәт ❤️"),
    });
  }
  if (feed.year > 0) {
    cards.push({
      key: "year",
      eyebrow: appText("За год", "Йылға"),
      title: appText(`${feed.year} ${ridesRu(feed.year)}`, `${feed.year} сәфәр`),
      note: appText("Спасибо, что вы вместе ❤️", "Бергә булғанға рәхмәт ❤️"),
    });
  }
  if (feed.donations_total > 0) {
    cards.push({
      key: "donate",
      eyebrow: appText("Поддержка", "Ярҙам"),
      title: appText(
        `${feed.donations_total.toLocaleString("ru-RU")} ₽ собрано`,
        `${feed.donations_total.toLocaleString("ru-RU")} һум йыйылды`
      ),
      note: appText("На развитие сервиса, от людей", "Хеҙмәтте үҫтереүгә, кешеләрҙән"),
    });
  }

  if (cards.length === 0) return null;

  return (
    <div className="feed-strip" role="list">
      {cards.map((c) => (
        <div key={c.key} className="feed-strip__card" role="listitem">
          <span className="feed-strip__eyebrow">{c.eyebrow}</span>
          <span className="feed-strip__title">{c.title}</span>
          <span className="feed-strip__note">{c.note}</span>
        </div>
      ))}
    </div>
  );
}
