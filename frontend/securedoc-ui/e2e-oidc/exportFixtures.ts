/**
 * Task 0029: the export API answers (tasks 0027/0028) for the mocked suites: e2e-oidc/exports.spec.ts, the
 * accessibility harness and the tablet suite. Codes, ids and times only, as the real API sends them. `inline` answers
 * are what an inline-mode server sends: nothing waiting, no groups, every destination working.
 */

const minsAgo = (m: number) => new Date(Date.now() - m * 60_000).toISOString();
const minsAhead = (m: number) => new Date(Date.now() + m * 60_000).toISOString();

export const VAULT = 'a0a0a0a0-0000-4000-8000-0000000000d1';
export const TITLE_CO = 'a0a0a0a0-0000-4000-8000-0000000000d2';
export const ARCHIVE = 'a0a0a0a0-0000-4000-8000-0000000000d3';

/** Task 0036: the default timings, as the server sends them with the case export and the IT queue. */
export const DEFAULT_TIMINGS = { probeIntervalSeconds: 300, retrySpanSeconds: 62_400, retries: 5, rebuiltOnce: true, maxInterruptions: 3 };

export interface ExportCase { packageId: string; loan: string; status: string; exportState: string; documents?: string[] }

/** The cases the export screens show, one per state, each with its loan number. */
export function exportCases(prefix: string): ExportCase[] {
  const id = (n: string) => `${prefix}-0000-4000-8000-00000000${n}`;
  return [
    { packageId: id('7020'), loan: 'LN-7020', status: 'PROCESSING', exportState: 'READY' },
    { packageId: id('7015'), loan: 'LN-7015', status: 'PROCESSING', exportState: 'DESTINATION_PAUSED' },
    { packageId: id('7004'), loan: 'LN-7004', status: 'IN_REVIEW', exportState: 'WAITING_FOR_YOU' },
    { packageId: id('7011'), loan: 'LN-7011', status: 'PROCESSING', exportState: 'WAITING_FOR_YOU' },
    { packageId: id('6987'), loan: 'LN-6987', status: 'PROCESSING', exportState: 'WAITING_FOR_YOU' },
    { packageId: id('7002'), loan: 'LN-7002', status: 'COMPLETED', exportState: 'WAITING_FOR_YOU', documents: [id('d701'), id('d702'), id('d703'), id('d704'), id('d705')] },
    { packageId: id('6990'), loan: 'LN-6990', status: 'PROCESSING', exportState: 'NEEDS_IT' },
    { packageId: id('6985'), loan: 'LN-6985', status: 'COMPLETED', exportState: 'EXPORTED' },
  ];
}

const job = (jobId: string, c: ExportCase, over: Record<string, unknown>) => ({
  jobId, packageId: c.packageId, stage: 'EXPORT', documentId: null, destinationId: VAULT, destinationName: 'Document vault', status: 'WAITING_FOR_YOU',
  attempts: 1, lvCode: 'LV-DLV-003', detail: 'DELIVERY_SENSITIVITY_REFUSED', since: minsAgo(40), nextAttemptAt: null, createdAt: minsAgo(60),
  firstFailedAt: minsAgo(40), resend: false, ...over,
});

