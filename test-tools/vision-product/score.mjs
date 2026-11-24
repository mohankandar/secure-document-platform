// Task 0064: scores the product's findings against the benchmark's answer files, with the benchmark's own rule
// (tools/vision-benchmark, Scorer.java). Numbers and kinds only.
//   node score.mjs <public folder> <product results folder>            the product's results
//   node score.mjs <public folder> --benchmark <results.json> <model>  a saved benchmark run (checks this scorer)
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const V1 = ['SSN', 'DATE_OF_BIRTH', 'CREDIT_CARD_NUMBER', 'CREDIT_SCORE'];
const ANSWER = { BANK_ROUTING_NUMBER: 'ROUTING_NUMBER', BANK_ACCOUNT_NUMBER: 'BANK_ACCOUNT', DRIVERS_LICENSE_NUMBER: 'GOVERNMENT_ID', PASSPORT_NUMBER: 'GOVERNMENT_ID' };
const ALL = [...V1, 'BANK_ACCOUNT', 'ROUTING_NUMBER', 'GOVERNMENT_ID', 'SIGNATURE', 'FACE_PHOTO'];

const read = (f) => JSON.parse(readFileSync(f, 'utf8').replace(/^﻿/, ''));

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

function score(truths, preds, keepPage, types) {
  const s = { expected: 0, found: 0, withoutBox: 0, falseFlags: 0, duplicates: 0, handwritten: [0, 0], byType: {}, byKind: {}, missed: [] };
  const t = (m, k) => (m[k] ??= { expected: 0, found: 0, falseFlags: 0 });
  for (const [doc, truth] of Object.entries(truths)) {
    for (const page of truth.pages) {
      if (!keepPage(doc, page.page)) continue;
      const ps = preds.filter((p) => p.doc === doc && p.page === page.page && types.includes(p.type));
      const used = new Set();
      const items = page.items.filter((i) => types.includes(i.type));
      for (const item of items) {
        s.expected++; t(s.byType, item.type).expected++; t(s.byKind, page.kind).expected++;
        if (item.handwritten) s.handwritten[0]++;
        let best = -1, bestO = -1;
        ps.forEach((p, i) => {
          if (used.has(i) || !p.box || p.type !== item.type || !match(item.box, p.box)) return;
          const o = overlap(item.box, p.box);
          if (o > bestO) { bestO = o; best = i; }
        });
        if (best < 0) {
          best = ps.findIndex((p, i) => !used.has(i) && !p.box && p.type === item.type);
          if (best >= 0) s.withoutBox++;
        }
        if (best < 0) { s.missed.push(`${doc} p${page.page} ${page.kind} ${item.type}`); continue; }
        used.add(best); s.found++; t(s.byType, item.type).found++; t(s.byKind, page.kind).found++;
        if (item.handwritten) s.handwritten[1]++;
      }
      ps.forEach((p, i) => {
        if (used.has(i)) return;
        if (items.some((it) => it.type === p.type && (!p.box || match(it.box, p.box)))) s.duplicates++;
        else { s.falseFlags++; t(s.byType, p.type).falseFlags++; t(s.byKind, page.kind).falseFlags++; }
      });
    }
  }
  return s;
}

const [folder, second, third, fourth] = process.argv.slice(2);
const truths = {};
for (const f of readdirSync(folder).filter((name) => name.endsWith('.truth.json')).sort()) {
  truths[f.replace('.truth.json', '')] = read(join(folder, f));
}

