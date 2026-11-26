/**
 * Task 0077: every intake path, tested end to end. Read README.md first; run Run-IntakePaths.cmd, or
 *
 *   node test-tools/intake-paths/intake-paths.mjs [folder that holds the built backend]
 *
 * A THROWAWAY database container (lexavault-0077-pg), the built app, the built Scan Bridge (simulator), a small source
 * system to poll and a mock AI server are started here and stopped at the end; nothing else is touched. The documents
 * hold fake test values only. The database and PDF passwords are made per run and never printed.
 */
import { spawn, spawnSync, execFileSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, openSync, readFileSync, readdirSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..', '..');
const backend = resolve(process.argv[2] ?? join(root, 'backend'));
const jar = join(backend, 'securedoc-api', 'target', 'securedoc-api-0.1.0-SNAPSHOT.jar');
const bridgeJar = join(backend, 'securedoc-scan-bridge', 'target', 'securedoc-scan-bridge-0.1.0-SNAPSHOT.jar');
const container = 'lexavault-0077-pg';
const [dbPort, appPort, aiPort, sourcePort, bridgePort] = [55477, 18077, 11577, 18177, 18277];
const base = `http://localhost:${appPort}`;
const ENTITY = '30216241-b724-5a3a-aef3-3034fd546d17';
const NO_ENTITY = '99999999-9999-4999-8999-999999999999';
const ACTOR = '00000000-0000-0000-0000-000000000099';
const MODEL = 'qwen3.5:9b';
const MB = 1024 * 1024;

const run = join(tmpdir(), 'lexavault-0077', new Date().toISOString().replace(/[:.]/g, '-'));
const watched = join(run, 'watched');
const answersFile = join(run, 'answers.txt');
for (const d of ['storage', 'watched', 'prepared', 'documents', 'bridge', 'logs', 'tmp']) mkdirSync(join(run, d), { recursive: true });
const dbPassword = randomUUID().replaceAll('-', '');
const pdfPassword = `Pw${randomUUID().replaceAll('-', '')}`;
const wrongPassword = `Wr${randomUUID().replaceAll('-', '')}`;
const suffix = String(Math.floor(100000 + Math.random() * 900000));
const answers = [];
let serial = 0;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const sql = (q) => execFileSync('docker', ['exec', container, 'psql', '-U', 'securedoc', '-d', 'securedoc', '-tA', '-c', q], { encoding: 'utf8' }).trim();
const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');
const identifiers = () => {
  const n = String(++serial).padStart(4, '0');
  return { contract_number: `77${n}${suffix}`, member_number: `7${n}${suffix}`, service_entity: '001' };
};
async function until(what, check, seconds = 90) {
  for (const end = Date.now() + seconds * 1000; Date.now() < end; await sleep(500)) {
    const value = await check();
    if (value) return value;
  }
  throw new Error(`waited ${seconds} seconds for: ${what}`);
}

/* ---------- the result table ---------- */
const PATHS = ['Manual upload', 'Watched folder, with trigger', 'Watched folder, no trigger', 'File polling', 'Scanner bridge (simulated)', 'API'];
const CHECKS = ['Accepted', 'Processed', 'Names the entity', 'Wrong type', 'Too big', 'Password-protected', 'Unknown entity', 'Server full'];
const table = new Map(PATHS.map((p) => [p, new Map()]));
const extra = [];
function result(path, check, ok, text) {
  if (!table.has(path) || !CHECKS.includes(check)) throw new Error(`no such cell: ${path} / ${check}`);
  table.get(path).set(check, { ok, text });
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${path}: ${check} - ${text}`);
}
function also(name, ok, text) {
  extra.push({ name, ok, text });
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name} - ${text}`);
}
/** A refusal passes only with its own code; `got` is every code the path gave. */
function refused(path, check, got, expected) {
  const ok = got.length > 0 && got.every((c) => expected.includes(c));
  result(path, check, ok, ok ? got.join(' + ') : `got ${got.join(' + ') || 'nothing'}, expected ${expected.join(' or ')}`);
}
/** One path's check that stops on an error is a FAIL of that check; the others still run. */
async function attempt(path, check, body) {
  try {
    await body();
  } catch (e) {
    result(path, check, false, e.message);
  }
}

/* ---------- calls ---------- */
async function call(method, path, { headers = {}, body, entity = ENTITY, url = base } = {}) {
  const raw = body instanceof FormData || body instanceof Uint8Array;
  const res = await fetch(url + path, {
    method,
    headers: {
      ...(url === base ? { 'X-Tenant-Id': entity, 'X-Actor-Id': ACTOR, 'X-Actor-Roles': 'DOCUMENT_PROCESSOR,TENANT_IT_ADMIN' } : {}),
      ...(body && !raw ? { 'Content-Type': 'application/json' } : {}), ...headers,
    },
    body: body ? (raw ? body : JSON.stringify(body)) : undefined,
  });
  const text = await res.text();
  answers.push(`${method} ${path} ${res.status} ${text}`);
  let json = null;
  try { json = JSON.parse(text); } catch { /* not JSON */ }
  return { status: res.status, json, code: json?.code ?? null, retryAfter: res.headers.get('retry-after') };
}
async function must(method, path, options) {
  const r = await call(method, path, options);
  if (r.status >= 300) throw new Error(`${method} ${path.replace(/[0-9a-f-]{36}/g, '{id}')}: HTTP ${r.status} ${r.code ?? ''}`);
  return r.json;
}
const codeOf = (r) => (r.code ? [r.code] : [`HTTP ${r.status}`]);
const form = (bytes, name, type, fields = {}) => {
  const f = new FormData();
  f.append('file', new Blob([bytes], { type }), name);
  for (const [k, v] of Object.entries(fields)) f.append(k, v);
  return f;
};

