const MAX_PRIVATE_PROPERTIES = 30;
const MAX_PROPERTY_BYTES = 124;
const encoder = new TextEncoder();

function utf8Length(value: string): number {
  return encoder.encode(value).length;
}

function truncateUtf8(value: string, maxBytes: number): string {
  if (maxBytes <= 0) return '';
  let result = '';
  let used = 0;
  for (const char of value) {
    const bytes = utf8Length(char);
    if (used + bytes > maxBytes) break;
    result += char;
    used += bytes;
  }
  return result;
}

export interface AppPropertyResult {
  properties: Record<string, string>;
  omitted: number;
  truncated: number;
}

/**
 * Merge searchable metadata within Drive's appProperties constraints.
 * Fixed provenance fields win on key collisions. Full user metadata remains
 * available in the Drive description and optional JSON sidecar.
 */
export function buildAppProperties(
  fixed: Record<string, string>,
  user: Record<string, string>,
): AppPropertyResult {
  const properties: Record<string, string> = {};
  let omitted = 0;
  let truncated = 0;

  for (const [key, value] of Object.entries(fixed)) {
    if (Object.keys(properties).length >= MAX_PRIVATE_PROPERTIES) break;
    const remaining = MAX_PROPERTY_BYTES - utf8Length(key);
    if (remaining <= 0) continue;
    properties[key] = truncateUtf8(String(value), remaining);
  }

  for (const [key, value] of Object.entries(user)) {
    if (Object.hasOwn(properties, key)) {
      omitted++;
      continue;
    }
    if (Object.keys(properties).length >= MAX_PRIVATE_PROPERTIES) {
      omitted++;
      continue;
    }
    const remaining = MAX_PROPERTY_BYTES - utf8Length(key);
    if (remaining <= 0) {
      omitted++;
      continue;
    }
    const safeValue = truncateUtf8(String(value), remaining);
    if (safeValue !== String(value)) truncated++;
    properties[key] = safeValue;
  }

  return { properties, omitted, truncated };
}
