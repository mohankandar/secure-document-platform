/** Task 0081: one made-up day of Reports › Daily intake and delivery (Tuesday Nov 24, 2026, 11:40 AM in New York). */
export const REPORT_SOURCES = [
  { key: 'c-scan', name: 'Branch scanners' }, { key: 'c-push', name: 'Loan origination' }, { key: 'type:MANUAL_UPLOAD', name: 'Manual upload' },
];

export const REPORT_QUEUE = {
  staged: true, allEntities: false, windowMinutes: 10, etaSeconds: 2400,
  stages: ['TEXT', 'FIND', 'VISION', 'REDACT', 'EXPORT'].map((stage, i) => ({ stage, waiting: 12 - i, running: 2, held: i === 4 ? 3 : 0, toCome: 4,
    workers: 2, highest: 4, oldestWaitingSeconds: 180, perMinute: 6, etaSeconds: 600 })),
};

export function dailyReport() {
  return {
    generatedAt: '2026-11-24T16:40:00Z', from: '2026-11-24T05:00:00Z', to: '2026-11-25T05:00:00Z', includesNow: true, refusalsCounted: true,
    quiet: [{ sourceKey: 'c-scan', name: 'Branch scanners', lastReceivedAt: '2026-11-24T14:12:00Z', usualMinutes: 60 }],
    received: { cases: 47, documents: 182, pages: 1463, urgent: 3, usual: { low: 38, high: 52 } },
    exported: { cases: 41, documents: 160, pages: 1302, splitDocuments: 18, urgent: 2, usual: { low: 35, high: 50 } },
    notExported: { cases: 9, ready: 2, waitingToRetry: 1, destinationPaused: 4, waitingForYou: 2, urgent: 1, oldestWaitingSeconds: 8100 },
    attention: { refused: 3, stopped: 2, exportFailed: 1, rejected: 1 },
    reasons: [
      { code: 'LV-INT-003', where: 'REFUSED_AT_INTAKE', cases: 2 }, { code: 'LV-INT-004', where: 'REFUSED_AT_INTAKE', cases: 1 },
      { code: 'LV-PRC-004', where: 'STOPPED_IN_PROCESSING', cases: 2 }, { code: 'LV-DLV-005', where: 'EXPORT_FAILED', cases: 1 },
      { code: 'LV-REV-013', where: 'REJECTED', cases: 1 },
    ],
    destinations: [
      { key: 'd-1', name: 'Records store', split: false, paused: false, exported: 41, documents: 160, pages: 1302, failed: 1, waitingToRetry: 1 },
      { key: 'd-2', name: 'Title desk', split: true, paused: true, exported: 18, documents: 18, pages: 54, failed: 0, waitingToRetry: 0 },
    ],
    sources: [
      { key: 'c-scan', name: 'Branch scanners', lastReceivedAt: '2026-11-24T14:12:00Z', received: 6, duplicates: 0, refused: 0, exported: 6, inProgress: 0, stopped: 0, quiet: true },
      { key: 'c-push', name: 'Loan origination', lastReceivedAt: '2026-11-24T16:38:00Z', received: 38, duplicates: 2, refused: 2, exported: 30, inProgress: 6, stopped: 2, quiet: false },
      { key: 'type:MANUAL_UPLOAD', name: 'Manual upload', lastReceivedAt: '2026-11-23T20:05:00Z', received: 3, duplicates: 0, refused: 1, exported: 1, inProgress: 2, stopped: 0, quiet: false },
    ],
    handled: { noPerson: 30, afterReview: 6, byHand: 1, inProgress: 8, stopped: 2, rejected: 0 },
    levels: [{ level: 'STANDARD', received: 120, exported: 101 }, { level: 'CONFIDENTIAL', received: 50, exported: 47 }, { level: 'HIGHLY_CONFIDENTIAL', received: 12, exported: 12 }],
    waitingForTrigger: 0,
  };
}

/** Task 0105: Reports, Automation (made-up counts). */
export const AUTOMATION_REPORT = {
  period: 'TODAY', from: '2026-11-24T05:00:00Z', to: '2026-11-25T05:00:00Z', automaticPercent: 90,
  automatic: 103, placedByPerson: 9, drawnByPerson: 2, byText: 92, byVision: 20,
};

/** The answer of the Reports tabs, or undefined for another path (under /api/v1). */
export function reportTabsAnswer(path: string, day: () => unknown = dailyReport): unknown {
  if (path === '/operations/reports/daily') return day();
  if (path === '/operations/reports/sources') return REPORT_SOURCES;
  if (path === '/operations/reports/queue') return REPORT_QUEUE;
  if (path === '/operations/reports/automation') return AUTOMATION_REPORT;
  return undefined;
}
