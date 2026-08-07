// ================================================================
//  Фильтры по умолчанию (город / цена / удобства / «только свои»).
//  Хранятся локально (localStorage), применяются к ленте и карте.
//  Публично: фильтры — локальная UX-настройка, вход не нужен.
// ================================================================
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import {
  clearFilters,
  loadFilters,
  saveFilters,
  type Amenity,
  type FilterPrefs,
} from "../filterPrefs";
import { SubHeader } from "./ConsentsScreen";
import { IconCheck, IconUsers } from "../components/Icons";
import { AmenityIcon } from "../components/amenityIcons";

export default function FiltersScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();
  const [prefs, setPrefs] = useState<FilterPrefs>(() => loadFilters());
  const [saved, setSaved] = useState(false);

  // Любое изменение сразу пишем в localStorage — «настройка живёт».
  function update(next: FilterPrefs) {
    setPrefs(next);
    saveFilters(next);
    setSaved(true);
  }

  function toggleAmenity(a: Amenity) {
    const has = prefs.amenities.includes(a);
    update({
      ...prefs,
      amenities: has
        ? prefs.amenities.filter((x) => x !== a)
        : [...prefs.amenities, a],
    });
  }

  const amenities: { key: Amenity; label: string }[] = [
    { key: "women_only", label: appText("Только женщины", "Тик ҡатын-ҡыҙ") },
    { key: "non_smoking", label: appText("Без курения", "Тартмайынса") },
    { key: "air_conditioner", label: appText("Кондиционер", "Кондиционер") },
    { key: "baggage", label: appText("Багаж", "Багаж") },
    { key: "child_seat", label: appText("Детское кресло", "Бала урыны") },
    { key: "pets", label: appText("С питомцем", "Хайуан менән") },
    { key: "quiet", label: appText("Тихая поездка", "Тыныс сәфәр") },
  ];

  return (
    <>
      <SubHeader
        title={appText("Фильтры", "Фильтрҙар")}
        subtitle={appText(
          "Настрой ленту под себя — сохраним на этом устройстве",
          "Таҫманы үҙеңә көйлә — был ҡорамала һаҡлайбыҙ"
        )}
        onBack={() => navigate(-1)}
      />

      <div className="form">
        <label className="field">
          <span className="field__label">{appText("Мой город", "Ҡалам")}</span>
          <input
            className="field__input"
            value={prefs.city}
            onChange={(e) => update({ ...prefs, city: e.target.value })}
            placeholder={appText("Например: Сибай", "Мәҫәлән: Сибай")}
            autoComplete="off"
          />
          <span className="field__hint">
            {appText(
              "Показываем поездки, где город встречается в маршруте.",
              "Ҡала маршрутта осраған сәфәрҙәрҙе күрһәтәбеҙ."
            )}
          </span>
        </label>

        <label className="field">
          <span className="field__label">{appText("Цена до, ₽", "Хаҡ, ₽ ҡәҙәр")}</span>
          <input
            className="field__input"
            type="number"
            inputMode="numeric"
            value={prefs.maxPrice ?? ""}
            onChange={(e) => {
              const v = parseInt(e.target.value, 10);
              update({ ...prefs, maxPrice: e.target.value && v > 0 ? v : null });
            }}
            placeholder={appText("не важно", "мөһим түгел")}
          />
        </label>

        <span className="field__label" style={{ marginTop: 4 }}>
          {appText("Удобства", "Уңайлыҡтар")}
        </span>
        <div className="opt-grid">
          {amenities.map((a) => (
            <button
              key={a.key}
              type="button"
              className={"opt-chip" + (prefs.amenities.includes(a.key) ? " is-active" : "")}
              onClick={() => toggleAmenity(a.key)}
            >
              {prefs.amenities.includes(a.key) ? <IconCheck size={16} /> : <AmenityIcon amenity={a.key} size={16} />}
              {a.label}
            </button>
          ))}
        </div>

        <button
          type="button"
          className={"onb__simple" + (prefs.onlyTrusted ? " is-active" : "")}
          onClick={() => update({ ...prefs, onlyTrusted: !prefs.onlyTrusted })}
          style={{ marginTop: 12 }}
        >
          <span className="onb__simple-emoji"><IconUsers size={24} /></span>
          <span className="onb__simple-text">
            <b>{appText("Только свои", "Тик үҙебеҙ")}</b>
            <span>
              {appText(
                "Показывать поездки проверенных попутчиков",
                "Тик тикшерелгән юлдаштарҙың сәфәрҙәрен күрһәтергә"
              )}
            </span>
          </span>
          <span className={"switch" + (prefs.onlyTrusted ? " on" : "")} />
        </button>

        {saved && (
          <div className="consents__status ok" style={{ marginTop: 16 }}>
            <IconCheck size={13} />{" "}
            {appText("Фильтры сохранены", "Фильтрҙар һаҡланды")}
          </div>
        )}

        <button
          type="button"
          className="btn-soft"
          style={{ marginTop: 14 }}
          onClick={() => {
            clearFilters();
            update({ city: "", maxPrice: null, amenities: [], onlyTrusted: false });
          }}
        >
          {appText("Сбросить фильтры", "Фильтрҙарҙы төшөрөргә")}
        </button>
      </div>
    </>
  );
}
