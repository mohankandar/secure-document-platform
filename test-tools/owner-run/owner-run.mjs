/**
 * Task 0106: the owner's test round in one command, with a scorecard. Read README.md first.
 *
 *   node test-tools/owner-run/owner-run.mjs              a round: start, drop the test set, wait, score, stop
 *   node test-tools/owner-run/owner-run.mjs --review     the same, but the app stays up on port 8080 for a person's review
 *   node test-tools/owner-run/owner-run.mjs --after      after the review: score again, show what the person changed, stop
 *   node test-tools/owner-run/owner-run.mjs --stop       stop a round kept with --review, without scoring
 *
 * Options: --test-pdfs <folder> (default C:\dev\lexavault\test-pdfs), --backend <folder>, --wait-minutes <n> (default 90),
 * --only drop-09,drop-14 (some drops only, for a quick try; such a scorecard is not comparable with a full round).
 *
 * A THROWAWAY database container (lexavault-0106-pg) is started for each round and removed at the end; the app runs in
 * LOCAL mode against it, with the real local Ollama (text model qwen3.5:9b, the pinned vision model qwen3-vl:8b-instruct).
 * Nothing else is touched: not the owner's containers, not his normal database, not his folders.
 * The scorecard holds kinds, pages, counts and codes only; never a value from a document.
 */
import { spawn, spawnSync, execFileSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { cpSync, existsSync, mkdirSync, openSync, readFileSync, readdirSync, renameSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..', '..');
const args = process.argv.slice(2);
const option = (name, fallback) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : fallback; };
const mode = args.includes('--after') ? 'after' : args.includes('--stop') ? 'stop' : args.includes('--review') ? 'review' : 'round';
const testPdfs = resolve(option('--test-pdfs', 'C:\\dev\\lexavault\\test-pdfs'));
const backend = resolve(option('--backend', join(root, 'backend')));
const waitMinutes = Number(option('--wait-minutes', '90'));
const only = option('--only', '').split(',').filter(Boolean);
const jar = join(backend, 'securedoc-api', 'target', 'securedoc-api-0.1.0-SNAPSHOT.jar');
const container = 'lexavault-0106-pg';
const dbPort = 55406;
const appPort = mode === 'round' ? 18106 : 8080;
const base = `http://localhost:${appPort}`;
const OLLAMA = 'http://localhost:11434';
const TEXT_MODEL = 'qwen3.5:9b';
const VISION_MODEL = 'qwen3-vl:8b-instruct';
const ENTITY = '30216241-b724-5a3a-aef3-3034fd546d17';
const ACTOR = '00000000-0000-0000-0000-000000000099';
const LOCKED = ['CREDIT_CARD_NUMBER', 'CVV', 'PIN'];
const DONE = ['COMPLETED', 'VAULTED', 'REJECTED', 'FAILED', 'CANCELLED'];
const WAITING_FOR_A_PERSON = ['PENDING_REVIEW', 'IN_REVIEW', 'PENDING_FINAL_APPROVAL', 'APPROVED'];
const workRoot = join(tmpdir(), 'lexavault-0106');
const keptFile = join(workRoot, 'kept-round.json');
const resultsDir = join(here, 'results');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const sql = (q) => execFileSync('docker', ['exec', container, 'psql', '-U', 'securedoc', '-d', 'securedoc', '-tA', '-F', '\t', '-c', q], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 }).trim();
const rows = (q) => { const out = sql(q); return out ? out.split('\n').map((l) => l.split('\t')) : []; };
const short = (id) => (id ? id.slice(0, 8) : '-');
const say = (line) => console.log(line);
async function until(what, check, seconds) {
  for (const end = Date.now() + seconds * 1000; Date.now() < end; await sleep(2000)) {
    const value = await check();
    if (value) return value;
  }
  throw new Error(`waited ${Math.round(seconds / 60)} minutes for: ${what}`);
}

/* ---------- the expected answers ---------- */
/** One expected value: file, page, kind, value. From expected.json (files 01-13) and long\*-expected.txt. */
function expectedValues() {
  const list = JSON.parse(readFileSync(join(here, 'expected.json'), 'utf8')).values.map((v) => ({ ...v }));
  const pageKinds = {};
  for (const file of readdirSync(join(testPdfs, 'long')).filter((f) => f.endsWith('-expected.txt'))) {
    const pdf = file.replace('-expected.txt', '.pdf');
    for (const line of readFileSync(join(testPdfs, 'long', file), 'utf8').split(/\r?\n/)) {
      const m = /^p(\d+)\s+(\w+)\s*(.*)$/.exec(line);
      if (!m) continue;
      const [page, type, text] = [Number(m[1]), m[2], m[3]];
      pageKinds[`${pdf}#${page}`] = type;
      const add = (kind, value) => list.push({ file: pdf, page, kind, value: value.trim() });
      for (const [re, kind] of [[/(?:Date of birth:|DOB)\s+(\d\d\/\d\d\/\d{4})/g, 'DATE_OF_BIRTH'], [/SSN:?\s+(\d{3}-\d\d-\d{4})/g, 'SSN'],
        [/Account(?: number)?:?\s+(\d{6,17})/g, 'BANK_ACCOUNT_NUMBER'], [/[Rr]outing:?\s+(\d{9})/g, 'BANK_ROUTING_NUMBER'],
        [/Card on file:\s+(\d{4} \d{4} \d{4} \d{4})/g, 'CREDIT_CARD_NUMBER'], [/Borrower:\s+(.+?)\s{2,}/g, 'PERSON_NAME'],
        [/Account holder:\s+(.+?)\s+\|/g, 'PERSON_NAME'], [/^(.+?)\s+\|\s+SSN/g, 'PERSON_NAME'], [/^(.+?)\s+signed on/g, 'PERSON_NAME']]) {
        for (const x of text.matchAll(re)) add(kind, x[1]);
      }
    }
  }
  return { list, pageKinds };
}

