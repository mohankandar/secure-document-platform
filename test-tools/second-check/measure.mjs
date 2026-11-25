/**
 * Task 0083: measures a second, independent AI check of finished (redacted) files. Input: the folder SecondCheckSamplesTest
 * writes (synthetic samples redacted by the product, one item per document left visible on purpose). Prints kinds, pages
 * and counts only, never a value.
 *
 *   node test-tools/second-check/measure.mjs <folder> [text|vision|both] [variant]
 *
 * The prompts are the product's own files, so what is measured is what runs.
 */
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { inflateSync } from 'node:zlib';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const [folder, mode = 'both', only = ''] = process.argv.slice(2);
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const prompt = (name) => readFileSync(join(root, 'backend', 'securedoc-ai', 'src', 'main', 'resources', `${name}.txt`), 'utf8').replace(/\r\n/g, '\n').trim();
const TEXT = prompt('second-check-text-v1');
const VISION = prompt('second-check-vision-v1');
const KINDS = ['SSN', 'DATE_OF_BIRTH', 'CREDIT_CARD_NUMBER', 'CREDIT_SCORE', 'BANK_ACCOUNT', 'ROUTING_NUMBER', 'GOVERNMENT_ID', 'FACE_PHOTO'];
const documents = JSON.parse(readFileSync(join(folder, 'finished.json'), 'utf8')).filter((d) => !only || d.id.endsWith(only));

async function ask(body) {
  const t = Date.now();
  const r = await (await fetch('http://localhost:11434/api/chat', { method: 'POST', body: JSON.stringify({ stream: false, keep_alive: '10m', ...body }) })).json();
  return { ms: Date.now() - t, content: r.message?.content ?? '', cut: r.done_reason === 'length' };
}
const kindsSchema = (kinds) => ({ type: 'object', additionalProperties: false, required: ['found'], properties: { found: { type: 'array', items: {
  type: 'object', additionalProperties: false, required: ['kind', 'value'], properties: { kind: { type: 'string', enum: kinds }, value: { type: 'string' } } } } } });

async function text(page) {
  if (page.text.trim().length < 3) return { kinds: [], ms: 0 };
  const kinds = KINDS.filter((k) => k !== 'FACE_PHOTO');
  const a = await ask({ model: 'qwen3.5:9b', think: false, format: kindsSchema(kinds), options: { temperature: 0, num_ctx: 8192, num_predict: 2048 },
    messages: [{ role: 'system', content: TEXT }, { role: 'user', content: `<PAGE>\n${page.text}\n</PAGE>` }] });
  let found = [];
  // An answer that cannot be used is a page for a person, as in the product.
  if (a.cut) return { kinds: ['UNREAD'], ms: a.ms };
  try { found = JSON.parse(a.content).found; if (!Array.isArray(found)) throw new Error(); } catch { return { kinds: ['UNREAD'], ms: a.ms }; }
  // As the product's rule for dates of birth: a birth label close before the value.
  const born = (all, v) => /birth|d.?o.?b|born/i.test(all.slice(Math.max(0, all.indexOf(v) - 80), all.indexOf(v)));
  // As the product: only what really stands on the page counts, and it must have the shape of its kind.
  const digits = (v) => v.replace(/\D/g, '').length;
  const shape = { SSN: (v) => digits(v) === 9, DATE_OF_BIRTH: (v) => /\d/.test(v) && digits(v) >= 5, CREDIT_CARD_NUMBER: (v) => digits(v) >= 13 && digits(v) <= 19,
    CREDIT_SCORE: (v) => /^\D*[3-8]\d\d\D*$/.test(v), BANK_ACCOUNT: (v) => digits(v) >= 4 && digits(v) <= 17, ROUTING_NUMBER: (v) => digits(v) === 9, GOVERNMENT_ID: (v) => digits(v) >= 5 };
  return { kinds: [...new Set(found.filter((f) => page.text.includes(f.value) && shape[f.kind]?.(f.value) && (f.kind !== 'DATE_OF_BIRTH' || born(page.text, f.value))).map((f) => f.kind))], ms: a.ms };
}

/** At least this share of a box is a cover: the model pointed at finished work. */
const BLACK = 0.35;

/** A PNG as Java's ImageIO writes it: 8 bits, RGB, not interlaced. */
function png(data) {
  let at = 8; let w = 0; let h = 0; let channels = 3; const parts = [];
  while (at < data.length) {
    const size = data.readUInt32BE(at); const type = data.toString('latin1', at + 4, at + 8);
    if (type === 'IHDR') { w = data.readUInt32BE(at + 8); h = data.readUInt32BE(at + 12); channels = { 0: 1, 2: 3, 6: 4 }[data[at + 17]]; }
    if (type === 'IDAT') parts.push(data.subarray(at + 8, at + 8 + size));
    at += 12 + size;
  }
  const raw = inflateSync(Buffer.concat(parts)); const stride = w * channels; const out = Buffer.alloc(h * stride);
  for (let y = 0; y < h; y++) {
    const filter = raw[y * (stride + 1)];
    for (let x = 0; x < stride; x++) {
      const a = x >= channels ? out[y * stride + x - channels] : 0; const b = y ? out[(y - 1) * stride + x] : 0;
      const c = y && x >= channels ? out[(y - 1) * stride + x - channels] : 0; const p = a + b - c;
      const paeth = Math.abs(p - a) <= Math.abs(p - b) && Math.abs(p - a) <= Math.abs(p - c) ? a : Math.abs(p - b) <= Math.abs(p - c) ? b : c;
      out[y * stride + x] = raw[y * (stride + 1) + 1 + x] + [0, a, b, (a + b) >> 1, paeth][filter];
    }
  }
  return { w, h, channels, out };
}
function black(image, box) {
  const [x1, y1, x2, y2] = box.map((v, i) => Math.max(0, Math.min(i % 2 ? image.h : image.w, Math.round(v / 1000 * (i % 2 ? image.h : image.w)))));
  let all = 0; let dark = 0;
  for (let y = y1; y < y2; y++) for (let x = x1; x < x2; x++) {
    const i = (y * image.w + x) * image.channels;
    all++;
    if (image.out[i] < 12 && image.out[i + Math.min(1, image.channels - 1)] < 12 && image.out[i + Math.min(2, image.channels - 1)] < 12) dark++;
  }
  return all === 0 ? 1 : dark / all;
}

