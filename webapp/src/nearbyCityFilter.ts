// ================================================================
//  Фильтры «Откуда» и «Куда» ленты «Ближайшие поездки» — состояние
//  (зеркало NearbyCityFilterState / rememberNearbyCityFilter из NearbyCityFilter.kt).
//
//  Что выбрано — русские имена (сервер сам ищет и по башкирскому). Из чего выбирать — города,
//  где поездки есть сейчас (`GET /rides/cities`); сверх них человек может найти любой город
//  или деревню по справочнику — такие хранятся в `found`, чтобы подписать чип на его языке.
//  Найденные живут, пока шторка открыта; закрыли — остаются только выбранные (`dropUnchosenFound`).
// ================================================================
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { fetchRideCities, type RideCity } from "./api/discovery";
import { addFoundCity, createRideCitiesLoader, keepChosenFound } from "./nearbyFeed";

export interface NearbyCityFilter {
  from: string[];
  to: string[];
  fromOptions: RideCity[];
  toOptions: RideCity[];
  /** Найденные поиском (город или деревня вне списка): их имя на двух языках — для подписи чипа. */
  found: RideCity[];
  /** Выбран хоть один город. */
  active: boolean;
  /** Есть что выбрать или что снять: тогда чипы на месте, даже если рядом поездок нет. */
  offered: boolean;
  setFrom: (cities: string[]) => void;
  setTo: (cities: string[]) => void;
  addFound: (city: RideCity) => void;
  /** Шторку закрыли: найденные поиском и не выбранные ни в «Откуда», ни в «Куда» уходят. */
  dropUnchosenFound: () => void;
  clear: () => void;
}

/**
 * Состояние фильтров городов. Список городов проверяется вместе с лентой (`reloadKey` растёт на
 * каждое её обновление: авто-раз в ~25 с, «Обновить», возврат на вкладку), а на сервер уходит
 * не чаще раза в 2 минуты — город с новой поездкой появляется сам, сбой сети при открытии карты
 * не прячет чипы до следующего входа, а сервер не получает запрос от каждой открытой карты
 * каждые 25 секунд. Старый сервер без `/rides/cities` — чипов нет.
 */
export function useNearbyCityFilter(reloadKey: number): NearbyCityFilter {
  const [from, setFromState] = useState<string[]>([]);
  const [to, setToState] = useState<string[]>([]);
  const [fromOptions, setFromOptions] = useState<RideCity[]>([]);
  const [toOptions, setToOptions] = useState<RideCity[]>([]);
  const [found, setFound] = useState<RideCity[]>([]);

  // Один загрузчик на жизнь экрана: он помнит, когда список приходил в последний раз.
  const loader = useRef<ReturnType<typeof createRideCitiesLoader> | null>(null);
  if (!loader.current) loader.current = createRideCitiesLoader((signal) => fetchRideCities(signal));

  // Запрос не отменяем при каждом обновлении ленты: загрузчик и так не пускает второй запрос
  // поверх идущего, а отмена оставила бы «идёт» без результата. Уходим с экрана — просто
  // не записываем ответ.
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  useEffect(() => {
    void loader.current?.refresh().then((cities) => {
      if (!cities || !alive.current) return;
      setFromOptions(cities.from);
      setToOptions(cities.to);
    });
  }, [reloadKey]);

  const setFrom = useCallback((cities: string[]) => setFromState(cities), []);
  const setTo = useCallback((cities: string[]) => setToState(cities), []);
  const addFound = useCallback((city: RideCity) => setFound((cur) => addFoundCity(cur, city)), []);
  const dropUnchosenFound = useCallback(
    () =>
      setFound((cur) => {
        const kept = keepChosenFound(cur, from, to);
        return kept.length === cur.length ? cur : kept; // нечего убирать — без лишней перерисовки
      }),
    [from, to]
  );
  const clear = useCallback(() => {
    // Пустое не заменяем новым пустым: иначе лента перезапросилась бы без причины.
    setFromState((cur) => (cur.length ? [] : cur));
    setToState((cur) => (cur.length ? [] : cur));
  }, []);

  const active = from.length > 0 || to.length > 0;
  const offered = active || fromOptions.length > 0 || toOptions.length > 0;

  return useMemo(
    () => ({ from, to, fromOptions, toOptions, found, active, offered, setFrom, setTo, addFound, dropUnchosenFound, clear }),
    [from, to, fromOptions, toOptions, found, active, offered, setFrom, setTo, addFound, dropUnchosenFound, clear]
  );
}
