/**
 * Task 0093: one rehearsed restore. A back-up is taken, work goes on (one case erased, one made), then the database, the
 * document storage and the key service are lost. All three are restored to new containers, the erase log is replayed, and
 * the result is verified. Then the things that must be refused: an old erase log, lost keys, keys destroyed at exit.
 *
 *   node test-tools/restore-rehearsal/restore-rehearsal.mjs
 *
 * THROWAWAY containers (lexavault-0093-pg-a/-b, lexavault-0093-vault-a/-b/-c) and the built app are started here and removed
 * at the end; nothing else is touched. The Vault containers are new, empty ones with their own unseal keys, kept in memory.
 * The documents hold fake test values only. No password, unseal key or token is printed or written to a file.
 * The commands under "IT:" are the ones of docs/install/backup-and-restore.md.
 */
import { spawn, spawnSync, execFileSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { cpSync, existsSync, mkdirSync, openSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const jar = join(root, 'backend', 'securedoc-api', 'target', 'securedoc-api-0.1.0-SNAPSHOT.jar');
const [dbPort, vaultPort, appPort] = [55993, 18293, 18993];
const names = { pgA: 'lexavault-0093-pg-a', pgB: 'lexavault-0093-pg-b', vA: 'lexavault-0093-vault-a', vB: 'lexavault-0093-vault-b', vC: 'lexavault-0093-vault-c' };
const run = join(tmpdir(), 'lexavault-0093', new Date().toISOString().replace(/[:.]/g, '-'));
const [storage, eraseLog, backup] = [join(run, 'storage'), join(run, 'erase-log'), join(run, 'backup')];
for (const d of [run, backup]) mkdirSync(d, { recursive: true });
const base = `http://127.0.0.1:${appPort}`;
const vaultBase = `http://127.0.0.1:${vaultPort}`;
const url = `jdbc:postgresql://localhost:${dbPort}/securedoc`;
const secret = () => randomUUID().replaceAll('-', '');
const [ownerPw, appPw] = [secret(), secret()];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failed = 0;
const check = (name, ok, detail = '') => { if (!ok) failed++; console.log(ok ? 'PASS' : 'FAIL', name, detail); };
async function timed(name, what) { const t = Date.now(); const r = await what(); console.log(`STEP ${name}: ${((Date.now() - t) / 1000).toFixed(1)} s`); return r; }
let pg = names.pgA;
let vault = names.vA;
const started = [];
const docker = (args, options = {}) => execFileSync('docker', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], ...options });
const sql = (q) => docker(['exec', pg, 'psql', '-U', 'securedoc', '-d', 'securedoc', '-tAc', q]).trim();
async function until(what, ask, seconds) {
  for (const end = Date.now() + seconds * 1000; Date.now() < end;) { const x = await ask(); if (x) return x; await sleep(1000); }
  throw new Error(`waited for ${what}`);
}