/** A score range such as 300-850 is not a score; with too little text to tell, the model is believed. */
function scoreIn(text) {
  if (text.trim().length < 20) return true;
  return /score/i.test(text) && /(?<![\d.,-])[3-8]\d\d(?![\d.,-])/.test(text.replace(/\b\d{3}\s*(?:-|–|to)\s*\d{3}\b/g, ' '));
}

async function vision(page) {
  const a = await ask({ model: 'qwen3-vl:8b-instruct', options: { temperature: 0, num_ctx: 8192, num_predict: 2048 },
    messages: [{ role: 'user', content: VISION, images: [readFileSync(join(folder, page.image)).toString('base64')] }] });
  let items = [];
  if (a.cut) return { kinds: ['UNREAD'], ms: a.ms };
  try { items = JSON.parse(a.content.slice(a.content.indexOf('{'), a.content.lastIndexOf('}') + 1)).items; if (!Array.isArray(items)) throw new Error(); } catch { return { kinds: ['UNREAD'], ms: a.ms }; }
  // An item of no known kind or without a usable box: the product sends the page to a person.
  const placed = (i) => [...KINDS, 'SIGNATURE'].includes(i.type) && Array.isArray(i.box) && i.box.length === 4 && i.box.every(Number.isFinite) && i.box[0] !== i.box[2] && i.box[1] !== i.box[3];
  const unplaced = items.some((i) => !placed(i)) ? ['UNREAD'] : [];
  const image = png(readFileSync(join(folder, page.image)));
  return { kinds: [...new Set([...unplaced, ...items.filter((i) => KINDS.includes(i.type) && Array.isArray(i.box) && i.box.length === 4 && black(image, i.box) < BLACK
    && (i.type !== 'CREDIT_SCORE' || scoreIn(page.text))).map((i) => i.type)])], ms: a.ms };
}

const score = { text: { caught: [], missed: [], falseAlarms: [], ms: [] }, vision: { caught: [], missed: [], falseAlarms: [], ms: [] } };
score.either = { caught: [], missed: [], falseAlarms: [], ms: [] };
let pages = 0;
// ROUTING and ACCOUNT on a check, and the account kinds in general, count as caught when the page is flagged with either.
const same = (a, b) => a === b || (['BANK_ACCOUNT', 'ROUTING_NUMBER'].includes(a) && ['BANK_ACCOUNT', 'ROUTING_NUMBER'].includes(b));
for (const d of documents) {
  for (const p of d.pages) {
    pages++;
    const results = {};
    if (mode !== 'vision') results.text = await text(p);
    if (mode !== 'text' && (p.source === 'OCR' || p.redacted || p.text.trim().length < 20)) results.vision = await vision(p);
    results.either = { kinds: [...new Set(Object.values(results).flatMap((r) => r.kinds))], ms: Object.values(results).reduce((s, r) => s + r.ms, 0) };
    const left = d.left?.page === p.page ? d.left.type : null;
    for (const [name, r] of Object.entries(results)) {
      const s = score[name];
      s.ms.push(r.ms);
      for (const k of r.kinds) if (!left || !same(k, left)) s.falseAlarms.push(`${d.id}p${p.page}:${k}`);
      if (left) (r.kinds.some((k) => same(k, left)) ? s.caught : s.missed).push(`${d.id}p${p.page}:${left}`);
    }
  }
}
const sha = (t) => createHash('sha256').update(t).digest('hex').slice(0, 12);
console.log(`pages ${pages}, left visible ${documents.filter((d) => d.left).length}, prompts ${sha(TEXT)} ${sha(VISION)}`);
for (const [name, s] of Object.entries(score)) {
  if (!s.ms.length) continue;
  const n = s.caught.length + s.missed.length;
  console.log(`${name}: caught ${s.caught.length} of ${n} (${Math.round(100 * s.caught.length / Math.max(1, n))}%), false alarms ${s.falseAlarms.length} (${(10 * s.falseAlarms.length / pages).toFixed(1)} per 10 pages), ${(s.ms.reduce((a, b) => a + b, 0) / s.ms.length / 1000).toFixed(2)} s a page on ${s.ms.length} pages`);
  console.log('  missed:', s.missed.join(' '), '\n  false alarms:', s.falseAlarms.join(' '));
}
