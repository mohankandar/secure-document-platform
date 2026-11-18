/**
 * Tracks work that would be lost or left half-done if the person switched organization now:
 * - writes in flight: every non-GET request made through the API client (saves, uploads, submits, decisions);
 * - unsaved work: screens register while they hold an open editor or unsent input (see studio/unsavedWork.ts).
 * Used only to ask for confirmation before an organization switch. It is never used for authorization.
 */
let writesInFlight = 0;
const unsavedWork = new Set<symbol>();

/** Marks one write as started; call the returned function exactly once when it settles. */
export function beginWrite(): () => void {
  writesInFlight += 1;
  let ended = false;
  return () => {
    if (ended) return;
    ended = true;
    writesInFlight -= 1;
  };
}

/** Registers unsaved work on screen; call the returned function to remove the registration. */
export function registerUnsavedWork(): () => void {
  const token = Symbol('unsaved-work');
  unsavedWork.add(token);
  return () => { unsavedWork.delete(token); };
}

export function hasPendingWork(): boolean {
  return writesInFlight > 0 || unsavedWork.size > 0;
}
