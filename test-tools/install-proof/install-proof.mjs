/**
 * Task 0114: LexaVault's install proof. Installs LexaVault from its package alone, step by step as docs/install/INSTALL.md
 * says, on a clean machine (GitHub's Linux and Windows runners), and checks the first-install criteria a machine can:
 *
 *   1. no secret is in the package;
 *   2. the schema step runs with an administrator account (the schema owner), and gives the everyday account its rights;
 *   3. setup and the first entity, then LexaVault runs with the everyday account;
 *   4. one document end to end (intake, redaction with a stand-in AI server, no GPU, delivery to a folder);
 *   5. stop and start again: the case is still there;
 *   6. a backup of the database restored to a test copy.
 *
 *   node test-tools/install-proof/install-proof.mjs <unpacked package folder>
 *
 * Needs PostgreSQL's command-line tools on the PATH and an administrator login in PGHOST, PGPORT, PGUSER and PGPASSWORD
 * (the runner's own PostgreSQL); Java 21 (java and jar) and Node 22. Everything it makes (accounts, databases, folders) is
 * new and named for this run. No password is printed or written to a file. Codes, counts and times only.
 */
import { spawn, spawnSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { appendFileSync, existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

const pkg = resolve(process.argv[2] ?? '');
if (!existsSync(join(pkg, 'lexavault.jar'))) {
  console.error(`Not a LexaVault package folder (no lexavault.jar): ${pkg}`);
  process.exit(2);
}
const jar = join(pkg, 'lexavault.jar');
const setupJar = join(pkg, 'lexavault-setup.jar');
const demo = join(pkg, 'install', 'demo');
const run = join(tmpdir(), 'lexavault-install-proof', new Date().toISOString().replace(/[:.]/g, '-'));
for (const d of ['work', 'storage', 'export', 'logs', 'tmp', 'scan', 'backup']) mkdirSync(join(run, d), { recursive: true });

const [appPort, aiPort] = [18114, 11514];
const base = `http://localhost:${appPort}`;
const ENTITY = '30216241-b724-5a3a-aef3-3034fd546d17'; // the demo entity of install/demo (slot ENT-DEMO-0001)
const ACTOR = '00000000-0000-0000-0000-000000000114';
const MODEL = 'qwen3.5:9b';
const VISION = 'qwen3-vl:8b-instruct';
const DIGEST = 'b'.repeat(64); // the stand-in AI server's models; the app is told to expect it
const DB = 'lexavault_proof';
const COPY = 'lexavault_proof_copy';
const OWNER = 'lexavault_owner';
const APP = 'lexavault_app';
const [ownerPassword, appPassword] = [randomUUID().replaceAll('-', ''), randomUUID().replaceAll('-', '')];
const dbUrl = (db) => `jdbc:postgresql://${process.env.PGHOST ?? 'localhost'}:${process.env.PGPORT ?? '5432'}/${db}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const results = [];
const started = Date.now();

/* ---------- reporting ---------- */
async function step(name, body) {
  const t0 = Date.now();
  console.log(`\n=== ${name}`);
  try {
    const detail = await body();
    results.push({ name, ok: true, seconds: Math.round((Date.now() - t0) / 1000), detail: detail ?? '' });
    console.log(`PASS ${name}${detail ? ` - ${detail}` : ''}`);
  } catch (e) {
    results.push({ name, ok: false, seconds: Math.round((Date.now() - t0) / 1000), detail: e.message });
    console.log(`FAIL ${name} - ${e.message}`);
    throw e;
  }
}
function summary() {
  const total = Math.round((Date.now() - started) / 1000);
  const lines = ['| Step | Result | Seconds | Detail |', '|---|---|---|---|',
    ...results.map((r) => `| ${r.name} | ${r.ok ? 'PASS' : 'FAIL'} | ${r.seconds} | ${r.detail.replaceAll('|', '/')} |`),
    '', `Install (steps 2 and 3): ${results.filter((r) => /^[23]\./.test(r.name)).reduce((s, r) => s + r.seconds, 0)} s · whole proof: ${total} s`];
  console.log(`\n${lines.join('\n')}`);
  if (process.env.GITHUB_STEP_SUMMARY) {
    appendFileSync(process.env.GITHUB_STEP_SUMMARY, `## LexaVault install proof (${process.platform})\n\n${lines.join('\n')}\n`);
  }
}

