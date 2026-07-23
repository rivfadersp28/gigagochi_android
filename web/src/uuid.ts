const hex = Array.from({ length: 256 }, (_, value) => value.toString(16).padStart(2, "0"));

/** RFC 4122 v4 UUID backed only by Web Crypto, including older API 23 WebViews. */
export function uuidV4(): string {
  if (!window.crypto?.getRandomValues) throw new Error("SECURE_RANDOM_UNAVAILABLE");
  const bytes = window.crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  return `${hex[bytes[0]]}${hex[bytes[1]]}${hex[bytes[2]]}${hex[bytes[3]]}-${hex[bytes[4]]}${hex[bytes[5]]}-${hex[bytes[6]]}${hex[bytes[7]]}-${hex[bytes[8]]}${hex[bytes[9]]}-${hex[bytes[10]]}${hex[bytes[11]]}${hex[bytes[12]]}${hex[bytes[13]]}${hex[bytes[14]]}${hex[bytes[15]]}`;
}
