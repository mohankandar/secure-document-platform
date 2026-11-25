/**
 * Admin › Audit trail in plain words (UI task U011, finding 29). Pure functions, so the words are tested without a
 * browser. What is recorded is never changed: rows only read it as a sentence, and a row opens to show every field
 * exactly as recorded.
 *
 * What the records hold (see the U011 report and task 0016): an actor id, and since 0016 the person's display name as
 * recorded at the time (none for system work and older records), and a resource type and id (no loan number). So
 * "Who" shows "LexaVault (automatic)" for the system actor, the recorded name where there is one (task 0019 A2), and a
 * short person id otherwise; "Case" shows a short case id where the record points at a case.
 */
import { findSensitiveType, sentenceCase } from '../words.ts';
import { ROLE_OPTIONS } from '../../security/uiAuthorization.ts';

export interface AuditRecordLike {
  actorId: string;
  action: string;
  resourceType: string;
  resourceId: string;
  outcome: string;
  detail?: string;
  /** Task 0016: recorded at the time of the action; null for system work and records written before 0016. */
  actorDisplayName?: string | null;
}

/* ---------- Toolbar ---------- */

/** The type chips, mapped to the server's audit categories. */
export const AUDIT_TYPES = [
  { key: 'MEANINGFUL', label: 'Everything' },
  { key: 'SECURITY', label: 'Sign-in' },
  { key: 'ADMINISTRATION', label: 'Settings' },
  { key: 'DOCUMENTS', label: 'Cases' },
  { key: 'EXPORTS', label: 'Exports' },
  { key: 'FAILURES_DENIED', label: 'Refused' },
] as const;
export type AuditTypeKey = (typeof AUDIT_TYPES)[number]['key'];

export const PERIODS = [
  { key: '24h', label: 'Last 24 hours', hours: 24 },
  { key: '7d', label: 'Last 7 days', hours: 24 * 7 },
  { key: '30d', label: 'Last 30 days', hours: 24 * 30 },
  { key: 'all', label: 'Any time', hours: 0 },
] as const;
export type PeriodKey = (typeof PERIODS)[number]['key'];

/** The start of a period as an ISO time, or undefined for "Any time". */
export function periodStart(period: PeriodKey, now = Date.now()): string | undefined {
  const hours = PERIODS.find((p) => p.key === period)?.hours ?? 0;
  return hours ? new Date(now - hours * 3600_000).toISOString() : undefined;
}

/** The id LexaVault records for its own automatic work. */
export const SYSTEM_ACTOR_ID = '00000000-0000-0000-0000-000000000000';

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export const isUuid = (v: string) => UUID_RE.test(v.trim());

/**
 * The case search. The audit records hold no loan number, so only a case id can be searched. Anything else is
 * refused with a reason rather than guessed (fail closed).
 */
export function caseSearch(raw: string): { caseId?: string; error?: string } {
  const v = raw.trim();
  if (!v) return {};
  if (isUuid(v)) return { caseId: v.toLowerCase() };
  return { error: 'The audit trail does not record loan numbers yet. Enter the case ID (shown in each row’s details).' };
}

/* ---------- Rows ---------- */

export const SYSTEM_ACTOR_NAME = 'LexaVault (automatic)';

/**
 * Who did it: "LexaVault (automatic)" for the system, the name recorded with the record when there is one, otherwise
 * "Person <last 8 of the id>" (older records carry no name).
 */
export function personName(actorId: string | null | undefined, recordedName?: string | null): string {
  if (!actorId) return 'Unknown';
  if (actorId === SYSTEM_ACTOR_ID) return SYSTEM_ACTOR_NAME;
  const name = recordedName?.trim();
  return name ? name : `Person ${shortId(actorId)}`;
}

/**
 * The "Person" filter: one option per person seen in the records (the filter still sends the id). Each shows the
 * latest name recorded for that id, else "Person <last 8>"; when two ids share a name, the short id is added so the
 * options stay apart. Sorted by what people read.
 */