/* ---------- reading a PDF: text layer, OCR, vision ---------- */
let classPath;
function pdfboxClassPath() {
  if (classPath) return classPath;
  const m2 = join(process.env.USERPROFILE, '.m2', 'repository');
  const logging = join(m2, 'commons-logging', 'commons-logging');
  const version = readdirSync(logging).find((v) => /^\d/.test(v) && readdirSync(join(logging, v)).some((f) => f.endsWith('.jar')));
  classPath = [...['pdfbox', 'fontbox', 'pdfbox-io'].map((a) => join(m2, 'org', 'apache', 'pdfbox', a, '3.0.2', `${a}-3.0.2.jar`)),
    join(logging, version, `commons-logging-${version}.jar`)].join(';');
  return classPath;
}
/** The pages of one PDF: { pages: [{ page, hasText, text, image }], error }. Cached in `folder`. */
function readPdf(pdf, folder) {
  if (!existsSync(join(folder, 'pages.json'))) {
    execFileSync('java', ['-cp', pdfboxClassPath(), join(here, 'PdfPages.java'), pdf, folder], { stdio: 'ignore' });
  }
  const read = JSON.parse(readFileSync(join(folder, 'pages.json'), 'utf8'));
  for (const p of read.pages) p.image = join(folder, `page-${p.page}.png`);
  return read;
}
function ocr(png) {
  const cache = `${png}.ocr.txt`;
  if (!existsSync(cache)) {
    const out = spawnSync('tesseract', [png, 'stdout'], { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
    writeFileSync(cache, out.status === 0 ? out.stdout : '');
  }
  return readFileSync(cache, 'utf8');
}
/** The vision model reads the page as a person would, also handwriting (OCR can't). Same settings every time. */
async function vision(png) {
  const cache = `${png}.vision.txt`;
  if (!existsSync(cache)) {
    const res = await fetch(`${OLLAMA}/api/chat`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({
      model: VISION_MODEL, stream: false, keep_alive: '10m', options: { temperature: 0, seed: 106, num_predict: 1500 },
      messages: [{ role: 'user', images: [readFileSync(png).toString('base64')],
        content: 'Write out all text you can read on this page, printed or handwritten, exactly as written, line by line. Skip nothing you can read. Write nothing else.' }] }) });
    if (!res.ok) throw new Error(`the vision model answered HTTP ${res.status}`);
    writeFileSync(cache, (await res.json()).message?.content ?? '');
  }
  const text = readFileSync(cache, 'utf8');
  // On an empty page the model says so ("There is no text on this page."): that is no text, not words of the page.
  return /^\W*(there is |there are )?no (visible |readable )?(text|words)\b[^\n]{0,40}$/i.test(text.trim()) ? '' : text;
}

/* ---------- matching ---------- */
const condense = (s) => s.toLowerCase().replace(/[^a-z0-9]/g, '');
/** Is this value readable in this text? Spaces, dashes and case don't matter; a number must not run into other digits. */
function occurrences(expected, text) {
  const v = condense(expected.value);
  if (!v) return 0;
  // A short number (a CVV, a score) counts only next to its label.
  if (expected.label) return (text.match(new RegExp(`${expected.label}\\W{0,4}${v}(?!\\d)`, 'gi')) ?? []).length;
  let n = 0;
  for (const line of text.split(/\r?\n/)) {
    const c = condense(line);
    for (let at = c.indexOf(v); at >= 0; at = c.indexOf(v, at + 1)) {
      if (!/^\d/.test(v) || (!/\d/.test(c[at - 1] ?? '') && !/\d/.test(c[at + v.length] ?? ''))) n++;
    }
  }
  return n;
}
const readable = (expected, text) => occurrences(expected, text) > 0;
const sha256 = (file) => createHash('sha256').update(readFileSync(file)).digest('hex');
const words = (text) => (text.toLowerCase().match(/[a-z0-9]{2,}/g) ?? []);
function bag(list) { const m = new Map(); for (const w of list) m.set(w, (m.get(w) ?? 0) + 1); return m; }

/* ---------- the throwaway database and the app ---------- */
function startDatabase(password) {
  if (execFileSync('docker', ['ps', '-aq', '--filter', `name=^/${container}$`], { encoding: 'utf8' }).trim()) {
    throw new Error(`the container ${container} exists already: another round is running or was kept (--after or --stop ends it)`);
  }
  execFileSync('docker', ['run', '-d', '--name', container, '-e', 'POSTGRES_DB=securedoc', '-e', 'POSTGRES_USER=securedoc',
    '-e', 'POSTGRES_PASSWORD', '-p', `127.0.0.1:${dbPort}:5432`, 'postgres:16-alpine'], { stdio: 'ignore', env: { ...process.env, POSTGRES_PASSWORD: password } });
}
async function waitForDatabase() {
  for (let i = 0; i < 60 && spawnSync('docker', ['exec', container, 'pg_isready', '-U', 'securedoc'], { stdio: 'ignore' }).status !== 0; i++) await sleep(1000);
  await sleep(3000);
}
function install(password, run) {
  const init = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command',
    `. '${join(root, 'scripts', 'lib', 'LexaVaultTools.ps1')}'; Initialize-LexaVaultDatabase -Jar '${jar}' -DbUrl 'jdbc:postgresql://localhost:${dbPort}/securedoc' -DbUser 'securedoc' -DbPassword $env:OWNER_RUN_DB_PASSWORD -StateDir '${join(run, 'install')}'`],
  { env: { ...process.env, OWNER_RUN_DB_PASSWORD: password }, encoding: 'utf8' });
  writeFileSync(join(run, 'logs', 'install.log'), `${init.stdout}\n${init.stderr}`);
  if (init.status !== 0) throw new Error(`the install steps failed (${join(run, 'logs', 'install.log')})`);
}
function startApp(password, run) {
  const app = spawn('java', [`-Djava.io.tmpdir=${join(run, 'tmp')}`, '-jar', jar, '--spring.profiles.active=local',
    `--securedoc.storage.local.root-path=${join(run, 'storage')}`, `--platform.erase-log.location=${join(run, 'erase-log')}`,
    `--securedoc.platform-admin.watched-folder-tenant-id=${ENTITY}`, '--securedoc.platform-admin.watched-folder-coordination-enabled=false'], {
    cwd: run, detached: mode === 'review', stdio: ['ignore', openSync(join(run, 'logs', 'app.log'), 'a'), openSync(join(run, 'logs', 'app.err'), 'a')],
    env: { ...process.env, SPRING_DATASOURCE_URL: `jdbc:postgresql://localhost:${dbPort}/securedoc`, SPRING_DATASOURCE_USERNAME: 'securedoc',
      SPRING_DATASOURCE_PASSWORD: password, SERVER_PORT: String(appPort), SECUREDOC_NODE_ID: 'node-owner-run', SECUREDOC_AI_PROVIDER: 'ollama',
      SECUREDOC_AI_OLLAMA_BASE_URL: OLLAMA, SECUREDOC_AI_VISION_BASE_URL: OLLAMA, SECUREDOC_AI_MODEL: TEXT_MODEL, SECUREDOC_AI_VISION_MODEL: VISION_MODEL,
      SECUREDOC_AI_OLLAMA_PRELOAD_ENABLED: 'false', SECUREDOC_WATCHED_FOLDER_ENABLED: 'true', SECUREDOC_WATCHED_FOLDER_SCHEDULING_ENABLED: 'true',
      SECUREDOC_WATCHED_FOLDER_SCAN_INTERVAL_MS: '2000', SECUREDOC_WATCHED_FOLDER_ROOT_PATH: join(run, 'watched'),
      SECUREDOC_PROCESSING_PIPELINE_MODE: 'staged', SECUREDOC_PROCESSING_EXPORT_MODE: 'job', SECUREDOC_DELIVERY_LOCAL_OUTPUT_PATH: join(run, 'export'),
      SECUREDOC_PRODUCT_TELEMETRY_OUTPUT_PATH: join(run, 'logs', 'telemetry.jsonl') },
  });
  if (mode === 'review') app.unref();
  return app;
}
async function waitForApp(app) {
  await until('the app to start', async () => {
    if (app && app.exitCode !== null) throw new Error('the app stopped while starting (logs\\app.log in the run folder)');
    try { return (await fetch(`${base}/actuator/health`)).ok; } catch { return false; }
  }, 240);
}
function stopProcess(pid) {
  if (!pid) return;
  spawnSync('taskkill', ['/PID', String(pid), '/T', '/F'], { stdio: 'ignore' });
}
function removeDatabase() { spawnSync('docker', ['rm', '-f', container], { stdio: 'ignore' }); }

