import { test, expect, type Page } from '@playwright/test';
import * as fs from 'node:fs';
import * as path from 'node:path';
import {
  ACTOR, ROLES, as, auditCount, blockCase, call, dbClockSkewMs, destinationState, doneOrder, ensureEntity, exportedCycles,
  exportedFiles, folderDown, folderUp, jobOf, jobsOf, localFolderDestination, markRefused, newCase, ok, queueOrder, sha256,
  sql, sqlRows, startApp, stopApp, tenantFolder, tenantFor, unblockCase, waitFor,
} from './stack';

/**
 * Task 0035 (L5): the P1 export path on a running LexaVault (export-mode=job) with the folder destination, scenarios
 * 1 to 7 of the task, and the L6 proof pictures taken while each state is live. One entity per scenario.
 */
test.describe.configure({ mode: 'serial' });

const PROOF_DIR = process.env.LEXAVAULT_E2E_PROOF_DIR || path.join(process.cwd(), 'test-results', '0035-proof');

test.beforeAll(async () => {
  fs.mkdirSync(PROOF_DIR, { recursive: true });
  await startApp();
  for (let n = 1; n <= 8; n++) ensureEntity(tenantFor(n), `Export e2e ${n}`);
});
test.afterAll(async () => { await stopApp(); });

// The database clock against this machine's, before and after each scenario (in the run's results.json).
test.beforeEach(() => { test.info().annotations.push({ type: 'db-clock-skew-ms', description: `start ${dbClockSkewMs()}` }); });
test.afterEach(() => { test.info().annotations.push({ type: 'db-clock-skew-ms', description: `end ${dbClockSkewMs()}` }); });

const caseState = async (tenant: string, packageId: string): Promise<string> =>
  (await ok(as(tenant, ROLES.processor), 'GET', `/packages/${packageId}/export-jobs`)).exportState;

/** Finishes cases one after the other: the next is submitted once the last one's export job is queued. */
async function finishCases(tenant: string, prefix: string, n: number, beforeSubmit?: (id: string) => void): Promise<string[]> {
  const ids: string[] = [];
  for (let i = 1; i <= n; i++) {
    const id = await newCase(tenant, `${prefix}-${i}`, beforeSubmit);
    await waitFor(`export job of ${prefix}-${i}`, async () => jobOf(tenant, id), 120_000);
    ids.push(id);
  }
  return ids;
}

/**
 * "In the order they finished": LexaVault orders its queue by the export job's created_at, which is the database's
 * clock. The cases here finish one after the other (the next starts once the last one's job exists), so that order
 * is also the real order unless the database clock stepped back in between (Docker Desktop's VM clock does; see the
 * report). The jobs must go out in LexaVault's own order; a difference from the real order is recorded, not failed.
 */
function expectQueueOrder(tenant: string, realOrder: string[]): void {
  const queue = queueOrder(tenant);
  if (queue.join() !== realOrder.join()) {
    test.info().annotations.push({ type: 'db-clock', description: `queue order differs from real order (db clock stepped back): ${queue.map((id) => realOrder.indexOf(id) + 1).join(',')}` });
  }
  expect(doneOrder(tenant)).toEqual(queue);
}

const statusOf = (tenant: string, id: string) => jobOf(tenant, id)?.status;
const allDone = (tenant: string, ids: string[]) => ids.every((id) => statusOf(tenant, id) === 'DONE');

// ---------------------------------------------------------------- the screens (L6)

/** Opens the app as this entity and role: the LOCAL identity headers of every API call are set to them. */
async function openAs(page: Page, tenant: string, roles: string, urlPath: string): Promise<void> {
  await page.unroute('**/api/v1/**');
  await page.route('**/api/v1/**', (route) => route.continue({
    headers: { ...route.request().headers(), 'x-tenant-id': tenant, 'x-actor-id': ACTOR, 'x-actor-roles': roles },
  }));
  await page.goto(urlPath);
  await page.locator('#cf-main h1').first().waitFor({ timeout: 30_000 });
}

async function shot(page: Page, name: string): Promise<void> {
  await page.waitForTimeout(400);
  await page.screenshot({ path: path.join(PROOF_DIR, `${name}.png`), fullPage: false });
}

const panel = (page: Page) => page.getByRole('complementary', { name: 'Case panel' });

// ---------------------------------------------------------------- 1