let preds, routed, extra = '';
const benchmark = second === '--benchmark';
if (benchmark) {
  const r = read(third);
  preds = []; routed = new Set();
  for (const d of r.documents) {
    const vision = (p) => p.pictureRegions.length > 0 || (p.layerAgreement >= 0 && p.layerAgreement < 0.5)
      || (p.ocrRun && p.ocrConfidence >= 0 && p.ocrConfidence < r.threshold) || p.handwritingRegions.length > 0;
    preds.push(...(d.text?.findings ?? []).map((p) => ({ ...p, doc: d.id })));
    for (const s of d.routing) {
      if (vision(s)) routed.add(`${d.id} ${s.page}`);
      else preds.push(...s.signatureRegions.map((box) => ({ doc: d.id, page: s.page, type: 'SIGNATURE', box })));
    }
    for (const p of d.vision[fourth] ?? []) {
      if (routed.has(`${d.id} ${p.page}`)) preds.push(...p.items.map((i) => ({ ...i, doc: d.id })));
    }
  }
} else {
  const findings = read(join(second, 'findings.json'));
  const routes = read(join(second, 'routes.json'));
  const cases = [read(join(second, 'cases.json'))].flat();
  const ran = new Set(cases.map((c) => c.sample));
  for (const id of Object.keys(truths)) if (!ran.has(id)) delete truths[id];
  preds = findings.map((f) => ({ ...f, type: ANSWER[f.type] ?? f.type }));
  routed = new Set(routes.filter((r) => r.path === 'VISION').map((r) => `${r.doc} ${r.page}`));
  const table = {};
  for (const [doc, truth] of Object.entries(truths)) {
    for (const p of truth.pages) {
      const got = routes.find((r) => r.doc === doc && r.page === p.page)?.path ?? 'NONE';
      const key = `${p.expectedRoute ?? 'not set'} -> ${got}`;
      table[key] = (table[key] ?? 0) + 1;
    }
  }
  const ms = [read(join(second, 'vision-ms.json'))].flat().filter((x) => typeof x === 'number').sort((a, b) => a - b);
  const paths = {};
  for (const f of findings) paths[f.foundBy ?? 'none'] = (paths[f.foundBy ?? 'none'] ?? 0) + 1;
  const unread = findings.filter((f) => f.type === 'UNREAD_PAGE').length;
  const noModel = findings.filter((f) => (f.foundBy ?? '').includes('VISION')
    && !/qwen3-vl:8b-instruct\|vision-prompt-v2\|[0-9a-f]{64}/.test(f.models ?? '')).length;
  const mean = ms.reduce((a, b) => a + b, 0) / Math.max(1, ms.length) / 1000;
  extra = `routing (expected -> product): ${JSON.stringify(table)}\n`
    + `found by: ${JSON.stringify(paths)}; unread pages: ${unread}; vision findings without model and digest: ${noModel}\n`
    + `vision pages read: ${ms.length}, seconds mean ${mean.toFixed(2)}, median ${((ms[ms.length >> 1] ?? 0) / 1000).toFixed(2)}, p90 ${((ms[Math.floor(ms.length * 0.9)] ?? 0) / 1000).toFixed(2)}\n`
    + `cases: ${cases.map((c) => `${c.sample} ${c.status} ${c.seconds}s`).join('; ')}\n`;
}
preds = preds.filter((p) => p.type !== 'UNREAD_PAGE');
const line = (name, s) => `${name}: expected ${s.expected}, found ${s.found} (without a box ${s.withoutBox}), misses ${s.expected - s.found}, `
  + `false flags ${s.falseFlags}, duplicates ${s.duplicates}, handwritten ${s.handwritten[1]}/${s.handwritten[0]}\n`
  + `  by kind of item: ${Object.entries(s.byType).map(([k, v]) => `${k} ${v.found}/${v.expected} ff ${v.falseFlags}`).join('; ')}\n`
  + `  by page: ${Object.entries(s.byKind).map(([k, v]) => `${k} ${v.found}/${v.expected} ff ${v.falseFlags}`).join('; ')}\n`
  + `  missed: ${s.missed.join('; ')}\n`;
const visionOnly = preds.filter((p) => (benchmark ? String(p.source ?? '').startsWith('vision') : (p.foundBy ?? '').includes('VISION')));
const isRouted = (d, p) => routed.has(`${d} ${p}`);
const out = extra
  + line('ALL pages, all kinds', score(truths, preds, () => true, ALL))
  + line('ALL pages, the four V1 kinds', score(truths, preds, () => true, V1))
  + line('ROUTED pages, every path', score(truths, preds, isRouted, ALL))
  + line('ROUTED pages, vision findings only', score(truths, visionOnly, isRouted, ALL));
console.log(out);
if (!benchmark) writeFileSync(join(second, 'summary.txt'), out);