/* ---------- commands ---------- */
/** A command that must succeed; its output is shown (it never holds a password: those are in the environment only). */
function must(command, args, env = {}, { quiet = false } = {}) {
  const r = spawnSync(command, args, { env: { ...process.env, ...env }, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  if (!quiet && r.stdout) process.stdout.write(r.stdout);
  if (r.stderr) process.stdout.write(r.stderr);
  if (r.status !== 0) throw new Error(`${command} ${args[0] ?? ''} exited ${r.status ?? r.error?.code}`);
  return r.stdout ?? '';
}
const psql = (sql, db = 'postgres') => must('psql', ['-v', 'ON_ERROR_STOP=1', '-qtA', '-d', db, '-c', sql], {}, { quiet: true }).trim();
const javaMain = (main, args, env) => must('java', ['-cp', jar, `-Dloader.main=${main}`,
  'org.springframework.boot.loader.launch.PropertiesLauncher', ...args], env);

/* ---------- 1. no secret in the package ---------- */
const SECRET_PATTERNS = [
  [/-----BEGIN [A-Z ]*PRIVATE KEY-----/, 'a private key'],
  [/AKIA[0-9A-Z]{16}/, 'a cloud access key'],
  [/gh[pousr]_[A-Za-z0-9]{36}/, 'a GitHub token'],
  [/xox[abprs]-[A-Za-z0-9-]{10,}/, 'a chat token'],
  [/(password|secret|token)\s*[:=]\s*["']?([A-Za-z0-9+/_\-]{12,})/i, 'a value after password, secret or token'],
];
/**
 * Values that are no secret, by their SHA-256 (never the value here): the local development database's default password
 * in application-local.yml, the same as docker/compose/postgres.yml in the public repository. A production package should
 * leave application-local.yml out (0114 report, for 0088).
 */
const NOT_SECRETS = new Set(['9727bcffc4909967fcf8f6d9a748151e3fd6c4b691afaef5e4693d8ad03a94bb']);
const SECRET_FILES = /\.(pem|key|p12|pfx|jks|keystore)$|(^|[\\/])\.env(\.|$)/i;
function filesUnder(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) filesUnder(path, out); else out.push(path);
  }
  return out;
}
function scanForSecrets() {
  // The package's own files, and LexaVault's own classes and settings inside its jars (the libraries in a jar are not ours).
  const unpacked = join(run, 'scan');
  for (const j of ['lexavault.jar', 'lexavault-setup.jar']) {
    const into = join(unpacked, j);
    mkdirSync(into, { recursive: true });
    const r = spawnSync('jar', ['xf', join(pkg, j), 'BOOT-INF/classes', 'META-INF'], { cwd: into, encoding: 'utf8' });
    if (r.status !== 0) throw new Error(`jar xf ${j} exited ${r.status}`);
  }
  const files = [...filesUnder(pkg).filter((f) => !f.endsWith('.jar')), ...filesUnder(unpacked)];
  const found = [];
  for (const f of files) {
    const shown = f.startsWith(unpacked) ? f.slice(unpacked.length + 1) : f.slice(pkg.length + 1);
    if (SECRET_FILES.test(f)) found.push(`${shown}: a key or settings file`);
    if (statSync(f).size > 5 * 1024 * 1024) continue;
    const text = readFileSync(f, 'latin1');
    for (const [pattern, what] of SECRET_PATTERNS) {
      const flags = pattern.flags.includes('g') ? pattern.flags : `${pattern.flags}g`;
      for (const m of text.matchAll(new RegExp(pattern.source, flags))) {
        const value = m.length > 2 ? m[m.length - 1] : null;
        if (value && NOT_SECRETS.has(createHash('sha256').update(value).digest('hex'))) continue;
        found.push(`${shown}: ${what}`);
        break;
      }
    }
  }
  if (found.length > 0) throw new Error(`secrets in the package: ${found.slice(0, 10).join('; ')}`);
  return `${files.length} files read`;
}

/* ---------- the stand-in AI server (no GPU on the runners) ---------- */
const VALUES = { ssn: '412-55-7531', dob: '03/14/1962' };
const aiAnswer = JSON.stringify({ done: true, done_reason: 'stop', message: { content: JSON.stringify({ findings: [
  { pageNumber: 1, entityType: 'SSN', matchedText: VALUES.ssn, contextText: '', confidence: 0.97 },
  { pageNumber: 1, entityType: 'DATE_OF_BIRTH', matchedText: VALUES.dob, contextText: '', confidence: 0.97 }] }) } });
const ai = createServer((req, res) => {
  req.resume().on('end', () => res.writeHead(200, { 'Content-Type': 'application/json' }).end(req.url === '/api/chat'
    ? aiAnswer : JSON.stringify({ models: [MODEL, VISION].map((m) => ({ name: m, model: m, digest: DIGEST })) })));
});

/** A one-page PDF with the fake values as text (no library). */
function fakePdf() {
  const text = `BT /F1 12 Tf 72 720 Td (Borrower SSN: ${VALUES.ssn}) Tj 0 -20 Td (Date of birth: ${VALUES.dob}) Tj ET`;
  const objects = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>',
    `<< /Length ${text.length} >>\nstream\n${text}\nendstream`, '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'];
  let body = '%PDF-1.4\n';
  const offsets = [];
  objects.forEach((o, i) => { offsets.push(body.length); body += `${i + 1} 0 obj\n${o}\nendobj\n`; });
  const xref = body.length;
  body += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n${offsets.map((o) => `${String(o).padStart(10, '0')} 00000 n \n`).join('')}`;
  body += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(body, 'latin1');
}

/* ---------- the app ---------- */
let app;
function appEnv() {
  return {
    SPRING_DATASOURCE_URL: dbUrl(DB), SPRING_DATASOURCE_USERNAME: APP, SPRING_DATASOURCE_PASSWORD: appPassword,
    PLATFORM_TENANT_CONFIG: `optional:file:${join(run, 'work', 'platform-tenant.properties')}`,
    // As an install: the schema step made the tables; nothing runs at start (the local profile would run Liquibase).
    SPRING_LIQUIBASE_ENABLED: 'false',
    SERVER_PORT: String(appPort), SECUREDOC_NODE_ID: 'install-proof',
    SECUREDOC_AI_PROVIDER: 'ollama', SECUREDOC_AI_OLLAMA_BASE_URL: `http://localhost:${aiPort}`,
    SECUREDOC_AI_VISION_BASE_URL: `http://localhost:${aiPort}`, SECUREDOC_AI_MODEL: MODEL,
    SECUREDOC_AI_MODEL_DIGEST: DIGEST, SECUREDOC_AI_VISION_MODEL_DIGEST: DIGEST, SECUREDOC_AI_OLLAMA_PRELOAD_ENABLED: 'false',
    SECUREDOC_STORAGE_LOCAL_ROOT_PATH: join(run, 'storage'), SECUREDOC_DELIVERY_LOCAL_OUTPUT_PATH: join(run, 'export'),
    SECUREDOC_PRODUCT_TELEMETRY_OUTPUT_PATH: join(run, 'logs', 'telemetry.jsonl'),
    SECUREDOC_PROCESSING_PIPELINE_MODE: 'staged', SECUREDOC_PROCESSING_EXPORT_MODE: 'job',
    SECUREDOC_WATCHED_FOLDER_ENABLED: 'false', SECUREDOC_WATCHED_FOLDER_SCHEDULING_ENABLED: 'false',
  };
}
async function startApp(label) {
  const log = join(run, 'logs', `app-${label}.log`);
  writeFileSync(log, '');
  app = spawn('java', [`-Djava.io.tmpdir=${join(run, 'tmp')}`, '-jar', jar, '--spring.profiles.active=local',
    '--spring.main.banner-mode=off'], { cwd: join(run, 'work'), env: { ...process.env, ...appEnv() } });
  app.stdout.on('data', (d) => appendFileSync(log, d));
  app.stderr.on('data', (d) => appendFileSync(log, d));
  let last = 'no answer';
  for (const end = Date.now() + 180_000; Date.now() < end; await sleep(1000)) {
    if (app.exitCode !== null) throw new Error(`the app stopped at start (exit ${app.exitCode}); log: ${tail(log)}`);
    try {
      // Live and answering. Not readiness: with the demo sign-in file its login-system check is down (a placeholder
      // login system); the sign-in with a throwaway login system is the proof's open part (0114 report).
      const live = (await fetch(`${base}/actuator/health/liveness`)).status;
      const api = await fetch(`${base}/api/v1/packages`, { headers: { 'X-Tenant-Id': ENTITY, 'X-Actor-Id': ACTOR,
        'X-Actor-Roles': 'DOCUMENT_PROCESSOR' } });
      last = `liveness ${live}, cases ${api.status} ${(await api.text()).slice(0, 160)}`;
      if (api.status === 200) return; // liveness is not exposed in every profile; answering the API is what counts
    } catch { /* not up yet */ }
  }
  throw new Error(`the app was not ready within 180 s (${last}); log: ${tail(log)}`);
}
const tail = (file) => readFileSync(file, 'utf8').split(/\r?\n/).filter((l) => /ERROR|LV_STARTUP|Exception|refused/i.test(l))
  .slice(-5).map((l) => l.slice(0, 300)).join(' / ');