test('scenario 1: happy path, 3 cases exported to the folder @e2e-export', async ({ page }) => {
  const tenant = tenantFor(1);
  const ids = await finishCases(tenant, 'LN-1001', 3);
  await waitFor('all 3 DONE', async () => allDone(tenant, ids), 60_000);
  for (const id of ids) {
    expect(exportedCycles(tenant, id)).toEqual(['cycle-1']);
    expect(exportedFiles(tenant, id)).toHaveLength(1);
    expect(await caseState(tenant, id)).toBe('EXPORTED');
  }
  expect(jobsOf(tenant).map((j) => j.status)).toEqual(['DONE', 'DONE', 'DONE']);
  expect(auditCount(tenant, 'EXPORT_JOB_QUEUED')).toBe(3);
  expect(auditCount(tenant, 'EXPORT_JOB_STATUS_CHANGED', '%from=RUNNING, to=DONE%')).toBe(3);
  expect(auditCount(tenant, 'PACKAGE_EXPORT_RECORDED')).toBe(3);
  expect(auditCount(tenant, 'PACKAGE_PROCESSING_COMPLETED')).toBe(3);

  await openAs(page, tenant, ROLES.processor, `/case/${ids[0]}`);
  await expect(panel(page)).toContainText('Exported');
  await shot(page, '04-case-exported');
});

// ---------------------------------------------------------------- 2

test('scenario 2: destination down, health check fails: paused, no attempts, resumed by the probe in order @e2e-export', async ({ page }) => {
  const tenant = tenantFor(2);
  const it = as(tenant, ROLES.itAdmin);
  folderDown(tenant);
  const ids = await finishCases(tenant, 'LN-2001', 3);

  await waitFor('destination PAUSED', async () => destinationState(tenant) === 'PAUSED', 60_000);
  // Every case is held, none has used an attempt.
  await waitFor('3 cases destination paused', async () => {
    for (const id of ids) if (await caseState(tenant, id) !== 'DESTINATION_PAUSED') return false;
    return true;
  }, 30_000);
  const probedBefore = sql(`select last_probe_at from export_destination_states where tenant_id='${tenant}'`);
  await page.waitForTimeout(9_000); // two probe intervals of the test profile (4 s): it is checked and stays paused
  expect(destinationState(tenant)).toBe('PAUSED');
  expect(sql(`select last_probe_at from export_destination_states where tenant_id='${tenant}'`)).not.toBe(probedBefore);
  const held = jobsOf(tenant);
  expect(held.map((j) => j.attempts)).toEqual([0, 0, 0]);
  // The one that tried waits for the destination (no attempt used); the other two were never tried.
  expect(held.filter((j) => j.status === 'WAITING_RETRY')).toMatchObject([{ detail: 'DESTINATION_PAUSED', lvCode: 'LV-DLV-001' }]);
  expect(held.filter((j) => j.status === 'READY')).toHaveLength(2);
  expect(auditCount(tenant, 'EXPORT_DESTINATION_PAUSED', '%reason=CHECK_FAILED%')).toBe(1);
  // The only destination: LexaVault pauses it. The "can't reach its destinations" alert needs at least 2 (see report).
  const alert = await ok(it, 'GET', '/support/export-destinations/alert');
  expect(alert.raised).toBe(false);
  expect(auditCount(tenant, 'EXPORT_DESTINATIONS_UNREACHABLE_ALERT')).toBe(0);
  const dests = await ok(it, 'GET', '/support/export-destinations');
  expect(dests).toHaveLength(1);
  expect(dests[0]).toMatchObject({ name: 'Local export folder', state: 'PAUSED', openJobs: 3 });

  await openAs(page, tenant, ROLES.processor, `/case/${ids[1]}`);
  await expect(panel(page).getByRole('region', { name: 'Next step' })).toContainText('destination paused');
  await shot(page, '01-case-exporting-destination-paused');
  await openAs(page, tenant, ROLES.itAdmin, '/support/export');
  await expect(page.getByTestId('export-destination').first()).toBeVisible();
  await shot(page, '01b-support-destination-paused');

  // Back: the probe resumes it on its own, and the cases go out in the order they finished.
  folderUp(tenant);
  await waitFor('all 3 DONE after the probe', async () => allDone(tenant, ids), 60_000);
  expect(auditCount(tenant, 'EXPORT_DESTINATION_RESUMED', '%by=probe%')).toBe(1);
  expectQueueOrder(tenant, ids);
  for (const id of ids) expect(exportedCycles(tenant, id)).toEqual(['cycle-1']);
  expect(jobsOf(tenant).map((j) => j.attempts)).toEqual([1, 1, 1]);
});

// ---------------------------------------------------------------- 3

