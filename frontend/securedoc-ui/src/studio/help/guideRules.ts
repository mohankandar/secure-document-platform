/**
 * Task 0082: the error code guide (Help › Error codes). Pure functions over the generated registry, so they are tested
 * without a browser. No text of an entry is written here: all of it comes from lvErrorCodes.generated.ts.
 */
import { LV_AREAS, LV_CODES, type LvCodeEntry, type LvKind } from '../lvErrorCodes.generated.ts';
import { isLvCode } from '../lvCodes.ts';

export const HELP_PATH = '/help/error-codes';
export const HELP_WORDS = {
  menu: 'Help: error codes',
  title: 'Error codes',
  lede: 'What each code means, who acts and what to do.',
  search: 'Find a code or words',
  link: 'Open in the error code guide (new tab)',
} as const;

export const KIND_WORDS: Record<LvKind, string> = {
  REFUSED: 'Refused', WARNING: 'Warning', WAITING: 'Waiting', STOPPED: 'Stopped', EXPORT_FAILED: 'Export failed',
  NOT_ALLOWED: 'Not allowed', SET_UP: 'Set-up', SYSTEM: 'System',
};

/** The address of a code's entry; the guide itself for anything the registry doesn't know. */
export function helpPath(code?: string | null): string {
  return isLvCode(code) ? `${HELP_PATH}/${code.trim()}` : HELP_PATH;
}

export function isHelpPath(pathname: string): boolean {
  return pathname === HELP_PATH || pathname.startsWith(`${HELP_PATH}/`);
}

/** A text cut into words and the known codes in it: "Busy · LV-DLV-002." → ["Busy · ", {code}, "."]. */
export function splitCodes(text: string): (string | { code: string })[] {
  return text.split(/(LV-[A-Z]+-\d{3}(?!\d))/).filter((part) => part !== '')
    .map((part) => (isLvCode(part) ? { code: part } : part));
}

/** "User, IT Admin (Source system, IT Admin)": the groups, then the catalogue's own words when they say more. */
export function whoActsText(entry: Pick<LvCodeEntry, 'whoActs' | 'whoActsGroups'>): string {
  const groups = entry.whoActsGroups.join(', ');
  return groups === entry.whoActs ? groups : `${groups} (${entry.whoActs})`;
}

const searchable = (e: LvCodeEntry) => [e.code, e.title, e.description, KIND_WORDS[e.kind], whoActsText(e), e.whatToDo, e.supportTells].join(' ').toLowerCase();
const plain = (text: string) => text.replace(/’/g, "'");

/** The areas with the entries that hold every word of the query (a code, part of one, or words); areas without any are left out. */
export function searchGuide(query: string): { key: string; name: string; entries: LvCodeEntry[] }[] {
  const words = plain(query).toLowerCase().split(/\s+/).filter(Boolean);
  return LV_AREAS.map((area) => ({
    ...area,
    entries: Object.values(LV_CODES).filter((e) => e.area === area.key && words.every((w) => searchable(e).includes(w))),
  })).filter((area) => area.entries.length > 0);
}

/** "161 codes", "1 code", "No code matches". */
export function foundText(n: number): string {
  return n === 0 ? 'No code matches' : `${n} ${n === 1 ? 'code' : 'codes'}`;
}

/** Any spelling of an address' code, as the registry writes it; null when it isn't one. */
export function codeFromAddress(value: string | undefined): string | null {
  const code = value?.trim().toUpperCase();
  return isLvCode(code) ? code : null;
}