async function stopApp() {
  if (!app || app.exitCode !== null) return;
  const gone = new Promise((r) => app.once('exit', r));
  app.kill();
  await Promise.race([gone, sleep(30_000)]);
  if (app.exitCode === null && app.signalCode === null) { app.kill('SIGKILL'); await gone; }
}
async function call(method, path, body) {
  const raw = body instanceof FormData;
  const res = await fetch(base + path, {
    method,
    headers: { 'X-Tenant-Id': ENTITY, 'X-Actor-Id': ACTOR, 'X-Actor-Roles': 'DOCUMENT_PROCESSOR,TENANT_IT_ADMIN',
      ...(body && !raw ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? (raw ? body : JSON.stringify(body)) : undefined,
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* not JSON */ }
  if (res.status >= 300) throw new Error(`${method} ${path.replace(/[0-9a-f-]{36}/g, '{id}')}: HTTP ${res.status} ${json?.code ?? ''}`);
  return json;
}
async function until(what, check, seconds) {
  for (const end = Date.now() + seconds * 1000; Date.now() < end; await sleep(1000)) {
    const value = await check();
    if (value) return value;
  }
  throw new Error(`waited ${seconds} s for ${what}`);
}

/* ---------- the proof ---------- */
let packageId;
try {
  await step('1. No secret in the package', scanForSecrets);

  await step('2. Database, accounts and the schema step (administrator account)', () => {
    psql(`create role ${OWNER} login password '${ownerPassword}'`);
    psql(`create role ${APP} login password '${appPassword}'`);
    psql(`create database ${DB} owner ${OWNER}`);
    psql(`revoke all on database ${DB} from public`);
    psql(`grant connect on database ${DB} to ${APP}`);
    javaMain('com.securedoc.install.SchemaStep', ['apply', '--runtime-user', APP], {
      LEXAVAULT_SCHEMA_DB_URL: dbUrl(DB), LEXAVAULT_SCHEMA_DB_USER: OWNER, LEXAVAULT_SCHEMA_DB_PASSWORD: ownerPassword });
    return `schema owner ${OWNER}, everyday account ${APP}`;
  });

  await step('3. Setup and the first entity (everyday account), then LexaVault starts with it', async () => {
    must('java', ['-cp', setupJar, '-Dloader.main=com.documentplatform.setup.SetupCommand',
      'org.springframework.boot.loader.launch.PropertiesLauncher', 'apply', '--licence', join(demo, 'licence-demo.json'),
      '--signin', join(demo, 'signin-demo.json'), '--product', 'LEXAVAULT',
      '--product-roles', join(demo, 'product-roles-development.json'), '--no-customer-keys',
      '--config-out', join(run, 'work', 'platform-tenant.properties'), '--run-log', join(run, 'logs', 'setup-runs.log')],
    { PLATFORM_SETUP_DB_URL: dbUrl(DB), PLATFORM_SETUP_DB_USER: APP, PLATFORM_SETUP_DB_PASSWORD: appPassword });
    javaMain('com.securedoc.install.DemoStep', ['apply'], {
      LEXAVAULT_DEMO_DB_URL: dbUrl(DB), LEXAVAULT_DEMO_DB_USER: APP, LEXAVAULT_DEMO_DB_PASSWORD: appPassword });
    await new Promise((r) => ai.listen(aiPort, '127.0.0.1', r));
    await startApp('first');
    return 'ready';
  });

  await step('4. One document end to end (intake, redaction, delivery)', async () => {
    // The demo entity's case fields: a 12-digit contract number, an 11-digit member number, a service entity.
    const identifiers = { contract_number: `114${String(Date.now()).slice(-9)}`, member_number: '71140000001', service_entity: '001' };
    packageId = (await call('POST', '/api/v1/packages', { packageType: 'MANUAL_UPLOAD', metadata: { source: 'install-proof' },
      businessIdentifiers: identifiers })).packageId;
    const form = new FormData();
    form.append('file', new Blob([fakePdf()], { type: 'application/pdf' }), 'install-proof.pdf');
    await call('POST', `/api/v1/packages/${packageId}/documents`, form);
    await call('POST', `/api/v1/packages/${packageId}/submit`);
    const status = await until('the case to be delivered', async () => {
      const s = (await call('GET', `/api/v1/packages/${packageId}`)).status;
      if (['FAILED', 'PENDING_REVIEW', 'IN_REVIEW'].includes(s)) {
        const detail = await call('GET', `/api/v1/packages/${packageId}`);
        const codes = JSON.stringify(detail).match(/LV-[A-Z]{2,3}-d{3}|[A-Z]+_[A-Z_]{4,}/g) ?? [];
        throw new Error(`the case went to ${s} (${[...new Set(codes)].slice(0, 8).join(' ')}); log: ${tail(join(run, 'logs', 'app-first.log'))}`);
      }
      return s === 'COMPLETED' ? s : null;
    }, 240);
    const delivered = filesUnder(join(run, 'export')).filter((f) => f.endsWith('.pdf'));
    if (delivered.length === 0) throw new Error('no PDF in the delivery folder');
    for (const f of delivered) {
      const bytes = readFileSync(f, 'latin1');
      if (bytes.includes(VALUES.ssn) || bytes.includes(VALUES.dob)) throw new Error('a redacted value is in the delivered file');
    }
    return `${status}, ${delivered.length} delivered file(s), no redacted value in them`;
  });

  await step('5. Stop and start again', async () => {
    await stopApp();
    await startApp('again');
    const s = (await call('GET', `/api/v1/packages/${packageId}`)).status;
    if (s !== 'COMPLETED') throw new Error(`after the restart the case is ${s}`);
    return 'the case is still COMPLETED';
  });

  await step('6. Backup, and its restore to a test copy', async () => {
    const backup = join(run, 'backup', 'lexavault.dump');
    must('pg_dump', ['-Fc', '-f', backup, DB]);
    psql(`create database ${COPY} owner ${OWNER}`);
    must('pg_restore', ['--no-owner', '--role', OWNER, '-d', COPY, backup]);
    const count = (db) => psql('select count(*) from document_packages', db);
    if (count(DB) !== count(COPY)) throw new Error(`cases: ${count(DB)} in the database, ${count(COPY)} in the copy`);
    javaMain('com.securedoc.install.SchemaStep', ['status'], {
      LEXAVAULT_SCHEMA_DB_URL: dbUrl(COPY), LEXAVAULT_SCHEMA_DB_USER: OWNER, LEXAVAULT_SCHEMA_DB_PASSWORD: ownerPassword });
    return `${count(COPY)} case(s) in the copy; the schema step reads it`;
  });
} catch {
  process.exitCode = 1;
} finally {
  await stopApp();
  ai.close();
  summary();
  console.log(`\nRun folder: ${run} (logs, delivered files; fake values only)`);
}