test('scenario 3: one case fails while the destination is healthy: its own schedule, one rebuild, then Needs IT @e2e-export', async ({ page }) => {
  const tenant = tenantFor(3);
  // The failing case: a plain file stands where its folder would go, so only its write fails; the check passes.
  const [failing] = await finishCases(tenant, 'LN-3001', 1, (id) => blockCase(tenant, id));
  const others = await finishCases(tenant, 'LN-3002', 2);

  await waitFor('the other cases DONE', async () => allDone(tenant, others), 60_000);
  expect(['WAITING_RETRY', 'RUNNING', 'READY']).toContain(statusOf(tenant, failing)); // not held up by it

  await waitFor('the failing case at NEEDS_IT', async () => statusOf(tenant, failing) === 'NEEDS_IT', 90_000);
  const job = jobOf(tenant, failing)!;
  expect(job).toMatchObject({ status: 'NEEDS_IT', attempts: 6, lvCode: 'LV-DLV-001', rebuilt: true });
  expect(destinationState(tenant)).toBe('WORKING');
  expect(auditCount(tenant, 'EXPORT_DESTINATION_PAUSED')).toBe(0);
  expect(auditCount(tenant, 'EXPORT_JOB_REBUILT')).toBe(1);
  expect(auditCount(tenant, 'EXPORT_JOB_NEEDS_IT_NOTIFICATION')).toBe(1);
  // Its schedule: 5 waits (2 s each in the test profile), then IT. The rebuild came before the 3rd retry.
  const steps = sqlRows(`select detail, extract(epoch from occurred_at) from audit_records where tenant_id='${tenant}'
      and resource_id='${failing}' and action in ('EXPORT_JOB_STATUS_CHANGED','EXPORT_JOB_REBUILT') order by occurred_at, id`);
  const kinds = steps.map(([d]) => (d.includes('rebuilt') ? 'REBUILT' : /to=(\w+)/.exec(d)![1]));
  expect(kinds).toEqual(['WAITING_RETRY', 'WAITING_RETRY', 'WAITING_RETRY', 'REBUILT', 'WAITING_RETRY', 'WAITING_RETRY', 'NEEDS_IT']);
  const times = steps.filter(([d]) => !d.includes('rebuilt')).map(([, t]) => Number(t));
  // Audit rows are written just after each try, so a 2 s wait can read a little shorter between rows.
  for (let i = 1; i < times.length; i++) expect(times[i] - times[i - 1]).toBeGreaterThanOrEqual(1.5);
  expect(await caseState(tenant, failing)).toBe('NEEDS_IT');
  for (const id of others) expect(await caseState(tenant, id)).toBe('EXPORTED');

  await openAs(page, tenant, ROLES.processor, `/case/${failing}`);
  await expect(panel(page).getByRole('region', { name: 'Next step' })).toContainText('Waiting for IT');
  await shot(page, '02-case-exporting-needs-it');
  unblockCase(tenant, failing);
});

// ---------------------------------------------------------------- 4

test('scenario 4: backup rule: 3 different cases failing within 10 minutes pause the destination, the check passing @e2e-export', async () => {
  const tenant = tenantFor(4);
  const ids: string[] = [];
  for (let i = 1; i <= 3; i++) ids.push(await newCase(tenant, `LN-4001-${i}`, (id) => blockCase(tenant, id)));

  await waitFor('paused by the backup rule', async () =>
    auditCount(tenant, 'EXPORT_DESTINATION_PAUSED', '%reason=FAILING_CASES, cases=3%') >= 1, 90_000);
  expect(auditCount(tenant, 'EXPORT_DESTINATION_PAUSED', '%reason=CHECK_FAILED%')).toBe(0);
  // The health check passes all along: Support's "Check now" says so.
  const check = await ok(as(tenant, ROLES.itAdmin), 'POST', `/support/export-destinations/${localFolderDestination(tenant)}/check`);
  expect(check.passed).toBe(true);

  for (const id of ids) unblockCase(tenant, id);
  await waitFor('all 3 DONE once the cases can be written', async () => allDone(tenant, ids), 90_000);
  for (const id of ids) expect(exportedCycles(tenant, id)).toEqual(['cycle-1']);
});

// ---------------------------------------------------------------- 5

