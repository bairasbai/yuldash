export function loginPhoneValid(phone: string): boolean;
export function nextNeedPhone(current: boolean, error: LoginError | null): boolean;
export function loginCodeValid(code: string): boolean;
export function filterLoginCode(value: string): string;
export type LoginError = "botMissing" | "start" | "enterTgCode" | "badTgCode" | "phoneRequired" | "notYet" | "expired" | "tooMany" | "verify" | "save" | "enterPhone" | "sendFail" | "smsTooMany" | "enterSmsCode" | "badSmsCode";
export interface LoginFlowDeps {
  getGeneration(): string;
  botAvailable(): boolean;
  tgStart(): Promise<{ request_id: string }>;
  tgVerify(requestId: string, code: string): Promise<{ access_token: string; refresh_token: string; user: unknown }>;
  smsRequest(phone: string): Promise<unknown>;
  smsVerify(phone: string, code: string, name: string): Promise<{ access_token: string; refresh_token: string; user: unknown }>;
  login(access: string, refresh: string, user: any): void;
  updateName(name: string): Promise<unknown>;
  getStatus(error: unknown): number;
  telegramStartUrl(id: string): string;
  telegramChatUrl(): string;
  openTelegram(url: string): void;
  track(event: string, properties: { method: string }): void;
  navigate(): void;
  onBusy(busy: boolean): void;
  onError(error: LoginError | null): void;
  onTgStarted(requestId: string): void;
  onSmsStarted(): void;
}
export function createLoginFlow(deps: LoginFlowDeps): {
  readonly busy: boolean;
  invalidate(): void;
  revive(): void;
  dispose(): void;
  start(): Promise<void> | void;
  openTelegramAgain(needPhone: boolean): Promise<void> | void;
  verify(requestId: string, code: string, name: string): Promise<void>;
  smsRequest(phone: string): Promise<void>;
  smsVerify(phone: string, code: string, name: string): Promise<void>;
};
