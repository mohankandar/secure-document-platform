/** Task 0059: the words and checks of Admin › Notifications and of the approval page. Free of React, so they run under `node --test`. */

export interface MailForm {
  enabled: boolean;
  mode: 'MICROSOFT_365' | 'OWN_SERVER';
  host: string;
  port: string;
  security: 'STARTTLS' | 'TLS' | 'NONE';
  username: string;
  fromAddress: string;
  fromName: string;
  directoryId: string;
  clientId: string;
  credentialType: 'CERTIFICATE' | 'SECRET';
  secretReference: string;
}

export interface MailViewLike {
  enabled: boolean; mode: string; host: string | null; port: number | null; security: string | null; username: string | null;
  fromAddress: string | null; fromName: string | null; directoryId: string | null; clientId: string | null; credentialType: string | null;
  secretNamed: boolean;
}

/** What the form opens with. A secret's name is never sent back by the server, so its field is always empty. */
export function mailForm(view: MailViewLike | null): MailForm {
  return {
    enabled: view?.enabled ?? false,
    mode: view?.mode === 'OWN_SERVER' ? 'OWN_SERVER' : 'MICROSOFT_365',
    host: view?.host ?? '',
    port: view?.port == null ? '587' : String(view.port),
    security: view?.security === 'TLS' || view?.security === 'NONE' ? view.security : 'STARTTLS',
    username: view?.username ?? '',
    fromAddress: view?.fromAddress ?? '',
    fromName: view?.fromName ?? '',
    directoryId: view?.directoryId ?? '',
    clientId: view?.clientId ?? '',
    credentialType: view?.credentialType === 'SECRET' ? 'SECRET' : 'CERTIFICATE',
    secretReference: '',
  };
}

const orNull = (v: string) => v.trim() || null;

/** Only the fields of the chosen way; the server checks everything again. */
export function mailRequest(form: MailForm): Record<string, unknown> {
  const common = { enabled: form.enabled, mode: form.mode, fromAddress: orNull(form.fromAddress), fromName: orNull(form.fromName), secretReference: orNull(form.secretReference) };
  return form.mode === 'MICROSOFT_365'
    ? { ...common, directoryId: orNull(form.directoryId), clientId: orNull(form.clientId), credentialType: form.credentialType }
    : { ...common, host: orNull(form.host), port: Number(form.port) || null, security: form.security, username: orNull(form.username) };
}

/** Whether a secret's name must be typed: there is a login, and none is stored for it. */
export function needsSecret(form: MailForm, view: MailViewLike | null): boolean {
  const login = form.mode === 'MICROSOFT_365' || form.username.trim() !== '';
  return login && form.secretReference.trim() === '' && !(view?.secretNamed && view.mode === form.mode);
}

export const FREQUENCY_WORDS: Record<string, string> = {
  IMMEDIATELY: 'Immediately', EVERY_HOUR: 'Every hour', EVERY_4_HOURS: 'Every 4 hours', ONCE_A_DAY: 'Once a day',
};

export const ROLE_WORDS: Record<string, string> = {
  TENANT_IT_ADMIN: 'IT Admin', BUSINESS_ADMIN: 'Business Admin', DOCUMENT_SUPERVISOR: 'Supervisor', DOCUMENT_PROCESSOR: 'Processor',
};

const duration = (m: number) => (m >= 60 && m % 60 === 0 ? `${m / 60} h` : `${m} min`);

/** "Immediately, after 15 min, again every 4 h": how the table says when a message is sent. */
export function eventTiming(e: { frequency: string; dailyAt: string | null; alertAfterMinutes: number | null; repeatMinutes: number | null }): string {
  const parts = [FREQUENCY_WORDS[e.frequency] ?? e.frequency];
  if (e.dailyAt) parts[0] += ` at ${e.dailyAt}`;
  if (e.alertAfterMinutes) parts.push(`after ${duration(e.alertAfterMinutes)}`);
  if (e.repeatMinutes) parts.push(`again every ${duration(e.repeatMinutes)}`);
  return parts.join(', ');
}

export const roleNames = (roles: string[]) => roles.map((r) => ROLE_WORDS[r] ?? r).join(', ');

const STATES: Record<string, string> = { SENT: 'Sent', FAILED: 'Not sent', PENDING: 'Waiting', SENDING: 'Being sent' };
export const deliveryState = (state: string) => STATES[state] ?? state;

export function peopleText(count: number): string {
  return count === 0 ? 'Nobody yet' : count === 1 ? '1 person' : `${count} people`;
}

/* ---------- The approval page ---------- */

export const DECLINE_REASONS: [string, string][] = [
  ['CHECK_REDACTION', 'The redaction needs another look'],
  ['WRONG_DESTINATION', 'Wrong destination'],
  ['NOT_READY', 'Not ready to be delivered'],
  ['OTHER', 'Another reason, told to the team directly'],
];

/** The token in an approval link's path, or null. */
export function approvalToken(pathname: string): string | null {
  const match = /^\/approvals\/link\/([A-Za-z0-9_-]{1,400}\.[A-Za-z0-9_-]{1,100})$/.exec(pathname);
  return match ? match[1] : null;
}
