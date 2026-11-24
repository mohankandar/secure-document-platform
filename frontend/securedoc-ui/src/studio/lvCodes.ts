/**
 * Task 0025: LexaVault error codes (LV-<AREA>-<NNN>) on screen. The words come only from the generated registry
 * (lvErrorCodes.generated.ts, written by a backend test); this module looks them up and formats them. Pure functions,
 * so they are tested without a browser.
 */
import { LV_BY_DETAIL, LV_CODES, LV_FALLBACK, type LvCodeEntry } from './lvErrorCodes.generated.ts';

export { LV_FALLBACK };
export type { LvCodeEntry };

/** True for a code the registry knows, for example "LV-DLV-002". */
export function isLvCode(code: unknown): code is string {
  return typeof code === 'string' && Object.prototype.hasOwnProperty.call(LV_CODES, code.trim());
}

/** The registry entry for an LV code; the fallback entry (LV-SYS-001) for anything the registry doesn't know. */
export function lvEntry(code: string | null | undefined): LvCodeEntry {
  return isLvCode(code) ? LV_CODES[code.trim()] : LV_CODES[LV_FALLBACK];
}

/** True when an internal identifier (enum name, errorCode, category, reason) has its own LV code. */
export function hasLvCode(detail: string | null | undefined): boolean {
  return Boolean(detail && Object.prototype.hasOwnProperty.call(LV_BY_DETAIL, detail.trim().toUpperCase()));
}

/**
 * The LV code for a failure: the server's own lvCode when it sent a known one; otherwise the code mapped from the
 * internal identifier; otherwise LV-SYS-001.
 */
export function lvCodeFor(detail: string | null | undefined, lvCode?: string | null): string {
  if (isLvCode(lvCode)) return lvCode.trim();
  if (hasLvCode(detail)) return LV_BY_DETAIL[(detail as string).trim().toUpperCase()];
  return LV_FALLBACK;
}

/** "Destination busy or down · LV-DLV-002". */
export function lvLabel(code: string | null | undefined): string {
  const entry = lvEntry(code);
  return `${entry.title} · ${entry.code}`;
}

/** "Message · LV-CFG-001", or the message alone when it already holds the code. */
export function withLvCode(message: string, code: string): string {
  return message.includes(code) ? message : `${message} · ${code}`;
}

/**
 * The words for a refused request (an API error body { errorCode, code, message, ... }): the message and the LV code
 * when the body has one ("Setting not valid: … · LV-CFG-001"); the message alone from an older server; the fallback
 * when there is no message.
 */
export function errorText(error: unknown, fallback: string): string {
  if (!error || typeof error !== 'object') return fallback;
  const e = error as { message?: unknown; code?: unknown };
  const message = typeof e.message === 'string' ? e.message : undefined;
  if (isLvCode(e.code)) {
    const code = e.code.trim();
    const words = withLvCode(message?.trim() ? message : lvEntry(code).title, code);
    // Task 0057: a refusal of the shared platform (the licence) says its own code and what to do about it.
    const details = (error as { details?: { platformCode?: unknown }; whatToDo?: unknown });
    const platform = typeof details.details?.platformCode === 'string' ? details.details.platformCode : undefined;
    if (!platform) return words;
    const steps = typeof details.whatToDo === 'string' && details.whatToDo.trim() ? details.whatToDo : lvEntry(code).whatToDo;
    return `${words} (${platform}). What to do: ${steps}`;
  }
  return message ?? fallback;
}

/** Task 0057: what to do about a code, the steps of the customer error code guide. */
export function whatToDo(code: string | null | undefined): string {
  return lvEntry(code).whatToDo;
}

/** The code a sign-in refusal shows when the server's 403 body has none. */
export const NO_ACCESS_CODE = 'LV-AUT-002';

/** The no-access heading: the title and the code only, never the exact reason ("No access to LexaVault · LV-AUT-002"). */
export function noAccessTitle(code: string | null | undefined): string {
  return lvLabel(isLvCode(code) ? code : NO_ACCESS_CODE);
}
