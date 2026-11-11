export const COMMAND_KEY_PREFIX = "cmd1_";
const MAX_BODY_BYTES = 64 * 1024;
const MAX_DEPTH = 32;

function jsonString(value: string): string {
  for (const character of value) {
    const first = character.charCodeAt(0);
    if (character.length === 1 && first >= 0xd800 && first <= 0xdfff) {
      throw new TypeError("Unpaired surrogate in command JSON");
    }
  }
  return JSON.stringify(value);
}

function decimalText(value: number): string {
  if (!Number.isFinite(value)) throw new TypeError("Invalid number in command JSON");
  const [mantissa, exponent] = String(value).split("e");
  if (exponent === undefined) return mantissa;
  const sign = mantissa.startsWith("-") ? "-" : "";
  const [whole, fraction = ""] = mantissa.slice(sign.length).split(".");
  const digits = whole + fraction;
  const decimalAt = whole.length + Number(exponent);
  if (decimalAt <= 0) return sign + "0." + "0".repeat(-decimalAt) + digits;
  if (decimalAt >= digits.length) return sign + digits + "0".repeat(decimalAt - digits.length);
  return sign + digits.slice(0, decimalAt) + "." + digits.slice(decimalAt);
}

function canonical(value: unknown, depth: number): string {
  if (depth > MAX_DEPTH) throw new TypeError("Command JSON is too deeply nested");
  if (value === null || typeof value === "boolean") return String(value);
  if (typeof value === "string") return jsonString(value);
  if (typeof value === "number") return decimalText(value);
  if (Array.isArray(value)) return "[" + value.map(item => canonical(item, depth + 1)).join(",") + "]";
  if (typeof value === "object") {
    const record = value as Record<string, unknown>;
    const members = Object.keys(record).sort().map(name => {
      return jsonString(name) + ":" + canonical(record[name], depth + 1);
    });
    return "{" + members.join(",") + "}";
  }
  throw new TypeError("Unsupported command JSON value");
}

/** The serialized body is the snapshot to send with the key and hash. */
export async function commandFingerprint(scope: string, targetId: number | null, body?: unknown) {
  if (targetId !== null && !Number.isSafeInteger(targetId)) throw new TypeError("Invalid command target");
  const wireBody = JSON.stringify(body, (_name, value: unknown) => {
    if (typeof value === "number" && !Number.isFinite(value)) {
      throw new TypeError("Invalid number in command JSON");
    }
    return value;
  });
  const encoder = new TextEncoder();
  if (wireBody !== undefined && encoder.encode(wireBody).byteLength > MAX_BODY_BYTES) {
    throw new TypeError("Command JSON body is too large");
  }
  const payload: unknown = wireBody === undefined ? null : JSON.parse(wireBody);
  const envelope = canonical({ body: payload, scope, target_id: targetId }, 0);
  const digest = await crypto.subtle.digest("SHA-256", encoder.encode(envelope));
  const payloadHash = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, "0")).join("");
  return { wireBody, payloadHash };
}
