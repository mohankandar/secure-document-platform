/** Task 0057: the words of Support › Status. Pure functions, tested without a browser. */

export interface CheckShown { name: string; group: string; state: string; code: string; cause: string | null; whatToDo: string | null; required: boolean; checkedAt: string }
export interface FailureShown { at: string; code: string; reference: string; cause: string; whatToDo: string }
export interface SupportStatusShown { overall: string; checks: CheckShown[]; failures: FailureShown[] }

export function stateWord(state: string): string {
  switch (state) {
    case 'WORKING': return 'Working';
    case 'HAVING_PROBLEMS': return 'Having problems';
    case 'DOWN': return 'Down';
    default: return 'Not known';
  }
}

/** The tone of the shared status dot: ok, warn, danger. */
export function stateTone(state: string): 'ok' | 'warn' | 'danger' {
  return state === 'WORKING' ? 'ok' : state === 'HAVING_PROBLEMS' ? 'warn' : 'danger';
}

export function checkName(name: string): string {
  switch (name) {
    case 'database': return 'Database';
    case 'service-model-server': return 'AI model server';
    case 'licence': return 'Licence';
    case 'storage': return 'Document storage';
    case 'login-system': return 'Login system';
    case 'key-service': return 'Key service';
    case 'erase-log': return 'Erase log';
    case 'channel-mail': return 'Mail server';
    default: return name.startsWith('folder-') ? `Folder: ${name.slice(7)}` : name.startsWith('service-') ? `Service: ${name.slice(8)}` : name;
  }
}

export function statusTitle(v: SupportStatusShown): string {
  if (v.checks.length === 0) return 'Not checked yet';
  const bad = v.checks.filter((c) => c.state !== 'WORKING').length;
  if (bad === 0) return 'Everything is working';
  return `${bad} of ${v.checks.length} ${bad === 1 ? 'needs' : 'need'} attention`;
}

/** Down first, then having problems, then working; by name within. */
export function sortedChecks(checks: CheckShown[]): CheckShown[] {
  const rank = (s: string) => (s === 'WORKING' ? 2 : s === 'HAVING_PROBLEMS' ? 1 : 0);
  return [...checks].sort((a, b) => rank(a.state) - rank(b.state) || checkName(a.name).localeCompare(checkName(b.name)));
}
