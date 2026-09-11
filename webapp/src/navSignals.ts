import { useSyncExternalStore } from "react";
import type { InstantOrder } from "./api/instant";

/**
 * Общий сигнал оболочке: во время поиска и самой поездки нижняя навигация прячется.
 * Это зеркало Android NavSignals.taxiOrderOnScreen без связи экрана с DOM.
 */
let taxiOrderOnScreen = false;
let activeTaxiOrder: InstantOrder | null = null;
const taxiScreenListeners = new Set<() => void>();
const activeOrderListeners = new Set<() => void>();

export function setTaxiOrderOnScreen(active: boolean): void {
  if (taxiOrderOnScreen === active) return;
  taxiOrderOnScreen = active;
  taxiScreenListeners.forEach((listener) => listener());
}

function subscribeTaxiScreen(listener: () => void): () => void {
  taxiScreenListeners.add(listener);
  return () => taxiScreenListeners.delete(listener);
}

function getSnapshot(): boolean {
  return taxiOrderOnScreen;
}

export function useTaxiOrderOnScreen(): boolean {
  return useSyncExternalStore(subscribeTaxiScreen, getSnapshot, () => false);
}

/**
 * Живая поездка пассажира доступна всей оболочке, как Android NavSignals.activeTaxiTrip.
 * Благодаря этому профиль и настройки меняют расчёт именно в текущем заказе, а не только
 * запоминают выбор для следующего.
 */
export function setActiveTaxiOrder(order: InstantOrder | null): void {
  if (activeTaxiOrder?.id === order?.id && activeTaxiOrder === order) return;
  activeTaxiOrder = order;
  activeOrderListeners.forEach((listener) => listener());
}

function subscribeActiveOrder(listener: () => void): () => void {
  activeOrderListeners.add(listener);
  return () => activeOrderListeners.delete(listener);
}

function getActiveOrderSnapshot(): InstantOrder | null {
  return activeTaxiOrder;
}

export function useActiveTaxiOrder(): InstantOrder | null {
  return useSyncExternalStore(subscribeActiveOrder, getActiveOrderSnapshot, () => null);
}
