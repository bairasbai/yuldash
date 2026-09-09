import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { LatestDestinationPreview } from "../src/utils/destinationPreview.js";

const HERE = dirname(fileURLToPath(import.meta.url));
const component = readFileSync(join(HERE, "../src/components/TaxiTripActions.tsx"), "utf8");

let bad = 0;
const check = (ok, label, extra = "") => {
  if (!ok) bad++;
  console.log(`${ok ? "✓" : "✗"} ${label}${extra ? "   " + extra : ""}`);
};

const A = { lat: 54.72, lng: 55.94, text: "Адрес A" };
const B = { lat: 54.75, lng: 56.01, text: "Адрес B" };
const quoteA = { price: 200 };
const quoteB = { price: 900 };
const samePoint = (left, right) =>
  left.lat === right.lat && left.lng === right.lng && left.text === right.text;

// A выбран первым, B вторым. Порядок ответов A→B: A уже чужой и не должен даже мигнуть.
{
  const gate = new LatestDestinationPreview(samePoint);
  const requestA = gate.begin(A);
  const requestB = gate.begin(B);
  const afterA = gate.resolve(requestA, quoteA);
  const afterB = gate.resolve(requestB, quoteB);
  check(afterA === null, "A→B, ответы A→B: старый ответ A отброшен");
  check(afterB?.point === B && afterB?.quote === quoteB, "A→B, ответы A→B: сохранена цена B");
}

// Тот же выбор, обратный порядок ответов B→A: поздний A не затирает уже показанный B.
{
  const gate = new LatestDestinationPreview(samePoint);
  const requestA = gate.begin(A);
  const requestB = gate.begin(B);
  const afterB = gate.resolve(requestB, quoteB);
  const afterA = gate.resolve(requestA, quoteA);
  check(afterB?.point === B && afterB?.quote === quoteB, "A→B, ответы B→A: сначала сохранена цена B");
  check(afterA === null, "A→B, ответы B→A: поздний ответ A отброшен");
  check(gate.canApply(B, afterB), "можно подтвердить цену текущего адреса B");
  check(!gate.canApply(A, afterB), "нельзя подтвердить quote, принадлежащий другому адресу");
}

// Ошибка старого запроса не должна заменить состояние нового запроса и снять его busy.
{
  const gate = new LatestDestinationPreview(samePoint);
  const requestA = gate.begin(A);
  gate.begin(B);
  check(!gate.isCurrent(requestA), "ошибка и finally старого A игнорируются");
}

check(
  component.includes("previewGate.current.resolve(revision, await previewDestination(order.id, p))"),
  "настоящий ChangeDestination пропускает ответ клиента через сторож порядка"
);
check(
  component.includes("!previewGate.current.canApply(point, preview)"),
  "настоящий ChangeDestination запрещает подтверждение чужой цены"
);
check(
  component.includes("changeDestination(order.id, preview.point)"),
  "клиент отправляет точку, к которой привязана показанная цена"
);

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
