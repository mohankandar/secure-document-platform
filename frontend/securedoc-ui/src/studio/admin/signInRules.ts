/** Task 0058: the words of Admin › Sign-in's draft. Free of React so the rules run under `node --test`. The server decides. */

export interface SignInDraft { providerId: string; issuerUri: string; browserClientId: string; apiAudience: string }

export function draftStatus(status: string): string {
  switch (status) {
    case 'DRAFT': return 'Draft, not tested';
    case 'VALIDATED': return 'Draft, tested';
    case 'ACTIVE': return 'In use';
    case 'DISABLED': return 'Not in use';
    default: return 'Unknown';
  }
}

/** Why the draft can't be saved as typed; null when it can be sent. */
export function draftProblem(v: SignInDraft): string | null {
  if (!v.providerId.trim() || !v.apiAudience.trim()) return 'Enter the provider ID and the API audience.';
  if (!/^https?:\/\/[^\s?#]+$/.test(v.issuerUri.trim())) return 'Enter the address as your login system names it, starting with https://.';
  return null;
}

/** Who made a change. A person's id is not shown as a name: the audit trail has it. */
export function changedByWords(h: { source: string; changedBy: string }): string {
  switch (h.source) {
    case 'SETUP': return 'Setup';
    case 'APP': return 'An IT Admin';
    case 'MIGRATION': return 'The identity migration';
    default: return 'LexaVault';
  }
}