/** Processed: exported. Names the entity: it is stored under this entity (by name), and no other entity can open it. */
async function follow(path, packageId) {
  await attempt(path, 'Processed', async () => {
    let status;
    try {
      await until('the case to be exported', async () => {
        status = (await must('GET', `/api/v1/packages/${packageId}`)).status;
        return ['COMPLETED', 'FAILED', 'REJECTED', 'PENDING_REVIEW', 'PENDING_FINAL_APPROVAL', 'CANCELLED'].includes(status);
      }, 180);
    } catch { /* the status is the result */ }
    result(path, 'Processed', status === 'COMPLETED', status);
  });
  await attempt(path, 'Names the entity', async () => {
    if (!/^[0-9a-f-]{36}$/.test(packageId)) throw new Error('no case id');
    const name = sql(`select t.name from document_packages p join tenants t on t.id = p.tenant_id where p.id = '${packageId}' and t.id = '${ENTITY}'`);
    const other = await call('GET', `/api/v1/packages/${packageId}`, { entity: NO_ENTITY });
    const audited = sql(`select count(*) from audit_records where tenant_id = '${ENTITY}' and resource_id = '${packageId}'`);
    result(path, 'Names the entity', name !== '' && other.status >= 400 && Number(audited) > 0,
      name === '' ? 'the case is not under this entity' : other.status < 400 ? 'another entity could open it' : name);
  });
}

/* ---------- documents ---------- */
const doc = {};
function makeDocuments() {
  const m2 = join(process.env.USERPROFILE, '.m2', 'repository');
  const logging = join(m2, 'commons-logging', 'commons-logging');
  const version = readdirSync(logging).find((v) => /^\d/.test(v) && readdirSync(join(logging, v)).some((f) => f.endsWith('.jar')));
  const cp = [...['pdfbox', 'fontbox', 'pdfbox-io'].map((a) => join(m2, 'org', 'apache', 'pdfbox', a, '3.0.2', `${a}-3.0.2.jar`)),
    join(logging, version, `commons-logging-${version}.jar`)].join(';');
  const make = (file, program, args) => {
    const out = join(run, 'documents', file);
    execFileSync('java', ['-cp', cp, join(root, 'test-tools', program), out, ...args]);
    return readFileSync(out);
  };
  doc.good = make('good.pdf', 'no-redacted-values/MakeFakeValuePdf.java', []);
  doc.locked = make('locked.pdf', 'no-redacted-values/MakeFakeValuePdf.java', [pdfPassword]);
  doc.manyPages = make('many-pages.pdf', 'two-servers/MakeCasePdf.java', ['typed', '501']);
  doc.text = Buffer.from('This is a text file, not a PDF.\n');
  // A real PDF followed by filler, over the 50 MB one upload may have.
  doc.huge = Buffer.concat([doc.good, Buffer.alloc(51 * MB, 32)]);
  doc.overTwoMb = Buffer.concat([doc.good, Buffer.alloc(2 * MB, 32)]);
}
/** Every case gets its own bytes, so no path mistakes it for the one before. */
const fresh = (bytes) => Buffer.concat([bytes, Buffer.from(`\n%${++serial}-${suffix}\n`)]);

/* ---------- the mock AI server ---------- */
const aiAnswer = JSON.stringify({ message: { content: JSON.stringify({ findings: [
  { pageNumber: 1, entityType: 'SSN', matchedText: '412-55-7531', contextText: '', confidence: 0.97 },
  { pageNumber: 1, entityType: 'CREDIT_CARD_NUMBER', matchedText: '4000 0566 5566 5556', contextText: '', confidence: 0.97 },
  { pageNumber: 1, entityType: 'DATE_OF_BIRTH', matchedText: '03/14/1962', contextText: '', confidence: 0.97 }] }) }, done_reason: 'stop' });
const ai = createServer((req, res) => {
  req.resume().on('end', () => res.writeHead(200, { 'Content-Type': 'application/json' })
    .end(req.url === '/api/chat' ? aiAnswer : JSON.stringify({ models: [{ name: MODEL, model: MODEL }] })));
});

/* ---------- the source system that is polled ---------- */
let offered = [];
const acknowledged = new Set();
const source = createServer((req, res) => {
  const parts = new URL(req.url, 'http://x').pathname.split('/').filter(Boolean); // lexavault v1 pickups id revisions 1 ...
  const item = offered.find((o) => o.externalSourcePackageId === parts[3]);
  req.resume().on('end', () => {
    if (parts.length === 3) {
      const items = offered.filter((o) => !acknowledged.has(o.externalSourcePackageId)).map(({ bytes, type, ...o }) => o);
      return res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ items }));
    }
    if (item && parts[6] === 'documents') return res.writeHead(200, { 'Content-Type': item.type }).end(item.bytes);
    if (item && parts[6] === 'acknowledgement') {
      acknowledged.add(item.externalSourcePackageId);
      return res.writeHead(200, { 'Content-Type': 'application/json' }).end('{"receiptReference":"R-1"}');
    }
    return res.writeHead(404).end();
  });
});

