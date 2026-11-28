/**
 * Task 0114: LexaVault's install proof. Installs LexaVault from its package alone, step by step as docs/install/INSTALL.md
 * says, on a clean machine (GitHub's Linux and Windows runners), and checks the first-install criteria a machine can:
 *
 *   1. no secret is in the package;
 *   -  a throwaway login system (Keycloak from its own zip), with one person and one administrator, and a throwaway key
 *      service (Vault from its own zip, development mode, Transit);
 *   2. the schema step runs with an administrator account (the schema owner), and gives the everyday account its rights;
 *   3. setup with the sign-in file, the first entity by the install command, then LexaVault runs with the everyday account;
 *   4. a person signs in; one document end to end (intake, redaction with a stand-in AI server since there is no GPU, one
 *      finding decided in review, delivery to a folder), and no redacted value in the delivered file;
 *   5. stop and start again: the case is still there;
 *   6. a backup of the database restored to a test copy;
 *   7. an upgrade from the previous build: the previous package installed in a database of its own with a case in it, then
 *      upgraded with this package (stop, schema step, start); the case is still there and a new one goes through.
 *
 *   node test-tools/install-proof/install-proof.mjs <package folder> <previous package folder>
 *
 * Needs PostgreSQL's command-line tools on the PATH and an administrator login in PGHOST, PGPORT, PGUSER and PGPASSWORD
 * (the runner's own PostgreSQL); KEYCLOAK_HOME (an unpacked Keycloak zip) and VAULT_BIN (Vault's program); Java 21 (java and jar) and Node 22. Everything
 * it makes (accounts, databases, folders, the login system's realm and people) is new and named for this run. No password
 * or token is printed or written to a file. Codes, counts and times only.
 */