/* ---------- the round: the drops ---------- */
/** The test set as folder drops: each folder of watched-drops-run2, the PDFs from the test set, new contract numbers. */
function makeDrops(run, roundNumber) {
  const source = join(testPdfs, 'watched-drops-run2');
  const drops = [];
  readdirSync(source).filter((d) => statSync(join(source, d)).isDirectory()).sort().forEach((folder, i) => {
    const name = folder.replace(/^run2-/, '');
    if (only.length && !only.some((o) => name.startsWith(o))) return;
    const prepared = join(run, 'prepared', name);
    mkdirSync(prepared, { recursive: true });
    const files = readdirSync(join(source, folder)).filter((f) => f.toLowerCase().endsWith('.pdf'));
    for (const f of files) {
      const original = [join(testPdfs, f), join(testPdfs, 'long', f)].find((p) => existsSync(p));
      if (!original) throw new Error(`not in the test set: ${f}`);
      cpSync(original, join(prepared, f));
    }
    const hasManifest = existsSync(join(source, folder, 'manifest.json'));
    const contract = `7${roundNumber}${String(i + 1).padStart(2, '0')}`;
    if (hasManifest) {
      const manifest = JSON.parse(readFileSync(join(source, folder, 'manifest.json'), 'utf8'));
      manifest.businessIdentifiers = { ...manifest.businessIdentifiers, contract_number: contract, member_number: contract.slice(1) };
      writeFileSync(join(prepared, 'manifest.json'), JSON.stringify(manifest, null, 2));
    }
    drops.push({ name, files, hasManifest, contract });
  });
  return drops;
}