/* ---------- PostgreSQL and Vault ---------- */
async function startPg(name) {
  docker(['run', '-d', '--name', name, '-e', 'POSTGRES_DB=securedoc', '-e', 'POSTGRES_USER=securedoc', '-e', 'POSTGRES_PASSWORD',
    '-p', `127.0.0.1:${dbPort}:5432`, 'postgres:16-alpine'], { env: { ...process.env, POSTGRES_PASSWORD: ownerPw } });
  started.push(name); pg = name;
  await until('PostgreSQL', () => spawnSync('docker', ['exec', name, 'pg_isready', '-U', 'securedoc'], { stdio: 'ignore' }).status === 0, 60);
  await sleep(3000);
}
const v = async (method, path, body, token) => {
  const r = await fetch(`${vaultBase}/v1/${path}`, { method, headers: token ? { 'X-Vault-Token': token } : {}, body: body ? JSON.stringify(body) : undefined });
  const t = await r.text();
  if (!r.ok) throw new Error(`Vault ${method} ${path}: HTTP ${r.status}`);
  return t ? JSON.parse(t) : {};
};
/** A new Vault with its own storage (raft), initialised and unsealed. */
async function startVault(name) {
  docker(['run', '-d', '--name', name, '--cap-add', 'IPC_LOCK', '-e', `VAULT_LOCAL_CONFIG=${JSON.stringify({ storage: { raft: { path: '/vault/file', node_id: 'n1' } },
    listener: { tcp: { address: '0.0.0.0:8200', tls_disable: true } }, api_addr: 'http://127.0.0.1:8200', cluster_addr: 'http://127.0.0.1:8201', disable_mlock: true })}`,
    '-p', `127.0.0.1:${vaultPort}:8200`, 'hashicorp/vault:1.21.4', 'server']);
  started.push(name); vault = name;
  await until('Vault', async () => { try { await fetch(`${vaultBase}/v1/sys/seal-status`); return true; } catch { return false; } }, 60);
  const init = await v('PUT', 'sys/init', { secret_shares: 1, secret_threshold: 1 });
  await unseal(init.keys[0]);
  return { key: init.keys[0], root: init.root_token };
}
async function unseal(key) {
  await v('PUT', 'sys/unseal', { key });
  await until('Vault to be ready', async () => (await fetch(`${vaultBase}/v1/sys/health`)).status === 200, 60);
}
const POLICY = {
  'platform-crypto-app': 'path "transit/datakey/plaintext/customer-*" { capabilities = ["update"] }\npath "transit/decrypt/customer-*" { capabilities = ["update"] }\npath "transit/rewrap/customer-*" { capabilities = ["update"] }\npath "transit/hmac/customer-*" { capabilities = ["update"] }\npath "transit/keys/customer-*" { capabilities = ["read"] }\npath "secret/data/platform/*" { capabilities = ["read"] }',
  'platform-crypto-setup': 'path "transit/keys/+" { capabilities = ["create", "update", "read"] }\npath "transit/keys/+/rotate" { capabilities = ["update"] }',
  'platform-crypto-offboard': 'path "transit/keys/+" { capabilities = ["read", "delete"] }\npath "transit/keys/+/config" { capabilities = ["update"] }',
};
async function prepareVault(rootToken) {
  await v('POST', 'sys/mounts/transit', { type: 'transit' }, rootToken);
  await v('POST', 'sys/auth/approle', { type: 'approle' }, rootToken);
  for (const [n, policy] of Object.entries(POLICY)) await v('PUT', `sys/policies/acl/${n}`, { policy }, rootToken);
  await v('POST', 'auth/approle/role/app', { token_policies: ['platform-crypto-app'] }, rootToken);
}
const login = async (policy, rootToken) => (await v('POST', 'auth/token/create', { policies: [policy], ttl: '1h' }, rootToken)).auth.client_token;
const vaultCli = (token, args) => docker(['exec', '-e', 'VAULT_TOKEN', '-e', 'VAULT_ADDR=http://127.0.0.1:8200', vault, 'vault', ...args], { env: { ...process.env, VAULT_TOKEN: token } });

/* ---------- LexaVault ---------- */
const USER = { 'X-Tenant-Id': '30216241-b724-5a3a-aef3-3034fd546d17', 'X-Actor-Id': '00000000-0000-0000-0000-000000000099', 'X-Actor-Roles': 'DOCUMENT_PROCESSOR,TENANT_IT_ADMIN' };
const SUPERVISOR = { ...USER, 'X-Actor-Roles': 'DOCUMENT_SUPERVISOR,DOCUMENT_PROCESSOR' };
let roleId; let secretId; let logLocation = eraseLog; let logNo = 0;
const appEnv = () => ({ ...process.env, SPRING_DATASOURCE_URL: url, SPRING_DATASOURCE_USERNAME: 'lexavault_app', SPRING_DATASOURCE_PASSWORD: appPw,
  SERVER_PORT: String(appPort), SECUREDOC_AI_PROVIDER: 'heuristic', SECUREDOC_AI_OLLAMA_PRELOAD_ENABLED: 'false', SECUREDOC_DELIVERY_LOCAL_OUTPUT_PATH: join(run, 'export'),
  SECUREDOC_LIFECYCLE_PURGE_INTERVAL_MS: '3000', SECUREDOC_WATCHED_FOLDER_ENABLED: 'false', SECUREDOC_WATCHED_FOLDER_SCHEDULING_ENABLED: 'false',
  SECUREDOC_PRODUCT_TELEMETRY_OUTPUT_PATH: join(run, 'telemetry.jsonl'), PLATFORM_ERASELOG_LOCATION: logLocation,
  PLATFORM_CRYPTO_VAULT_ADDRESS: vaultBase, PLATFORM_CRYPTO_VAULT_ROLEID: roleId, PLATFORM_CRYPTO_VAULT_SECRETID: secretId, PLATFORM_CRYPTO_VAULT_TIMEOUT: '3s' });