test('scenario 5a: IT queue: one grouped alert, "Retry all" works @e2e-export', async ({ page }) => {
  const tenant = tenantFor(5);
  const ids: string[] = [];
  for (let i = 1; i <= 2; i++) ids.push(await newCase(tenant, `LN-5001-${i}`, (id) => blockCase(tenant, id)));
  await waitFor('both NEEDS_IT', async () => ids.every((id) => statusOf(tenant, id) === 'NEEDS_IT'), 120_000);

  const it = as(tenant, ROLES.itAdmin);
  const queue = await ok(it, 'GET', '/support/export-jobs/needs-it');
  expect(queue.total).toBe(2);
  expect(queue.groups).toHaveLength(1);
  expect(queue.groups[0]).toMatchObject({ destinationName: 'Local export folder', lvCode: 'LV-DLV-001', count: 2 });
  expect(auditCount(tenant, 'EXPORT_JOB_NEEDS_IT_NOTIFICATION')).toBe(1);

  for (const id of ids) unblockCase(tenant, id);
  await openAs(page, tenant, ROLES.itAdmin, '/support/export');
  const group = page.getByTestId('it-group');
  await expect(group).toHaveCount(1);
  await expect(group).toContainText('LV-DLV-001');
  await shot(page, '05-support-it-queue-grouped-alert');
  await group.getByRole('button', { name: /^Retry all 2/ }).click();
  await waitFor('both DONE after Retry all', async () => allDone(tenant, ids), 60_000);
  expect(auditCount(tenant, 'EXPORT_JOB_IT_GROUP_RETRY_REQUESTED')).toBe(1);
  expect(auditCount(tenant, 'EXPORT_JOB_IT_RETRY_REQUESTED')).toBe(2);
  await shot(page, '05b-support-after-retry-all');
});

