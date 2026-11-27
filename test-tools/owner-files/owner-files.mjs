/**
 * Task 0099: the owner's test files of 2026-11-25 through the built product, by the watched folder, and every delivered
 * file read again for the values that must not be in it. Read README.md first.
 *
 *   node test-tools/owner-files/owner-files.mjs [folder of test PDFs] [folder that holds the built backend]
 *
 * A THROWAWAY database container (lexavault-0099-pg), the built app and a stand-in AI that finds nothing (so only the
 * pattern rules and OCR find anything: the hardest case for them) are started here and stopped at the end; nothing else
 * is touched. A case held in Review has its value items confirmed as a reviewer would ("redact"); a page to check is
 * left for a person and the case counts as held. The documents hold synthetic values only; none is printed.
 */
import { spawn, spawnSync, execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, openSync, readFileSync, readdirSync, renameSync, statSync, writeFileSync, copyFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { homedir, tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..', '..');
// --real-ai: the local Ollama (text and vision models) instead of the stand-in; --only=a.pdf,b.pdf: just these files.
const flags = process.argv.slice(2).filter((a) => a.startsWith('--'));
const args = process.argv.slice(2).filter((a) => !a.startsWith('--'));
const realAi = flags.includes('--real-ai');
const only = (flags.find((f) => f.startsWith('--only=')) ?? '').slice(7).split(',').filter(Boolean);
const files = resolve(args[0] ?? 'C:\\dev\\lexavault\\test-pdfs');
const backend = resolve(args[1] ?? join(root, 'backend'));
const jar = join(backend, 'securedoc-api', 'target', 'securedoc-api-0.1.0-SNAPSHOT.jar');
const container = 'lexavault-0099-pg';
const [dbPort, appPort, aiPort] = [55499, 18099, 11599];
const base = `http://localhost:${appPort}`;
const aiUrl = realAi ? 'http://localhost:11434' : `http://localhost:${aiPort}`;
const ENTITY = '30216241-b724-5a3a-aef3-3034fd546d17';
const ACTOR = '00000000-0000-0000-0000-000000000099';
const MODEL = 'qwen3.5:9b';
const HEADERS = { 'X-Tenant-Id': ENTITY, 'X-Actor-Id': ACTOR, 'X-Actor-Roles': 'DOCUMENT_PROCESSOR,BUSINESS_ADMIN,TENANT_IT_ADMIN' };
/** The synthetic values of the files (README.md, long\*-expected.txt): never printed, only looked for. */
const VALUES = ['512-34-8876', '287-65-4321', '318-40-2295', '401-22-7731', '03/14/1979', '11/02/1981', '01/25/1966',
  '07/09/1990', '4111 1111 1111 1111'];

const run = join(tmpdir(), 'lexavault-0099', new Date().toISOString().replace(/[:.]/g, '-'));
const watched = join(run, 'watched');
for (const d of ['storage', 'watched', 'prepared', 'logs', 'tmp', 'export', 'delivered']) mkdirSync(join(run, d), { recursive: true });
const dbPassword = randomUUID().replaceAll('-', '');
/** Contract 12 digits, member 11, as the identifier rules want; unique per run. */
const suffix = String(Math.floor(100000 + Math.random() * 900000));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const sql = (q) => execFileSync('docker', ['exec', container, 'psql', '-U', 'securedoc', '-d', 'securedoc', '-tA', '-c', q], { encoding: 'utf8' }).trim();
const rows = [];
let app;

async function until(what, check, seconds) {
  for (const end = Date.now() + seconds * 1000; Date.now() < end; await sleep(1000)) {
    const value = await check();
    if (value) return value;
  }
  throw new Error(`waited ${seconds} seconds for: ${what}`);
}
async function call(method, path, body) {
  const res = await fetch(base + path, { method, headers: { ...HEADERS, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined });
  const text = await res.text();
  try { return { status: res.status, json: JSON.parse(text) }; } catch { return { status: res.status, json: null }; }
}

const ai = createServer((req, res) => {
  req.resume().on('end', () => res.writeHead(200, { 'Content-Type': 'application/json' }).end(req.url === '/api/chat'
    ? JSON.stringify({ message: { content: JSON.stringify({ findings: [] }) }, done_reason: 'stop' })
    : JSON.stringify({ models: [{ name: MODEL, model: MODEL }] })));
});

async function startApp() {
  app = spawn('java', [`-Djava.io.tmpdir=${join(run, 'tmp')}`, '-jar', jar, '--spring.profiles.active=local',
    `--securedoc.storage.local.root-path=${join(run, 'storage')}`, `--platform.erase-log.location=${join(run, 'erase-log')}`,
    '--securedoc.intake.watched-folder.incomplete-visibility-minutes=0',
    `--securedoc.platform-admin.watched-folder-tenant-id=${ENTITY}`,
    '--securedoc.platform-admin.watched-folder-coordination-enabled=false'], {
    cwd: run, stdio: ['ignore', openSync(join(run, 'logs', 'app.log'), 'w'), openSync(join(run, 'logs', 'app.err'), 'w')],
    env: { ...process.env, SPRING_DATASOURCE_URL: `jdbc:postgresql://localhost:${dbPort}/securedoc`, SPRING_DATASOURCE_USERNAME: 'securedoc',
      SPRING_DATASOURCE_PASSWORD: dbPassword, SERVER_PORT: String(appPort), SECUREDOC_NODE_ID: 'node-0099', SECUREDOC_AI_PROVIDER: 'ollama',
      SECUREDOC_AI_OLLAMA_BASE_URL: aiUrl, SECUREDOC_AI_VISION_BASE_URL: aiUrl,
      SECUREDOC_AI_MODEL: MODEL, SECUREDOC_AI_OLLAMA_PRELOAD_ENABLED: 'false', SECUREDOC_WATCHED_FOLDER_ENABLED: 'true',
      SECUREDOC_WATCHED_FOLDER_SCHEDULING_ENABLED: 'true', SECUREDOC_WATCHED_FOLDER_SCAN_INTERVAL_MS: '1000',
      SECUREDOC_WATCHED_FOLDER_ROOT_PATH: watched, SECUREDOC_PROCESSING_PIPELINE_MODE: 'staged', SECUREDOC_PROCESSING_EXPORT_MODE: 'job',
      SECUREDOC_DELIVERY_LOCAL_OUTPUT_PATH: join(run, 'export'), SECUREDOC_PRODUCT_TELEMETRY_OUTPUT_PATH: join(run, 'logs', 'telemetry.jsonl') },
  });
  await until('the app to start', async () => {
    if (app.exitCode !== null) throw new Error('the app stopped while starting (logs/app.log)');
    try { return (await fetch(`${base}/actuator/health/liveness`)).ok || (await fetch(`${base}/actuator/health`)).status < 600; } catch { return false; }
  }, 180);
}

let serial = 0;
function drop(file) {
  const name = `drop-${++serial}`;
  const prepared = join(run, 'prepared', name);
  mkdirSync(prepared);
  copyFileSync(file, join(prepared, basename(file)));
  const n = String(serial).padStart(4, '0');
  writeFileSync(join(prepared, 'manifest.tmp'), JSON.stringify({ businessIdentifiers: { contract_number: `99${n}${suffix}`,
    member_number: `9${n}${suffix}`, service_entity: '001' }, metadata: {}, files: [basename(file)] }));
  renameSync(join(prepared, 'manifest.tmp'), join(prepared, 'manifest.json'));
  renameSync(prepared, join(watched, name));
  return name;
}
const FINAL = ['COMPLETED', 'FAILED', 'REJECTED', 'PENDING_REVIEW', 'PENDING_FINAL_APPROVAL', 'CANCELLED'];
async function statusOf(packageId, seconds) {
  let status = 'UNKNOWN';
  try {
    await until('the case to settle', async () => {
      status = (await call('GET', `/api/v1/packages/${packageId}`)).json?.status ?? status;
      return FINAL.includes(status);
    }, seconds);
  } catch { /* the status is the result */ }
  return status;
}
const exported = () => {
  const out = [];
  const walk = (d) => { for (const n of readdirSync(d)) { const p = join(d, n); statSync(p).isDirectory() ? walk(p) : n.endsWith('.pdf') && out.push(p); } };
  walk(join(run, 'export'));
  return out;
};

async function one(file) {
  const before = new Set(exported());
  const name = drop(file);
  const where = await until(`${basename(file)} to be taken or refused`, () => ['_archive', '_problem'].find((d) => {
    try { return readdirSync(join(watched, d)).some((n) => n === name || n.startsWith(`${name}-`)); } catch { return false; }
  }), 120);
  if (where === '_problem') return { file: basename(file), outcome: 'refused at intake', review: '' };
  const packageId = sql("select package_id::text from intake_observations where package_id is not null order by updated_at desc limit 1");
  const seconds = 1200; // returns as soon as the case settles
  let status = await statusOf(packageId, seconds);
  const review = [];
  for (let round = 0; status === 'PENDING_REVIEW' && round < 3; round++) {
    const items = (await call('GET', `/api/v1/review-items?packageId=${packageId}&limit=500`)).json?.items ?? [];
    const values = items.filter((i) => i.entityType !== 'UNREAD_PAGE');
    for (const i of items) review.push(i.entityType === 'UNREAD_PAGE' ? `page ${i.pageNumber} to check${i.secondCheck ? ` [${i.secondCheck.join(',')}]` : ''}` : `${i.entityType} p${i.pageNumber}`);
    if (values.length === 0) break; // only pages to check: a person's
    for (const i of values) {
      const r = await call('POST', `/api/v1/review-items/${i.findingId}/confirm`, { reason: 'owner-files run: redact' });
      // A finding without a place can't be redacted without a mark: it stays, and so does the case (task 0100).
      if (r.status >= 300) review.push(`${i.entityType} p${i.pageNumber}${i.hasBoundingBox ? '' : ' (no place)'} not redacted: ${r.json?.code ?? `HTTP ${r.status}`}`);
    }
    status = await statusOf(packageId, seconds);
  }
  const delivered = exported().filter((p) => !before.has(p));
  delivered.forEach((p, k) => copyFileSync(p, join(run, 'delivered', `${basename(file, '.pdf')}${k ? `-${k}` : ''}.pdf`)));
  return { file: basename(file), outcome: status === 'COMPLETED' ? `delivered (${delivered.length} file)` : `not delivered: ${status}`,
    review: [...new Set(review)].join('; '), delivered: delivered.length };
}

function noValueIn(texts) {
  const forms = VALUES.flatMap((v) => [v, v.replace(/[^0-9]/g, '')]);
  return texts.every((t) => !forms.some((f) => t.includes(f)));
}

let started = false; let failed = false;
try {
  if (!existsSync(jar)) throw new Error(`not built yet: ${jar}`);
  if (execFileSync('docker', ['ps', '-aq', '--filter', `name=^/${container}$`], { encoding: 'utf8' }).trim()) throw new Error(`the container ${container} exists already`);
  console.log(`Run folder: ${run}`);
  execFileSync('docker', ['run', '-d', '--name', container, '-e', 'POSTGRES_DB=securedoc', '-e', 'POSTGRES_USER=securedoc',
    '-e', 'POSTGRES_PASSWORD', '-p', `127.0.0.1:${dbPort}:5432`, 'postgres:16-alpine'], { stdio: 'ignore', env: { ...process.env, POSTGRES_PASSWORD: dbPassword } });
  started = true;
  for (let i = 0; i < 60 && spawnSync('docker', ['exec', container, 'pg_isready', '-U', 'securedoc'], { stdio: 'ignore' }).status !== 0; i++) await sleep(1000);
  await sleep(3000);
  const init = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command',
    `. '${join(root, 'scripts', 'lib', 'LexaVaultTools.ps1')}'; Initialize-LexaVaultDatabase -Jar '${jar}' -DbUrl 'jdbc:postgresql://localhost:${dbPort}/securedoc' -DbUser 'securedoc' -DbPassword $env:OWNER_DB_PASSWORD -StateDir '${join(run, 'install')}'`],
    { env: { ...process.env, OWNER_DB_PASSWORD: dbPassword }, encoding: 'utf8' });
  if (init.status !== 0) throw new Error('the install steps failed');
  if (!realAi) await new Promise((r) => ai.listen(aiPort, '127.0.0.1', r));
  await startApp();
  const all = [...readdirSync(files).filter((n) => n.endsWith('.pdf')).sort().map((n) => join(files, n)),
    ...(existsSync(join(files, 'long')) ? readdirSync(join(files, 'long')).filter((n) => n.endsWith('.pdf')).sort().map((n) => join(files, 'long', n)) : [])];
  for (const file of all.filter((f) => only.length === 0 || only.includes(basename(f)))) {
    try {
      const row = await one(file);
      rows.push(row);
      console.log(`  ${row.file}: ${row.outcome}${row.review ? ` | review: ${row.review}` : ''}`);
    } catch (e) {
      rows.push({ file: basename(file), outcome: `error: ${e.message}`, review: '' });
      console.log(`  ${basename(file)}: error: ${e.message}`);
      failed = true;
    }
  }
  const dumped = execFileSync('docker', ['exec', container, 'pg_dump', '-U', 'securedoc', '-d', 'securedoc'], { encoding: 'utf8', maxBuffer: 512 * 1024 * 1024 });
  const logs = readdirSync(join(run, 'logs')).map((n) => readFileSync(join(run, 'logs', n), 'utf8'));
  const clean = noValueIn([dumped, ...logs]);
  console.log(`No value in the database or the logs: ${clean ? 'PASS' : 'FAIL'}`);
  if (!clean) failed = true;
} catch (e) {
  failed = true;
  console.error(`STOPPED: ${e.message}`);
} finally {
  if (app && app.exitCode === null) { const gone = new Promise((r) => app.once('exit', r)); app.kill('SIGKILL'); await gone; }
  if (ai.listening) { ai.close(); ai.closeAllConnections(); }
  if (started) spawnSync('docker', ['rm', '-f', container], { stdio: 'ignore' });
}
writeFileSync(join(run, 'summary.json'), JSON.stringify(rows, null, 2));
// The delivered files are checked by CheckDelivered.java (PDF text layer and OCR); see README.md.
const m2 = join(homedir(), '.m2', 'repository');
const cp = [['org/apache/pdfbox/pdfbox/3.0.8/pdfbox-3.0.8.jar'], ['org/apache/pdfbox/fontbox/3.0.8/fontbox-3.0.8.jar'],
  ['org/apache/pdfbox/pdfbox-io/3.0.8/pdfbox-io-3.0.8.jar'], ['commons-logging/commons-logging/1.3.5/commons-logging-1.3.5.jar']]
  .map(([p]) => join(m2, ...p.split('/'))).join(process.platform === 'win32' ? ';' : ':');
const delivered = readdirSync(join(run, 'delivered')).map((n) => join(run, 'delivered', n));
if (delivered.length) {
  // Task 0103: with the originals, so the words covered that are no value (over-redaction) are counted too.
  const check = spawnSync('java', ['-cp', cp, `-Doriginals=${files}${process.platform === 'win32' ? ';' : ':'}${join(files, 'long')}`,
    join(here, 'CheckDelivered.java'), ...delivered], { encoding: 'utf8' });
  console.log(check.stdout.split('\n').filter((l) => !l.includes('WARN')).join('\n'));
  if (check.status !== 0) failed = true;
}
console.log(`Details: ${run}`);
process.exit(failed ? 1 : 0);
