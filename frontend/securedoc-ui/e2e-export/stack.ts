/**
 * Task 0035 (test layer L5): the real LexaVault stack for the export end-to-end run against the folder destination.
 *
 * - The backend jar, started and stopped here (scenario 6 restarts it), in export job mode with the "export-e2e" test
 *   profile (test-tools/export-e2e/application-export-e2e.yml: shortened timings, never the defaults).
 * - A THROWAWAY PostgreSQL container started by scripts/Test-LexaVault-ExportE2E.ps1 (its name, port and generated
 *   password come in through the environment; the password is never printed or written to a file).
 * - A mock Ollama that finds nothing (e2e-export/global-setup.ts), so a case finishes without review.
 * - The folder destination: <run>/out/<tenant>/<case>/cycle-<n>/<document>/<file>. "Down" for one entity puts a plain
 *   file where its folder was (the adapter creates missing folders, so only a file in the way makes it unreachable);
 *   "up" puts the folder back.
 *
 * Every scenario uses its own entity (tenant id), so destination states, pauses and the backup rule's count of
 * failing cases never mix between scenarios. The UI screens use the same entities by setting the LOCAL identity
 * headers on the page's API calls.
 */
import { execFileSync, spawn } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import * as fs from 'node:fs';
import * as path from 'node:path';

function env(name: string, fallback?: string): string {
  const v = process.env[name] ?? fallback;
  if (v === undefined || v === '') throw new Error(`${name} is not set (run scripts/Test-LexaVault-ExportE2E.ps1)`);
  return v;
}

export const RUN_DIR = env('LEXAVAULT_E2E_RUN_DIR');
export const OUT_DIR = path.join(RUN_DIR, 'out');
export const LOG_DIR = path.join(RUN_DIR, 'logs');
export const APP_PORT = Number(env('LEXAVAULT_E2E_APP_PORT', '18035'));
export const BACKEND = `http://localhost:${APP_PORT}`;
const DB_CONTAINER = env('LEXAVAULT_E2E_DB_CONTAINER');
const PROTECTED = ['securedoc-postgres', 'sdv-postgres', 'sdv-vault', 'lexa-keycloak', 'lexa-keycloak-db'];
if (PROTECTED.includes(DB_CONTAINER)) throw new Error('Refusing to use a protected container');

export const ROLES = {
  processor: 'DOCUMENT_PROCESSOR',
  supervisor: 'DOCUMENT_SUPERVISOR',
  reviewer: 'REVIEWER',
  businessAdmin: 'BUSINESS_ADMIN',
  reportViewer: 'REPORT_VIEWER',
  itAdmin: 'TENANT_IT_ADMIN',
  platformOps: 'PLATFORM_OPERATIONS_ADMIN',
} as const;
export const ACTOR = '00000000-0000-0000-0000-000000000099';
/** Each scenario's own entity. The local folder's destination id is derived from it (ExportDestinations). */
export const tenantFor = (n: number) => `00000000-0000-4035-8000-${String(n).padStart(12, '0')}`;
export const localFolderDestination = (tenant: string) => {
  // UUID.nameUUIDFromBytes("lexavault:local-export-folder:" + tenant): MD5, version 3, IETF variant.
  const md5 = createHash('md5').update(`lexavault:local-export-folder:${tenant}`, 'utf8').digest();
  md5[6] = (md5[6] & 0x0f) | 0x30;
  md5[8] = (md5[8] & 0x3f) | 0x80;
  const h = md5.toString('hex');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
};

// ---------------------------------------------------------------- the app

const PID_FILE = path.join(RUN_DIR, 'backend.pid');
let starts = 0;

