import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { canOpenParcelDispute } from "../src/utils/parcelDispute.js";

const HERE = dirname(fileURLToPath(import.meta.url));
const parcels = readFileSync(join(HERE, "../src/screens/ParcelsScreen.tsx"), "utf8");
const actions = readFileSync(join(HERE, "../src/components/ParcelProblemActions.tsx"), "utf8");
const api = readFileSync(join(HERE, "../src/api/parcels.ts"), "utf8");
const mine = parcels.match(/function MineTab\(\) \{([\s\S]*?)\/\/ ={20,} Вкладка «Возить»/)?.[1] ?? "";

let bad = 0;
const check = (ok, label) => {
  if (!ok) bad++;
  console.log(`${ok ? "✓" : "✗"} ${label}`);
};

check(
  mine.includes('role="sender"'),
  "настоящая карточка MineTab подключает спор отправителя"
);
check(
  mine.includes("canOpenParcelDispute(p, user?.id)"),
  "карточка скрывает действие до назначения курьера и в неизвестном состоянии"
);
check(
  actions.includes("parcelDispute(parcel.id, { reason: reason.trim(), type: dtype })"),
  "форма создаёт настоящий спор через parcelDispute"
);
check(
  actions.includes('setIncidentHref(incidentId ? `/incidents/${incidentId}` : "/fairness")') &&
    actions.includes("to={incidentHref}"),
  "после создания появляется ссылка на настоящий разбор"
);
check(
  api.includes('apiPost(`/parcels/${id}/dispute`'),
  "клиент отправляет спор в серверную ручку посылки"
);

const parcel = (status, extra = {}) => ({
  id: 7,
  sender_id: 10,
  courier_id: 20,
  status,
  delivery_type: "poputka",
  ...extra,
});

for (const status of ["accepted", "in_transit", "returning", "delivered", "returned", "canceled"]) {
  check(canOpenParcelDispute(parcel(status), 10), `отправитель может открыть спор: ${status}`);
}
check(
  canOpenParcelDispute(
    parcel("in_transit", { delivery_type: "buy_bring", settlement: { goods_actual_kop: 120000 } }),
    10
  ),
  "купленный buy_bring имеет путь спора"
);
check(!canOpenParcelDispute(parcel("created", { courier_id: null }), 10), "без курьера действие скрыто");
check(!canOpenParcelDispute(parcel("unknown"), 10), "неизвестный статус не получает действие");
check(!canOpenParcelDispute(parcel("in_transit"), 99), "посторонний не получает действие");

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
