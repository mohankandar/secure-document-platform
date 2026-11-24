/**
 * Task 0058: days in the entity's time zone (its own, else the customer's). Without one everything works in the browser's
 * zone, as before. Free of React so the rules run under `node --test`.
 */

export interface Day { y: number; m: number; d: number }

/** The browser's IANA time zone, for example America/New_York. */
export function browserZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}

/** The entity's zone when this browser knows it, else the browser's own. */
export function zoneOrBrowser(zone: string | null | undefined): string {
  if (!zone) return browserZone();
  try { new Intl.DateTimeFormat('en-US', { timeZone: zone }); return zone; } catch { return browserZone(); }
}

function parts(at: Date, zone: string) {
  const out: Record<string, number> = {};
  for (const p of new Intl.DateTimeFormat('en-US', { timeZone: zone, hourCycle: 'h23', year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric' }).formatToParts(at)) {
    if (p.type !== 'literal') out[p.type] = Number(p.value);
  }
  return out;
}

/** The calendar day of a moment in a zone. */
export function dayIn(at: Date, zone: string): Day {
  const p = parts(at, zone);
  return { y: p.year, m: p.month, d: p.day };
}

/** Days are calendar days: adding one never depends on how long a day is. */
export function plusDays(day: Day, n: number): Day {
  const t = new Date(Date.UTC(day.y, day.m - 1, day.d + n));
  return { y: t.getUTCFullYear(), m: t.getUTCMonth() + 1, d: t.getUTCDate() };
}

/** The moment a day starts in a zone (right after the gap, where the clocks go forward at midnight). */
export function startOfDay(day: Day, zone: string): Date {
  const wanted = Date.UTC(day.y, day.m - 1, day.d);
  const offset = (at: number) => { const p = parts(new Date(at), zone); return Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second) - at; };
  const first = wanted - offset(wanted);
  const second = wanted - offset(first);
  // Both candidates are right where the offset is the same; where it changes, the earlier moment that is in the day.
  const inDay = [Math.min(first, second), Math.max(first, second)].find((at) => {
    const d = dayIn(new Date(at), zone);
    return d.y === day.y && d.m === day.m && d.d === day.d;
  });
  return new Date(inDay ?? Math.max(first, second));
}

const two = (n: number) => String(n).padStart(2, '0');
/** "2026-11-03", as a date field has it. */
export const dayText = (day: Day) => `${day.y}-${two(day.m)}-${two(day.d)}`;

/** A date field's value, or null when it is not a day. */
export function dayFrom(value: string): Day | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!m) return null;
  const day = { y: Number(m[1]), m: Number(m[2]), d: Number(m[3]) };
  return dayText(plusDays(day, 0)) === value ? day : null;
}
