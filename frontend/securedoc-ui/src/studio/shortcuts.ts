/**
 * U027 (WCAG 2.1.4 Character Key Shortcuts): where the single-key shortcuts work.
 *
 * Review's R, V, J, K and Shift R work only while focus is in the queue, the page viewer or the decision panel;
 * the Cases J, K and "/" (focus the filter, U028) only while focus is in the case list. None of them fire while
 * focus is in a text field, a select, a dialog or the account menu. Ctrl or Cmd K ("Find a case or action") is not a single-key shortcut and
 * stays page-wide.
 */
export const TYPING_OR_DIALOG = 'input, textarea, select, [contenteditable="true"], dialog, [role="dialog"], [role="alertdialog"], [role="menu"]';
export const REVIEW_KEY_ZONE = '.cf-queue, .cf-viewer, .cf-decision';
export const CASES_KEY_ZONE = '.cf-cases';