test('scenario 5b: "Resend all" per destination; who may @e2e-export', async ({ page }) => {
  const tenant = tenantFor(8);
  const ids: string[] = [];
  for (let i = 1; i <= 2; i++) ids.push(await newCase(tenant, `LN-8001-${i}`, (id) => blockCase(tenant, id)));
  for (const id of ids) {
    await waitFor(`refusal in place for ${id}`, async () => markRefused(tenant, jobOf(tenant, id)!.id), 60_000, 100);
    unblockCase(tenant, id);
  }
  expect(ids.map((id) => statusOf(tenant, id))).toEqual(['WAITING_FOR_YOU', 'WAITING_FOR_YOU']);

  await openAs(page, tenant, ROLES.processor, `/case/${ids[0]}`);
  const box = panel(page).getByRole('region', { name: 'Next step' });
  await expect(box.locator('.cf-eyebrow')).toHaveText('Exporting · waiting for you');
  await shot(page, '03-case-exporting-waiting-for-you');

  await openAs(page, tenant, ROLES.processor, '/waiting');
  const groups = page.getByTestId('waiting-group');
  await expect(groups).toHaveCount(1);
  await shot(page, '03b-waiting-for-you');
  await groups.first().getByRole('button', { name: 'Resend all 2' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await shot(page, '06-resend-all-confirm');
  await dialog.getByRole('button', { name: 'Resend 2 cases' }).click();
  await expect(page.locator('.cf-toast')).toContainText('2 cases queued for');
  await waitFor('both DONE after Resend all', async () => allDone(tenant, ids), 60_000);
  expect(auditCount(tenant, 'EXPORT_JOB_RESEND_ALL_REQUESTED')).toBe(1);
  for (const id of ids) expect(exportedCycles(tenant, id)).toEqual(['cycle-1']);

  // EXPORT_RESEND: Processor and Supervisor may; roles without it are refused.
  const body = { destinationId: localFolderDestination(tenant) };
  const status = async (roles: string) => (await call(as(tenant, roles), 'POST', '/export-jobs/resend-all', body)).status;
  expect(await status(ROLES.processor)).toBe(200);
  expect(await status(ROLES.supervisor)).toBe(200);
  for (const roles of [ROLES.businessAdmin, ROLES.reportViewer, ROLES.itAdmin, ROLES.platformOps]) expect(await status(roles)).toBe(403);
  // "Reviewer" is not a role of its own: LexaVault reads it as a Document Processor (ProductRole), so it may (see report).
  expect(await status(ROLES.reviewer)).toBe(200);
});

// ---------------------------------------------------------------- 6

test('scenario 6: restart while jobs are WAITING_RETRY and PAUSED: nothing lost, nothing sent twice @e2e-export', async () => {
  const tenant = tenantFor(6);
  folderDown(tenant);
  const ids = await finishCases(tenant, 'LN-6001', 3);
  await waitFor('destination PAUSED', async () => destinationState(tenant) === 'PAUSED', 60_000);
  const before = jobsOf(tenant).map((j) => `${j.status}/${j.attempts}`);
  expect([...before].sort()).toEqual(['READY/0', 'READY/0', 'WAITING_RETRY/0']);

  await stopApp();
  const stoppedAt = Date.now();
  await startApp();
  // After the restart, still down: everything is where it was.
  await new Promise((r) => setTimeout(r, 6_000));
  expect(destinationState(tenant)).toBe('PAUSED');
  expect(jobsOf(tenant).map((j) => `${j.status}/${j.attempts}`)).toEqual(before);

  folderUp(tenant);
  await waitFor('all 3 DONE after the restart', async () => allDone(tenant, ids), 60_000);
  expectQueueOrder(tenant, ids);
  for (const id of ids) {
    expect(exportedCycles(tenant, id)).toEqual(['cycle-1']);
    expect(exportedFiles(tenant, id)).toHaveLength(1);
  }
  expect(auditCount(tenant, 'EXPORT_JOB_STATUS_CHANGED', '%to=DONE%')).toBe(3);
  expect(auditCount(tenant, 'PACKAGE_EXPORT_RECORDED')).toBe(3);
  expect(Number(sql(`select count(*) from package_export_records where tenant_id='${tenant}'`))).toBe(3);
  expect(fs.readdirSync(path.join(tenantFolder(tenant), '.staging')).length).toBe(0);
  // Not export: the stopped server still counts as an active node for processing storage for 30 s after its last
  // heartbeat (ProcessingStorageConfigurationService.ACTIVE_NODE_WINDOW), and LOCAL_FILESYSTEM storage refuses new
  // uploads while two nodes look active (LV-SYS-002). Wait that out so the next scenario can upload (see report).
  const left = stoppedAt + 36_000 - Date.now();
  if (left > 0) await new Promise((r) => setTimeout(r, left));
});

// ---------------------------------------------------------------- 7

test('scenario 7: retention: a resend uses the copy kept for the destination; after the window an exported copy is gone @e2e-export', async () => {
  const tenant = tenantFor(7);
  const processor = as(tenant, ROLES.processor);
  const ops = as(tenant, ROLES.platformOps);
  const redacted = (id: string) => sqlRows(`select a.status, a.checksum_value from artifacts a join documents d on d.id = a.document_id
      where a.tenant_id='${tenant}' and d.package_id='${id}' and a.artifact_type='REDACTED' order by a.created_at`);
  // The retention window is at least 1 hour (ExportRetentionService.MIN_HOURS); its end is moved into the past here.
  const windowPassed = (id: string) => sql(`update package_lifecycles set redacted_delete_at = now() - interval '1 minute'
      where tenant_id='${tenant}' and package_id='${id}' returning package_id`);

  // A: refused and waiting for a person while its window passes: the copy is kept, the resend sends that copy.
  const [waiting] = await finishCases(tenant, 'LN-7001', 1, (id) => blockCase(tenant, id));
  await waitFor('refusal in place', async () => markRefused(tenant, jobOf(tenant, waiting)!.id), 60_000, 100);
  unblockCase(tenant, waiting);
  sql(`update package_lifecycles set redacted_delete_at = now() - interval '1 minute' where tenant_id='${tenant}' and package_id='${waiting}'`);
  await ok(ops, 'POST', '/lifecycle/purge-due?limit=100');
  expect(redacted(waiting).map(([s]) => s)).toEqual(['AVAILABLE']);
  const resent = await ok(processor, 'POST', '/export-jobs/resend', { jobIds: [jobOf(tenant, waiting)!.id] });
  expect(resent).toMatchObject([{ status: 'DONE', saved: true }]);
  const [file] = exportedFiles(tenant, waiting);
  expect(sha256(path.join(tenantFolder(tenant), waiting, file))).toBe(redacted(waiting)[0][1]);

  // B: exported, then its window passes: the purge removes the copy, and a resend is refused with a code.
  const [exported] = await finishCases(tenant, 'LN-7002', 1);
  await waitFor('B DONE', async () => statusOf(tenant, exported) === 'DONE', 60_000);
  expect(windowPassed(exported)).toBe(exported);
  const purge = await ok(ops, 'POST', '/lifecycle/purge-due?limit=100');
  expect(purge.redactedCachesPurged).toBeGreaterThanOrEqual(1);
  expect(redacted(exported).map(([s]) => s)).toEqual(['DELETED']);
  expect(auditCount(tenant, 'REDACTED_ARTIFACT_PURGED')).toBeGreaterThanOrEqual(1);
  const again = await ok(processor, 'POST', '/export-jobs/resend', { jobIds: [jobOf(tenant, exported)!.id] });
  expect(again).toMatchObject([{ saved: false, lvCode: 'LV-CFG-011', status: null }]);
  const lifecycle = await ok(processor, 'GET', `/packages/${exported}/lifecycle`);
  expect(lifecycle.redactedPurgedAt).toBeTruthy();
});
