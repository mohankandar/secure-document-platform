/**
 * Task 0075: a fresh install with sign-in. The install command activates the first entity, a test user signs in and lands
 * on it, and a simulated scan arrives through the Scan Bridge's own sign-in and names the entity.
 *
 *   node test-tools/first-entity/first-entity.mjs <test-users.env>
 *
 * THROWAWAY containers (lexavault-0075-start-pg, -kc, -vault), the built app and the built Scan Bridge are
 * started here and removed at the end; nothing else is touched. The login system is a new, empty one: the users of the file
 * (LV_TEST_PROCESSOR_*, _IT_ADMIN_*, _BUSINESS_ADMIN_*) are made in it. No password, secret or token is printed or written to a file.
 */
import { spawn, spawnSync, execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { mkdirSync, openSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const target = (m) => join(root, 'backend', m, 'target', `${m}-0.1.0-SNAPSHOT.jar`);
const [jar, setupJar, bridgeJar] = [target('securedoc-api'), target('securedoc-setup'), target('securedoc-scan-bridge')];
const [vaultPort, dbPort, kcPort, appPort, bridgePort] = [18295, 55985, 18985, 18975, 18276];
const [pg, kc, vault] = ['lexavault-0075-start-pg', 'lexavault-0075-start-kc', 'lexavault-0075-start-vault'];
const run = join(tmpdir(), 'lexavault-0075', new Date().toISOString().replace(/[:.]/g, '-'));
mkdirSync(run, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const secret = () => randomUUID().replaceAll('-', '');
const users = Object.fromEntries(readFileSync(process.argv[2], 'utf8').split(/\r?\n/).filter((l) => /^LV_TEST_\w+=/.test(l))
  .map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1).replace(/^(['"])(.*)\1$/, '$2')]));
const [dbPassword, kcPassword, bridgeSecret, vaultToken] = [secret(), secret(), secret(), secret()];
const [SLOT, NAME, REALM, AUDIENCE] = ['ENT-DEMO-0001', 'Harbor Test Entity', 'lv75', 'lexavault-api'];
const base = `http://localhost:${appPort}`;
const kcBase = `http://localhost:${kcPort}`;
const issuer = `${kcBase}/realms/${REALM}`;
const url = `jdbc:postgresql://localhost:${dbPort}/securedoc`;

let failed = 0;
const check = (name, ok, detail = '') => { if (!ok) failed++; console.log(ok ? 'PASS' : 'FAIL', name, detail); };
const sql = (q) => execFileSync('docker', ['exec', pg, 'psql', '-U', 'securedoc', '-d', 'securedoc', '-tA', '-c', q], { encoding: 'utf8' }).trim();
async function until(what, ask, seconds) {
  for (const end = Date.now() + seconds * 1000; Date.now() < end;) {
    const found = await ask();
    if (found) return found;
    await sleep(1000);
  }
  throw new Error(`waited for ${what}`);
}
async function call(method, address, { headers = {}, body } = {}) {
  const form = body instanceof FormData || body instanceof URLSearchParams;
  const res = await fetch(address, { method, headers: { ...headers, ...(body && !form ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? (form ? body : JSON.stringify(body)) : undefined });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* none */ }
  return { status: res.status, json };
}
async function must(method, address, options) {
  const r = await call(method, address, options);
  if (r.status >= 300) throw new Error(`${method} ${new URL(address).pathname}: HTTP ${r.status} ${r.json?.code ?? ''}`);
  return r.json;
}
const main = (from, mainClass, args, env) => spawnSync('java', ['-cp', from, `-Dloader.main=${mainClass}`,
  'org.springframework.boot.loader.launch.PropertiesLauncher', ...args], { cwd: run, env: { ...process.env, ...env }, encoding: 'utf8' });
const db = (prefix) => ({ [`${prefix}_DB_URL`]: url, [`${prefix}_DB_USER`]: 'securedoc', [`${prefix}_DB_PASSWORD`]: dbPassword });
const appEnv = { ...process.env, PLATFORM_CRYPTO_VAULT_ADDRESS: `http://localhost:${vaultPort}`, PLATFORM_CRYPTO_VAULT_LOGIN: 'TOKEN', PLATFORM_CRYPTO_VAULT_TOKEN: vaultToken,
  SPRING_DATASOURCE_URL: url, SPRING_DATASOURCE_USERNAME: 'securedoc', SPRING_DATASOURCE_PASSWORD: dbPassword,
  SECUREDOC_SECURITY_MODE: 'OIDC', SECUREDOC_SECURITY_OIDC_ISSUER_URI: issuer, SECUREDOC_SECURITY_OIDC_AUDIENCE: AUDIENCE,
  SECUREDOC_SECURITY_OIDC_BROWSER_CLIENT_ID: 'lexavault-web', SECUREDOC_SECURITY_OIDC_PERSISTENT_CONNECTION_ENABLED: 'true',
  SECUREDOC_SECURITY_OIDC_PROVIDER_ID: 'start-kc', SECUREDOC_WATCHED_FOLDER_ENABLED: 'false',
  SECUREDOC_WATCHED_FOLDER_SCHEDULING_ENABLED: 'false', SECUREDOC_AI_OLLAMA_PRELOAD_ENABLED: 'false',
  SECUREDOC_AI_PROVIDER: 'heuristic', SECUREDOC_DELIVERY_LOCAL_OUTPUT_PATH: join(run, 'export'),
  SECUREDOC_PRODUCT_TELEMETRY_OUTPUT_PATH: join(run, 'telemetry.jsonl') };
const appOptions = ['--spring.profiles.active=local,oidc-local', `--securedoc.storage.local.root-path=${join(run, 'storage')}`,
  `--platform.erase-log.location=${join(run, 'erase-log')}`];
function first(slot, name) {
  const r = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command',
    `& '${join(root, 'scripts', 'Invoke-LexaVault-FirstEntity.ps1')}' -SlotId '${slot}' -Name '${name}' -JarPath '${jar}' -AppOptions ${appOptions.map((o) => `'${o}'`).join(',')}; exit $LASTEXITCODE`],
  { cwd: run, env: appEnv, encoding: 'utf8' });
  const said = /^(ACTIVATED|UNCHANGED|NOT_THE_FIRST|NO_SUCH_SLOT|NOT_ONE_CUSTOMER|NO_LICENCE|FAILED|BAD_OPTION)\b.*/m.exec(r.stdout + r.stderr)?.[0] ?? '(no answer)';
  return { exit: r.status, said };
}

let app; let bridge;
const started = [];
try {
  for (const name of [pg, kc, vault]) {
    if (execFileSync('docker', ['ps', '-aq', '--filter', `name=^/${name}$`], { encoding: 'utf8' }).trim()) throw new Error(`${name} exists already`);
  }
  execFileSync('docker', ['run', '-d', '--name', pg, '-e', 'POSTGRES_DB=securedoc', '-e', 'POSTGRES_USER=securedoc', '-e', 'POSTGRES_PASSWORD',
    '-p', `127.0.0.1:${dbPort}:5432`, 'postgres:16-alpine'], { stdio: 'ignore', env: { ...process.env, POSTGRES_PASSWORD: dbPassword } });
  started.push(pg);
  execFileSync('docker', ['run', '-d', '--name', kc, '-e', 'KC_BOOTSTRAP_ADMIN_USERNAME=admin', '-e', 'KC_BOOTSTRAP_ADMIN_PASSWORD',
    '-p', `127.0.0.1:${kcPort}:8080`, 'quay.io/keycloak/keycloak:26.8.2', 'start-dev', `--hostname=${kcBase}`],
  { stdio: 'ignore', env: { ...process.env, KC_BOOTSTRAP_ADMIN_PASSWORD: kcPassword } });
  started.push(kc);
  execFileSync('docker', ['run', '-d', '--name', vault, '-e', 'VAULT_DEV_ROOT_TOKEN_ID', '-p', `127.0.0.1:${vaultPort}:8200`, 'hashicorp/vault:1.21.4'],
    { stdio: 'ignore', env: { ...process.env, VAULT_DEV_ROOT_TOKEN_ID: vaultToken } });
  started.push(vault);
  await until('Vault', async () => { try { return (await fetch(`http://localhost:${vaultPort}/v1/sys/health`)).ok; } catch { return false; } }, 60);
  await must('POST', `http://localhost:${vaultPort}/v1/sys/mounts/transit`, { headers: { 'X-Vault-Token': vaultToken }, body: { type: 'transit' } });

  /* ---------- the login system ---------- */
  await until('Keycloak', async () => { try { return (await fetch(`${kcBase}/realms/master`)).ok; } catch { return false; } }, 180);
  const token = (realm, form) => must('POST', `${kcBase}/realms/${realm}/protocol/openid-connect/token`, { body: new URLSearchParams(form) });
  const admin = { Authorization: `Bearer ${(await token('master', { grant_type: 'password', client_id: 'admin-cli', username: 'admin', password: kcPassword })).access_token}` };
  const audience = { name: 'aud', protocol: 'openid-connect', protocolMapper: 'oidc-audience-mapper', config: { 'included.custom.audience': AUDIENCE, 'access.token.claim': 'true' } };
  const groups = { name: 'groups', protocol: 'openid-connect', protocolMapper: 'oidc-group-membership-mapper', config: { 'claim.name': 'groups', 'full.path': 'false', 'access.token.claim': 'true' } };
  const user = (who, group) => ({ username: users[`LV_TEST_${who}_USERNAME`], enabled: true, emailVerified: true, email: `${who.toLowerCase()}@example.test`,
    firstName: 'Test', lastName: 'Person', groups: [group], credentials: [{ type: 'password', value: users[`LV_TEST_${who}_PASSWORD`], temporary: false }] });
  await must('POST', `${kcBase}/admin/realms`, { headers: admin, body: { realm: REALM, enabled: true, sslRequired: 'none',
    groups: [{ name: 'BusinessUsers' }, { name: 'TenantITAdmins' }, { name: 'BusinessAdmins' }], users: [user('PROCESSOR', 'BusinessUsers'), user('IT_ADMIN', 'TenantITAdmins'), user('BUSINESS_ADMIN', 'BusinessAdmins')],
    clients: [{ clientId: 'lexavault-web', publicClient: true, directAccessGrantsEnabled: true, protocolMappers: [audience, groups] },
      { clientId: 'scan-bridge', secret: bridgeSecret, serviceAccountsEnabled: true, standardFlowEnabled: false, protocolMappers: [audience] },
      { clientId: 'other-machine', secret: bridgeSecret, serviceAccountsEnabled: true, standardFlowEnabled: false, protocolMappers: [audience] }] } });
  const signIn = async (who) => ({ Authorization: `Bearer ${(await token(REALM, { grant_type: 'password', client_id: 'lexavault-web', scope: 'openid profile email',
    username: users[`LV_TEST_${who}_USERNAME`], password: users[`LV_TEST_${who}_PASSWORD`] })).access_token}` });

  /* ---------- the install: schema step and setup, no demo step ---------- */
  await until('PostgreSQL', () => spawnSync('docker', ['exec', pg, 'pg_isready', '-U', 'securedoc'], { stdio: 'ignore' }).status === 0, 60);
  await sleep(3000);
  const demo = join(root, 'install', 'demo');
  check('schema step', main(jar, 'com.securedoc.install.SchemaStep', ['apply'], db('LEXAVAULT_SCHEMA')).status === 0);
  check('sign-in file', main(jar, 'com.securedoc.install.LocalOidcSignin', ['--from', join(demo, 'signin-demo.json'), '--out', join(run, 'signin'), '--issuer', issuer,
    '--provider-id', 'start-kc', '--audience', AUDIENCE, '--browser-client-id', 'lexavault-web'], {}).status === 0);
  const setup = main(setupJar, 'com.documentplatform.setup.SetupCommand', ['apply', '--licence', join(demo, 'licence-demo.json'), '--signin', join(run, 'signin', 'signin-local-oidc.json'),
    '--product', 'LEXAVAULT', '--product-roles', join(root, 'install', 'product-roles.json'),
    '--config-out', join(run, 'platform-tenant.properties'), '--run-log', join(run, 'setup-runs.log')],
  { ...db('PLATFORM_SETUP'), PLATFORM_SETUP_VAULT_ADDR: `http://localhost:${vaultPort}`, PLATFORM_SETUP_VAULT_TOKEN: vaultToken });
  check('setup', setup.status === 0, `exit ${setup.status} ${/^[A-Z_]{8,}:.*/m.exec(setup.stderr)?.[0] ?? ''}`);
  check('no entity is active after setup', sql("select count(*) from idn_entity where status = 'ACTIVE'") === '0' && sql('select count(*) from tenants') === '0');

  /* ---------- the command ---------- */
  let r = first('ENT-NOWHERE', NAME);
  check('an unknown slot is refused', r.exit === 2 && r.said.startsWith('NO_SUCH_SLOT'), `exit ${r.exit} ${r.said}`);
  r = first(SLOT, NAME);
  check('the first entity is activated', r.exit === 0 && r.said.startsWith('ACTIVATED'), `exit ${r.exit} ${r.said}`);
  check('it is active and named', sql('select status || name from tenants') === `ACTIVE${NAME}`, sql('select status from tenants'));
  r = first(SLOT, NAME);
  check('again changes nothing', r.exit === 0 && r.said.startsWith('UNCHANGED'), `exit ${r.exit} ${r.said}`);
  r = first('ENT-DEMO-0002', 'Second');
  check('a second entity is refused', r.exit === 2 && r.said.startsWith('NOT_THE_FIRST'), `exit ${r.exit} ${r.said}`);
  const audit = sql("select detail from audit_records where action = 'ENTITY_ACTIVATED'");
  check('audited once as an install action', audit === 'code=ACTIVATED, source=INSTALL', audit);

  /* ---------- sign-in ---------- */
  app = spawn('java', ['-jar', jar, ...appOptions], { cwd: run, env: { ...appEnv, SERVER_PORT: String(appPort), SERVER_ADDRESS: '127.0.0.1' },
    stdio: ['ignore', openSync(join(run, 'app.log'), 'w'), openSync(join(run, 'app.err'), 'w')] });
  await until('the app', async () => {
    if (app.exitCode !== null) throw new Error('the app stopped while starting (app.log)');
    try { return (await fetch(`${base}/actuator/health`)).ok; } catch { return false; }
  }, 180);
  const person = await signIn('PROCESSOR');
  const me = await call('GET', `${base}/api/v1/me`, { headers: person });
  check('the test user signs in and lands on the entity', me.status === 200 && me.json?.tenantName === NAME, `HTTP ${me.status} ${me.json?.tenantName ?? me.json?.code ?? ''}`);
  const open = (await call('GET', `${base}/api/v1/me/tenants`, { headers: person })).json;
  const list = Array.isArray(open) ? open : open?.tenants ?? [];
  check('it is the only entity to choose', list.length === 1, `${list.length}`);

  /* ---------- the scanner ---------- */
  await must('PUT', `${base}/api/v1/business-admin/identifiers`, { headers: await signIn('BUSINESS_ADMIN'), body: { identifiers: [{ label: 'Contract Number', required: true, primary: true,
    searchable: true, extractable: true, displayable: true, validationType: 'TEXT', exactLength: 12 }] } });
  const it = await signIn('IT_ADMIN');
  const source = (name, providerType, clientPrincipal) => must('POST', `${base}/api/v1/integrations`, { headers: it, body: { name, purpose: 'INGESTION_SOURCE',
    direction: 'INBOUND', providerType, authenticationType: 'OAUTH2_CLIENT_CREDENTIALS', clientPrincipal, enabled: true, acknowledgementMode: 'NONE', timeoutSeconds: 60,
    retryMaxAttempts: 2, incompleteWindowHours: 24, maxDocumentsPerIntake: providerType === 'SCANNER' ? 1 : 5, maxPdfSizeBytes: 10485760, maxPdfPages: 100 } });
  await source('Front desk scanner', 'SCANNER', 'service-account-scan-bridge');
  await source('Sending system', 'PUSH_API', 'service-account-other-machine');
  const machine = async (client) => ({ Authorization: `Bearer ${(await token(REALM, { grant_type: 'client_credentials', client_id: client, client_secret: bridgeSecret })).access_token}` });
  const empty = { externalSourcePackageId: randomUUID(), sourceRevision: '1', businessIdentifiers: {}, metadata: {}, documents: [] };
  for (const [name, headers, route] of [['a person on the scanner route', person, 'scanner-intakes'], ['a push client on the scanner route', await machine('other-machine'), 'scanner-intakes'],
    ['the scanner client on the push route', await machine('scan-bridge'), 'external-intakes']]) {
    const x = await call('POST', `${base}/api/v1/${route}`, { headers, body: empty });
    check(`${name} is refused`, x.status === 403, `HTTP ${x.status} ${x.json?.code ?? ''}`);
  }
  check('nobody without a token', (await call('POST', `${base}/api/v1/scanner-intakes`, { body: empty })).status === 401);

  const m2 = join(process.env.USERPROFILE, '.m2', 'repository');
  const logging = join(m2, 'commons-logging', 'commons-logging');
  const version = readdirSync(logging).find((v) => /^\d/.test(v) && readdirSync(join(logging, v)).some((f) => f.endsWith('.jar')));
  const cp = [...['pdfbox', 'fontbox', 'pdfbox-io'].map((a) => join(m2, 'org', 'apache', 'pdfbox', a, '3.0.2', `${a}-3.0.2.jar`)), join(logging, version, `commons-logging-${version}.jar`)].join(';');
  const pdf = join(run, 'scan.pdf');
  execFileSync('java', ['-cp', cp, join(root, 'test-tools', 'two-servers', 'MakeCasePdf.java'), pdf, 'typed', '1']);
  for (const d of ['work', 'problem', 'inbox']) mkdirSync(join(run, 'bridge', d), { recursive: true });
  bridge = spawn('java', ['-jar', bridgeJar], { cwd: run, stdio: ['ignore', openSync(join(run, 'bridge.log'), 'w'), openSync(join(run, 'bridge.err'), 'w')],
    env: { ...process.env, SECUREDOC_SCAN_BRIDGE_PORT: String(bridgePort), SECUREDOC_SCAN_BRIDGE_MODE: 'SIMULATOR', SECUREDOC_SCAN_BRIDGE_LEXAVAULT_BASE_URL: `${base}/api/v1`,
      SECUREDOC_SCAN_BRIDGE_OIDC_TOKEN_URI: `${issuer}/protocol/openid-connect/token`, SECUREDOC_SCAN_BRIDGE_OIDC_CLIENT_ID: 'scan-bridge',
      SECUREDOC_SCAN_BRIDGE_OIDC_CLIENT_SECRET: bridgeSecret, SECUREDOC_SCAN_BRIDGE_WORK_PATH: join(run, 'bridge', 'work'),
      SECUREDOC_SCAN_BRIDGE_PROBLEM_PATH: join(run, 'bridge', 'problem'), SECUREDOC_SCAN_BRIDGE_INBOX_PATH: join(run, 'bridge', 'inbox'),
      SECUREDOC_SCAN_BRIDGE_MIN_USABLE_SPACE_BYTES: '1048576' } });
  const b = `http://127.0.0.1:${bridgePort}/api/v1`;
  const headers = { 'X-LexaVault-Bridge-Token': (await until('the Scan Bridge', async () => { try { return await (await fetch(`${b}/configuration`)).json(); } catch { return null; } }, 60)).browserRequestToken };
  const session = await must('POST', `${b}/scan-sessions`, { headers, body: { businessIdentifiers: { contract_number: '880000000175' } } });
  const form = new FormData();
  form.append('file', new Blob([readFileSync(pdf)], { type: 'application/pdf' }), 'scan.pdf');
  await must('POST', `${b}/scan-sessions/${session.sessionId}/simulator-pdf`, { headers, body: form });
  await until('the scan', async () => (await must('GET', `${b}/scan-sessions/${session.sessionId}`)).status === 'READY', 30);
  await must('POST', `${b}/scan-sessions/${session.sessionId}/submit`, { headers });
  const sent = await until('the delivery', async () => {
    const s = await must('GET', `${b}/scan-sessions/${session.sessionId}`);
    return ['SUBMITTED', 'ATTENTION_REQUIRED'].includes(s.status) ? s : null;
  }, 120);
  check('the scan arrives through the bridge\'s own sign-in', sent.status === 'SUBMITTED', `${sent.status} ${sent.lastDeliveryErrorCode ?? ''} ${sent.lastDeliveryLvCode ?? ''}`);
  if (sent.lexavaultPackageId) {
    const answer = await call('GET', `${base}/api/v1/scanner-intakes/${sent.lexavaultPackageId}`, { headers: await machine('scan-bridge') });
    check('the answer names the entity', answer.json?.entityName === NAME, `HTTP ${answer.status} ${answer.json?.entityName ?? ''}`);
    check('the case is in that entity', sql(`select t.name from document_packages p join tenants t on t.id = p.tenant_id where p.id = '${sent.lexavaultPackageId}'`) === NAME);
    check('its person sees it', (await call('GET', `${base}/api/v1/packages/${sent.lexavaultPackageId}`, { headers: person })).status === 200);
    const names = sql("select distinct actor_display_name from audit_records where actor_display_name like 'Scanner:%'");
    check('audited as the scanner', names === 'Scanner: Front desk scanner', names);
  }
  const logs = ['app.log', 'app.err', 'bridge.log', 'bridge.err'].map((f) => readFileSync(join(run, f), 'utf8')).join('\n')
    + spawnSync('powershell.exe', ['-NoProfile', '-Command', `Get-ChildItem -Recurse -File '${join(run, 'bridge')}' -Include *.json | Get-Content -Raw`], { encoding: 'utf8' }).stdout;
  const secrets = [bridgeSecret, dbPassword, vaultToken, users.LV_TEST_PROCESSOR_PASSWORD, users.LV_TEST_IT_ADMIN_PASSWORD, users.LV_TEST_BUSINESS_ADMIN_PASSWORD];
  check('no secret or token in a log or a bridge file', secrets.every((s) => s && !logs.includes(s)) && !/eyJ[\w-]{20,}\./.test(logs));
} catch (e) {
  failed++;
  console.log('FAIL stopped:', e.message);
} finally {
  for (const p of [bridge, app]) if (p && p.exitCode === null) spawnSync('taskkill', ['/PID', String(p.pid), '/T', '/F'], { stdio: 'ignore' });
  for (const name of started) execFileSync('docker', ['rm', '-f', '-v', name], { stdio: 'ignore' });
  await sleep(2000);
  if (!failed) rmSync(run, { recursive: true, force: true });
  console.log(failed ? `FAILED ${failed}, files kept in ${run}` : 'ALL PASS');
}
process.exit(failed ? 1 : 0);