/** Where each drop ended: taken (a case), refused (with its code), or waiting for its manifest. */
async function intakeResults(run, drops) {
  const watched = join(run, 'watched');
  const placed = (d) => ['_archive', '_problem'].find((sub) => {
    try { return readdirSync(join(watched, sub)).some((n) => n === d.name || n.startsWith(`${d.name}-`)); } catch { return false; }
  });
  await until('every folder drop to be taken, refused or seen waiting', () => drops.every((d) => placed(d)
    || (!d.hasManifest && Number(sql(`select count(*) from intake_observations where status = 'WAITING_FOR_TRIGGER'`)) > 0)), 600);
  const observations = rows(`select coalesce(source_reference, ''), status, coalesce(package_id::text, ''), coalesce(reason_code, '') from intake_observations`);
  for (const d of drops) {
    d.where = placed(d) ?? 'still in the watched folder';
    // Drops are named only by a hash of the folder name (WatchedFolderDropReference): the first 12 hex of its SHA-256.
    d.reference = `installed/${createHash('sha256').update(d.name, 'utf8').digest('hex').slice(0, 12)}`;
    const o = observations.find((r) => r[0] === d.reference);
    d.intake = o ? o[1] : (d.hasManifest ? 'not seen' : 'WAITING_FOR_TRIGGER');
    d.packageId = o?.[2] || null;
    const codes = sql(`select distinct substring(detail from 'LV-[A-Z]+-[0-9]+') from audit_records where resource_type = 'WatchedFolderIntake' and (resource_id::text = '${d.reference}' or detail like '%${d.reference}%') and detail like '%LV-%'`);
    d.codes = [...new Set([...(codes ? codes.split('\n') : []), ...(o?.[3] ? [o[3]] : [])])];
  }
}

/** Waits until every case is done or waits for a person (and every exported case's files are in the export folder). */
async function waitForCases(run, drops) {
  const cases = drops.filter((d) => d.packageId);
  const firstSeen = {};
  await until('every case to finish or wait for a person', () => {
    const now = new Map(rows(`select id::text, status from document_packages where id in (${cases.map((c) => `'${c.packageId}'`).join(',') || 'null'})`));
    for (const c of cases) {
      const status = now.get(c.packageId);
      const exported = status !== 'COMPLETED' || deliveredFiles(run, c.packageId).length > 0;
      if ((DONE.includes(status) || WAITING_FOR_A_PERSON.includes(status)) && exported) firstSeen[c.packageId] ??= Date.now();
      else delete firstSeen[c.packageId];
    }
    const left = cases.filter((c) => !firstSeen[c.packageId]).length;
    process.stdout.write(`\r  cases still processing: ${left}   `);
    return left === 0;
  }, waitMinutes * 60);
  process.stdout.write('\n');
  // The time of a case is until it first finished or waited for a person; a later wait (after a review) keeps it.
  for (const c of cases) c.settledAt ??= firstSeen[c.packageId];
}

/** The files the export folder received for one case, newest export cycle. */
function deliveredFiles(run, packageId) {
  const dir = join(run, 'export', ENTITY, packageId);
  if (!existsSync(dir)) return [];
  const cycles = readdirSync(dir).filter((c) => c.startsWith('cycle-')).sort((a, b) => Number(a.slice(6)) - Number(b.slice(6)));
  if (!cycles.length) return [];
  const cycle = join(dir, cycles.at(-1));
  return readdirSync(cycle).flatMap((doc) => readdirSync(join(cycle, doc)).map((f) => ({ documentId: doc, file: f, path: join(cycle, doc, f), cycle: cycles.at(-1) })));
}

/* ---------- scoring ---------- */
function enabledKinds() {
  const on = rows(`select kind_key from tenant_redaction_catalog where tenant_id = '${ENTITY}' and enabled`).map((r) => r[0]);
  return [...new Set([...on, ...LOCKED])].sort();
}