const options = ['--spring.profiles.active=local', `--securedoc.storage.local.root-path=${storage}`];
let app = null;
async function startApp() {
  const log = openSync(join(run, `app-${++logNo}.log`), 'w');
  app = spawn('java', ['-jar', jar, ...options, '--server.address=127.0.0.1'], { cwd: run, env: appEnv(), stdio: ['ignore', log, log] });
  await until('the app', async () => {
    if (app.exitCode !== null) throw new Error(`the app stopped while starting (app-${logNo}.log)`);
    try { return (await fetch(`${base}/api/v1/me`, { headers: USER })).status < 500; } catch { return false; }
  }, 180);
}
async function stopApp() { if (app && app.exitCode === null) spawnSync('taskkill', ['/PID', String(app.pid), '/T', '/F'], { stdio: 'ignore' }); app = null; await sleep(2000); }
async function call(method, path, body, who = USER) {
  const form = body instanceof FormData;
  const r = await fetch(base + path, { method, headers: { ...who, ...(body && !form ? { 'Content-Type': 'application/json' } : {}) }, body: body ? (form ? body : JSON.stringify(body)) : undefined });
  const text = await r.text(); let json = null; try { json = JSON.parse(text); } catch { /* none */ }
  return { status: r.status, json, code: json?.code ?? '' };
}
function pdf() {
  const text = `BT /F1 12 Tf 60 700 Td (Applicant file of a synthetic person, nothing real in here at all.) Tj 0 -20 Td (Social Security Number: 219-09-9999 and a second line of words.) Tj 0 -20 Td (Reference ${secret().slice(0, 12)}) Tj ET`;
  const objs = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [3 0 R] /Count 1 >>', '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>', `<< /Length ${text.length} >>\nstream\n${text}\nendstream`, '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'];
  let out = '%PDF-1.4\n'; const at = [];
  objs.forEach((o, i) => { at.push(out.length); out += `${i + 1} 0 obj\n${o}\nendobj\n`; });
  const x = out.length;
  return Buffer.from(`${out}xref\n0 6\n0000000000 65535 f \n${at.map((a) => `${String(a).padStart(10, '0')} 00000 n \n`).join('')}trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${x}\n%%EOF\n`, 'latin1');
}
let serial = Math.floor(Math.random() * 9000) + 1000;
const newCase = () => { const n = String(++serial).padStart(5, '0'); return call('POST', '/api/v1/packages', { packageType: 'MANUAL_UPLOAD', metadata: {}, businessIdentifiers: { contract_number: `7700000${n}`, member_number: `700000${n}`, service_entity: '001' } }); };
async function upload(id) { const f = new FormData(); f.append('file', new Blob([pdf()], { type: 'application/pdf' }), 'synthetic.pdf'); return call('POST', `/api/v1/packages/${id}/documents`, f); }
async function wholeCase() {
  const c = await newCase(); if (c.status >= 300) throw new Error(`create: HTTP ${c.status} ${c.code}`);
  const id = c.json.packageId;
  const u = await upload(id); if (u.status >= 300) throw new Error(`upload: HTTP ${u.status} ${u.code}`);
  await call('POST', `/api/v1/packages/${id}/submit`);
  await until('the case to finish', () => sql(`select status from document_packages where id='${id}'`) === 'COMPLETED', 120);
  return id;
}
const files = (d) => existsSync(d) ? readdirSync(d, { recursive: true, withFileTypes: true }).filter((e) => e.isFile()).map((e) => join(e.parentPath ?? e.path, e.name)) : [];
/** What IT's check does: every file the database says is stored is there, with the recorded checksum; and what has no record. */
function storageAgainstDatabase() {
  const rows = sql("select storage_key || '|' || coalesce(ciphertext_sha256,'') from stored_files").split('\n').filter(Boolean).map((l) => l.split('|'));
  const missing = rows.filter(([key]) => !existsSync(join(storage, key)));
  const changed = rows.filter(([key, sum]) => sum && existsSync(join(storage, key)) && createHash('sha256').update(readFileSync(join(storage, key))).digest('hex') !== sum);
  return { rows: rows.length, missing: missing.length, changed: changed.length };
}
function replay(extra = '') {
  // IT: scripts/Invoke-LexaVault-EraseReplay.ps1
  const r = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command',
    `& '${join(root, 'scripts', 'Invoke-LexaVault-EraseReplay.ps1')}' ${extra} -JarPath '${jar}' -AppOptions ${options.map((o) => `'${o}'`).join(',')}; exit $LASTEXITCODE`], { cwd: run, env: appEnv(), encoding: 'utf8' });
  const said = r.stdout.split(/\r?\n/).filter((l) => !l.startsWith('{') && /^(Erase|The erase|\s|Entries|Applied|Checked|Not|NOT)/i.test(l) && /eras|entr|verif|check/i.test(l)).slice(0, 4).join(' / ');
  return { exit: r.status, said };
}