export function personOptions(records: ReadonlyArray<Pick<AuditRecordLike, 'actorId' | 'actorDisplayName'> & { occurredAt?: string }>,
  alsoIds: ReadonlyArray<string> = []): Array<{ id: string; label: string }> {
  const latest = new Map<string, { name: string; at: string }>();
  const ids = new Set<string>();
  for (const r of records) {
    if (!r.actorId || r.actorId === SYSTEM_ACTOR_ID) continue;
    ids.add(r.actorId);
    const name = r.actorDisplayName?.trim();
    const at = r.occurredAt ?? '';
    const seen = latest.get(r.actorId);
    if (name && (!seen || at > seen.at)) latest.set(r.actorId, { name, at });
  }
  for (const id of alsoIds) if (id && id !== SYSTEM_ACTOR_ID) ids.add(id);
  const labels = [...ids].map((id) => ({ id, label: personName(id, latest.get(id)?.name) }));
  const uses = new Map<string, number>();
  for (const o of labels) uses.set(o.label, (uses.get(o.label) ?? 0) + 1);
  return labels
    .map((o) => ((uses.get(o.label) ?? 0) > 1 && latest.has(o.id) ? { id: o.id, label: `${o.label} · ${shortId(o.id)}` } : o))
    .sort((a, b) => a.label.localeCompare(b.label) || a.id.localeCompare(b.id));
}

/** The last 8 characters: development and system ids share their leading zeros, so the end is what tells them apart. */
export const shortId = (id: string) => id.replace(/-/g, '').slice(-8);

const CASE_RESOURCES = new Set(['DocumentPackage', 'Package']);

/** The case the record is about, if the record says so: the resource itself, or "package=<id>" in the detail. */
export function caseId(r: AuditRecordLike): string | undefined {
  if (CASE_RESOURCES.has(r.resourceType) && r.resourceId) return r.resourceId;
  const m = /\bpackage(?:Id)?=([0-9a-f-]{36})\b/i.exec(r.detail ?? '');
  return m?.[1];
}

export function resultWord(outcome: string): { word: string; tone: 'ok' | 'danger' | 'neutral' } {
  switch (outcome) {
    case 'SUCCESS': return { word: 'Success', tone: 'ok' };
    case 'FAILURE': return { word: 'Failed', tone: 'danger' };
    case 'DENIED': return { word: 'Refused', tone: 'danger' };
    default: return { word: outcome ? sentenceCase(outcome) : 'Not recorded', tone: 'neutral' };
  }
}

/** "a Social Security Number", "a credit score", "an unknown type" in running text. */
function typePhrase(detail: string | undefined): string {
  const raw = /\btype=([A-Za-z_]+)/.exec(detail ?? '')?.[1];
  if (!raw) return 'a finding';
  const known = findSensitiveType(raw);
  const name = known ? known.name : sentenceCase(raw);
  const words = name.split(' ');
  const proper = words.length > 1 && words.every((w) => /^[A-Z]/.test(w));
  const phrase = proper ? name : name.charAt(0).toLowerCase() + name.slice(1);
  return `${/^[aeiou]/i.test(phrase) ? 'an' : 'a'} ${phrase}`;
}

