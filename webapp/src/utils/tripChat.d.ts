import type { ChatMessage } from "../api/chat";

export const NO_MARK: number;
export const NEAR_BOTTOM_PX: number;
export const KEYBOARD_MIN_PX: number;
export function getSeen(generation: string | number, bookingId: number): number;
export function setSeen(generation: string | number, bookingId: number, id: number): number;
export function clearSeen(): void;
export function visibleMessages(list: ChatMessage[]): ChatMessage[];
export function newestId(list: ChatMessage[]): number;
export function unreadCount(list: ChatMessage[], seenId: number, myId: number): number;
export function lastVisible(list: ChatMessage[]): ChatMessage | null;
export function previewKind(
  m: ChatMessage | null,
  imgPrefix?: string
): { kind: "none" | "deleted" | "voice" | "photo" | "text"; text: string };
export function isNearBottom(
  scrollTop: number,
  clientHeight: number,
  scrollHeight: number,
  threshold?: number
): boolean;
export function viewportBox(
  innerHeight: number,
  vvHeight: number,
  vvTop: number
): { top: number; height: number; covered: number; keyboardOpen: boolean };
