import { approvalToken } from './admin/notificationRules';

/** Task 0059: an approval link's token while the person signs in: in this tab only, gone when the tab is closed or it is used. */
const KEY = 'lexavault.approval-link';

export function holdApprovalLink(token: string): void {
  try { sessionStorage.setItem(KEY, token); } catch { /* the page then asks to open the link again */ }
}

export function approvalLinkHeld(): string | null {
  try {
    const held = sessionStorage.getItem(KEY);
    return held && approvalToken(`/approvals/link/${held}`) ? held : null;
  } catch { return null; }
}

export function forgetApprovalLink(): void {
  try { sessionStorage.removeItem(KEY); } catch { /* nothing held */ }
}

/**
 * At page load, before sign-in (which comes back to the start page): keeps a link's token, and takes it out of the address so
 * that it is never sent to the login system as a referrer.
 */
export function takeApprovalLinkFromAddress(): void {
  const token = approvalToken(window.location.pathname);
  if (!token) return;
  holdApprovalLink(token);
  window.history.replaceState(window.history.state, '', '/approvals/link');
}
