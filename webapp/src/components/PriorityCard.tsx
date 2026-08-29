import { useEffect, useState } from "react";

import { fetchCourierPriority, fetchDriverPriority, type Priority } from "../api/courier";
import { useLang } from "../i18n/lang";

/**
 * ⭐ Мой приоритет — кому заказ достаётся первым.
 *
 * Показываем целиком: сколько баллов, за что каждый и что их отнимает. И отдельно
 * проговариваем то, чего нет у агрегаторов: за малый объём мы не наказываем, а отказаться
 * от заказа можно свободно. Это надо сказать словами — иначе человек боится по привычке.
 */
export default function PriorityCard({ courier = false }: { courier?: boolean }) {
  const { appText } = useLang();
  const [data, setData] = useState<Priority | null>(null);

  useEffect(() => {
    const ac = new AbortController();
    (courier ? fetchCourierPriority(ac.signal) : fetchDriverPriority(ac.signal))
      .then(setData)
      .catch(() => {
        /* тихо: приоритет — не то, ради чего стоит показывать ошибку на весь экран */
      });
    return () => ac.abort();
  }, [courier]);

  if (!data) return null;

  const текст = (p: PriorityPartLike): string => {
    switch (p.code) {
      case "rating":
        return appText(`Рейтинг ${p.value.toFixed(1)}`, `Рейтинг ${p.value.toFixed(1)}`);
      case "active":
        return appText(`${Math.round(p.value)} заказов за неделю`, `Аҙнала ${Math.round(p.value)} заказ`);
      case "hard_trips":
        return appText(
          "Возишь туда, куда не хотят: ночь, метель, село",
          "Бүтәндәр теләмәгән ергә йөрөтәһең: төн, буран, ауыл"
        );
      case "newbie":
        return appText(
          `Новичку — аванс на первые ${Math.round(p.value)} дней`,
          `Яңы башлаусыға — тәүге ${Math.round(p.value)} көнгә аванс`
        );
      default:
        return appText("Бросил принятый заказ", "Алынған заказды ташлағанһың");
    }
  };

  return (
    <div className="admin-card" style={{ padding: 14 }}>
      <div className="funnel__top">
        <b>
          {data.points}/{data.max_points}
        </b>
        <span>
          {data.points >= data.max_points
            ? appText("заказы приходят первыми", "заказдар беренсе килә")
            : appText("чем больше баллов, тем раньше заказ", "балл күберәк — заказ иртәрәк")}
        </span>
      </div>
      {data.parts.map((p) => (
        <p key={p.code} className="funnel__period">
          +{p.points} · {текст(p)}
        </p>
      ))}
      {data.minus > 0 && (
        <p className="funnel__period">
          −{data.minus} ·{" "}
          {appText("бросил принятый заказ за неделю", "аҙнала алынған заказды ташлағанһың")}
        </p>
      )}
      {courier && (data.feed_delay_sec ?? 0) > 0 && (
        <p className="funnel__period">
          {appText(
            `Новые заказы ты видишь на ${data.feed_delay_sec} сек позже приоритетных`,
            `Яңы заказдарҙы өҫтөнлөклөләрҙән ${data.feed_delay_sec} сек һуңыраҡ күрәһең`
          )}
        </p>
      )}
      <p className="funnel__period">
        {appText(
          "Мало заказов — не страшно, за это мы не снимаем ничего. Отказаться от заказа тоже можно свободно.",
          "Заказ аҙ булыуы — ҡурҡыныс түгел, уның өсөн бер нәмә лә алмайбыҙ. Заказдан баш тартырға ла ирекле."
        )}
      </p>
    </div>
  );
}

type PriorityPartLike = { code: string; points: number; value: number };