/** Everything the scorecard shows, for every drop, case and file. Values never leave this function. */
async function score(run, drops, label) {
  const { list: expected } = expectedValues();
  const kinds = enabledKinds();
  const scored = { label, at: new Date().toISOString(), kinds, drops: [], files: [], totals: {} };
  for (const d of drops) {
    const entry = { name: d.name, files: d.files, contract: d.contract, intake: d.intake, where: d.where, codes: d.codes, packageId: d.packageId };
    scored.drops.push(entry);
    if (!d.packageId) continue;
    const [pkg] = rows(`select status, extract(epoch from created_at)::bigint from document_packages where id = '${d.packageId}'`);
    entry.status = pkg?.[0];
    entry.seconds = d.settledAt && pkg ? Math.max(0, Math.round(d.settledAt / 1000 - Number(pkg[1]))) : null;
    entry.findings = await findingsOf(d.packageId);
    entry.unreadPages = entry.findings.list.filter((f) => f.kind === 'UNREAD_PAGE').length;
    const delivered = deliveredFiles(run, d.packageId);
    entry.delivered = delivered.length;
    // The pages LexaVault holds as unreadable, by file (a document is matched to its file by its SHA-256).
    const fileOf = new Map(d.files.map((f) => [sha256(join(run, 'prepared', d.name, f)), f]));
    const unread = new Set(rows(`select d.checksum_value, f.page_number from findings f join documents d on d.id = f.document_id
      where d.package_id = '${d.packageId}' and f.entity_type = 'UNREAD_PAGE'`).map(([sum, page]) => `${fileOf.get(sum.toLowerCase())}#${page}`));
    for (const file of d.files) {
      const out = delivered.find((x) => x.file === file);
      const result = { drop: d.name, file, delivered: Boolean(out), leaks: [], extra: [], pages: 0 };
      scored.files.push(result);
      if (!out) continue;
      const original = readPdf(join(run, 'prepared', d.name, file), join(run, 'read', 'original', d.name, file));
      const redacted = readPdf(out.path, join(run, 'read', label, out.cycle, d.name, file));
      result.pages = redacted.pages.length;
      if (redacted.pages.length !== original.pages.length) result.pageCountChanged = `${original.pages.length} -> ${redacted.pages.length}`;
      for (const page of redacted.pages) {
        const before = original.pages.find((p) => p.page === page.page);
        const imagePage = before && !before.hasText;
        const methods = [['text layer', () => page.text], ['OCR', () => ocr(page.image)]];
        if (imagePage) methods.push(['vision model', () => vision(page.image)]);
        const values = expected.filter((v) => v.file === file && (v.page === page.page || v.page === null));
        for (const v of values.filter((x) => kinds.includes(x.kind))) {
          const by = [];
          for (const [name, read] of methods) if (readable(v, await read())) by.push(name);
          if (by.length) result.leaks.push({ page: page.page, kind: v.kind, by });
        }
        // Extra redaction: visible words of the original page that the redacted page lost, apart from where the
        // expected values stand (each of their words, as often as the value is on the original page). Both pages are
        // read the same way from their renderings, so removed hidden text does not count: by OCR, or by the vision model
        // where the original page is an image (OCR can't read handwriting). A page LexaVault itself holds as
        // unreadable is left out: any reading of it is mostly noise.
        if (!before || unread.has(`${file}#${page.page}`)) continue;
        const look = imagePage ? vision : async (png) => ocr(png);
        const originalText = await look(before.image);
        const redactedText = await look(page.image);
        const a = bag(words(originalText));
        const b = bag(words(redactedText));
        for (const v of values) {
          const n = occurrences(v, originalText);
          for (const w of words(v.value)) if (a.has(w)) a.set(w, Math.max(0, a.get(w) - n));
        }
        // The TEST watermark makes OCR run words together ("tothe", "begiven"): a word counts as lost only if it is
        // also missing from the page's letters and digits run together.
        const [ca, cb] = [condense(originalText), condense(redactedText)];
        const inside = (c, w) => { let n = 0; for (let at = c.indexOf(w); at >= 0; at = c.indexOf(w, at + 1)) n++; return n; };
        let lost = 0;
        for (const [w, n] of a) {
          const missing = Math.max(0, n - (b.get(w) ?? 0));
          if (missing) lost += Math.min(missing, Math.max(0, inside(ca, w) - inside(cb, w)));
        }
        if (lost) result.extra.push({ page: page.page, words: lost, by: imagePage ? 'vision' : 'OCR' });
      }
    }
  }
  const cases = scored.drops.filter((d) => d.packageId);
  const all = cases.flatMap((c) => c.findings.list);
  scored.totals = {
    drops: scored.drops.length,
    accepted: cases.length,
    refused: scored.drops.filter((d) => d.where === '_problem').length,
    waiting: scored.drops.filter((d) => !d.packageId && d.where !== '_problem').length,
    automatic: cases.filter((c) => DONE.includes(c.status) && c.status !== 'FAILED' && !c.findings.toPerson).length,
    casesWaitingForAPerson: cases.filter((c) => WAITING_FOR_A_PERSON.includes(c.status)).length,
    leaks: scored.files.reduce((n, f) => n + f.leaks.length, 0),
    extraWords: scored.files.reduce((n, f) => n + f.extra.reduce((m, e) => m + e.words, 0), 0),
    findings: all.length,
    withoutPlace: all.filter((f) => !f.place).length,
    toPerson: all.filter((f) => f.toPerson).length,
    unreadPages: cases.reduce((n, c) => n + c.unreadPages, 0),
    filesNotDelivered: scored.files.filter((f) => !f.delivered).length,
  };
  scored.totals.automaticPercent = cases.length ? Math.round((100 * scored.totals.automatic) / cases.length) : 0;
  return scored;
}

/**
 * The findings of one case: kind, page, status, whether it has a place, and whether it waits for a person and why
 * (the Review queue's reason). The Review answer also carries the found text: only id, kind, page and reason are kept.
 */
async function findingsOf(packageId) {
  const waiting = new Map();
  try {
    const res = await fetch(`${base}/api/v1/review-items?limit=1000&packageId=${packageId}`, { headers: {
      'X-Tenant-Id': ENTITY, 'X-Actor-Id': ACTOR, 'X-Actor-Roles': 'DOCUMENT_PROCESSOR,TENANT_IT_ADMIN' } });
    if (res.ok) for (const item of (await res.json()).items ?? []) waiting.set(item.findingId, item.reason ?? 'no reason given');
  } catch { /* the app is gone: the list is empty */ }
  const list = rows(`select f.id::text, f.entity_type, coalesce(f.page_number::text, ''), f.status, (f.bbox_left is not null)::text
    from findings f join documents d on d.id = f.document_id where d.package_id = '${packageId}' order by 3, 2, 1`)
    .map(([id, kind, page, status, place]) => ({ id, kind, page: Number(page) || null, status, place: place === 'true',
      toPerson: waiting.has(id), reason: waiting.get(id) ?? '' }));
  return { list, toPerson: list.filter((f) => f.toPerson).length };
}

