// Task 0084: how sure the product's findings are and how often they are right, per kind and threshold, and how many cases
// would skip Review. Reads the results of test-tools/vision-product and the answer files. Numbers and kinds only.
//   node measure.mjs <samples folder> <results folder>
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const [samples, results] = process.argv.slice(2);
const THRESHOLDS = [0.75, 0.8, 0.85, 0.9, 0.95, 0.97, 0.98, 0.99];
const LOCKED = ['CVV', 'PIN', 'CREDIT_CARD_NUMBER'];
const ANSWER = { BANK_ROUTING_NUMBER: 'ROUTING_NUMBER', BANK_ACCOUNT_NUMBER: 'BANK_ACCOUNT', DRIVERS_LICENSE_NUMBER: 'GOVERNMENT_ID', PASSPORT_NUMBER: 'GOVERNMENT_ID' };
const read = (f) => JSON.parse(readFileSync(f, 'utf8').replace(/^\uFEFF/, ''));

// The benchmark's own rule (test-tools/vision-product/score.mjs).
function overlap(a, b) {
  const w = Math.min(a[0] + a[2], b[0] + b[2]) - Math.max(a[0], b[0]);
  const h = Math.min(a[1] + a[3], b[1] + b[3]) - Math.max(a[1], b[1]);
  const smaller = Math.min(a[2] * a[3], b[2] * b[3]);
  return w <= 0 || h <= 0 || smaller <= 0 ? 0 : (w * h) / smaller;
}
function match(t, f) {
  if (f[2] * f[3] > Math.max(10 * t[2] * t[3], 0.02)) return false;
  if (overlap(t, f) >= 0.3) return true;
  const cx = t[0] + t[2] / 2, cy = t[1] + t[3] / 2;
  return cx >= f[0] - 0.01 && cx <= f[0] + f[2] + 0.01 && cy >= f[1] - 0.01 && cy <= f[1] + f[3] + 0.01;
}

const truth = {};
for (const f of readdirSync(samples).filter((n) => n.endsWith('.truth.json'))) truth[f.replace('.truth.json', '')] = read(join(samples, f));
const findings = read(join(results, 'findings.json'));
const cases = [read(join(results, 'cases.json'))].flat();
const is = (f, item) => (ANSWER[f.type] ?? f.type) === item.type && (!f.box || match(item.box, f.box));
for (const f of findings) f.right = Boolean(truth[f.doc]?.pages.find((p) => p.page === f.page)?.items.some((i) => is(f, i)));

const kinds = {};
for (const f of findings) (kinds[f.type] ??= []).push(f);
console.log('kind; all; wrong; at or above each threshold as findings/wrong');
for (const [kind, all] of Object.entries(kinds).sort()) {
  console.log(`${kind}; ${all.length}; ${all.filter((f) => !f.right).length}; ` + THRESHOLDS.map((t) => {
    const sure = all.filter((f) => f.confidence >= t);
    return `${t}: ${sure.length}/${sure.filter((f) => !f.right).length}`;
  }).join('; '));
}
const of = (doc) => findings.filter((f) => f.doc === doc);
const docs = cases.map((c) => c.sample);
console.log(`cases ${docs.length}, in Review today ${cases.filter((c) => c.status === 'PENDING_REVIEW').length}`);
for (const t of THRESHOLDS) {
  const skipped = docs.filter((d) => of(d).every((f) => f.box && f.confidence >= t));
  const excluded = skipped.filter((d) => of(d).every((f) => !LOCKED.includes(f.type)));
  const missing = skipped.filter((d) => truth[d].pages.some((p) => p.items.some((i) => i.type !== 'PERSON_NAME'
    && !of(d).some((f) => f.page === p.page && is(f, i)))));
  console.log(`${t}: skip Review ${skipped.length}, without locked items ${excluded.length}, skipped cases with a missed item ${missing.length}`);
}
const visionOnly = findings.filter((f) => f.foundBy === 'VISION');
console.log(`vision only: ${visionOnly.length}, wrong ${visionOnly.filter((f) => !f.right).length}, confidences ${[...new Set(visionOnly.map((f) => f.confidence))]}`);