/* ---------- the app and the bridge ---------- */
let app; let bridge;
async function stop(child) {
  if (!child || child.exitCode !== null || child.signalCode !== null) return;
  const gone = new Promise((r) => child.once('exit', r));
  child.kill('SIGKILL');
  await gone;
}
async function startApp(name, { entity = ENTITY, env = {} } = {}) {
  await stop(app);
  app = spawn('java', [`-Djava.io.tmpdir=${join(run, 'tmp')}`, '-jar', jar, '--spring.profiles.active=local',
    `--securedoc.storage.local.root-path=${join(run, 'storage')}`, `--platform.erase-log.location=${join(run, 'erase-log')}`,
    '--securedoc.intake.watched-folder.incomplete-visibility-minutes=0',
    `--securedoc.platform-admin.watched-folder-tenant-id=${entity}`,
    '--securedoc.platform-admin.watched-folder-coordination-enabled=false'], {
    cwd: run, stdio: ['ignore', openSync(join(run, 'logs', `app-${name}.log`), 'w'), openSync(join(run, 'logs', `app-${name}.err`), 'w')],
    env: { ...process.env, SPRING_DATASOURCE_URL: `jdbc:postgresql://localhost:${dbPort}/securedoc`, SPRING_DATASOURCE_USERNAME: 'securedoc',
      SPRING_DATASOURCE_PASSWORD: dbPassword, SERVER_PORT: String(appPort), SECUREDOC_NODE_ID: 'node-intake', SECUREDOC_AI_PROVIDER: 'ollama',
      SECUREDOC_AI_OLLAMA_BASE_URL: `http://localhost:${aiPort}`, SECUREDOC_AI_VISION_BASE_URL: `http://localhost:${aiPort}`,
      SECUREDOC_AI_MODEL: MODEL, SECUREDOC_AI_OLLAMA_PRELOAD_ENABLED: 'false', SECUREDOC_WATCHED_FOLDER_ENABLED: 'true',
      SECUREDOC_WATCHED_FOLDER_SCHEDULING_ENABLED: 'true', SECUREDOC_WATCHED_FOLDER_SCAN_INTERVAL_MS: '1000',
      SECUREDOC_WATCHED_FOLDER_ROOT_PATH: watched, SECUREDOC_PROCESSING_PIPELINE_MODE: 'staged', SECUREDOC_PROCESSING_EXPORT_MODE: 'job',
      SECUREDOC_DELIVERY_LOCAL_OUTPUT_PATH: join(run, 'export'), SECUREDOC_PRODUCT_TELEMETRY_OUTPUT_PATH: join(run, 'logs', 'telemetry.jsonl'),
      ...env },
  });
  await until('the app to start', async () => {
    if (app.exitCode !== null) throw new Error(`the app stopped while starting (logs/app-${name}.log)`);
    try { return (await fetch(`${base}/actuator/health/liveness`)).ok || (await fetch(`${base}/actuator/health`)).status < 600; } catch { return false; }
  }, 180);
}
let bridgeToken; let bridgeName;
const bridgeLog = () => readFileSync(join(run, 'logs', `bridge-${bridgeName}.log`), 'utf8');
async function startBridge(name, entity) {
  await stop(bridge);
  bridgeName = name;
  const at = (d) => join(run, 'bridge', name, d);
  for (const d of ['work', 'problem', 'inbox']) mkdirSync(at(d), { recursive: true });
  bridge = spawn('java', ['-jar', bridgeJar, '--securedoc.scan-bridge.delivery.retry-initial-delay-ms=500'], {
    cwd: run, stdio: ['ignore', openSync(join(run, 'logs', `bridge-${name}.log`), 'w'), openSync(join(run, 'logs', `bridge-${name}.err`), 'w')],
    env: { ...process.env, SECUREDOC_SCAN_BRIDGE_PORT: String(bridgePort), SECUREDOC_SCAN_BRIDGE_TENANT_ID: entity,
      SECUREDOC_SCAN_BRIDGE_MODE: 'SIMULATOR', SECUREDOC_SCAN_BRIDGE_LEXAVAULT_BASE_URL: `${base}/api/v1`,
      SECUREDOC_SCAN_BRIDGE_CLIENT_PRINCIPAL: 'bridge-0077', SECUREDOC_SCAN_BRIDGE_LOCAL_ACTOR_ID: ACTOR,
      SECUREDOC_SCAN_BRIDGE_WORK_PATH: at('work'), SECUREDOC_SCAN_BRIDGE_PROBLEM_PATH: at('problem'), SECUREDOC_SCAN_BRIDGE_INBOX_PATH: at('inbox'),
      SECUREDOC_SCAN_BRIDGE_MAX_SCAN_PDF_SIZE_BYTES: String(MB), SECUREDOC_SCAN_BRIDGE_MIN_USABLE_SPACE_BYTES: '1048576' },
  });
  bridgeToken = (await until('the Scan Bridge to start', async () => {
    if (bridge.exitCode !== null) throw new Error(`the Scan Bridge stopped while starting (logs/bridge-${name}.log)`);
    try { return await (await fetch(`http://127.0.0.1:${bridgePort}/api/v1/configuration`)).json(); } catch { return null; }
  }, 60)).browserRequestToken;
}

/* ---------- the paths ---------- */
async function manual() {
  const path = 'Manual upload';
  const create = () => must('POST', '/api/v1/packages', { body: { packageType: 'MANUAL_UPLOAD', metadata: { source: 'intake-paths' }, businessIdentifiers: identifiers() } });
  const upload = async (bytes, name, type) => call('POST', `/api/v1/packages/${(await create()).packageId}/documents`, { body: form(bytes, name, type) });
  await attempt(path, 'Accepted', async () => {
    const { packageId } = await create();
    const up = await call('POST', `/api/v1/packages/${packageId}/documents`, { body: form(fresh(doc.good), 'good.pdf', 'application/pdf') });
    const submitted = await call('POST', `/api/v1/packages/${packageId}/submit`);
    result(path, 'Accepted', up.status < 300 && submitted.status < 300, `HTTP ${up.status}, then ${submitted.status}`);
    await follow(path, packageId);
  });
  await attempt(path, 'Wrong type', async () => refused(path, 'Wrong type',
    [...codeOf(await upload(doc.text, 'notes.txt', 'text/plain')), ...codeOf(await upload(doc.text, 'notes.pdf', 'application/pdf'))], ['LV-INT-007', 'LV-INT-008']));
  await attempt(path, 'Too big', async () => refused(path, 'Too big',
    [...codeOf(await upload(doc.huge, 'huge.pdf', 'application/pdf')), ...codeOf(await upload(doc.manyPages, 'pages.pdf', 'application/pdf'))], ['LV-INT-004', 'LV-INT-028']));
  await attempt(path, 'Password-protected', async () => refused(path, 'Password-protected', codeOf(await upload(doc.locked, 'locked.pdf', 'application/pdf')), ['LV-INT-008']));
  await attempt(path, 'Unknown entity', async () => refused(path, 'Unknown entity', codeOf(await call('POST', '/api/v1/packages', {
    entity: NO_ENTITY, body: { packageType: 'MANUAL_UPLOAD', metadata: {}, businessIdentifiers: identifiers() } })), ['LV-AUT-003']));
}