try {
  for (const n of Object.values(names)) if (docker(['ps', '-aq', '--filter', `name=^/${n}$`]).trim()) throw new Error(`${n} exists already`);

  /* ---------- the installation ---------- */
  await startPg(names.pgA);
  const first = await startVault(names.vA);
  await prepareVault(first.root);
  roleId = (await v('GET', 'auth/approle/role/app/role-id', null, first.root)).data.role_id;
  secretId = (await v('POST', 'auth/approle/role/app/secret-id', null, first.root)).data.secret_id;
  const init = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command',
    `. '${join(root, 'scripts', 'lib', 'LexaVaultTools.ps1')}'; Initialize-LexaVaultDatabase -Jar '${jar}' -DbUrl '${url}' -DbUser 'securedoc' -DbPassword $env:PW93 -StateDir '${join(run, 'config')}'`],
  { env: { ...process.env, PW93: ownerPw, PLATFORM_SETUP_VAULT_ADDR: vaultBase, PLATFORM_SETUP_VAULT_TOKEN: await login('platform-crypto-setup', first.root) }, encoding: 'utf8' });
  if (init.status !== 0) throw new Error(`install steps: exit ${init.status} ${(init.stdout + init.stderr).match(/[A-Z_]{8,}:.*/)?.[0] ?? ''}`);
  // The app's own database user, as an installation has one; the schema step then sets its rights on the platform's tables.
  docker(['exec', '-i', pg, 'psql', '-U', 'securedoc', '-d', 'securedoc', '-v', 'ON_ERROR_STOP=1', '-q'], { stdio: ['pipe', 'pipe', 'pipe'],
    input: `create role lexavault_app login password '${appPw}'; grant usage on schema public to lexavault_app; grant select, insert, update, delete on all tables in schema public to lexavault_app; grant usage on all sequences in schema public to lexavault_app;` });
  const step = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command',
    `& '${join(root, 'scripts', 'Invoke-LexaVault-SchemaStep.ps1')}' apply -RuntimeUser lexavault_app -JarPath '${jar}'; exit $LASTEXITCODE`],
  { env: { ...process.env, LEXAVAULT_SCHEMA_DB_URL: url, LEXAVAULT_SCHEMA_DB_USER: 'securedoc', LEXAVAULT_SCHEMA_DB_PASSWORD: ownerPw }, encoding: 'utf8' });
  check('schema step sets the app user\'s rights', step.status === 0, step.status === 0 ? '' : (step.stdout + step.stderr).slice(-400));
  await startApp();
  const erased = await wholeCase();
  const kept = await wholeCase();
  check('files are stored encrypted', sql("select string_agg(distinct protection, ',') from stored_files") === 'ENCRYPTED', sql("select string_agg(distinct protection, ',') from stored_files"));
  check('storage and database agree before the back-up', storageAgainstDatabase().missing === 0, JSON.stringify(storageAgainstDatabase()));

  /* ---------- the back-up ---------- */
  console.log('--- back-up');
  await timed('stop LexaVault', stopApp);
  await timed('back up the database', async () => {
    docker(['exec', pg, 'pg_dump', '-U', 'securedoc', '-d', 'securedoc', '--format=custom', '--file=/tmp/lexavault.dump']);          // IT
    docker(['exec', pg, 'pg_dumpall', '-U', 'securedoc', '--roles-only', '--no-role-passwords', '--file=/tmp/lexavault-roles.sql']); // IT
    for (const f of ['lexavault.dump', 'lexavault-roles.sql']) docker(['cp', `${pg}:/tmp/${f}`, join(backup, f)]);
  });
  await timed('back up the document storage', async () => {
    const r = spawnSync('robocopy', [storage, join(backup, 'storage'), '/MIR', '/COPY:DAT', '/DCOPY:DAT', '/R:2', '/W:5', '/NFL', '/NDL', '/NJH', '/NJS'], { encoding: 'utf8' }); // IT
    if (r.status >= 8) throw new Error(`robocopy ${r.status}`);
  });
  await timed('back up the key service', async () => {
    vaultCli(first.root, ['operator', 'raft', 'snapshot', 'save', '/tmp/vault.snap']); // IT
    docker(['cp', `${vault}:/tmp/vault.snap`, join(backup, 'vault.snap')]);
  });
  await timed('back up the configuration', async () => cpSync(join(run, 'config'), join(backup, 'config'), { recursive: true }));
  const oldLog = join(backup, 'erase-log-of-the-back-up'); // only to show below that an old one is refused
  cpSync(eraseLog, oldLog, { recursive: true });
  const bytes = files(backup).reduce((n, f) => n + readFileSync(f).length, 0);
  console.log(`back-up size: ${bytes} bytes, ${files(join(backup, 'storage')).length} stored files`);

  /* ---------- work after the back-up ---------- */
  await startApp();
  const erasedFiles = files(storage).filter((f) => f.includes(erased));
  sql(`update package_lifecycles set original_delete_at = now(), redacted_delete_at = now() where package_id='${erased}'`);
  await until('the retention run', () => erasedFiles.every((f) => !existsSync(f)), 60);
  check('after the back-up: one case is erased and logged', erasedFiles.length >= 2 && readFileSync(join(eraseLog, 'erase-log.jsonl'), 'utf8').includes(erased), `${erasedFiles.length} files`);
  const lost = await wholeCase();
  await stopApp();

  /* ---------- the loss ---------- */
  console.log('--- loss of the database, the document storage and the key service');
  for (const n of [names.pgA, names.vA]) { docker(['rm', '-f', '-v', n]); started.splice(started.indexOf(n), 1); }
  rmSync(storage, { recursive: true, force: true });

  /* ---------- the restore ---------- */
  console.log('--- restore');
  const restoreStart = Date.now();
  await timed('key service: new Vault, restore the snapshot, unseal with the old keys', async () => {
    const fresh = await startVault(names.vB);
    docker(['cp', join(backup, 'vault.snap'), `${vault}:/tmp/vault.snap`]);
    vaultCli(fresh.root, ['operator', 'raft', 'snapshot', 'restore', '-force', '/tmp/vault.snap']); // IT
    await sleep(3000);
    await unseal(first.key); // IT: vault operator unseal
  });
  await timed('database: new server, roles, restore', async () => {
    await startPg(names.pgB);
    for (const f of ['lexavault.dump', 'lexavault-roles.sql']) docker(['cp', join(backup, f), `${pg}:/tmp/${f}`]);
    spawnSync('docker', ['exec', pg, 'psql', '-U', 'securedoc', '-d', 'postgres', '-q', '-f', '/tmp/lexavault-roles.sql'], { stdio: 'ignore' }); // IT ("role already exists" for the owner is expected)
    docker(['exec', '-i', pg, 'psql', '-U', 'securedoc', '-d', 'postgres', '-q'], { stdio: ['pipe', 'pipe', 'pipe'], input: `alter role lexavault_app password '${appPw}';` }); // IT
    docker(['exec', pg, 'pg_restore', '-U', 'securedoc', '-d', 'securedoc', '--exit-on-error', '--single-transaction', '/tmp/lexavault.dump']); // IT
  });
  await timed('document storage: copy back', async () => {
    const r = spawnSync('robocopy', [join(backup, 'storage'), storage, '/MIR', '/COPY:DAT', '/DCOPY:DAT', '/R:2', '/W:5', '/NFL', '/NDL', '/NJH', '/NJS'], { encoding: 'utf8' }); // IT
    if (r.status >= 8) throw new Error(`robocopy ${r.status}`);
  });
  const s = storageAgainstDatabase();
  check('restored storage and database agree', s.rows > 0 && s.missing === 0 && s.changed === 0, JSON.stringify(s));
  check('the erased case\'s files are back', erasedFiles.every((f) => existsSync(f)));

  /* ---------- started too early ---------- */
  await startApp();
  let r = await newCase();
  check('started before the replay: new work is refused', r.status === 503 && r.code === 'LV-SYS-013', `HTTP ${r.status} ${r.code}`);
  check('... reading works', (await call('GET', '/api/v1/packages')).status === 200);
  await stopApp();

  /* ---------- the replay ---------- */
  let p = await timed('erase replay', async () => replay());
  console.log('replay said:', p.said);
  check('the replay exits 0', p.exit === 0, `exit ${p.exit}`);
  check('the erased case\'s files are gone again', erasedFiles.every((f) => !existsSync(f)));
  p = replay();
  check('the replay run again is safe', p.exit === 0, `exit ${p.exit}`);
  await timed('wait 30 s and start', async () => { await sleep(32000); await startApp(); });
  console.log(`STEP restore, from the first command to started (with the early start, a second replay and the checks): ${((Date.now() - restoreStart) / 1000).toFixed(1)} s`);

  /* ---------- verify ---------- */
  const status = await until('the status page', async () => { const x = (await call('GET', '/api/v1/support/status')).json?.checks ?? []; const of = (n) => x.find((c) => c.name === n)?.state; return of('erase-log') && of('key-service') ? `${of('erase-log')} ${of('key-service')}` : null; }, 120);
  check('Support, Status: erase log and key service work', status === 'WORKING WORKING', status);
  check('a case of before the back-up is there', sql(`select status from document_packages where id='${kept}'`) === 'COMPLETED');
  check('a case made after the back-up is lost', sql(`select count(*) from document_packages where id='${lost}'`) === '0');
  const cycles = sql(`select successful_export_cycles from package_lifecycles where package_id='${kept}'`);
  const again = await call('POST', `/api/v1/packages/${kept}/reprocess`, undefined, SUPERVISOR);
  check('its original opens with the restored keys (processed again)', again.status < 300 && Boolean(await until('reprocessing', () => Number(sql(`select successful_export_cycles from package_lifecycles where package_id='${kept}'`)) > Number(cycles), 120).catch(() => false)), `HTTP ${again.status} ${again.code}`);
  const fresh = await wholeCase();
  check('a new case is processed and exported', sql(`select status from document_packages where id='${fresh}'`) === 'COMPLETED');
  const t = storageAgainstDatabase();
  check('storage and database agree after work', t.missing === 0 && t.changed === 0, JSON.stringify(t));
  await stopApp();

  /* ---------- an erase log older than the database ---------- */
  console.log('--- what must be refused');
  logLocation = oldLog;
  await startApp();
  r = await newCase();
  check('an erase log older than the database: work is refused', r.status === 503 && r.code === 'LV-SYS-014', `HTTP ${r.status} ${r.code}`);
  await stopApp();
  logLocation = eraseLog;

  /* ---------- the keys are lost ---------- */
  const goodSnap = join(backup, 'vault-2.snap');
  vaultCli(first.root, ['operator', 'raft', 'snapshot', 'save', '/tmp/vault-2.snap']);
  docker(['cp', `${vault}:/tmp/vault-2.snap`, goodSnap]);
  docker(['rm', '-f', '-v', names.vB]); started.splice(started.indexOf(names.vB), 1);
  const empty = await startVault(names.vC);
  await prepareVault(empty.root);
  const [oldRole, oldSecret] = [roleId, secretId];
  roleId = (await v('GET', 'auth/approle/role/app/role-id', null, empty.root)).data.role_id;
  secretId = (await v('POST', 'auth/approle/role/app/secret-id', null, empty.root)).data.secret_id;
  await startApp();
  r = await newCase();
  let u = r.status < 300 ? await upload(r.json.packageId) : r;
  check('keys lost: nothing is stored', u.status === 503 && ['LV-SYS-011', 'LV-SYS-014'].includes(u.code), `HTTP ${u.status} ${u.code}`);
  const before = sql(`select successful_export_cycles from package_lifecycles where package_id='${kept}'`);
  r = await call('POST', `/api/v1/packages/${kept}/reprocess`, undefined, SUPERVISOR);
  await sleep(15000);
  check('keys lost: a stored document can\'t be opened', sql(`select successful_export_cycles from package_lifecycles where package_id='${kept}'`) === before, `HTTP ${r.status} ${r.code}`);
  await stopApp();

  /* ---------- the snapshot brings them back, then customer exit ---------- */
  docker(['cp', goodSnap, `${vault}:/tmp/vault-2.snap`]);
  vaultCli(empty.root, ['operator', 'raft', 'snapshot', 'restore', '-force', '/tmp/vault-2.snap']);
  await sleep(3000);
  await unseal(first.key);
  [roleId, secretId] = [oldRole, oldSecret];
  const out = join(run, 'customer-keys-destroyed.json');
  const destroy = async (confirm) => spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', join(root, 'scripts', 'Invoke-LexaVault-DestroyCustomerKeys.ps1'), '-TenantId', 'TEN-DEMO-0001',
    '-Operator', 'Rehearsal', '-BackupRetentionDays', '35', '-Confirm', confirm, '-ResultOut', out], { encoding: 'utf8', env: { ...process.env, PLATFORM_SETUP_VAULT_ADDR: vaultBase, PLATFORM_SETUP_VAULT_TOKEN: await login('platform-crypto-offboard', first.root) } });
  let d = await destroy('TEN-OTHER');
  check('customer exit: a wrong confirmation destroys nothing', d.status === 2 && !existsSync(out), `exit ${d.status}`);
  d = await timed('customer exit: destroy the keys', async () => destroy('TEN-DEMO-0001'));
  const cert = existsSync(out) ? JSON.parse(readFileSync(out, 'utf8')) : {};
  check('customer exit: both keys destroyed, two dates', d.status === 0 && cert.keys?.length === 2 && cert.keys.every((k) => k.state === 'DESTROYED') && Boolean(cert.liveDataDestroyedOn && cert.unreadableEverywhereFrom), `exit ${d.status} ${cert.liveDataDestroyedOn} ${cert.unreadableEverywhereFrom}`);
  await startApp();
  r = await newCase();
  u = r.status < 300 ? await upload(r.json.packageId) : r;
  check('customer exit: nothing is stored any more', u.status === 503 && ['LV-SYS-011', 'LV-SYS-014'].includes(u.code), `HTTP ${u.status} ${u.code}`);
  await stopApp();

  const written = files(run).filter((f) => /\.(log|jsonl|json|properties|sql|txt)$/.test(f)).map((f) => readFileSync(f, 'utf8')).join('\n');
  check('no password, key or token in a log, back-up of roles or file', [ownerPw, appPw, first.key, first.root, oldSecret, secretId, empty.key, empty.root].every((x) => !written.includes(x)));
} catch (e) {
  failed++;
  console.log('FAIL stopped:', e.message.split('\n')[0]);
} finally {
  await stopApp();
  for (const n of started) spawnSync('docker', ['rm', '-f', '-v', n], { stdio: 'ignore' });
  if (!failed) rmSync(run, { recursive: true, force: true });
  else writeFileSync(join(run, 'FAILED.txt'), String(failed));
  console.log(failed ? `FAILED ${failed}, files kept in ${run}` : 'ALL PASS');
}
process.exit(failed ? 1 : 0);