export async function startApp(): Promise<void> {
  if (await healthy()) throw new Error(`Something already answers on ${BACKEND}`);
  fs.mkdirSync(LOG_DIR, { recursive: true });
  fs.mkdirSync(OUT_DIR, { recursive: true });
  starts++;
  const out = fs.openSync(path.join(LOG_DIR, `backend-${starts}.out.log`), 'a');
  const err = fs.openSync(path.join(LOG_DIR, `backend-${starts}.err.log`), 'a');
  const profileDir = env('LEXAVAULT_E2E_PROFILE_DIR').replace(/\\/g, '/').replace(/\/?$/, '/');
  const child = spawn('java', [
    '-jar', env('LEXAVAULT_E2E_JAR'),
    '--spring.profiles.active=local,export-e2e',
    `--spring.config.additional-location=optional:file:${profileDir}`,
    `--securedoc.storage.local.root-path=${path.join(RUN_DIR, 'storage')}`,
  ], {
    cwd: RUN_DIR, detached: true, windowsHide: true, stdio: ['ignore', out, err],
    env: {
      ...process.env,
      SPRING_DATASOURCE_URL: `jdbc:postgresql://localhost:${env('LEXAVAULT_E2E_DB_PORT')}/securedoc`,
      SPRING_DATASOURCE_USERNAME: 'securedoc',
      SPRING_DATASOURCE_PASSWORD: env('LEXAVAULT_E2E_DB_PASSWORD'),
      SERVER_PORT: String(APP_PORT),
      SECUREDOC_AI_PROVIDER: 'ollama',
      SECUREDOC_AI_OLLAMA_BASE_URL: `http://127.0.0.1:${env('LEXAVAULT_E2E_OLLAMA_PORT', '11535')}`,
      SECUREDOC_AI_MODEL: 'qwen3.5:9b',
      SECUREDOC_AI_OLLAMA_PRELOAD_ENABLED: 'false',
      SECUREDOC_WATCHED_FOLDER_ENABLED: 'false',
      SECUREDOC_WATCHED_FOLDER_SCHEDULING_ENABLED: 'false',
      SECUREDOC_LOGGING_CONFIG: 'classpath:logback-json-rolling.xml',
      SECUREDOC_LOG_DIR: LOG_DIR,
      SECUREDOC_PRODUCT_TELEMETRY_OUTPUT_PATH: path.join(LOG_DIR, 'product-telemetry.jsonl'),
      SECUREDOC_DELIVERY_LOCAL_OUTPUT_PATH: OUT_DIR,
      SECUREDOC_SECURITY_CORS_ALLOWED_ORIGINS: 'http://localhost:15235',
    },
  });
  child.unref();
  fs.writeFileSync(PID_FILE, String(child.pid));
  const deadline = Date.now() + 180_000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error(`backend exited during start-up (see ${LOG_DIR})`);
    if (await healthy()) return;
    await sleep(1000);
  }
  throw new Error('backend did not become healthy in 180 s');
}

/** Ends the backend at once (no graceful shut-down: scenario 6 is a server that stops mid-run). */
export async function stopApp(): Promise<void> {
  if (!fs.existsSync(PID_FILE)) return;
  const pid = Number(fs.readFileSync(PID_FILE, 'utf8'));
  try { process.kill(pid, 'SIGKILL'); } catch { /* already gone */ }
  fs.rmSync(PID_FILE, { force: true });
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline && await healthy()) await sleep(250);
  await sleep(500);
}

async function healthy(): Promise<boolean> {
  try {
    const r = await fetch(`${BACKEND}/actuator/health`, { signal: AbortSignal.timeout(2000) });
    return r.ok && (await r.json()).status === 'UP';
  } catch { return false; }
}

export const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** Waits until {@code check} returns a value that is not null/false, or fails with what it last saw. */
export async function waitFor<T>(what: string, check: () => Promise<T | null | undefined | false>, timeoutMs = 60_000, everyMs = 500): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  let last: unknown;
  while (Date.now() < deadline) {
    try {
      const v = await check();
      if (v !== null && v !== undefined && v !== false) return v as T;
      last = v;
    } catch (e) { last = e instanceof Error ? e.message : e; }
    await sleep(everyMs);
  }
  throw new Error(`Timed out after ${timeoutMs} ms waiting for: ${what} (last: ${JSON.stringify(last)})`);
}

// ---------------------------------------------------------------- the API

export interface Who { tenant: string; roles: string }
export const as = (tenant: string, roles: string): Who => ({ tenant, roles });

