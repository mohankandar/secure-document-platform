/** Task 0057: the words of Admin › Licensing. Pure functions, tested without a browser. Dates are the licence's own days. */

export interface LicenceShown {
  licenceId: string;
  version: number;
  customer: string;
  type: string;
  kind: string;
  testEnvironment: boolean;
  state: string;
  message: string;
  startsOn: string;
  expiresOn?: string | null;
  updatesUntil?: string | null;
  daysUntilExpiry?: number | null;
  graceEndsOn?: string | null;
  clockMessage?: string | null;
  waiting?: { licenceId: string; version: number; startsOn: string } | null;
}

export interface AllowanceShown { type: string; unit: string; used: number; limit: number; level: string }

export interface LicensingShown {
  intakeAllowed: boolean;
  stopped?: { code: string; title: string; platformCode?: string | null } | null;
  licence?: LicenceShown | null;
  entity?: { slotId: string; month: string; allowances: AllowanceShown[] } | null;
}

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

/** "2026-06-30" as "30 June 2026": a day of the licence, the same everywhere. */
export function dayText(day: string | null | undefined): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(day ?? '');
  return m && MONTHS[Number(m[2]) - 1] ? `${Number(m[3])} ${MONTHS[Number(m[2]) - 1]} ${m[1]}` : 'Not given';
}

export function monthText(month: string): string {
  const m = /^(\d{4})-(\d{2})$/.exec(month);
  return m && MONTHS[Number(m[2]) - 1] ? `${MONTHS[Number(m[2]) - 1]} ${m[1]}` : month;
}

export function licenceTitle(v: LicensingShown): string {
  if (!v.intakeAllowed) return 'New cases are not taken in';
  switch (v.licence?.state) {
    case 'EXPIRING': return 'The licence ends soon';
    case 'EXPIRED_IN_GRACE': return 'The licence has ended: renew it now';
    default: return 'The licence is valid';
  }
}

export function licenceKind(kind: string): string {
  switch (kind) {
    case 'PRODUCTION': return 'Production';
    case 'NON_PRODUCTION': return 'Test environment (not for production)';
    case 'INSTALL': return 'Installation (short)';
    default: return 'Not known';
  }
}

export function licenceTerm(l: LicenceShown): string {
  if (!l.expiresOn) return dayText(l.updatesUntil);
  const day = dayText(l.expiresOn);
  if (l.state === 'EXPIRED_IN_GRACE' || l.state === 'EXPIRED_GRACE_OVER') return `${day} (grace period until ${dayText(l.graceEndsOn)})`;
  const days = l.daysUntilExpiry;
  return typeof days === 'number' && days >= 0 && days <= 60 ? `${day} (${days === 0 ? 'today' : days === 1 ? '1 day left' : `${days} days left`})` : day;
}

const NAMES: Record<string, string> = {
  PAGES_PER_MONTH: 'Pages this month',
  STORAGE: 'Storage',
  CONCURRENT_SEATS: 'People at the same time',
  NAMED_SEATS: 'Named people',
};

export function allowanceText(a: AllowanceShown): { name: string; use: string; level: string } {
  const use = a.limit > 0 ? `${a.used.toLocaleString('en-US')} of ${a.limit.toLocaleString('en-US')} ${a.unit}`
    : `${a.used.toLocaleString('en-US')} ${a.unit}, none in the licence`;
  return { name: NAMES[a.type] ?? 'Other use', use, level: levelWord(a.level, a.limit) };
}

function levelWord(level: string, limit: number): string {
  if (limit <= 0) return 'Not in the licence';
  switch (level) {
    case 'OVER_LIMIT': return 'Over the limit';
    case 'AT_LIMIT': return 'At the limit';
    case 'NEAR_LIMIT': return 'Near the limit';
    default: return 'Within the limit';
  }
}