const SENTENCES: Record<string, string> = {
  // Cases
  PACKAGE_CREATED: 'Created a case',
  PACKAGE_SUBMITTED: 'Sent a case for processing',
  PACKAGE_PROCESSING_STARTED: 'Started processing',
  PACKAGE_PROCESSING_RECOVERED: 'Picked up interrupted processing',
  PACKAGE_PROCESSING_RECOVERY_EXHAUSTED: 'Gave up on interrupted processing',
  PACKAGE_PROCESSING_COMPLETED: 'Finished processing',
  PACKAGE_APPROVED: 'Approved the case',
  PACKAGE_REJECTED: 'Rejected the case',
  PACKAGE_VAULTED: 'Delivered to the document store',
  PACKAGE_CANCELLED: 'Cancelled the case',
  PACKAGE_FINAL_APPROVAL_REQUIRED: 'Asked for delivery approval',
  PACKAGE_FINAL_APPROVED: 'Approved delivery',
  PACKAGE_REPROCESS_REQUESTED: 'Asked to process the case again',
  PACKAGE_TECHNICAL_RETRY_REQUESTED: 'Retried after a technical problem',
  PACKAGE_HIDDEN_FROM_DASHBOARD: 'Hid the case from Cases',
  PACKAGE_EXPORT_RECORDED: 'Recorded an export',
  PACKAGE_PURGED: 'Deleted the case after its retention period',
  REDACTED_ARTIFACT_PURGED: 'Deleted the redacted copy after its retention period',
  ORIGINAL_ARTIFACT_PURGED: 'Deleted the original after its retention period',
  RETENTION_SCHEDULED: 'Scheduled deletion',
  RETENTION_HOLD_EXTENDED: 'Extended a hold',
  RETENTION_HOLD_RELEASED: 'Released a hold',
  TECHNICAL_ATTENTION_AUTO_CLOSED: 'Closed a solved technical problem',
  SUPPORT_INCIDENT_ACTION_RECORDED: 'Recorded a support action',
  DOCUMENT_UPLOADED: 'Added a document',
  DOCUMENT_EXTRACTION_STARTED: 'Started reading a document',
  DOCUMENT_EXTRACTION_COMPLETED: 'Read a document',
  DOCUMENT_DETECTION_COMPLETED: 'Checked a document for sensitive data',
  DOCUMENT_REDACTION_COMPLETED: 'Redacted a document',
  DOCUMENT_REDACTION_VERIFIED: 'Verified a redacted document',
  DOCUMENT_SECOND_CHECK: 'Checked the finished file a second time',
  DOCUMENT_SIGNATURE_REMOVED: 'Removed a digital signature by redaction',
  DOCUMENT_PROCESSING_FAILED: 'Could not process a document',
  DOCUMENT_DOWNLOADED: 'Downloaded a document',
  DOCUMENT_EXPORTED: 'Exported a document',
  DOCUMENT_EXPORT_FAILED: 'Could not export a document',
  REVIEW_TASK_CREATED: 'Sent the case to review',
  REVIEW_TASK_ASSIGNED: 'Assigned a review',
  REVIEW_STARTED: 'Started a review',
  REVIEW_APPROVED: 'Finished the review',
  REVIEW_REJECTED: 'Rejected in review',
  REVIEW_ESCALATED: 'Escalated a review',
  REVIEW_PAGE_PREVIEWED: 'Viewed a page in review',
  REVIEW_PAGE_CHECKED: 'Checked an unreadable page by hand',
  BUSINESS_MANUAL_FALLBACK_STARTED: 'Started redacting by hand',
  BUSINESS_MANUAL_FALLBACK_REGION_ADDED: 'Marked an area to redact',
  BUSINESS_MANUAL_FALLBACK_REGION_REMOVED: 'Removed a marked area',
  BUSINESS_MANUAL_FALLBACK_SUBMITTED: 'Finished redacting by hand',
  BUSINESS_RECORD_CREATED: 'Created a business record',
  BUSINESS_RECORD_LINKED: 'Linked a business record',
  INTAKE_WAITING_FOR_TRIGGER: 'Waiting for the READY file',
  INTAKE_ACCEPTED: 'Took in a case from the watched folder',
  INTAKE_REJECTED: 'Turned away a case from the watched folder',
  INTAKE_DUPLICATE: 'Skipped a case already taken in',
  INTAKE_SENSITIVITY_LEVEL_UNKNOWN: 'Took in an unknown sensitivity level as Standard',
  EXTERNAL_INTAKE_CREATED: 'Opened a link for receiving documents',
  EXTERNAL_DOCUMENT_RECEIVED: 'Received a document',
  EXTERNAL_INTAKE_ACCEPTED: 'Took in received documents',
  EXTERNAL_INTAKE_EXPIRED: 'A link for receiving documents expired',
  EXTERNAL_INTAKE_CLEANED: 'Cleaned up an expired link',
  VAULT_ENTRY_REGISTERED: 'Registered with the document store',
  VAULT_ENTRY_SEALED: 'Sealed in the document store',
  ACCESS_REQUESTED: 'Asked for access',
  ACCESS_APPROVED: 'Approved access',
  ACCESS_DENIED: 'Refused access',
  ACCESS_REVOKED: 'Removed access',
  DERIVED_DOCUMENT_CREATED: 'Made a document for delivery',
  DERIVED_DOCUMENT_DELIVERED: 'Delivered a document',
  DERIVED_DOCUMENT_DELIVERY_FAILED: 'Could not deliver a document',
  ROUTING_ATTENTION_REQUIRED: 'A delivery needs attention',
  // Export jobs (tasks 0027 and 0028; words task 0029)
  EXPORT_JOB_QUEUED: 'Queued a case for export',
  EXPORT_JOB_STATUS_CHANGED: 'An export moved on',
  EXPORT_JOB_RESEND_REQUESTED: 'Sent a refused case again',
  EXPORT_JOB_RESEND_ALL_REQUESTED: 'Sent again every case a destination refused',
  EXPORT_JOB_IT_RETRY_REQUESTED: 'Retried an export that needed IT',
  EXPORT_JOB_IT_GROUP_RETRY_REQUESTED: 'Retried a group of exports that needed IT',
  EXPORT_JOB_NEEDS_IT_NOTIFICATION: 'Told IT an export needs them',
  EXPORT_JOB_REBUILT: 'Rebuilt an export from the kept redacted copies',
  EXPORT_DESTINATION_PAUSED: 'Paused a destination that wasn’t answering',
  EXPORT_DESTINATION_RESUMED: 'Set a destination back to working',
  EXPORT_DESTINATION_NEEDS_IT: 'A destination needs IT',
  EXPORT_DESTINATION_RATE_LIMITED: 'A destination asked LexaVault to slow down',
  EXPORT_DESTINATIONS_UNREACHABLE_ALERT: 'LexaVault couldn’t reach its destinations',
  EXPORT_DESTINATIONS_UNREACHABLE_ALERT_CLEARED: 'LexaVault could reach its destinations again',
  SPLIT_COPY_PURGED: 'Deleted a split copy after its retention period',
  PIPELINE_JOBS_CLEANED: 'Removed finished export jobs after their keep time',
  UNRECORDED_FILES_CLEANED: 'Removed stored files that no record points to',
  FILES_ERASED: 'Erased stored files (recorded in the erase log)',
  CASE_FIELDS_ERASED: 'Case field values erased',
  // Staged processing (task 0048)
  PIPELINE_JOB_QUEUED: 'Queued a document for its next processing step',
  PIPELINE_JOB_STATUS_CHANGED: 'A processing step moved on',
  // Settings
  BUSINESS_IDENTIFIER_CONFIGURATION_CHANGED: 'Changed the case identifiers',
  SENSITIVE_DATA_POLICY_CHANGED: 'Changed the sensitive-data settings',
  DOCUMENT_INTELLIGENCE_CONFIGURATION_CHANGED: 'Changed the AI settings',
  DOCUMENT_INTELLIGENCE_LAST_KNOWN_GOOD_CHANGED: 'Saved the AI settings as known good',
  DOCUMENT_INTELLIGENCE_ROLLED_BACK: 'Went back to the known-good AI settings',
  DASHBOARD_CONFIGURATION_CHANGED: 'Changed the Cases columns',
  LIFECYCLE_POLICY_CHANGED: 'Changed how long cases are kept',
  EXPORT_RETENTION_PERIOD_CHANGED: 'Changed how long exported copies are kept',
  POLICY_CREATED: 'Created a policy',
  POLICY_VERSION_PUBLISHED: 'Published a policy',
  POLICY_DEACTIVATED: 'Switched off a policy',
  POLICY_EVALUATED: 'Checked a policy',
  REPORTING_CONFIGURATION_CHANGED: 'Changed the report settings',
  TENANT_CREATED: 'Created a customer',
  TENANT_ACTIVATED: 'Switched on a customer',
  TENANT_SUSPENDED: 'Paused a customer',
  TENANT_DEACTIVATED: 'Switched off a customer',
  CAPABILITY_CHANGED: 'Changed what a role can do',
  ENTITY_ACTIVATED: 'Activated an entity',
  ENTITY_RENAMED: 'Renamed an entity',
  ENTITY_SUSPENDED: 'Suspended an entity',
  ENTITY_RESUMED: 'Resumed an entity',
  ENTITY_TIME_ZONE_CHANGED: 'Changed an entity’s time zone',
  ENTITY_SWITCHED: 'Switched to this entity',
  NOTIFICATION_SETTINGS_CHANGED: 'Changed the e-mail settings for notifications',
  NOTIFICATION_EVENT_CHANGED: 'Changed when a notification is sent',
  NOTIFICATION_TEST_SENT: 'Sent a test notification',
  DELIVERY_APPROVAL_LINK_USED: 'Used an approval link',
  DELIVERY_APPROVAL_LINK_REFUSED: 'An approval link was refused',
  INTEGRATION_CONNECTION_CREATED: 'Added a connection',
  INTEGRATION_CONNECTION_UPDATED: 'Changed a connection',
  INTEGRATION_CONNECTION_ENABLED: 'Switched on a connection',
  INTEGRATION_CONNECTION_DISABLED: 'Switched off a connection',
  INTEGRATION_CONNECTION_TESTED: 'Tested a connection',
  POLLING_RUN_STARTED: 'Started checking a connection for documents',
  POLLING_RUN_COMPLETED: 'Checked a connection for documents',
  POLLING_RUN_FAILED: 'Could not check a connection for documents',
  POLLING_PACKAGE_FAILED: 'Could not take in a case from a connection', // task 0023 B1: one row per failed case in a run
  POLLING_SOURCE_ACKNOWLEDGED: 'Confirmed receipt to a connection',
  WATCHED_FOLDER_SCAN_TRIGGERED: 'Checked the watched folder',
  WATCHED_FOLDER_SETTINGS_CHANGED: 'Changed the watched folder’s sensitivity level',
  PLATFORM_HEALTH_CHECKED: 'Ran health checks',
  PROCESSING_MAXIMUMS_CHANGED: 'Changed the processing maximums',
  CASE_PRIORITY_CHANGED: 'Changed the case’s priority',
  CASE_INSTRUCTIONS_CHANGED: 'Changed the case’s instructions for the AI',
  SOURCE_PRIORITY_CHANGED: 'Changed a source’s default priority',
  SOURCE_BULK_CHANGED: 'Changed a source’s migration setting',
  PROCESSING_STORAGE_PROVIDER_CREATED: 'Added processing storage',
  PROCESSING_STORAGE_PROVIDER_TESTED: 'Tested processing storage',
  PROCESSING_STORAGE_PROVIDER_ACTIVATED: 'Made processing storage the default',
  PROCESSING_STORAGE_PROVIDER_RETIRED: 'Stopped using processing storage',
  PROCESSING_STORAGE_TENANT_OVERRIDE_CHANGED: 'Changed a customer’s processing storage',
  LICENSE_INSTALLED: 'Installed a licence',
  BRANDING_CONFIGURATION_CHANGED: 'Changed the branding',
  ROUTING_RULE_CREATED: 'Added a delivery rule',
  ROUTING_RULE_UPDATED: 'Changed a delivery rule',
  ROUTING_RULE_DELETED: 'Removed a delivery rule',
  // Sign-in and security
  ENTERPRISE_IDENTITY_DRAFT_SAVED: 'Saved a sign-in draft',
  ENTERPRISE_IDENTITY_CONNECTION_TESTED: 'Tested the sign-in setup',
  ENTERPRISE_IDENTITY_CONNECTION_ACTIVATED: 'Switched on the new sign-in setup',
  ENTERPRISE_IDENTITY_CONNECTION_ACTIVATION_FAILED: 'Could not switch on the new sign-in setup',
  ENTERPRISE_IDENTITY_CONNECTION_ROLLED_BACK: 'Went back to the previous sign-in setup',
  ENTERPRISE_IDENTITY_CONNECTION_ROLLBACK_FAILED: 'Could not go back to the previous sign-in setup',
  AUTHORIZATION_FAILED: 'Tried something their role does not allow',
  SECURITY_CONTEXT_INVALID: 'Sent a request that could not be trusted',
  LICENSE_VALIDATION_FAILED: 'The licence could not be checked',
  LICENSE_ENFORCEMENT_DENIED: 'Refused by the licence',
  // Exports
  REPORT_CSV_EXPORTED: 'Exported a report',
  AUDIT_EXPORTED: 'Exported the audit trail',
};

