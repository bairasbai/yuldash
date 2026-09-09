export function winterCheckNeedsAnswer(state: unknown): boolean;
export function winterCheckDelay(asked: boolean, dueAt: number, now?: number): number;
export function wasWinterCheckAsked(key: string): boolean;
export function rememberWinterCheck(key: string): void;
export function forgetWinterCheck(key: string): void;
