import { readSessionRecord, readWebPushBinding } from "../utils/sessionRecord";
import { tokenPayload } from "../utils/tokenPayload";

const DATABASE = "yuldash-rate-draft";

export interface RatingDraft {
  bookingId: number;
  userId: number;
  generation: string;
  sessionId: string;
  bindingId: string;
  expiresAt: number;
}

async function updateRatingDraft(
  decide: (draft: RatingDraft) => "keep" | "discard" | "take",
): Promise<RatingDraft | undefined> {
  const db = await new Promise<IDBDatabase>((resolve, reject) => {
    const open = indexedDB.open(DATABASE, 1);
    open.onupgradeneeded = () => open.result.createObjectStore("draft");
    open.onerror = () => reject(open.error);
    open.onsuccess = () => resolve(open.result);
  });
  try {
    return await new Promise((resolve, reject) => {
      const tx = db.transaction("draft", "readwrite");
      const store = tx.objectStore("draft");
      const request = store.get("current");
      let result: RatingDraft | undefined;
      tx.oncomplete = () => resolve(result);
      tx.onabort = () => reject(tx.error);
      request.onsuccess = () => {
        const draft = request.result as RatingDraft | undefined;
        if (!draft) return;
        const action = decide(draft);
        if (action !== "keep") store.delete("current");
        if (action === "take") result = draft;
      };
    });
  } finally {
    db.close();
  }
}

async function currentRatingOwner() {
  const session = await readSessionRecord();
  if (!session.access) return;
  const binding = await readWebPushBinding();
  const payload = tokenPayload(session.access);
  if (binding?.generation !== session.generation || payload?.sid !== binding.sessionId) return;
  const subject = payload.sub;
  const userId = Number(subject);
  if (!Number.isSafeInteger(userId) || userId <= 0 || String(userId) !== subject) return;
  return { userId, generation: session.generation, sessionId: binding.sessionId, bindingId: binding.bindingId };
}

function belongsTo(draft: RatingDraft, owner: Awaited<ReturnType<typeof currentRatingOwner>>) {
  const now = Date.now();
  return owner !== undefined && draft.userId === owner.userId && draft.generation === owner.generation &&
    draft.sessionId === owner.sessionId && draft.bindingId === owner.bindingId &&
    Number.isSafeInteger(draft.bookingId) && draft.bookingId > 0 &&
    Number.isSafeInteger(draft.expiresAt) && draft.expiresAt > now && draft.expiresAt <= now + 600_000;
}

async function withRatingLock<T>(task: () => Promise<T>): Promise<T | undefined> {
  if (typeof navigator === "undefined" || !navigator.locks || typeof indexedDB === "undefined") {
    return undefined;
  }
  return navigator.locks.request("yuldash-session-write", task).catch(() => undefined);
}

export function takeRatingDraft(bookingId: number, userId: number, generation: string) {
  return withRatingLock(async () => {
    const owner = await currentRatingOwner();
    if (owner?.userId !== userId || owner.generation !== generation) return;
    return updateRatingDraft(draft => {
      if (!belongsTo(draft, owner)) return "discard";
      return draft.bookingId === bookingId ? "take" : "keep";
    });
  });
}

export async function isCurrentRatingDraft(draft: RatingDraft): Promise<boolean> {
  return await withRatingLock(async () => belongsTo(draft, await currentRatingOwner())) === true;
}

export function pruneRatingDraft() {
  return withRatingLock(async () => {
    const owner = await currentRatingOwner();
    await updateRatingDraft(draft => belongsTo(draft, owner) ? "keep" : "discard");
  });
}