/** Drops one folder into the watched folder in one move; returns what happened to it. */
let drops = 0;
function drop(file, bytes, { trigger = true } = {}) {
  const name = `drop-${++drops}`;
  const prepared = join(run, 'prepared', name);
  mkdirSync(prepared);
  writeFileSync(join(prepared, file), bytes);
  if (trigger) writeTrigger(prepared, file);
  renameSync(prepared, join(watched, name));
  return name;
}
const writeTrigger = (folder, file) => {
  writeFileSync(join(folder, 'manifest.tmp'), JSON.stringify({ businessIdentifiers: identifiers(), metadata: {}, files: [file] }));
  renameSync(join(folder, 'manifest.tmp'), join(folder, 'manifest.json'));
};
const lastAudit = () => sql("select coalesce(max(occurred_at), 'epoch')::text from audit_records");
/** Task 0102: the cases the dashboard shows. A refused drop or polled case must not add one. */
const dashboardCases = () => Number(sql('select count(*) from document_packages where dashboard_hidden_at is null'));
/** Task 0102: one drop with several files; the manifest lists `listed` (a name not written is a missing file). */
function dropFiles(files, listed) {
  const name = `drop-${++drops}`;
  const prepared = join(run, 'prepared', name);
  mkdirSync(prepared);
  for (const [file, bytes] of files) writeFileSync(join(prepared, file), bytes);
  writeFileSync(join(prepared, 'manifest.tmp'), JSON.stringify({ businessIdentifiers: identifiers(), metadata: {}, files: listed }));
  renameSync(join(prepared, 'manifest.tmp'), join(prepared, 'manifest.json'));
  renameSync(prepared, join(watched, name));
  return name;
}
async function settled(name, since) {
  const where = await until(`folder drop ${name} to be taken or refused`, () => ['_archive', '_problem'].find((d) => {
    try { return readdirSync(join(watched, d)).some((n) => n === name || n.startsWith(`${name}-`)); } catch { return false; }
  }), 60);
  const row = sql(`select status || '|' || coalesce(package_id::text, '') from intake_observations order by updated_at desc limit 1`).split('|');
  const codes = sql(`select distinct substring(detail from 'LV-[A-Z]+-[0-9]+') from audit_records where occurred_at > '${since}' and resource_type = 'WatchedFolderIntake' and detail like '%LV-%'`);
  return { where, status: row[0], packageId: row[1], codes: codes ? codes.split('\n') : [] };
}
async function watchedWithTrigger() {
  const path = 'Watched folder, with trigger';
  let left = 0;
  const send = async (file, bytes) => {
    const since = lastAudit();
    const cases = dashboardCases();
    const r = await settled(drop(file, bytes), since);
    if (r.status !== 'ACCEPTED') left += dashboardCases() - cases;
    return r;
  };
  await attempt(path, 'Accepted', async () => {
    const r = await send('good.pdf', fresh(doc.good));
    result(path, 'Accepted', r.where === '_archive' && r.status === 'ACCEPTED', `${r.status}, moved to ${r.where} ${r.codes.join(' ')}`.trim());
    await follow(path, r.packageId);
  });
  await attempt(path, 'Wrong type', async () => refused(path, 'Wrong type', [...(await send('notes.txt', doc.text)).codes, ...(await send('notes.pdf', fresh(doc.text))).codes], ['LV-INT-007', 'LV-INT-008']));
  await attempt(path, 'Too big', async () => refused(path, 'Too big', (await send('pages.pdf', fresh(doc.manyPages))).codes, ['LV-INT-028']));
  await attempt(path, 'Password-protected', async () => refused(path, 'Password-protected', (await send('locked.pdf', fresh(doc.locked))).codes, ['LV-INT-008']));
  // Task 0102 (owner test U3): a drop is all or nothing; a refused one leaves no case, only its intake problem.
  for (const [what, files, listed, codes] of [
    ['a good and a password-protected file', [['good.pdf', fresh(doc.good)], ['locked.pdf', fresh(doc.locked)]], ['good.pdf', 'locked.pdf'], ['LV-INT-008']],
    ['a listed file missing', [['good.pdf', fresh(doc.good)]], ['good.pdf', 'missing.pdf'], null]]) {
    try {
      const since = lastAudit();
      const cases = dashboardCases();
      const r = await settled(dropFiles(files, listed), since);
      const ok = r.where === '_problem' && r.status === 'REJECTED' && dashboardCases() === cases && r.codes.length > 0
        && (!codes || r.codes.every((c) => codes.includes(c)));
      also(`${path}: ${what} leaves no case`, ok, `${r.status}, moved to ${r.where}, cases on the dashboard +${dashboardCases() - cases} ${r.codes.join(' ')}`.trim());
    } catch (e) {
      also(`${path}: ${what} leaves no case`, false, e.message);
    }
  }
  also(`${path}: refused drops leave no case`, left === 0, `cases on the dashboard +${left}`);
}
/** Nothing is taken or judged until the trigger file comes; then each is judged as with a trigger. */
async function watchedNoTrigger() {
  const path = 'Watched folder, no trigger';
  const observed = () => Number(sql("select count(*) from intake_observations where status = 'WAITING_FOR_TRIGGER'"));
  const late = async (file, bytes) => {
    const before = observed();
    const cases = sql('select count(*) from document_packages');
    const name = drop(file, bytes, { trigger: false });
    await until('the waiting folder to be seen', () => observed() > before, 30);
    await sleep(2500);
    if (!existsSync(join(watched, name)) || sql('select count(*) from document_packages') !== cases) throw new Error('taken without its trigger file');
    const since = lastAudit();
    writeTrigger(join(watched, name), file);
    return settled(name, since);
  };
  await attempt(path, 'Accepted', async () => {
    const r = await late('good.pdf', fresh(doc.good));
    result(path, 'Accepted', r.status === 'ACCEPTED', r.status === 'ACCEPTED' ? 'waited, then ACCEPTED' : r.status);
    await follow(path, r.packageId);
  });
  for (const [check, file, bytes, codes] of [['Wrong type', 'notes.txt', doc.text, ['LV-INT-007']], ['Too big', 'pages.pdf', fresh(doc.manyPages), ['LV-INT-028']],
    ['Password-protected', 'locked.pdf', fresh(doc.locked), ['LV-INT-008']]]) {
    await attempt(path, check, async () => refused(path, check, (await late(file, bytes)).codes, codes));
  }
}