const FINDING_SENTENCES: Record<string, string> = {
  FINDING_DETECTED: 'Found',
  FINDING_CONFIRMED: 'Confirmed',
  FINDING_REDACTED: 'Redacted',
  FINDING_DISMISSED: 'Kept',
  FINDING_OVERRIDDEN: 'Changed the decision on',
  FINDING_BOUNDING_BOX_UPDATED: 'Moved the box around',
};

/** What happened, as a sentence: "Approved delivery", "Redacted a Social Security Number". Never a raw code. */
export function whatHappened(r: Pick<AuditRecordLike, 'action' | 'detail'>): string {
  const verb = FINDING_SENTENCES[r.action];
  if (verb) {
    const thing = typePhrase(r.detail);
    return r.action === 'FINDING_DISMISSED' ? `${verb} ${thing} visible` : `${verb} ${thing}`;
  }
  return SENTENCES[r.action] ?? sentenceCase(r.action);
}

/**
 * The short second line for a change, from what the detail records: "auto-redact level 85%". The records hold the
 * new value only, so no "before → after" is shown.
 */
export function changeLine(r: Pick<AuditRecordLike, 'action' | 'detail'>): string | undefined {
  if (r.action === 'SENSITIVE_DATA_POLICY_CHANGED') {
    const t = /\bthreshold=([0-9.]+)/.exec(r.detail ?? '')?.[1];
    if (t && Number.isFinite(Number(t))) return `auto-redact level now ${Math.round(Number(t) * 100)}%`;
  }
  return undefined;
}

