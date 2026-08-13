// ================================================================
//  🗺 «Где работаю» — зона заказов таксиста. Зеркало Android GeoUi.
//
//  Как «Мой район» у Яндекс Про, но бесплатно: в базовом режиме ОБЕ точки
//  заказа внутри зоны, выход за неё — только по тумблеру. Без этого выбора
//  заказы сыплются отовсюду, водитель читает каждый вручную и через неделю
//  просто перестаёт выходить на линию.
//
//  «Соседние регионы» без «выезда загород» смысла не имеют — сервер всё равно
//  их свяжет, поэтому и в интерфейсе второй тумблер включается только вместе
//  с первым: показывать выбор, который ничего не меняет, — врать человеку.
//
//  Влияет только на такси: попутка зоной не ограничивается.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { fetchWorkZone, saveWorkZone, type WorkZone } from "../api/instant";
import { fetchDistricts, type District } from "../api/geo";
import { IconCheck, IconPin } from "./Icons";

type Base = "city" | "district";

export default function WorkZoneCard() {
  const { appText } = useLang();

  const [zone, setZone] = useState<WorkZone | null>(null);
  const [open, setOpen] = useState(false);
  const [base, setBase] = useState<Base>("city");
  const [city, setCity] = useState("");
  const [district, setDistrict] = useState("");
  const [intercity, setIntercity] = useState(false);
  const [regions, setRegions] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  // Подсказки районов — те же, что в приложении: опечатка в районе = тишина в офферах.
  const [districts, setDistricts] = useState<District[]>([]);

  const load = useCallback((signal?: AbortSignal) => {
    fetchWorkZone(signal)
      .then((z) => {
        setZone(z);
        setBase(z.work_zone === "district" ? "district" : "city");
        setCity(z.work_city ?? "");
        setDistrict(z.work_district ?? "");
        setIntercity(z.work_intercity);
        setRegions(z.work_regions);
      })
      .catch(() => setZone(null)); // 403/404 — не таксист или ручки нет → карточки нет
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  useEffect(() => {
    if (!open || base !== "district") return;
    const ac = new AbortController();
    fetchDistricts(district, ac.signal)
      .then((rows) => setDistricts(rows.slice(0, 8)))
      .catch(() => setDistricts([]));
    return () => ac.abort();
  }, [open, base, district]);

  if (!zone) return null;

  const summary = (() => {
    const head =
      zone.work_zone === "district"
        ? zone.work_district
          ? appText(`Мой район · ${zone.work_district}`, `Минең район · ${zone.work_district}`)
          : appText("Мой район", "Минең район")
        : zone.work_city
          ? appText(`Мой город · ${zone.work_city}`, `Минең ҡала · ${zone.work_city}`)
          : appText("Мой город или село", "Минең ҡала йәки ауыл");
    const extra = [
      zone.work_intercity ? appText("+ загород", "+ ҡала тышы") : "",
      zone.work_regions ? appText("+ соседние регионы", "+ күрше төбәктәр") : "",
    ]
      .filter(Boolean)
      .join(" ");
    return extra ? `${head} ${extra}` : head;
  })();

  async function save() {
    if (busy) return;
    // Пустое поле = заказы отовсюду, а человек будет думать, что выбрал зону.
    if (base === "city" && !city.trim()) {
      setError(
        appText(
          "Выбери город или село — иначе заказы будут приходить отовсюду.",
          "Ҡала йәки ауылды һайла — юғиһә заказдар бөтә ерҙән килә."
        )
      );
      return;
    }
    if (base === "district" && !district.trim()) {
      setError(
        appText(
          "Выбери район — иначе заказы будут приходить отовсюду.",
          "Районды һайла — юғиһә заказдар бөтә ерҙән килә."
        )
      );
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const z = await saveWorkZone({
        zone: base,
        work_city: base === "city" ? city.trim() : null,
        work_district: base === "district" ? district.trim() : null,
        work_intercity: intercity,
        work_regions: intercity && regions,
      });
      setZone(z);
      setOpen(false);
      setSaved(true);
      window.setTimeout(() => setSaved(false), 2000);
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось сохранить. Проверь сеть и повтори.", "Һаҡлап булманы. Селтәрҙе тикшереп ҡабатла.")
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="workzone">
      <button
        type="button"
        className="workzone__head"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
      >
        <span className="workzone__ic" aria-hidden>
          <IconPin size={18} />
        </span>
        <span className="workzone__main">
          <span className="workzone__title">{appText("Где работаю", "Ҡайҙа эшләйем")}</span>
          <span className="workzone__sub">{summary}</span>
        </span>
        <span className="workzone__action">
          {saved ? <IconCheck size={18} /> : open ? appText("Скрыть", "Йәшерергә") : appText("Изменить", "Үҙгәртеү")}
        </span>
      </button>

      {open && (
        <div className="workzone__body">
          <p className="workzone__note">
            {appText(
              "Заказы придут только по выбранной зоне. Поменять можно в любой момент.",
              "Заказдар тик һайланған зона буйынса килә. Теләһә ҡасан үҙгәртеп була."
            )}
          </p>

          <div className="seg">
            <button
              type="button"
              className={"seg__item" + (base === "city" ? " is-active" : "")}
              onClick={() => setBase("city")}
            >
              {appText("Мой город или село", "Минең ҡала йәки ауыл")}
            </button>
            <button
              type="button"
              className={"seg__item" + (base === "district" ? " is-active" : "")}
              onClick={() => setBase("district")}
            >
              {appText("Мой район", "Минең район")}
            </button>
          </div>

          {base === "city" ? (
            <>
              <label className="field">
                <span className="field__label">{appText("Город или село", "Ҡала йәки ауыл")}</span>
                <input
                  className="field__input"
                  value={city}
                  onChange={(e) => setCity(e.target.value)}
                  placeholder={appText("Например, Сибай", "Мәҫәлән, Сибай")}
                  autoComplete="off"
                />
              </label>
              <p className="workzone__hint">
                {appText("Заказы внутри одного населённого пункта", "Бер иленең эсендәге заказдар")}
              </p>
            </>
          ) : (
            <>
              <label className="field">
                <span className="field__label">{appText("Район работы", "Эш районы")}</span>
                <input
                  className="field__input"
                  value={district}
                  onChange={(e) => setDistrict(e.target.value)}
                  placeholder={appText("Например, Абзелиловский", "Мәҫәлән, Әбйәлил")}
                  autoComplete="off"
                />
              </label>
              {districts.length > 0 && (
                <div className="chips" style={{ marginTop: 8 }}>
                  {districts.map((d) => (
                    <button
                      key={d.district}
                      type="button"
                      className={"chip" + (district === d.district ? " chip--on" : "")}
                      onClick={() => setDistrict(d.district)}
                      title={`${d.count} · ${d.region}`}
                    >
                      {d.district}
                    </button>
                  ))}
                </div>
              )}
              <p className="workzone__hint">
                {appText("Весь район: райцентр и все сёла", "Бөтә район: район үҙәге һәм бөтә ауылдар")}
              </p>
            </>
          )}

          <label className="admin-check" style={{ marginTop: 12 }}>
            <input
              type="checkbox"
              checked={intercity}
              onChange={(e) => {
                setIntercity(e.target.checked);
                if (!e.target.checked) setRegions(false);
              }}
            />
            <span>{appText("Выезд загород", "Ҡала тышына сығыу")}</span>
          </label>
          <p className="workzone__hint">
            {appText("Дальние заказы за пределы моей зоны", "Зонамдан тыш алыҫ заказдар")}
          </p>

          {/* Второй тумблер живёт только вместе с первым: «регионы» без «загорода»
              сервер всё равно выключит, и человек решил бы, что его обманули. */}
          {intercity && (
            <>
              <label className="admin-check">
                <input
                  type="checkbox"
                  checked={regions}
                  onChange={(e) => setRegions(e.target.checked)}
                />
                <span>{appText("Соседние регионы", "Күрше төбәктәр")}</span>
              </label>
              <p className="workzone__hint">
                {appText(
                  "Магнитогорск, Оренбург, Казань и другие",
                  "Магнитогорск, Ырымбур, Ҡазан һәм башҡалар"
                )}
              </p>
            </>
          )}

          {error && <div className="auth__error">{error}</div>}

          <button type="button" className="btn-primary" onClick={save} disabled={busy}>
            {busy ? appText("Сохраняем…", "Һаҡлайбыҙ…") : appText("Сохранить зону", "Зонаны һаҡларға")}
          </button>
        </div>
      )}
    </section>
  );
}