let pollingId;
let pollingLeft = 0;
async function polling() {
  const path = 'File polling';
  pollingId = (await must('POST', '/api/v1/integrations', { body: { name: 'Polled source 0077', purpose: 'INGESTION_SOURCE', direction: 'OUTBOUND',
    providerType: 'HTTP_API', authenticationType: 'NONE', endpoint: `http://localhost:${sourcePort}`, enabled: true, acknowledgementMode: 'SOURCE_PICKUP_ACK',
    timeoutSeconds: 30, retryMaxAttempts: 0, incompleteWindowHours: 24, maxDocumentsPerIntake: 5, maxPdfSizeBytes: 2 * MB, maxPdfPages: 100,
    pollingScheduleType: 'INTERVAL', pollingIntervalMinutes: 1440, pollingBatchLimit: 10 } })).connectionId;
  const send = async (file, bytes, type = 'application/pdf') => {
    const id = `SRC-${++serial}-${suffix}`;
    const since = lastAudit();
    offered = [{ externalSourcePackageId: id, sourceRevision: '1', retry: false, businessIdentifiers: identifiers(), metadata: {}, bytes, type,
      documents: [{ externalSourceDocumentId: 'DOC-1', fileName: file, sha256: sha(bytes) }] }];
    const r = await must('POST', `/api/v1/integrations/${pollingId}/polling/run-now`);
    offered = [];
    const codes = sql(`select distinct substring(detail from 'LV-[A-Z]+-[0-9]+') from audit_records where occurred_at > '${since}' and action = 'POLLING_PACKAGE_FAILED' and detail like '%LV-%'`);
    const packageId = sql(`select package_id from external_intakes where external_source_package_id = '${id}'`);
    // Task 0102: a refused polled case is closed at once (cancelled, off the dashboard).
    if (packageId && !acknowledged.has(id)) {
      pollingLeft += Number(sql(`select count(*) from document_packages where id = '${packageId}' and dashboard_hidden_at is null`));
    }
    return { status: r.status, acknowledged: acknowledged.has(id), codes: codes ? codes.split('\n') : [], packageId };
  };
  await attempt(path, 'Accepted', async () => {
    const r = await send('good.pdf', fresh(doc.good));
    result(path, 'Accepted', r.status === 'SUCCESS' && r.acknowledged, `${r.status}, receipt confirmed to the source: ${r.acknowledged ? 'yes' : 'no'} ${r.codes.join(' ')}`.trim());
    await follow(path, r.packageId);
  });
  const no = async (check, sends, expected) => attempt(path, check, async () => {
    const all = [];
    for (const args of sends) all.push(await send(...args));
    if (all.some((r) => r.acknowledged)) throw new Error('the source was told it was received');
    refused(path, check, all.flatMap((r) => (r.codes.length ? r.codes : [r.status])), expected);
  });
  await no('Wrong type', [['notes.txt', doc.text, 'text/plain'], ['notes.pdf', fresh(doc.text)]], ['LV-INT-007', 'LV-INT-008']);
  await no('Too big', [['big.pdf', fresh(doc.overTwoMb)], ['pages.pdf', fresh(doc.manyPages)]], ['LV-INT-004', 'LV-INT-009', 'LV-INT-028']);
  await no('Password-protected', [['locked.pdf', fresh(doc.locked)]], ['LV-INT-008']);
  also(`${path}: refused cases leave no case`, pollingLeft === 0, `cases on the dashboard +${pollingLeft}`);
  await attempt(path, 'Unknown entity', async () => refused(path, 'Unknown entity',
    codeOf(await call('POST', `/api/v1/integrations/${pollingId}/polling/run-now`, { entity: NO_ENTITY })), ['LV-AUT-003']));
}

