import { useState } from "react";
import { createRoot } from "react-dom/client";
import { initializeSessionStorage, setSession } from "./src/api/client";
import { fetchInstantOrder, type InstantOrder } from "./src/api/instant";
import { LangProvider } from "./src/i18n/lang";
import Driver from "./src/components/TaxiDriverTripActions";
import Passenger from "./src/components/TaxiTripActions";

type Seed = { order: number; token: string };
const root = createRoot(document.getElementById("root")!);
function Mount({ initial }: { initial: InstantOrder }) {
  const [order, setOrder] = useState(initial);
  const refresh = () => { void fetchInstantOrder(initial.id).then(setOrder).catch(() => {
    root.render(<p role="alert">Fixture read failed</p>);
  }); };
  const Screen = order.role === "driver" ? Driver : Passenger;
  return <LangProvider><Screen order={order} onChanged={refresh} /></LangProvider>;
}
async function boot() {
  const fixture = (window as Window & { __tf09QA?: Seed }).__tf09QA;
  if (!fixture || location.hostname !== "127.0.0.1") throw Error("Fixture origin rejected");
  delete (window as Window & { __tf09QA?: Seed }).__tf09QA;
  await initializeSessionStorage();
  if (!await setSession(fixture.token)) throw Error("Fixture session failed");
  root.render(<Mount initial={await fetchInstantOrder(fixture.order)} />);
}
void boot().catch(() => root.render(<p role="alert">Fixture boot failed</p>));