export function exportApi(cases: ExportCase[]) {
  const by = (loan: string) => cases.find((c) => c.loan === loan) as ExportCase;
  const vaultJobs = [
    // Task 0030: the destination's own code after LexaVault's reason; j-7011's is not a safe code, so no screen shows it.
    job('j-7004', by('LN-7004'), { since: minsAgo(50), detail: 'DELIVERY_SENSITIVITY_REFUSED; destination=SDV-SEN-006' }),
    job('j-7011', by('LN-7011'), { since: minsAgo(35), detail: 'DELIVERY_SENSITIVITY_REFUSED; destination=<b>SDV SEN 006</b>' }),
    job('j-6987', by('LN-6987'), { lvCode: 'LV-DLV-004', detail: 'DELIVERY_REJECTED', since: minsAgo(60 * 22) }),
  ];
  const split = by('LN-7002');
  const titleJobs = [
    job('j-7002-3', split, { stage: 'SPLIT_EXPORT', documentId: split.documents?.[2], destinationId: TITLE_CO, destinationName: 'Title company', lvCode: 'LV-DLV-004', detail: 'DERIVED_DELIVERY_REFUSED', since: minsAgo(20) }),
    job('j-7002-5', split, { stage: 'SPLIT_EXPORT', documentId: split.documents?.[4], destinationId: TITLE_CO, destinationName: 'Title company', lvCode: 'LV-DLV-004', detail: 'DERIVED_DELIVERY_REFUSED', since: minsAgo(20) }),
  ];
  const waiting = {
    total: 5,
    destinations: [
      { destinationId: VAULT, destinationName: 'Document vault', total: 3, countsByCode: { 'LV-DLV-003': 2, 'LV-DLV-004': 1 }, jobs: vaultJobs },
      { destinationId: TITLE_CO, destinationName: 'Title company', total: 2, countsByCode: { 'LV-DLV-004': 2 }, jobs: titleJobs },
    ],
  };
  const it = by('LN-6990');
  const needsIt = {
    total: 13,
    groups: [
      { destinationId: VAULT, destinationName: 'Document vault', lvCode: 'LV-DLV-007', count: 1, firstFailedAt: minsAgo(90), caseIds: [it.packageId], cases: [{ packageId: it.packageId, caseNumber: it.loan }],
        jobs: [job('j-6990', it, { status: 'NEEDS_IT', lvCode: 'LV-DLV-007', detail: 'DELIVERY_IO; destination=SDV-SUB-017', attempts: 6 })] },
      { destinationId: TITLE_CO, destinationName: 'Title company', lvCode: 'LV-DLV-005', count: 1, firstFailedAt: minsAgo(30), caseIds: [split.packageId], cases: [{ packageId: split.packageId, caseNumber: split.loan }],
        jobs: [job('j-title-auth', split, { stage: 'SPLIT_EXPORT', status: 'NEEDS_IT', destinationId: TITLE_CO, destinationName: 'Title company', lvCode: 'LV-DLV-005' })] },
    ],
    timings: DEFAULT_TIMINGS,
  };
  const destinations = [
    { destinationId: VAULT, name: 'Document vault', purpose: 'SDV_DELIVERY', enabled: true, state: 'WORKING', pausedAt: null, consecutiveFailures: 0, lastProbeAt: null,
      lastLvCode: null, lastCheckFailedAt: null, rateLimitedUntil: null, workerLimit: 4, nextCheckAt: null, openJobs: 7,
      waiting: { queued: 3, waitingForYou: 3, needsIt: 1 } },
    { destinationId: ARCHIVE, name: 'Archive service', purpose: 'DERIVED_DOCUMENT_DELIVERY', enabled: true, state: 'PAUSED', pausedAt: minsAgo(18), consecutiveFailures: 0,
      lastProbeAt: minsAgo(3), lastLvCode: 'LV-DLV-001', lastCheckFailedAt: minsAgo(3), rateLimitedUntil: null, workerLimit: 4, nextCheckAt: minsAhead(2), openJobs: 27,
      waiting: { queued: 27, waitingForYou: 0, needsIt: 0 } },
    { destinationId: TITLE_CO, name: 'Title company', purpose: 'DERIVED_DOCUMENT_DELIVERY', enabled: true, state: 'NEEDS_IT', pausedAt: minsAgo(30), consecutiveFailures: 0,
      lastProbeAt: null, lastLvCode: 'LV-DLV-005', lastCheckFailedAt: null, rateLimitedUntil: null, workerLimit: 4, nextCheckAt: null, openJobs: 12,
      waiting: { queued: 11, waitingForYou: 0, needsIt: 1 } },
  ];
  const caseExport = (packageId: string) => {
    const c = cases.find((x) => x.packageId === packageId);
    if (!c) return { exportState: 'NONE', jobs: [], timings: DEFAULT_TIMINGS };
    const jobs = [...vaultJobs, ...titleJobs, ...needsIt.groups.flatMap((g) => g.jobs)].filter((j) => j.packageId === packageId);
    if (c.exportState === 'READY') jobs.push(job('j-ready', c, { status: 'READY', lvCode: null, detail: null }));
    if (c.exportState === 'DESTINATION_PAUSED') jobs.push(job('j-paused', c, { status: 'WAITING_RETRY', destinationId: ARCHIVE, destinationName: 'Archive service', lvCode: 'LV-DLV-001', detail: 'DESTINATION_PAUSED' }));
    if (c.exportState === 'EXPORTED') jobs.push(job('j-done', c, { status: 'DONE', lvCode: null, detail: null }));
    return { exportState: c.exportState, jobs, timings: DEFAULT_TIMINGS };
  };
  return { waiting, needsIt, destinations, alert: { raised: true, lvCode: 'LV-DLV-001', detail: 'ALL_DESTINATIONS_UNREACHABLE', raisedAt: minsAgo(4), lastCheckAt: minsAgo(1), clearedAt: null }, caseExport };
}