async function scan(file, bytes) {
  const url = `http://127.0.0.1:${bridgePort}`;
  const headers = { 'X-LexaVault-Bridge-Token': bridgeToken };
  const session = await must('POST', '/api/v1/scan-sessions', { url, headers, body: { businessIdentifiers: identifiers() } });
  const at = `/api/v1/scan-sessions/${session.sessionId}`;
  const logAt = bridgeLog().length;
  const staged = await call('POST', `${at}/simulator-pdf`, { url, headers, body: form(bytes, file, 'application/pdf') });
  if (staged.status >= 300) {
    await call('POST', `${at}/cancel`, { url, headers });
    return { codes: codeOf(staged) };
  }
  let now = await until('the scan to be ready', async () => {
    const moved = /SCAN_BRIDGE_FILE_REJECTED.*code=(LV-[A-Z]+-[0-9]+)/.exec(bridgeLog().slice(logAt));
    if (moved) return { status: 'MOVED', code: moved[1] };
    const s = (await call('GET', at, { url })).json;
    return s && s.status !== 'WAITING_FOR_SCAN' ? s : null;
  }, 30);
  if (now.status === 'MOVED') {
    await must('POST', `${at}/cancel`, { url, headers });
    return { codes: [now.code] };
  }
  if (now.status === 'READY') await must('POST', `${at}/submit`, { url, headers });
  const item = await until('the scan to be delivered or refused', async () => {
    now = (await call('GET', at, { url })).json;
    const one = ((await call('GET', '/api/v1/delivery-items', { url })).json ?? []).find((d) => d.sessionId === session.sessionId);
    return ['SUBMITTED', 'ATTENTION_REQUIRED', 'CANCELLED'].includes(now.status) || (one?.lastDeliveryLexaVaultCode === 'LV-INT-027') ? { ...one, status: now.status } : null;
  }, 90);
  const codes = item.status === 'SUBMITTED' ? [] : [item.lastDeliveryLexaVaultCode ?? item.lastDeliveryLvCode].filter(Boolean);
  return { status: item.status, packageId: now.lexavaultPackageId, codes };
}
async function scanner() {
  const path = 'Scanner bridge (simulated)';
  await must('POST', '/api/v1/integrations', { body: { name: 'Scan Bridge 0077', purpose: 'INGESTION_SOURCE', direction: 'INBOUND', providerType: 'SCANNER',
    authenticationType: 'JWT', clientPrincipal: 'bridge-0077', enabled: true, acknowledgementMode: 'NONE', timeoutSeconds: 60, retryMaxAttempts: 2,
    incompleteWindowHours: 24, maxDocumentsPerIntake: 1, maxPdfSizeBytes: 10 * MB, maxPdfPages: 100 } });
  await startBridge('main', ENTITY);
  await attempt(path, 'Accepted', async () => {
    const r = await scan('good.pdf', fresh(doc.good));
    result(path, 'Accepted', r.status === 'SUBMITTED', [r.status, ...r.codes].join(' '));
    await follow(path, r.packageId);
  });
  await attempt(path, 'Wrong type', async () => refused(path, 'Wrong type', [...(await scan('notes.txt', doc.text)).codes, ...(await scan('notes.pdf', fresh(doc.text))).codes], ['LV-SCN-004', 'LV-INT-007', 'LV-INT-008']));
  await attempt(path, 'Too big', async () => refused(path, 'Too big', [...(await scan('big.pdf', fresh(doc.overTwoMb))).codes, ...(await scan('pages.pdf', fresh(doc.manyPages))).codes], ['LV-SCN-005', 'LV-INT-009', 'LV-INT-028']));
  await attempt(path, 'Password-protected', async () => refused(path, 'Password-protected', (await scan('locked.pdf', fresh(doc.locked))).codes, ['LV-INT-008']));
  await attempt(path, 'Unknown entity', async () => {
    await startBridge('unknown-entity', NO_ENTITY);
    refused(path, 'Unknown entity', (await scan('good.pdf', fresh(doc.good))).codes, ['LV-AUT-003']);
  });
  await stop(bridge);
}

const push = { 'X-Integration-Client': 'push-0077' };
const pushCreate = (bytes, file, entity) => call('POST', '/api/v1/external-intakes', { entity, headers: push, body: {
  externalSourcePackageId: `API-${++serial}-${suffix}`, sourceRevision: '1', businessIdentifiers: identifiers(), metadata: {},
  documents: [{ externalSourceDocumentId: 'DOC-1', fileName: file, sha256: sha(bytes) }], retry: false } });
async function api() {
  const path = 'API';
  await must('POST', '/api/v1/integrations', { body: { name: 'Push source 0077', purpose: 'INGESTION_SOURCE', direction: 'INBOUND', providerType: 'PUSH_API',
    authenticationType: 'JWT', clientPrincipal: 'push-0077', enabled: true, acknowledgementMode: 'NONE', timeoutSeconds: 60, retryMaxAttempts: 2,
    incompleteWindowHours: 24, maxDocumentsPerIntake: 1, maxPdfSizeBytes: 2 * MB, maxPdfPages: 100 } });
  const upload = (packageId, bytes, file, type, fields = {}, query = '') => call('POST', `/api/v1/external-intakes/${packageId}/documents/DOC-1${query}`, {
    headers: { ...push, 'Idempotency-Key': randomUUID(), 'X-Content-SHA256': sha(bytes) }, body: form(bytes, file, type, fields) });
  const send = async (bytes, file, type = 'application/pdf') => {
    const made = await pushCreate(bytes, file);
    return made.status >= 300 ? made : upload(made.json.packageId, bytes, file, type);
  };
  await attempt(path, 'Accepted', async () => {
    const bytes = fresh(doc.good);
    const made = await pushCreate(bytes, 'good.pdf');
    const up = await upload(made.json.packageId, bytes, 'good.pdf', 'application/pdf');
    result(path, 'Accepted', made.status === 201 && up.status === 201, `HTTP ${made.status}, then ${up.status}`);
    await follow(path, made.json.packageId);
  });
  await attempt(path, 'Wrong type', async () => refused(path, 'Wrong type', [...codeOf(await send(doc.text, 'notes.txt', 'text/plain')), ...codeOf(await send(fresh(doc.text), 'notes.pdf'))], ['LV-INT-007', 'LV-INT-008']));
  await attempt(path, 'Too big', async () => refused(path, 'Too big', [...codeOf(await send(fresh(doc.overTwoMb), 'big.pdf')), ...codeOf(await send(fresh(doc.manyPages), 'pages.pdf'))], ['LV-INT-004', 'LV-INT-009', 'LV-INT-028']));
  await attempt(path, 'Password-protected', async () => {
    const bytes = fresh(doc.locked);
    const { packageId } = (await pushCreate(bytes, 'locked.pdf')).json;
    const none = await upload(packageId, bytes, 'locked.pdf', 'application/pdf');
    const wrong = await upload(packageId, bytes, 'locked.pdf', 'application/pdf', { password: wrongPassword });
    const address = await upload(packageId, bytes, 'locked.pdf', 'application/pdf', {}, '?password=not-a-secret');
    const right = await upload(packageId, bytes, 'locked.pdf', 'application/pdf', { password: pdfPassword });
    const got = [none.code, wrong.code, address.code, right.status];
    const done = right.status === 201 && await until('the decrypted case to be exported', async () => (await must('GET', `/api/v1/packages/${packageId}`)).status === 'COMPLETED', 180);
    result(path, 'Password-protected', got.join() === 'LV-INT-008,LV-INT-036,LV-INT-037,201' && done === true,
      `none ${none.code}, wrong ${wrong.code}, in the address ${address.code}, right ${right.status === 201 ? 'ACCEPTED and exported' : `HTTP ${right.status}`}`);
  });
  await attempt(path, 'Unknown entity', async () => refused(path, 'Unknown entity', codeOf(await pushCreate(doc.good, 'good.pdf', NO_ENTITY)), ['LV-AUT-003']));
}