/** Every recorded field, in order, exactly as recorded. */
export function recordedFields(r: AuditRecordLike & { id: string; correlationId: string; occurredAt: string }): Array<[string, string]> {
  const name = r.actorDisplayName?.trim();
  return [
    ['Time', r.occurredAt],
    ['Action', r.action],
    ['Result', r.outcome],
    // Task 0019 A2: the name as recorded, only when the record has one.
    ...(name ? [['Person', name] as [string, string]] : []),
    ['Person ID', r.actorId],
    ['Item type', r.resourceType],
    ['Item ID', r.resourceId],
    ['Reference', r.correlationId],
    ['Detail', r.detail ?? ''],
    ['Record ID', r.id],
  ];
}

/** Every known action for the "Action" filter, by its sentence, sorted by the words people read. */
export function actionOptions(): Array<[string, string]> {
  return [...Object.keys(SENTENCES), ...Object.keys(FINDING_SENTENCES)]
    .map((a): [string, string] => [a, whatHappened({ action: a })])
    .sort((x, y) => x[1].localeCompare(y[1]));
}

/* ---------- Sign-in access changes (task 0041) ---------- */

/**
 * One change in platform-identity's grant history: which login-system group gives which LexaVault role in this entity.
 * Read-only; groups and roles are written exactly as stored.
 */
