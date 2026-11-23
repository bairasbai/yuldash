// Inactive: no browser storage, network, session observer, or UI is installed here.
export type DriverRecoveryOwner = Readonly<{
  api: string;
  // Trusted adapter resolves a hash of exact authenticated owner + durable session + API.
  // It must be based on the same authoritative login record, not merely token/subject claims.
  scopeHash: string;
  epoch: object; // current in-memory callback identity; never persisted
}>;

interface RecoverySlot {
  isCurrent(): boolean;
  read(): Promise<string | null>;
  commit(value: string | null): Promise<boolean>;
}

export interface DriverRecoveryBoundary {
  // Coordinate the single slot and authoritative session transitions across tabs.
  // The adapter must hold the fence across awaits, guarantee commit durability and
  // allow final synchronous admission while current. A localStorage read/write pair
  // (or a cached per-tab auth check) does not meet this contract.
  exclusively<T>(owner: DriverRecoveryOwner, work: (slot: RecoverySlot) => Promise<T>): Promise<T | undefined>;
}

const handleWire = /^[A-Za-z0-9_-]{1,1000}={0,2}$/u;
const hashWire = /^[0-9a-f]{64}$/u;
function validHandle(value: string): boolean {
  return typeof value === "string" && handleWire.test(value) && !/[\r\n]/u.test(value);
}

function validOwner(owner: DriverRecoveryOwner): boolean {
  try {
    if (typeof owner.api !== "string" || owner.api.length > 512 || !hashWire.test(owner.scopeHash)
        || owner.scopeHash.length !== 64 || !owner.epoch || typeof owner.epoch !== "object") return false;
    const url = new URL(owner.api);
    return (url.protocol === "https:" || url.protocol === "http:") && !url.username && !url.password
      && !url.search && !url.hash && url.href.replace(/\/$/u, "") === owner.api;
  } catch { return false; }
}

type Permit = object;
type Admission = "not-invoked" | "invoked-outcome-unknown";
const prefix = (owner: DriverRecoveryOwner) => `1\n${owner.api}\n${owner.scopeHash}\n`;

function readOwned(owner: DriverRecoveryOwner, row: string | null): string | null {
  const start = prefix(owner);
  const handle = row?.startsWith(start) ? row.slice(start.length) : null;
  return handle !== null && validHandle(handle) ? handle : null;
}

export class TaxiDriverRecoveryJournal {
  private armed: { permit: Permit; owner: DriverRecoveryOwner; handle: string } | null = null;
  private uncertain = false;
  constructor(private readonly boundary: DriverRecoveryBoundary) {}

  private async guarded<T>(owner: DriverRecoveryOwner, work: (slot: RecoverySlot) => Promise<T>): Promise<T | undefined> {
    if (!validOwner(owner)) return undefined;
    try {
      return await this.boundary.exclusively<T | undefined>(owner, async slot => slot.isCurrent() ? work(slot) : undefined);
    } catch { return undefined; }
  }

  private async save(slot: RecoverySlot, value: string | null): Promise<boolean> {
    this.uncertain = true;
    const durable = await slot.commit(value) && await slot.read() === value;
    if (durable) this.uncertain = false;
    return durable && slot.isCurrent();
  }

  /** A fresh issuance from the explicit click, not restored status, is the only caller. */
  async prepare(input: DriverRecoveryOwner, handle: string): Promise<Permit | null> {
    if (!validHandle(handle)) return null;
    const owner = Object.freeze({ ...input });
    return await this.guarded(owner, async slot => {
      if (this.uncertain || await slot.read() !== null || !slot.isCurrent() || !await this.save(slot, prefix(owner) + handle)) return null;
      const permit: Permit = Object.freeze({});
      this.armed = { permit, owner, handle };
      return permit;
    }) ?? null;
  }

  /** Invoke only at final command admission. Invocation never proves an HTTP outcome. */
  async consume(permit: Permit, admit: () => void): Promise<Admission> {
    const armed = this.armed;
    if (!armed || armed.permit !== permit) return "not-invoked";
    let invoked = false;
    await this.guarded(armed.owner, async slot => {
      if (this.armed !== armed) return;
      this.armed = null;
      if (this.uncertain || readOwned(armed.owner, await slot.read()) !== armed.handle || !slot.isCurrent()) return;
      // Once control passed to the caller, neither its throw nor lock cleanup
      // failure proves the command was never dispatched.
      invoked = true;
      admit();
    });
    return invoked ? "invoked-outcome-unknown" : "not-invoked";
  }

  /** Status only. Keep the value out of URLs/cache/logs; never send accept from it. */
  async restoreStatus(input: DriverRecoveryOwner): Promise<string | null> {
    const owner = Object.freeze({ ...input });
    return await this.guarded(owner, async slot => {
      const handle = this.uncertain ? null : readOwned(owner, await slot.read());
      return slot.isCurrent() ? handle : null;
    }) ?? null;
  }

  /** Only exact authenticated server commitment clears the slot. Expiry/errors do not. */
  async reconcile(input: DriverRecoveryOwner, handle: string, evidence: unknown): Promise<boolean> {
    if (evidence !== "committed" || !validHandle(handle)) return false;
    const owner = Object.freeze({ ...input });
    return await this.guarded(owner, async slot => {
      if (this.uncertain || readOwned(owner, await slot.read()) !== handle || !slot.isCurrent()) return false;
      this.armed = null;
      return await this.save(slot, null);
    }) ?? false;
  }

  /** Trusted logout before loss of ownership or explicitly verified session replacement.
   * Never invoke on refresh, cold restore, an unknown result, or a late network callback.
   */
  async clearAtSessionBoundary(input: DriverRecoveryOwner): Promise<boolean> {
    const owner = Object.freeze({ ...input });
    return await this.guarded(owner, async slot => {
      this.armed = null;
      return await this.save(slot, null);
    }) ?? false;
  }
}