/** The watched folder belongs to one configured entity; here the app is started with one that does not exist. */
async function folderOfAnUnknownEntity() {
  await startApp('unknown-entity', { entity: NO_ENTITY });
  for (const [path, trigger] of [['Watched folder, with trigger', true], ['Watched folder, no trigger', false]]) {
    await attempt(path, 'Unknown entity', async () => {
      const cases = sql('select count(*) from document_packages');
      const name = drop('good.pdf', fresh(doc.good), { trigger });
      await sleep(4000);
      const log = () => readFileSync(join(run, 'logs', 'app-unknown-entity.log'), 'utf8');
      const line = await until('a reason in the log', () => log().split('\n').find((l) => l.includes('WATCHED_FOLDER_') && /LV-[A-Z]+-\d+|reasonCode/.test(l)), 30);
      const code = /LV-[A-Z]+-\d+/.exec(line)?.[0] ?? `no LV- code: ${/reasonCode=\w+/.exec(line)?.[0]}`;
      const left = existsSync(join(watched, name));
      rmSync(join(watched, name), { recursive: true, force: true });
      if (sql('select count(*) from document_packages') !== cases) throw new Error('a case was made');
      if (!left) throw new Error('the folder was moved away');
      refused(path, 'Unknown entity', [code], ['LV-LIC-003']);
    });
  }
}

/** The safety stop (task 0067): nothing is taken, and nothing is lost: each waits at its source. */
async function serverFull() {
  await startApp('full', { env: { SECUREDOC_INTAKE_SPACE_STOP_BELOW_PERCENT: '98', SECUREDOC_INTAKE_SPACE_RESUME_ABOVE_PERCENT: '99' } });
  const cases = sql('select count(*) from document_packages');
  await attempt('Manual upload', 'Server full', async () => {
    const r = await call('POST', '/api/v1/packages', { body: { packageType: 'MANUAL_UPLOAD', metadata: {}, businessIdentifiers: identifiers() } });
    refused('Manual upload', 'Server full', [...codeOf(r), ...(r.status === 429 && r.retryAfter ? [] : [`HTTP ${r.status}, Retry-After ${r.retryAfter}`])], ['LV-INT-027']);
  });
  await attempt('API', 'Server full', async () => {
    const r = await pushCreate(doc.good, 'good.pdf');
    refused('API', 'Server full', [...codeOf(r), ...(r.status === 429 && r.retryAfter ? [] : [`HTTP ${r.status}, Retry-After ${r.retryAfter}`])], ['LV-INT-027']);
  });
  await attempt('File polling', 'Server full', async () => refused('File polling', 'Server full',
    codeOf(await call('POST', `/api/v1/integrations/${pollingId}/polling/run-now`)), ['LV-INT-027']));
  await attempt('Scanner bridge (simulated)', 'Server full', async () => {
    await startBridge('full', ENTITY);
    refused('Scanner bridge (simulated)', 'Server full', (await scan('good.pdf', fresh(doc.good))).codes, ['LV-INT-027']);
  });
  await stop(bridge);
  const withTrigger = drop('good.pdf', fresh(doc.good));
  const without = drop('good.pdf', fresh(doc.good), { trigger: false });
  await sleep(5000);
  const scanNow = await call('POST', '/api/v1/intake/watched-folder/scan');
  for (const [path, name] of [['Watched folder, with trigger', withTrigger], ['Watched folder, no trigger', without]]) {
    await attempt(path, 'Server full', async () => {
      if (!existsSync(join(watched, name))) throw new Error('the folder was taken');
      refused(path, 'Server full', codeOf(scanNow), ['LV-INT-027']);
    });
  }
  also('Server full: no case was made', sql('select count(*) from document_packages') === cases, 'number of cases unchanged');
  await startApp('again');
  await attempt('Watched folder, with trigger', 'Server full', async () => {
    await until('the waiting folder to be taken', () => !existsSync(join(watched, withTrigger)), 60);
    also('Server full: the folder that waited is taken once there is space', true, 'taken');
  });
}