export async function call(who: Who, method: string, apiPath: string, body?: unknown): Promise<{ status: number; body: any }> {
  const r = await fetch(`${BACKEND}/api/v1${apiPath}`, {
    method,
    headers: {
      'X-Tenant-Id': who.tenant, 'X-Actor-Id': ACTOR, 'X-Actor-Roles': who.roles, 'X-Correlation-Id': randomUUID(),
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await r.text();
  let parsed: unknown = text;
  try { parsed = text ? JSON.parse(text) : null; } catch { /* not JSON */ }
  return { status: r.status, body: parsed };
}

export async function ok(who: Who, method: string, apiPath: string, body?: unknown): Promise<any> {
  const r = await call(who, method, apiPath, body);
  if (r.status < 200 || r.status >= 300) throw new Error(`${method} ${apiPath} -> HTTP ${r.status} ${JSON.stringify(r.body)}`);
  return r.body;
}

/** A one-page PDF with plain words and no sensitive value. */
export function testPdf(title: string): Buffer {
  const text = `(${title.replace(/[()\\]/g, '')}) Tj`;
  const stream = `BT /F1 18 Tf 72 720 Td ${text} 0 -28 Td (Export end-to-end test page. Nothing sensitive here.) Tj ET`;
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>',
    `<< /Length ${Buffer.byteLength(stream, 'latin1')} >>\nstream\n${stream}\nendstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ];
  let pdf = '%PDF-1.4\n';
  const offsets: number[] = [];
  objects.forEach((o, i) => { offsets.push(Buffer.byteLength(pdf, 'latin1')); pdf += `${i + 1} 0 obj\n${o}\nendobj\n`; });
  const xref = Buffer.byteLength(pdf, 'latin1');
  pdf += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  for (const o of offsets) pdf += `${String(o).padStart(10, '0')} 00000 n \n`;
  pdf += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(pdf, 'latin1');
}

/** Task 0036: each case's case number (its 12-digit contract number, the main ID the Cases list shows), by package id. */
export const caseNumbers = new Map<string, string>();

/** Creates a case with one PDF and submits it (after {@code beforeSubmit}, if given). Returns its id. */
export async function newCase(tenant: string, loan: string, beforeSubmit?: (packageId: string) => void): Promise<string> {
  const who = as(tenant, ROLES.processor);
  // The demo identifier rules: a 12-digit contract number and an 11-digit member number.
  const digits = (n: number) => Array.from({ length: n }, () => Math.floor(Math.random() * 10)).join('');
  const contract = `35${digits(10)}`;
  const created = await ok(who, 'POST', '/packages', {
    packageType: 'MANUAL_UPLOAD',
    businessIdentifiers: { contract_number: contract, member_number: `9035${digits(7)}`, service_entity: '001' },
    metadata: { source: 'task-0035-export-e2e', loan },
  });
  const packageId: string = created.packageId;
  caseNumbers.set(packageId, contract);
  const form = new FormData();
  form.append('file', new Blob([testPdf(`Loan ${loan}`)], { type: 'application/pdf' }), `${loan}.pdf`);
  const up = await fetch(`${BACKEND}/api/v1/packages/${packageId}/documents`, {
    method: 'POST', body: form,
    headers: { 'X-Tenant-Id': tenant, 'X-Actor-Id': ACTOR, 'X-Actor-Roles': ROLES.processor, 'X-Correlation-Id': randomUUID() },
  });
  if (!up.ok) throw new Error(`upload failed: HTTP ${up.status} ${await up.text()}`);
  beforeSubmit?.(packageId);
  await ok(who, 'POST', `/packages/${packageId}/submit`);
  return packageId;
}

// ---------------------------------------------------------------- the database (throwaway container only)

export function sql(query: string): string {
  return execFileSync('docker', ['exec', DB_CONTAINER, 'psql', '-q', '-U', 'securedoc', '-d', 'securedoc', '-v', 'ON_ERROR_STOP=1', '-tAc', query],
    { encoding: 'utf8', windowsHide: true }).trim();
}

export function sqlRows(query: string): string[][] {
  const out = sql(query);
  return out ? out.split(/\r?\n/).map((l) => l.split('|')) : [];
}

/**
 * A scenario's entity in the throwaway database: an ACTIVE tenant with the LOCAL demo entity's set-up copied (its
 * capabilities, identifier rules and sensitive-data policy; the demo step creates those for the demo entity, whose
 * UUID is derived from demo slot ENT-DEMO-0001 since task 0037).
 */
export function ensureEntity(tenant: string, name: string): void {
  const slug = name.toLowerCase().replace(/[^a-z0-9]+/g, '-');
  sql(`DO $$
DECLARE t text; cols text; sel text; nt uuid := '${tenant}'; demo uuid := '30216241-b724-5a3a-aef3-3034fd546d17';
BEGIN
  IF EXISTS (SELECT 1 FROM tenants WHERE id = nt) THEN RETURN; END IF;
  INSERT INTO tenants (id, name, slug, status, created_at, updated_at, version)
    VALUES (nt, '${name.replace(/'/g, "''")}', '${slug}', 'ACTIVE', now(), now(), 0);
  FOREACH t IN ARRAY ARRAY['tenant_capabilities','business_identifier_definitions','tenant_redaction_policies','tenant_redaction_catalog'] LOOP
    SELECT string_agg(quote_ident(column_name), ', ' ORDER BY ordinal_position),
           string_agg(CASE WHEN column_name = 'tenant_id' THEN quote_literal(nt) || '::uuid'
                           WHEN column_name = 'id' AND data_type = 'uuid' THEN 'gen_random_uuid()'
                           ELSE quote_ident(column_name) END, ', ' ORDER BY ordinal_position)
      INTO cols, sel FROM information_schema.columns WHERE table_schema = 'public' AND table_name = t;
    EXECUTE format('INSERT INTO %I (%s) SELECT %s FROM %I WHERE tenant_id = %L', t, cols, sel, t, demo);
  END LOOP;
END $$;`);
}

export interface JobRow { id: string; packageId: string; status: string; attempts: number; lvCode: string; detail: string; rebuilt: boolean; createdAt: string; updatedAt: string }

export function jobsOf(tenant: string): JobRow[] {
  return sqlRows(`select id, package_id, status, attempts, coalesce(last_lv_code,''), coalesce(last_detail,''), rebuilt,
      to_char(created_at at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US'), to_char(updated_at at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US')
      from pipeline_jobs where tenant_id = '${tenant}' and stage = 'EXPORT' order by queue_seq`)
    .map(([id, packageId, status, attempts, lvCode, detail, rebuilt, createdAt, updatedAt]) => ({
      id, packageId, status, attempts: Number(attempts), lvCode, detail, rebuilt: rebuilt === 't', createdAt, updatedAt,
    }));
}

export const jobOf = (tenant: string, packageId: string) => jobsOf(tenant).find((j) => j.packageId === packageId);

export function destinationState(tenant: string): string {
  return sql(`select coalesce((select state from export_destination_states where tenant_id='${tenant}' and connection_id='${localFolderDestination(tenant)}'), 'WORKING')`);
}

export function auditCount(tenant: string, action: string, detailLike = '%'): number {
  return Number(sql(`select count(*) from audit_records where tenant_id='${tenant}' and action='${action}' and coalesce(detail,'') like '${detailLike.replace(/'/g, "''")}'`));
}