/** What an inline-mode server sends: nothing waiting, nothing for IT, the alert not raised. */
export const INLINE_EXPORT = {
  waiting: { total: 0, destinations: [] },
  needsIt: { total: 0, groups: [] },
  destinations: [] as unknown[],
  alert: { raised: false, lvCode: null, detail: null, raisedAt: null, lastCheckAt: null, clearedAt: null },
  caseExport: { exportState: 'NONE', jobs: [] },
};

export function retentionSettings(master: number | null = null, title: number | null = 12) {
  const masterHours = master ?? 24;
  return {
    master: { destinationId: VAULT, name: 'Document vault', setHours: master, hours: masterHours },
    masterDefaultHours: 24, minHours: 1, maxHours: 72,
    splitDestinations: [
      { destinationId: TITLE_CO, name: 'Title company', setHours: title, hours: title ?? masterHours },
      { destinationId: ARCHIVE, name: 'Archive service', setHours: null, hours: masterHours },
    ],
  };
}

/**
 * Task 0030: every visible status chip in the Cases list is on one line and whole (not wrapped, not cut, inside its
 * column), and no row scrolls sideways. Returns what is wrong, so a test can show it; empty when all is well.
 */
export async function chipProblems(page: import('@playwright/test').Page): Promise<string[]> {
  return page.$$eval('.cf-case-stage .cf-stage', (chips) => chips.filter((c) => (c as HTMLElement).offsetParent !== null).flatMap((c) => {
    const chip = c as HTMLElement;
    const box = chip.getBoundingClientRect();
    const style = getComputedStyle(chip);
    const oneLine = parseFloat(style.fontSize) * 1.5 + parseFloat(style.paddingTop) + parseFloat(style.paddingBottom) + 2;
    const cell = chip.parentElement!.getBoundingClientRect();
    const row = chip.closest('.cf-case') as HTMLElement;
    const problems: string[] = [];
    if (box.height > oneLine) problems.push(`"${chip.textContent}" is ${Math.round(box.height)} px high (one line is up to ${Math.round(oneLine)})`);
    if (chip.scrollWidth > chip.clientWidth + 1) problems.push(`"${chip.textContent}" is cut`);
    if (box.right > cell.right + 0.5) problems.push(`"${chip.textContent}" runs out of its column`);
    if (row.scrollWidth > row.clientWidth + 1) problems.push(`the row of "${chip.textContent}" scrolls sideways`);
    return problems;
  }));
}

/** Task 0030: the case panel's export eyebrow and stage names are each on one line. */
export async function panelLineProblems(page: import('@playwright/test').Page): Promise<string[]> {
  return page.$$eval('.cf-drawer .cf-export-step .cf-eyebrow, .cf-drawer .cf-timeline-label', (els) => els.flatMap((e) => {
    const el = e as HTMLElement;
    const style = getComputedStyle(el);
    const line = style.lineHeight === 'normal' ? parseFloat(style.fontSize) * 1.5 : parseFloat(style.lineHeight) * 1.2;
    const h = el.getBoundingClientRect().height;
    return h > line + 1 ? [`"${el.textContent}" is ${Math.round(h)} px high (one line is up to ${Math.round(line)})`] : [];
  }));
}