/* ---------- the scorecard ---------- */
function markdown(s, before) {
  const t = s.totals;
  const lines = [`# Owner test round ${s.round} · ${s.label}`, '',
    `${s.at.replace('T', ' ').slice(0, 16)} UTC · commit \`${s.commit}\` · text model ${TEXT_MODEL} · vision model ${VISION_MODEL} · Ollama ${s.ollama}`, '',
    `Redaction kinds on for the entity (locked ones always on): ${s.kinds.join(', ')}`, '',
    '## Totals', '', '| | |', '|---|---|',
    `| Leaks (values still readable in a delivered file) | **${t.leaks}** |`,
    `| Extra redaction (words covered that are not sensitive) | ${t.extraWords} |`,
    `| Automatic (done before anyone touched it) | ${t.automatic} of ${t.accepted} cases (${t.automaticPercent}%) |`,
    `| Cases waiting for a person | ${t.casesWaitingForAPerson} |`,
    `| Findings | ${t.findings} (sent to a person ${t.toPerson}, without a place ${t.withoutPlace}) |`,
    `| Unread pages | ${t.unreadPages} |`,
    `| Folder drops | ${t.drops}: ${t.accepted} taken, ${t.refused} refused, ${t.waiting} waiting |`,
    `| Files not delivered (yet) | ${t.filesNotDelivered} |`];
  if (before) {
    const b = before.totals;
    if (s.accepted) {
      lines.push('', '## What the stand-in reviewer did', '',
        'No person: every value finding with a place was redacted as proposed, every page to check marked checked with nothing added, the case approved. A finding without a place can\'t be redacted without a mark, so its case stays held.', '',
        '| Drop | Redacted as proposed | Pages marked checked | Approved | Still held because |', '|---|---|---|---|---|',
        ...Object.entries(s.accepted).map(([name, a]) => `| ${name} | ${a.confirmed} | ${a.pagesChecked} | ${a.approved ? 'yes' : '-'} | ${a.held.join('; ') || '-'} |`));
    }
    lines.push('', `## What changed since the first scorecard`, '', '| | Before | Now |', '|---|---|---|',
      ...[['Leaks', 'leaks'], ['Extra redaction (words)', 'extraWords'], ['Cases waiting for a person', 'casesWaitingForAPerson'], ['Findings', 'findings'],
        ['Findings sent to a person', 'toPerson'], ['Files not delivered', 'filesNotDelivered']].map(([n, k]) => `| ${n} | ${b[k]} | ${t[k]} |`));
    for (const d of s.drops.filter((x) => x.packageId)) {
      const was = before.drops.find((x) => x.name === d.name);
      if (!was) continue;
      const byId = new Map(was.findings.list.map((f) => [f.id, f]));
      // Same order every round (ids differ): by page, kind, then the decision.
      const order = (x, y) => (x.page ?? 0) - (y.page ?? 0) || x.kind.localeCompare(y.kind) || x.status.localeCompare(y.status);
      const added = d.findings.list.filter((f) => !byId.has(f.id)).sort(order);
      const changed = d.findings.list.filter((f) => byId.has(f.id) && byId.get(f.id).status !== f.status).sort((x, y) => order(x, y) || byId.get(x.id).status.localeCompare(byId.get(y.id).status));
      const removed = was.findings.list.filter((f) => !d.findings.list.some((x) => x.id === f.id)).sort(order);
      if (was.status === d.status && !added.length && !changed.length && !removed.length) continue;
      lines.push('', `**${d.name}** (case ${short(d.packageId)}): ${was.status} -> ${d.status}`);
      for (const f of added) lines.push(`- new: ${f.kind}, page ${f.page ?? '?'}`);
      for (const f of changed) lines.push(`- ${f.kind}, page ${f.page ?? '?'}: ${byId.get(f.id).status} -> ${f.status}`);
      for (const f of removed) lines.push(`- removed: ${f.kind}, page ${f.page ?? '?'}`);
    }
  }
  lines.push('', '## Intake', '', '| Drop | Files | Result | Code | Case | Status | Time |', '|---|---|---|---|---|---|---|');
  for (const d of s.drops) {
    const result = d.where === '_problem' ? 'refused' : d.packageId ? 'taken' : 'waiting';
    lines.push(`| ${d.name} | ${d.files.length} | ${result} (${d.intake}) | ${d.codes.join(' ') || '-'} | ${short(d.packageId)} | ${d.status ?? '-'} | ${d.seconds != null ? `${d.seconds} s` : '-'} |`);
  }
  lines.push('', '## Findings per case', '', '| Drop | Findings | Kinds | Sent to a person (reasons) | Without a place | Unread pages |', '|---|---|---|---|---|---|');
  for (const d of s.drops.filter((x) => x.packageId)) {
    const counts = (list) => Object.entries(list.reduce((m, f) => ({ ...m, [f]: (m[f] ?? 0) + 1 }), {})).map(([k, n]) => `${k} ${n}`).join(', ');
    const person = d.findings.list.filter((f) => f.toPerson);
    lines.push(`| ${d.name} | ${d.findings.list.length} | ${counts(d.findings.list.map((f) => f.kind)) || '-'} | ${person.length}${person.length ? ` (${counts(person.map((f) => f.reason || f.status))})` : ''} | ${d.findings.list.filter((f) => !f.place).length} | ${d.unreadPages} |`);
  }
  lines.push('', '## Per file', '', '| File | Delivered | Leaks (page: kind, read by) | Extra redaction (page: words) |', '|---|---|---|---|');
  for (const f of s.files) {
    lines.push(`| ${f.file} | ${f.delivered ? `yes, ${f.pages} pages${f.pageCountChanged ? ` (pages ${f.pageCountChanged})` : ''}` : 'no'} | ${f.leaks.map((l) => `p${l.page}: ${l.kind} (${l.by.join(', ')})`).join('; ') || (f.delivered ? 'none' : '-')} | ${f.extra.map((e) => `p${e.page}: ${e.words}`).join('; ') || (f.delivered ? 'none' : '-')} |`);
  }
  lines.push('', 'How to read this: README.md next to the tool. Values are never shown here, only kinds and pages.', '');
  return lines.join('\n');
}
function consoleSummary(s, file) {
  const t = s.totals;
  say('');
  say(`ROUND ${s.round} (${s.label})`);
  say(`  Leaks:             ${t.leaks}${t.leaks ? `  (${s.files.filter((f) => f.leaks.length).map((f) => `${f.file}: ${f.leaks.map((l) => `p${l.page} ${l.kind}`).join(', ')}`).join('; ')})` : ''}`);
  say(`  Extra redaction:   ${t.extraWords} words`);
  say(`  Automatic:         ${t.automatic} of ${t.accepted} cases (${t.automaticPercent}%); waiting for a person: ${t.casesWaitingForAPerson}`);
  say(`  Findings:          ${t.findings}; sent to a person ${t.toPerson}; without a place ${t.withoutPlace}; unread pages ${t.unreadPages}`);
  say(`  Intake:            ${t.accepted} taken, ${t.refused} refused, ${t.waiting} waiting`);
  say(`Scorecard: ${file}`);
}
function context() {
  let commit = 'unknown';
  try { commit = execFileSync('git', ['-C', root, 'rev-parse', '--short', 'HEAD'], { encoding: 'utf8' }).trim(); } catch { /* not a checkout */ }
  return { commit };
}
async function ollamaVersion() {
  try { return (await (await fetch(`${OLLAMA}/api/version`)).json()).version; } catch { return 'not reachable'; }
}
function write(s, before) {
  // Automatic means done before anyone touched the case: a later scorecard keeps the first one's count.
  if (before) Object.assign(s.totals, { automatic: before.totals.automatic, automaticPercent: before.totals.automaticPercent });
  mkdirSync(resultsDir, { recursive: true });
  const file = join(resultsDir, `${s.round}-${s.label}${s.part ? '-part' : ''}.md`);
  writeFileSync(file, markdown(s, before));
  writeFileSync(join(s.run, `score-${s.label}.json`), JSON.stringify(s, null, 2));
  consoleSummary(s, file);
  return file;
}