/** The cases in LexaVault's own queue order: by their export job's queue number (task 0036; never the clock). */
export const queueOrder = (tenant: string) => jobsOf(tenant).map((j) => j.packageId);

/**
 * Task 0036: the order the cases really went out in, from the folder itself: when each case's exported file was
 * written (this machine's clock). With one worker per destination a case's file is written only after the one before
 * it is DONE, so this is the send order whatever the number of workers. Cases with no file yet are left out.
 */
export function sentOrder(tenant: string, packageIds: string[]): string[] {
  const written = packageIds.flatMap((id) => {
    const files = exportedFiles(tenant, id);
    if (!files.length) return [];
    const dir = path.join(tenantFolder(tenant), id);
    return [{ id, at: Math.min(...files.map((f) => fs.statSync(path.join(dir, f)).mtimeMs)) }];
  });
  return written.sort((a, b) => a.at - b.at).map((w) => w.id);
}

/** How far the database clock is behind (+) or ahead (-) of this machine's clock, in milliseconds. */
export function dbClockSkewMs(): number {
  const before = Date.now();
  const db = Number(sql('select extract(epoch from clock_timestamp()) * 1000')) ;
  const after = Date.now();
  return Math.round((before + after) / 2 - db);
}

/** The order the case jobs became DONE in, from their status audit rows. */
export function doneOrder(tenant: string): string[] {
  return sqlRows(`select resource_id from audit_records where tenant_id='${tenant}' and action='EXPORT_JOB_STATUS_CHANGED'
      and detail like '%to=DONE%' order by occurred_at, id`).map((r) => r[0]);
}

