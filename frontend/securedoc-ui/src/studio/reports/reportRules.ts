/**
 * UI task U006: the plain-word formats on Reports and the trend chart's axis (UI spec findings 13 and 32).
 * Kept free of React so the rules run under `node --test`.
 */

/** A duration in hours as people read it: "45 min", "2.4 h", "4 h", "3.1 days". Never negative, never NaN. */
export function hours(h: number): string {
  if (!Number.isFinite(h) || h <= 0) return '0 min';
  if (h < 1) return `${Math.max(1, Math.round(h * 60))} min`;
  if (h < 48) return `${Number(h.toFixed(1))} h`;
  return `${Number((h / 24).toFixed(1))} days`;
}

/** The server sends rates either as 0–1 or as 0–100; both read as a whole percentage. */
export const ratio = (v: number) => (Number.isFinite(v) ? (v <= 1 ? v * 100 : v) : 0);
export const pct = (v: number) => `${Math.round(ratio(v))}%`;

/**
 * The left axis of the trend chart: whole-number steps of 1, 2 or 5 × 10ⁿ, from 0 up to the first step at or above
 * `max`, with at most `maxTicks` gaps. The top of the axis is the chart's scale, so every bar fits.
 */
export function axisTicks(max: number, maxTicks = 4): number[] {
  const top = Number.isFinite(max) && max > 0 ? Math.ceil(max) : 1;
  let step = 1;
  for (let magnitude = 1; ; magnitude *= 10) {
    const found = [1, 2, 5].map((m) => m * magnitude).find((s) => Math.ceil(top / s) <= maxTicks);
    if (found) { step = found; break; }
  }
  const count = Math.ceil(top / step);
  return Array.from({ length: count + 1 }, (_, i) => i * step);
}

/**
 * Which buckets carry a date under the chart: the first, then every `step`th, so the labels sit at regular
 * intervals and never more than `maxLabels` of them. The last bucket is labelled only when it falls on the step.
 */
export function labelledBuckets(count: number, maxLabels = 7): number[] {
  if (count <= 0) return [];
  const step = Math.max(1, Math.ceil(count / maxLabels));
  const out: number[] = [];
  for (let i = 0; i < count; i += step) out.push(i);
  return out;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/**
 * A bucket as a short date: "Nov 3". A bucket is the start of a day in the database's time zone, sent as an instant
 * ("2026-11-03T00:00:00Z" when that zone is UTC, "2026-11-03T05:00:00Z" for UTC−5, "2026-11-02T18:30:00Z" for
 * UTC+5:30). Rounding to the nearest UTC midnight gives that day for any zone within 12 hours of UTC, whatever zone
 * the browser is in. Unreadable values stay as sent.
 */
export function bucketDate(bucket: string): string {
  const ms = new Date(bucket).getTime();
  if (Number.isNaN(ms)) return bucket;
  const d = new Date(ms + 12 * 3600_000);
  return `${MONTHS[d.getUTCMonth()]} ${d.getUTCDate()}`;
}