/* ---------- a person who accepts every proposal ---------- */
const PERSON = { 'X-Tenant-Id': ENTITY, 'X-Actor-Id': ACTOR, 'X-Actor-Roles': 'DOCUMENT_PROCESSOR,BUSINESS_ADMIN,TENANT_IT_ADMIN' };
async function post(path, body) {
  const res = await fetch(base + path, { method: 'POST', headers: { ...PERSON, 'Content-Type': 'application/json' }, body: JSON.stringify(body ?? {}) });
  let json = null;
  try { json = JSON.parse(await res.text()); } catch { /* no JSON */ }
  return { status: res.status, code: json?.code ?? null };
}
const statusOf = (packageId) => sql(`select status from document_packages where id = '${packageId}'`);
/**
 * Unattended stand-in for a reviewer who changes nothing: every value finding with a place is redacted as proposed,
 * every page to check is marked checked with nothing added, and the case is approved for delivery. A finding without a
 * place can't be redacted without a mark (task 0100), so its case stays held, as it would for a person who adds none.
 * Returns, per drop, what was done and why a case is still held. Never shows a value.
 */
async function acceptAsProposed(drops) {
  const done = {};
  for (const d of drops.filter((x) => x.packageId)) {
    const log = (done[d.name] = { confirmed: 0, pagesChecked: 0, approved: false, held: [] });
    for (let step = 0; step < 6; step++) {
      const status = statusOf(d.packageId);
      if (status === 'PENDING_FINAL_APPROVAL') {
        const r = await post(`/api/v1/packages/${d.packageId}/finalize`);
        if (r.status >= 300) { log.held.push(`approval refused: ${r.code ?? `HTTP ${r.status}`}`); break; }
        log.approved = true;
      } else if (status === 'PENDING_REVIEW' || status === 'IN_REVIEW') {
        const res = await fetch(`${base}/api/v1/review-items?limit=1000&packageId=${d.packageId}`, { headers: PERSON });
        const items = res.ok ? (await res.json()).items ?? [] : [];
        let acted = 0; let left = 0;
        for (const i of items) {
          if (i.entityType === 'UNREAD_PAGE') {
            const r = await post(`/api/v1/review-items/${i.findingId}/page-checked`, { reviewed: true, boxes: [] });
            if (r.status < 300) { log.pagesChecked++; acted++; } else { left++; log.held.push(`page ${i.pageNumber} check refused: ${r.code ?? `HTTP ${r.status}`}`); }
          } else if (!i.hasBoundingBox) {
            left++;
            log.held.push(`${i.fileName ?? 'a file'} p${i.pageNumber}: ${i.entityType} with no place`);
          } else {
            const r = await post(`/api/v1/review-items/${i.findingId}/confirm`, { reason: 'owner-run: accepted as proposed' });
            if (r.status < 300) { log.confirmed++; acted++; } else { left++; log.held.push(`${i.entityType} p${i.pageNumber}: ${r.code ?? `HTTP ${r.status}`}`); }
          }
        }
        // Something stays for a person: the case can't move on, so don't wait for it.
        if (!acted || left) break;
      } else break;
      // The next state: processing resumes after the last decision, so give it time.
      await until('the case to move on', () => statusOf(d.packageId) !== status, 1200).catch(() => {});
      for (let i = 0; i < 600 && ['PROCESSING', 'SUBMITTED', 'APPROVED'].includes(statusOf(d.packageId)); i++) await sleep(2000);
    }
    log.held = [...new Set(log.held)];
  }
  return done;
}