/** Nothing that was redacted, and no password, in the database, the logs, the answers, the audit export or this output. */
async function noRedactedValue(dumpBefore, dumpAfter) {
  const needles = [];
  for (const v of ['412-55-7531', '4000 0566 5566 5556', '4000-0566-5566-5556', '03/14/1962']) {
    const digits = v.replace(/\D/g, '');
    needles.push(new RegExp(v.replace(/[/]/g, '\\/'), 'g'), new RegExp(`(?<![0-9A-Fa-f])${digits}(?![0-9A-Fa-f])`, 'g'), new RegExp(`(?<![0-9A-Fa-f.])${digits.slice(-4)}(?![0-9A-Fa-f])`, 'g'));
  }
  for (const s of [pdfPassword, wrongPassword]) needles.push(new RegExp(s, 'g'), new RegExp(Buffer.from(s).toString('base64').replace(/=+$/, '').slice(0, 40).replace(/[+/]/g, '\\$&'), 'g'));
  const count = (text) => needles.map((n) => (text.match(n) ?? []).length);
  // The search must find what it looks for, or "0 matches" says nothing.
  const planted = ['412-55-7531', '4000 0566 5566 5556', '4000-0566-5566-5556', '03/14/1962'].flatMap((v) => [v, v.replace(/[^0-9]/g, ''), v.replace(/[^0-9]/g, '').slice(-4)])
    .concat([pdfPassword, wrongPassword].flatMap((x) => [x, Buffer.from(x).toString('base64')]));
  if (count(planted.join(' ; ')).some((n) => n === 0)) throw new Error('the value search does not work');
  const before = count(dumpBefore);
  const found = { database: count(dumpAfter).reduce((a, n, i) => a + Math.max(0, n - before[i]), 0) };
  const sum = (text) => count(text).reduce((a, b) => a + b, 0);
  found.logs = readdirSync(join(run, 'logs')).reduce((a, f) => a + sum(readFileSync(join(run, 'logs', f), 'utf8')), 0);
  found.answers = sum(answers.join('\n'));
  const exported = [];
  for (const format of ['json', 'csv']) {
    const res = await fetch(`${base}/api/v1/audit-records/export?format=${format}&includeRoutineActivity=true`, { headers: { 'X-Tenant-Id': ENTITY, 'X-Actor-Id': ACTOR, 'X-Actor-Roles': 'TENANT_IT_ADMIN' } });
    if (!res.ok) throw new Error(`audit export ${format}: HTTP ${res.status}`);
    exported.push(await res.text());
  }
  found.audit = sum(exported.join('\n'));
  found.summary = sum(summaryText());
  const total = Object.values(found).reduce((a, b) => a + b, 0);
  also('No redacted value or password anywhere', total === 0 && exported[0].length > 1000, Object.entries(found).map(([k, n]) => `${k} ${n}`).join(', '));
}

function summaryText() {
  const lines = ['', 'RESULT', ''];
  for (const path of PATHS) {
    lines.push(path);
    for (const check of CHECKS) {
      const cell = table.get(path).get(check) ?? { ok: false, text: 'not run' };
      lines.push(`   ${cell.ok ? 'PASS' : 'FAIL'}  ${check.padEnd(20)} ${cell.text}`);
    }
  }
  lines.push('Everything together', ...extra.map((e) => `   ${e.ok ? 'PASS' : 'FAIL'}  ${e.name}: ${e.text}`));
  return lines.join('\n');
}
const failures = () => PATHS.flatMap((p) => CHECKS.map((c) => table.get(p).get(c)?.ok === true)).concat(extra.map((e) => e.ok)).filter((ok) => !ok).length;
const dump = () => execFileSync('docker', ['exec', container, 'pg_dump', '-U', 'securedoc', '-d', 'securedoc'], { encoding: 'utf8', maxBuffer: 512 * MB });

let started = false; let stoppedEarly = false;
try {
  for (const j of [jar, bridgeJar]) if (!existsSync(j)) throw new Error(`not built yet: ${j} (see README.md, "Before the first run")`);
  if (execFileSync('docker', ['ps', '-aq', '--filter', `name=^/${container}$`], { encoding: 'utf8' }).trim()) throw new Error(`the container ${container} exists already (another run?)`);
  console.log(`Run folder: ${run}`);
  execFileSync('docker', ['run', '-d', '--name', container, '-e', 'POSTGRES_DB=securedoc', '-e', 'POSTGRES_USER=securedoc',
    '-e', 'POSTGRES_PASSWORD', '-p', `127.0.0.1:${dbPort}:5432`, 'postgres:16-alpine'], { stdio: 'ignore', env: { ...process.env, POSTGRES_PASSWORD: dbPassword } });
  started = true;
  for (let i = 0; i < 60 && spawnSync('docker', ['exec', container, 'pg_isready', '-U', 'securedoc'], { stdio: 'ignore' }).status !== 0; i++) await sleep(1000);
  await sleep(3000);
  const init = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command',
    `. '${join(root, 'scripts', 'lib', 'LexaVaultTools.ps1')}'; Initialize-LexaVaultDatabase -Jar '${jar}' -DbUrl 'jdbc:postgresql://localhost:${dbPort}/securedoc' -DbUser 'securedoc' -DbPassword $env:INTAKE_DB_PASSWORD -StateDir '${join(run, 'install')}'`],
    { env: { ...process.env, INTAKE_DB_PASSWORD: dbPassword }, encoding: 'utf8' });
  if (init.status !== 0) throw new Error('the install steps failed');
  makeDocuments();
  const dumpBefore = dump();
  await new Promise((r) => ai.listen(aiPort, '127.0.0.1', r));
  await new Promise((r) => source.listen(sourcePort, '127.0.0.1', r));
  console.log('Starting the app...');
  await startApp('main');
  for (const part of [manual, watchedWithTrigger, watchedNoTrigger, polling, scanner, api, folderOfAnUnknownEntity, serverFull]) {
    try { await part(); } catch (e) { also(`${part.name} did not finish`, false, e.message); }
  }
  await noRedactedValue(dumpBefore, dump());
} catch (e) {
  stoppedEarly = true;
  console.error(`STOPPED: ${e.message}`);
} finally {
  await stop(bridge);
  await stop(app);
  for (const s of [ai, source]) { s.close(); s.closeAllConnections(); }
  if (started) spawnSync('docker', ['rm', '-f', container], { stdio: 'ignore' });
  writeFileSync(answersFile, answers.join('\n'));
}
const bad = failures();
if (!stoppedEarly) {
  console.log(summaryText());
  console.log(`\n${bad === 0 ? 'ALL PASS' : `${bad} FAILED`}`);
  writeFileSync(join(run, 'summary.txt'), summaryText());
}
console.log(`Details: ${run}`);
process.exit(stoppedEarly || bad ? 1 : 0);