// ---------------------------------------------------------------- the folder destination

export const tenantFolder = (tenant: string) => path.join(OUT_DIR, tenant);

/** The entity's export folder can't be written: a plain file stands where the folder was. */
export function folderDown(tenant: string): void {
  const dir = tenantFolder(tenant);
  if (fs.existsSync(dir) && fs.statSync(dir).isDirectory()) fs.renameSync(dir, `${dir}.away`);
  fs.writeFileSync(dir, 'export folder taken away by the task 0035 test\n');
}

/** The folder is back, with whatever it held before. */
export function folderUp(tenant: string): void {
  const dir = tenantFolder(tenant);
  if (fs.existsSync(dir) && fs.statSync(dir).isFile()) fs.rmSync(dir);
  if (fs.existsSync(`${dir}.away`)) fs.renameSync(`${dir}.away`, dir);
}

/** One case can't be written while the folder is fine: a plain file stands where that case's folder would go. */
export function blockCase(tenant: string, packageId: string): void {
  fs.mkdirSync(tenantFolder(tenant), { recursive: true });
  fs.writeFileSync(path.join(tenantFolder(tenant), packageId), 'this case is blocked by the task 0035 test\n');
}

export function unblockCase(tenant: string, packageId: string): void {
  const p = path.join(tenantFolder(tenant), packageId);
  if (fs.existsSync(p) && fs.statSync(p).isFile()) fs.rmSync(p);
}

/** The case's export runs in the folder: cycle-1, cycle-2, … Each holds one folder per document. */
export function exportedCycles(tenant: string, packageId: string): string[] {
  const dir = path.join(tenantFolder(tenant), packageId);
  if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) return [];
  return fs.readdirSync(dir).filter((n) => n.startsWith('cycle-')).sort();
}

export function exportedFiles(tenant: string, packageId: string): string[] {
  const dir = path.join(tenantFolder(tenant), packageId);
  if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) return [];
  const files: string[] = [];
  const walk = (d: string) => fs.readdirSync(d, { withFileTypes: true }).forEach((e) => {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p); else files.push(path.relative(dir, p).replace(/\\/g, '/'));
  });
  walk(dir);
  return files.sort();
}

export const sha256 = (file: string) => createHash('sha256').update(fs.readFileSync(file)).digest('hex');

/**
 * The folder can't refuse a file the way a destination's API can (LV-DLV-003/004), so a refusal is put in place by
 * hand in the throwaway database: the job, while it waits for its next try, becomes WAITING_FOR_YOU with LV-DLV-004,
 * exactly the row a refusal leaves. Everything after it (Waiting for you, the resend, the export) runs for real.
 */
export function markRefused(tenant: string, jobId: string): boolean {
  return sql(`update pipeline_jobs set status='WAITING_FOR_YOU', last_lv_code='LV-DLV-004', last_detail='DELIVERY_REJECTED',
      claim_owner=null, claim_until=null, updated_at=clock_timestamp()
      where tenant_id='${tenant}' and id='${jobId}' and status='WAITING_RETRY' returning id`) !== '';
}