import { spawn, spawnSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { appendFileSync, existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

const isPackage = (dir) => dir && existsSync(join(dir, 'lexavault.jar'));
const pkg = resolve(process.argv[2] ?? '');
const previousPkg = process.argv[3] ? resolve(process.argv[3]) : null;
if (!isPackage(pkg)) {
  console.error(`Not a LexaVault package folder (no lexavault.jar): ${pkg}`);
  process.exit(2);
}
const keycloakHome = process.env.KEYCLOAK_HOME ? resolve(process.env.KEYCLOAK_HOME) : '';
if (!existsSync(join(keycloakHome, 'bin'))) {
  console.error('KEYCLOAK_HOME does not name an unpacked Keycloak (no bin folder).');
  process.exit(2);
}
const vaultBin = process.env.VAULT_BIN ? resolve(process.env.VAULT_BIN) : '';
if (!existsSync(vaultBin)) {
  console.error('VAULT_BIN does not name the Vault program.');
  process.exit(2);
}
const windows = process.platform === 'win32';
const run = join(tmpdir(), 'lexavault-install-proof', new Date().toISOString().replace(/[:.]/g, '-'));
mkdirSync(join(run, 'logs'), { recursive: true });

const [appPort, aiPort, kcPort, kcManagementPort, vaultPort] = [18114, 11514, 18180, 19114, 18200];
const vaultAddress = `http://127.0.0.1:${vaultPort}`;
const base = `http://localhost:${appPort}`;
const kcBase = `http://localhost:${kcPort}`;
const [REALM, PROVIDER, AUDIENCE, WEB] = ['lv114', 'proof-kc', 'lexavault-api', 'lexavault-web'];
const issuer = `${kcBase}/realms/${REALM}`;
const [SLOT, ENTITY_NAME] = ['ENT-DEMO-0001', 'Install Proof Entity']; // a slot of the demo licence
const MODEL = 'qwen3.5:9b';
const VISION = 'qwen3-vl:8b-instruct';
const DIGEST = 'b'.repeat(64); // the stand-in AI server's models; the app is told to expect it
const [DB, COPY, UPGRADE] = ['lexavault_proof', 'lexavault_proof_copy', 'lexavault_proof_upgrade'];
const OWNER = 'lexavault_owner';
const APP = 'lexavault_app';
const secret = () => randomUUID().replaceAll('-', '');
const [ownerPassword, appPassword, kcAdminPassword, vaultToken] = [secret(), secret(), secret(), secret()];
// The login system's people: groups of the demo role mapping (install/demo/role-mapping-lexavault-demo.csv).
const people = {
  person: { username: 'proof-person', password: secret(), groups: ['BusinessUsers', 'ElevatedUsers'] },
  admin: { username: 'proof-admin', password: secret(), groups: ['BusinessAdmins', 'TenantITAdmins'] },
};
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
  const version = (dir) => (dir && existsSync(join(dir, 'VERSION')) ? readFileSync(join(dir, 'VERSION'), 'utf8').split(/\r?\n/)[0] : 'none');
  const kind = process.env.LEXAVAULT_BUILD_KIND ?? 'development';
  const lines = [`Package: ${version(pkg)} (${kind} build${kind === 'development' ? ', demo licence' : ''}) · previous: ${version(previousPkg)}`, '',
    '| Step | Result | Seconds | Detail |', '|---|---|---|---|',
    ...results.map((r) => `| ${r.name} | ${r.ok ? 'PASS' : 'FAIL'} | ${r.seconds} | ${r.detail.replaceAll('|', '/')} |`),
    '', `Install (steps 2 and 3): ${results.filter((r) => /^[23]\./.test(r.name)).reduce((s, r) => s + r.seconds, 0)} s · whole proof: ${total} s`];
  console.log(`\n${lines.join('\n')}`);
  if (process.env.GITHUB_STEP_SUMMARY) {
    appendFileSync(process.env.GITHUB_STEP_SUMMARY, `## LexaVault install proof (${process.platform})\n\n${lines.join('\n')}\n`);
  }
}

/* ---------- commands ---------- */
/** A command that must succeed; its output is shown (it never holds a password: those are in the environment only). */
function must(command, args, env = {}, { quiet = false, cwd } = {}) {
  const r = spawnSync(command, args, { cwd, env: { ...process.env, ...env }, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  if (!quiet && r.stdout) process.stdout.write(r.stdout);
  if (r.stderr) process.stdout.write(r.stderr);
  if (r.status !== 0 && quiet && r.stdout) {
    // Why it failed: the error lines only (Spring's start report names the setting or the check, never a password).
    const why = r.stdout.split(/\r?\n/).filter((l) => /ERROR|Exception|LV[-_][A-Z]|Description:|Reason:|Action:|^\s{2,}\S.*(property|bean|config)/i.test(l));
    console.log(why.slice(-25).map((l) => l.slice(0, 400)).join('\n'));
  }
  if (r.status !== 0) throw new Error(`${command} ${args.find((a) => !a.startsWith('-')) ?? ''} exited ${r.status ?? r.error?.code}`);
  return r.stdout ?? '';
}
const psql = (sql, db = 'postgres') => must('psql', ['-v', 'ON_ERROR_STOP=1', '-qtA', '-d', db, '-c', sql], {}, { quiet: true }).trim();
const javaMain = (jar, main, args, env) => must('java', ['-cp', jar, `-Dloader.main=${main}`,
  'org.springframework.boot.loader.launch.PropertiesLauncher', ...args], env);
/** Ends a process and what it started (Keycloak's start script starts Java). */
function endTree(p) {
  if (!p || p.exitCode !== null || p.signalCode !== null) return;
  if (windows) spawnSync('taskkill', ['/PID', String(p.pid), '/T', '/F'], { stdio: 'ignore' });
  else try { process.kill(-p.pid, 'SIGTERM'); } catch { p.kill('SIGTERM'); }
}

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

/* ---------- the throwaway login system ---------- */
let keycloak;
function startKeycloak() {
  const log = join(run, 'logs', 'keycloak.log');
  writeFileSync(log, '');
  const script = join(keycloakHome, 'bin', windows ? 'kc.bat' : 'kc.sh');
  keycloak = spawn(script, ['start-dev', `--http-port=${kcPort}`, `--http-management-port=${kcManagementPort}`,
    `--hostname=${kcBase}`], { shell: windows, detached: !windows,
    env: { ...process.env, KC_BOOTSTRAP_ADMIN_USERNAME: 'admin', KC_BOOTSTRAP_ADMIN_PASSWORD: kcAdminPassword } });
  keycloak.stdout.on('data', (d) => appendFileSync(log, d));
  keycloak.stderr.on('data', (d) => appendFileSync(log, d));
}
async function form(url, fields) {
  const res = await fetch(url, { method: 'POST', body: new URLSearchParams(fields) });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(`the login system refused a token (HTTP ${res.status} ${json.error ?? ''})`);
  return json;
}
async function makeRealm() {
  await until('the login system', async () => {
    if (keycloak.exitCode !== null) throw new Error(`the login system stopped (exit ${keycloak.exitCode})`);
    try { return (await fetch(`${kcBase}/realms/master`)).ok; } catch { return false; }
  }, 240);
  const admin = (await form(`${kcBase}/realms/master/protocol/openid-connect/token`,
    { grant_type: 'password', client_id: 'admin-cli', username: 'admin', password: kcAdminPassword })).access_token;
  const mapper = (name, protocolMapper, config) => ({ name, protocol: 'openid-connect', protocolMapper, config });
  const user = ({ username, password, groups }) => ({ username, enabled: true, emailVerified: true, email: `${username}@example.test`,
    firstName: 'Proof', lastName: username.split('-')[1], groups, credentials: [{ type: 'password', value: password, temporary: false }] });
  const res = await fetch(`${kcBase}/admin/realms`, { method: 'POST', headers: { Authorization: `Bearer ${admin}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ realm: REALM, enabled: true, sslRequired: 'none', accessTokenLifespan: 900,
      groups: [...new Set(Object.values(people).flatMap((p) => p.groups))].map((name) => ({ name })),
      users: Object.values(people).map(user),
      clients: [{ clientId: WEB, publicClient: true, directAccessGrantsEnabled: true, protocolMappers: [
        mapper('aud', 'oidc-audience-mapper', { 'included.custom.audience': AUDIENCE, 'access.token.claim': 'true' }),
        mapper('groups', 'oidc-group-membership-mapper', { 'claim.name': 'groups', 'full.path': 'false', 'access.token.claim': 'true' })] }] }) });
  if (res.status !== 201) throw new Error(`the realm was not made (HTTP ${res.status})`);
}
const tokens = {};
/** A person's access token from the login system (password grant on the web client), kept for two minutes. */
async function tokenOf(who) {
  const t = tokens[who];
  if (t && Date.now() - t.at < 120_000) return t.value;
  const { username, password } = people[who];
  const value = (await form(`${issuer}/protocol/openid-connect/token`,
    { grant_type: 'password', client_id: WEB, scope: 'openid profile email', username, password })).access_token;
  tokens[who] = { value, at: Date.now() };
  return value;
}

/* ---------- the throwaway key service ---------- */
let vault;
function startVault() {
  const log = join(run, 'logs', 'vault.log');
  writeFileSync(log, '');
  vault = spawn(vaultBin, ['server', '-dev', `-dev-listen-address=127.0.0.1:${vaultPort}`],
    { env: { ...process.env, VAULT_DEV_ROOT_TOKEN_ID: vaultToken } });
  vault.stdout.on('data', (d) => appendFileSync(log, d));
  vault.stderr.on('data', (d) => appendFileSync(log, d));
}
/** Transit, the engine LexaVault's keys live in (KEY_SERVICE.md). */
async function transit() {
  await until('the key service', async () => {
    if (vault.exitCode !== null) throw new Error(`the key service stopped (exit ${vault.exitCode})`);
    try { return (await fetch(`${vaultAddress}/v1/sys/health`)).ok; } catch { return false; }
  }, 60);
  const res = await fetch(`${vaultAddress}/v1/sys/mounts/transit`, { method: 'POST',
    headers: { 'X-Vault-Token': vaultToken, 'Content-Type': 'application/json' }, body: JSON.stringify({ type: 'transit' }) });
  if (!res.ok) throw new Error(`Transit was not mounted (HTTP ${res.status})`);
}

/* ---------- the stand-in AI server (no GPU on the runners) ---------- */
// One value the AI is sure of (redacted without a person) and one it is not (decided in review).
const VALUES = { ssn: '412-55-7531', dob: '03/14/1962' };
const aiAnswer = JSON.stringify({ done: true, done_reason: 'stop', message: { content: JSON.stringify({ findings: [
  { pageNumber: 1, entityType: 'SSN', matchedText: VALUES.ssn, contextText: '', confidence: 0.97 },
  { pageNumber: 1, entityType: 'DATE_OF_BIRTH', matchedText: VALUES.dob, contextText: '', confidence: 0.62 }] }) } });
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

/* ---------- an installation: a package, its database and its folders ---------- */
function installation(name, from, db) {
  const root = join(run, name);
  for (const d of ['work', 'storage', 'export', 'logs', 'tmp']) mkdirSync(join(root, d), { recursive: true });
  return { name, pkg: from, db, root, jar: join(from, 'lexavault.jar'), setupJar: join(from, 'lexavault-setup.jar'),
    demo: join(from, 'install', 'demo'), dbUser: APP, dbPassword: appPassword, note: '' };
}
/** The app's settings, as the install guide names them (step 4), for this installation. */
function appEnv(inst) {
  return {
    SPRING_DATASOURCE_URL: dbUrl(inst.db), SPRING_DATASOURCE_USERNAME: inst.dbUser, SPRING_DATASOURCE_PASSWORD: inst.dbPassword,
    PLATFORM_TENANT_CONFIG: `optional:file:${join(inst.root, 'work', 'platform-tenant.properties')}`,
    SECUREDOC_SECURITY_MODE: 'OIDC', SECUREDOC_SECURITY_OIDC_ISSUER_URI: issuer, SECUREDOC_SECURITY_OIDC_AUDIENCE: AUDIENCE,
    SECUREDOC_SECURITY_OIDC_PROVIDER_ID: PROVIDER,
    SERVER_PORT: String(appPort), SECUREDOC_NODE_ID: `install-proof-${inst.name}`,
    SECUREDOC_AI_PROVIDER: 'ollama', SECUREDOC_AI_OLLAMA_BASE_URL: `http://localhost:${aiPort}`,
    SECUREDOC_AI_VISION_BASE_URL: `http://localhost:${aiPort}`, SECUREDOC_AI_MODEL: MODEL,
    SECUREDOC_AI_MODEL_DIGEST: DIGEST, SECUREDOC_AI_VISION_MODEL_DIGEST: DIGEST, SECUREDOC_AI_OLLAMA_PRELOAD_ENABLED: 'false',
    // The key service (KEY_SERVICE.md). A customer gives LexaVault an AppRole login with platform-crypto-app only; the
    // throwaway development server has its root token.
    PLATFORM_CRYPTO_VAULT_ADDRESS: vaultAddress, PLATFORM_CRYPTO_VAULT_LOGIN: 'TOKEN', PLATFORM_CRYPTO_VAULT_TOKEN: vaultToken,
    SECUREDOC_STORAGE_LOCAL_ROOT_PATH: join(inst.root, 'storage'), PLATFORM_ERASELOG_LOCATION: join(inst.root, 'erase-log'),
    SECUREDOC_DELIVERY_TYPE: 'local', SECUREDOC_DELIVERY_LOCAL_OUTPUT_PATH: join(inst.root, 'export'),
    SECUREDOC_PRODUCT_TELEMETRY_OUTPUT_PATH: join(inst.root, 'logs', 'telemetry.jsonl'),
    SECUREDOC_PROCESSING_PIPELINE_MODE: 'staged', SECUREDOC_PROCESSING_EXPORT_MODE: 'job',
    SECUREDOC_WATCHED_FOLDER_ENABLED: 'false', SECUREDOC_WATCHED_FOLDER_SCHEDULING_ENABLED: 'false',
  };
}
/** The install guide's steps 1 and 2: the database, its two accounts (made once), and the schema step with the owner. */
function databaseAndSchema(inst) {
  if (psql(`select count(*) from pg_roles where rolname = '${OWNER}'`) === '0') {
    psql(`create role ${OWNER} login password '${ownerPassword}'`);
    psql(`create role ${APP} login password '${appPassword}'`);
  }
  psql(`create database ${inst.db} owner ${OWNER}`);
  psql(`revoke all on database ${inst.db} from public`);
  psql(`grant connect on database ${inst.db} to ${APP}`);
  schemaStep(inst);
}
const schemaStep = (inst, jar = inst.jar) => javaMain(jar, 'com.securedoc.install.SchemaStep', ['apply', '--runtime-user', APP], {
  LEXAVAULT_SCHEMA_DB_URL: dbUrl(inst.db), LEXAVAULT_SCHEMA_DB_USER: OWNER, LEXAVAULT_SCHEMA_DB_PASSWORD: ownerPassword });
/** The install guide's step 3: the sign-in file for this login system, setup, and the first entity. */
function setupAndFirstEntity(inst) {
  const signin = join(inst.root, 'work', 'signin');
  javaMain(inst.jar, 'com.securedoc.install.LocalOidcSignin', ['--from', join(inst.demo, 'signin-demo.json'), '--out', signin,
    '--issuer', issuer, '--provider-id', PROVIDER, '--audience', AUDIENCE, '--browser-client-id', WEB], {});
  javaMain(inst.setupJar, 'com.documentplatform.setup.SetupCommand', ['apply', '--licence', join(inst.demo, 'licence-demo.json'),
    '--signin', join(signin, 'signin-local-oidc.json'), '--product', 'LEXAVAULT',
    '--product-roles', join(inst.pkg, 'install', 'product-roles.json'),
    '--config-out', join(inst.root, 'work', 'platform-tenant.properties'), '--run-log', join(inst.root, 'logs', 'setup-runs.log')],
  { PLATFORM_SETUP_DB_URL: dbUrl(inst.db), PLATFORM_SETUP_DB_USER: APP, PLATFORM_SETUP_DB_PASSWORD: appPassword,
    PLATFORM_SETUP_VAULT_ADDR: vaultAddress, PLATFORM_SETUP_VAULT_TOKEN: vaultToken });
  const said = must('java', ['-jar', inst.jar, '--activate-first-entity', `--entity-slot=${SLOT}`, `--entity-name=${ENTITY_NAME}`,
    '--server.port=0', '--server.address=127.0.0.1', '--spring.main.banner-mode=off'], { ...appEnv(inst), SERVER_PORT: '0' },
  { quiet: true, cwd: join(inst.root, 'work') });
  if (!/^ACTIVATED\b/m.test(said)) throw new Error(`the first entity was not activated (${said.split(/\r?\n/).find((l) => /^[A-Z_]{6,}:/.test(l)) ?? 'no answer'})`);
}

/* ---------- the app ---------- */
let app;
const logOf = (inst, label) => join(inst.root, 'logs', `app-${label}.log`);
async function startApp(inst, label, jar = inst.jar) {
  const log = logOf(inst, label);
  writeFileSync(log, '');
  // As the install guide says: java -jar lexavault.jar, no profile; the settings are in the environment.
  app = spawn('java', [`-Djava.io.tmpdir=${join(inst.root, 'tmp')}`, '-jar', jar, '--spring.main.banner-mode=off'],
    { cwd: join(inst.root, 'work'), env: { ...process.env, ...appEnv(inst) } });
  app.stdout.on('data', (d) => appendFileSync(log, d));
  app.stderr.on('data', (d) => appendFileSync(log, d));
  let last = 'no answer';
  for (const end = Date.now() + 180_000; Date.now() < end; await sleep(1000)) {
    if (app.exitCode !== null) throw new Error(`the app stopped at start (exit ${app.exitCode}); log: ${tail(log)}`);
    try {
      // The guide: ready when /actuator/health answers 200 (UP: every required check is up, the login system too).
      const health = await fetch(`${base}/actuator/health`);
      last = `health ${health.status} ${(await health.text()).slice(0, 80)}`;
      if (health.status === 200) return;
    } catch { /* not up yet */ }
  }
  throw new Error(`the app was not ready within 180 s (${last}); log: ${tail(log)}`);
}
const tail = (file) => readFileSync(file, 'utf8').split(/\r?\n/).filter((l) => /ERROR|LV_STARTUP|Exception|refused/i.test(l))
  .slice(-5).map((l) => l.slice(0, 300)).join(' / ');
async function stopApp() {
  if (!app || app.exitCode !== null || app.signalCode !== null) return;
  const gone = new Promise((r) => app.once('exit', r));
  app.kill();
  await Promise.race([gone, sleep(30_000)]);
  if (app.exitCode === null && app.signalCode === null) { app.kill('SIGKILL'); await gone; }
}
/** A call to the app as a signed-in person of the login system. */
async function call(who, method, path, body) {
  const raw = body instanceof FormData;
  const res = await fetch(base + path, {
    method,
    headers: { Authorization: `Bearer ${await tokenOf(who)}`, ...(body && !raw ? { 'Content-Type': 'application/json' } : {}) },
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

/* ---------- a case, as a person works it ---------- */
/** The Business Admin gives the new entity its case field (a 12-digit contract number); returns the field's key. */
async function caseField() {
  const config = await call('admin', 'PUT', '/api/v1/business-admin/identifiers', { identifiers: [{ label: 'Contract Number',
    required: true, primary: true, searchable: true, extractable: true, displayable: true, validationType: 'TEXT', exactLength: 12 }] });
  const key = config?.identifiers?.[0]?.key;
  if (!key) throw new Error('the case field was not saved');
  return key;
}
let contracts = 0;
/** One case with the fake PDF, followed to its delivery. Every finding the review shows is decided as proposed. */
async function oneCase(inst, label, field) {
  const contract = `114${String(Date.now() + contracts++).slice(-9)}`;
  const packageId = (await call('person', 'POST', '/api/v1/packages', { packageType: 'MANUAL_UPLOAD',
    metadata: { source: 'install-proof' }, businessIdentifiers: { [field]: contract } })).packageId;
  const upload = new FormData();
  upload.append('file', new Blob([fakePdf()], { type: 'application/pdf' }), 'install-proof.pdf');
  await call('person', 'POST', `/api/v1/packages/${packageId}/documents`, upload);
  await call('person', 'POST', `/api/v1/packages/${packageId}/submit`);
  const decided = { findings: 0, pages: 0, approved: false };
  await until('the case to be delivered', async () => {
    const s = (await call('person', 'GET', `/api/v1/packages/${packageId}`)).status;
    if (s === 'COMPLETED') return s;
    if (s === 'FAILED') {
      const detail = await call('person', 'GET', `/api/v1/packages/${packageId}`);
      const codes = JSON.stringify(detail).match(/LV-[A-Z]{2,3}-\d{3}|[A-Z]+_[A-Z_]{4,}/g) ?? [];
      throw new Error(`the case went to FAILED (${[...new Set(codes)].slice(0, 8).join(' ')}); log: ${tail(logOf(inst, label))}`);
    }
    if (s === 'PENDING_FINAL_APPROVAL') {
      await call('person', 'POST', `/api/v1/packages/${packageId}/finalize`);
      decided.approved = true;
    } else if (s === 'PENDING_REVIEW' || s === 'IN_REVIEW') {
      const items = (await call('person', 'GET', `/api/v1/review-items?limit=1000&packageId=${packageId}`))?.items ?? [];
      for (const i of items) {
        if (i.entityType === 'UNREAD_PAGE') {
          await call('person', 'POST', `/api/v1/review-items/${i.findingId}/page-checked`, { reviewed: true, boxes: [] });
          decided.pages++;
        } else if (i.hasBoundingBox) {
          await call('person', 'POST', `/api/v1/review-items/${i.findingId}/confirm`, { reason: 'install proof: redact as proposed' });
          decided.findings++;
        } else {
          throw new Error(`a ${i.entityType} finding without a place is in review (a person would have to mark it)`);
        }
      }
      await sleep(2000);
    }
    return null;
  }, 300);
  const delivered = filesUnder(join(inst.root, 'export')).filter((f) => f.endsWith('.pdf'));
  if (delivered.length === 0) throw new Error('no PDF in the delivery folder');
  for (const f of delivered) {
    const bytes = readFileSync(f, 'latin1');
    if (bytes.includes(VALUES.ssn) || bytes.includes(VALUES.dob)) throw new Error('a redacted value is in the delivered file');
  }
  return { packageId, decided, delivered: delivered.length };
}
const statusOf = async (packageId) => (await call('person', 'GET', `/api/v1/packages/${packageId}`)).status;

/* ---------- the proof ---------- */
const fresh = installation('fresh', pkg, DB);
let first;
let field;
try {
  startKeycloak(); // it takes a while to start; step 1 runs meanwhile
  startVault();
  // The AI server is up before anything starts LexaVault (the first-entity command starts it too, and its start checks the
  // AI server's models).
  await new Promise((r) => ai.listen(aiPort, '127.0.0.1', r));
  await step('1. No secret in the package', scanForSecrets);

  await step('A throwaway login system (Keycloak) and key service (Vault), each from its own zip', async () => {
    await makeRealm();
    await transit();
    return `realm ${REALM}: one person (processor and supervisor), one administrator; Transit on the key service`;
  });

  await step('2. Database, accounts and the schema step (administrator account)', () => {
    databaseAndSchema(fresh);
    return `schema owner ${OWNER}, everyday account ${APP}`;
  });

  await step('3. Setup, the first entity, and LexaVault starts (everyday account)', async () => {
    setupAndFirstEntity(fresh);
    await startApp(fresh, 'first');
    return `${SLOT} active as "${ENTITY_NAME}"; health UP`;
  });

  await step('4. Sign-in, and one document end to end (intake, redaction, review, delivery)', async () => {
    const me = await call('person', 'GET', '/api/v1/me');
    if (me?.tenantName !== ENTITY_NAME) throw new Error(`the person signed in to "${me?.tenantName ?? 'no entity'}"`);
    field = await caseField();
    first = await oneCase(fresh, 'first', field);
    if (first.decided.findings < 1) throw new Error('the case went through without a review decision');
    return `signed in to "${ENTITY_NAME}"; COMPLETED after ${first.decided.findings} finding(s) decided in review`
      + `${first.decided.approved ? ' and the final approval' : ''}; ${first.delivered} delivered file(s), no redacted value in them`;
  });

  await step('5. Stop and start again', async () => {
    await stopApp();
    await startApp(fresh, 'again');
    const s = await statusOf(first.packageId);
    if (s !== 'COMPLETED') throw new Error(`after the restart the case is ${s}`);
    return 'the case is still COMPLETED';
  });

  await step('6. Backup, and its restore to a test copy', async () => {
    const backup = join(run, 'lexavault.dump');
    must('pg_dump', ['-Fc', '-f', backup, DB]);
    psql(`create database ${COPY} owner ${OWNER}`);
    must('pg_restore', ['--no-owner', '--role', OWNER, '-d', COPY, backup]);
    const count = (db) => psql('select count(*) from document_packages', db);
    if (count(DB) !== count(COPY)) throw new Error(`cases: ${count(DB)} in the database, ${count(COPY)} in the copy`);
    javaMain(fresh.jar, 'com.securedoc.install.SchemaStep', ['status'], {
      LEXAVAULT_SCHEMA_DB_URL: dbUrl(COPY), LEXAVAULT_SCHEMA_DB_USER: OWNER, LEXAVAULT_SCHEMA_DB_PASSWORD: ownerPassword });
    return `${count(COPY)} case(s) in the copy; the schema step reads it`;
  });

  await step('7. Upgrade from the previous build', async () => {
    if (!isPackage(previousPkg)) throw new Error('no previous package given (second argument)');
    await stopApp();
    // The previous build, installed by its own package in a database of its own.
    const old = installation('upgrade', previousPkg, UPGRADE);
    databaseAndSchema(old);
    if (psql(`select has_table_privilege('${APP}', 'document_packages', 'INSERT')`, UPGRADE) !== 't') {
      // A build before 0114 gave the everyday account no rights on LexaVault's tables: it ran with the owner account.
      Object.assign(old, { dbUser: OWNER, dbPassword: ownerPassword, note: ' (the previous build ran with the owner account: it gave the everyday account no rights on its tables)' });
    }
    setupAndFirstEntity(old);
    await startApp(old, 'previous');
    const oldField = await caseField();
    const before = await oneCase(old, 'previous', oldField);
    await stopApp();
    // The upgrade, as the guide says: every server stopped, the new schema step, then the new build starts.
    schemaStep(old, fresh.jar);
    Object.assign(old, { dbUser: APP, dbPassword: appPassword });
    await startApp(old, 'upgraded', fresh.jar);
    const s = await statusOf(before.packageId);
    if (s !== 'COMPLETED') throw new Error(`after the upgrade the previous build's case is ${s}`);
    const after = await oneCase(old, 'upgraded', oldField);
    return `the previous build's case is still COMPLETED; a new case is ${await statusOf(after.packageId)} on the upgraded install${old.note}`;
  });
} catch {
  process.exitCode = 1;
} finally {
  await stopApp();
  endTree(keycloak);
  endTree(vault);
  ai.close();
  summary();
  console.log(`\nRun folder: ${run} (logs, delivered files; fake values only)`);
}