/* ---------- the modes ---------- */
async function round() {
  for (const f of [jar, join(testPdfs, 'watched-drops-run2'), join(testPdfs, 'long')]) if (!existsSync(f)) throw new Error(`not found: ${f}`);
  if (mode === 'review') {
    if (existsSync(keptFile)) throw new Error('a round is kept for review already: end it first with --after or --stop');
    try { await fetch(`${base}/actuator/health`); throw new Error('port 8080 is in use: stop LexaVault first (.\\Stop-LexaVault-Local.ps1)'); } catch (e) { if (e.message.startsWith('port')) throw e; }
  }
  const versions = await ollamaVersion();
  if (versions === 'not reachable') throw new Error('Ollama is not running');
  const roundId = new Date().toISOString().replace(/[-:]/g, '').replace('T', '-').slice(0, 15);
  const run = join(workRoot, roundId);
  for (const d of ['storage', 'watched', 'prepared', 'logs', 'tmp', 'export', 'install']) mkdirSync(join(run, d), { recursive: true });
  say(`Round ${roundId}. Run folder: ${run}`);
  const password = randomUUID().replaceAll('-', '');
  let app; let kept = false;
  startDatabase(password);
  try {
    await waitForDatabase();
    say('Database ready; install steps...');
    install(password, run);
    say('Starting the app...');
    app = startApp(password, run);
    await waitForApp(app);
    const drops = makeDrops(run, roundId.replace('-', '').slice(4, 13));
    say(`Dropping ${drops.length} folders into the watched folder...`);
    for (const d of drops) renameSync(join(run, 'prepared', d.name), join(run, 'watched', d.name));
    // The originals stay readable for the scoring: copy them back once the app has taken the folders.
    await intakeResults(run, drops);
    for (const d of drops) {
      mkdirSync(join(run, 'prepared', d.name), { recursive: true });
      for (const f of d.files) cpSync([join(testPdfs, f), join(testPdfs, 'long', f)].find((p) => existsSync(p)), join(run, 'prepared', d.name, f));
    }
    say(`Intake: ${drops.map((d) => `${d.name} ${d.packageId ? 'taken' : d.where === '_problem' ? 'refused' : 'waiting'}`).join(', ')}`);
    await waitForCases(run, drops);
    say('Scoring (text layer, OCR, and the vision model for image pages)...');
    const s = { round: roundId, run, part: only.length > 0, ...context(), ollama: versions, ...(await score(run, drops, 'before-review')) };
    write(s);
    writeFileSync(join(run, 'drops.json'), JSON.stringify(drops, null, 2));
    if (mode === 'round' && !args.includes('--no-accept')) {
      say('');
      say('Accepting every proposal as it stands (a stand-in for a person who changes nothing), then scoring the delivered files...');
      const accepted = await acceptAsProposed(drops);
      await waitForCases(run, drops);
      const s2 = { round: roundId, run, part: only.length > 0, ...context(), ollama: versions, ...(await score(run, drops, 'as-proposed')), accepted };
      write(s2, s);
    }
    if (mode === 'review') {
      writeFileSync(keptFile, JSON.stringify({ round: roundId, run, pid: app.pid, part: only.length > 0 }));
      kept = true;
      say('');
      say('The app stays up for the review: open http://localhost:5173 (start the screen with .\\restart-ui.cmd if it is not running).');
      say('When the review is done: node test-tools\\owner-run\\owner-run.mjs --after');
    }
  } finally {
    if (!kept) { stopProcess(app?.pid); removeDatabase(); }
  }
}

async function after(scoreIt) {
  if (!existsSync(keptFile)) throw new Error('no round is kept for review (run one with --review first)');
  const kept = JSON.parse(readFileSync(keptFile, 'utf8'));
  try {
    if (scoreIt) {
      const drops = JSON.parse(readFileSync(join(kept.run, 'drops.json'), 'utf8'));
      const before = JSON.parse(readFileSync(join(kept.run, 'score-before-review.json'), 'utf8'));
      say('Waiting for the exports the review released...');
      await waitForCases(kept.run, drops);
      const s = { round: kept.round, run: kept.run, part: kept.part, ...context(), ollama: await ollamaVersion(), ...(await score(kept.run, drops, 'after-review')) };
      write(s, before);
    }
  } finally {
    stopProcess(kept.pid);
    removeDatabase();
    rmSync(keptFile, { force: true });
    say('The round is ended: app stopped, its throwaway database removed.');
  }
}

try {
  if (mode === 'round' || mode === 'review') await round();
  else await after(mode === 'after');
} catch (e) {
  console.error(`STOPPED: ${e.message}`);
  process.exit(1);
}