export interface GrantChangeLike {
  change: 'ADDED' | 'REMOVED' | 'CHANGED';
  group: string;
  roleBefore: string | null;
  roleAfter: string | null;
  source: 'SETUP' | 'APP';
  changedBy: string;
  setupRunId: string | null;
}

/** A role's everyday name; an unknown name is shown as stored. */
export function roleWord(role: string | null | undefined): string {
  if (!role) return '—';
  return ROLE_OPTIONS.find((o) => o.value === role)?.label ?? role;
}

/** The change as a sentence. */
export function grantChangeLine(c: GrantChangeLike): string {
  switch (c.change) {
    case 'ADDED': return `Group ${c.group} now gives ${roleWord(c.roleAfter)}`;
    case 'REMOVED': return `Group ${c.group} no longer gives ${roleWord(c.roleBefore)}`;
    default: return `Group ${c.group} now gives ${roleWord(c.roleAfter)} instead of ${roleWord(c.roleBefore)}`;
  }
}

/** Who made it: setup (with its run, so the run log can be found) or the app. */
export function grantChangeBy(c: GrantChangeLike): string {
  if (c.source === 'SETUP') return c.setupRunId ? `Setup, run ${c.setupRunId}` : 'Setup';
  return c.changedBy && c.changedBy !== 'app' ? c.changedBy : 'LexaVault';
}
