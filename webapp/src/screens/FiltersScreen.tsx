// ================================================================
//  Фильтры по умолчанию — зеркало FiltersScreen из приложения (SecondaryScreens.kt): ровно семь
//  условий пассажира, тумблерами; сохраняются на устройстве и применяются к ленте «Ближайшие
//  поездки» при открытии карты. Публично: фильтры — локальная UX-настройка, вход не нужен.
//
//  Раньше здесь были ещё «Мой город» и «Цена до». В приложении их нет: город заменил чип «Откуда»
//  прямо в ленте, а цену сервер не фильтрует — потолок на стороне браузера давал бы ложное
//  «поездок нет», когда подходящие остались на следующих порциях. «Только проверенные» — это
//  настройка раздела «Безопасность», а не условие ленты, и включают её там.
// ================================================================
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { loadFilters, saveFilters, type FilterPrefs } from "../filterPrefs";
import { NEARBY_CONDITIONS, toggleCondition, type NearbyCondition } from "../nearbyFeed";
import { SubHeader } from "./ConsentsScreen";
import { SettingsGroup, SettingSwitchRow } from "../components/cabinetUi";
import { ConditionIcon } from "../components/conditionIcons";

export default function FiltersScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();
  const [prefs, setPrefs] = useState<FilterPrefs>(() => loadFilters());

  // Любое изменение сразу пишем в localStorage — «настройка живёт».
  function toggle(key: NearbyCondition) {
    const next = { ...prefs, conditions: toggleCondition(prefs.conditions, key) };
    setPrefs(next);
    saveFilters(next);
  }

  return (
    <>
      <SubHeader title={appText("Фильтры по умолчанию", "Ғәҙәти фильтрҙар")} onBack={() => navigate(-1)} />

      <div className="form">
        <p className="filters-intro">
          {appText(
            "Эти условия применятся к «Ближайшим поездкам» автоматически при открытии карты.",
            "Был шарттар карта асылғанда «Яҡын сәфәрҙәргә» автомат ҡулланыла."
          )}
        </p>
        <SettingsGroup>
          {NEARBY_CONDITIONS.map((c) => (
            <SettingSwitchRow
              key={c.key}
              icon={<ConditionIcon condition={c.key} size={24} />}
              title={appText(c.ru, c.ba)}
              subtitle={appText(c.hintRu, c.hintBa)}
              checked={prefs.conditions.includes(c.key)}
              onChange={() => toggle(c.key)}
            />
          ))}
        </SettingsGroup>
      </div>
    </>
  );
}
