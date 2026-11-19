import useSWR from 'swr';
import { apiClient } from '../api/client';
import { scanBridgeClient } from '../api/scanBridgeClient';
import type { BusinessIdentifier, FocusedReviewItem, FocusedReviewQueue, IdentifierDefinition, PackageDetail, PackageLifecycleSummary as PackageLifecycle, PackageSummary } from '../types';
import { checked, isArrayOf, isRecord } from './failure';
import { STAGE_NAMES } from './words';

export type StageKey = 'intake' | 'redacting' | 'review' | 'approval' | 'delivered' | 'stopped' | 'unknown';

/**
 * Every backend PackageStatus the real API can return, mapped explicitly.
 * SUBMITTED belongs to Redacting because the real dashboard projects it as PROCESSING.
 */
export const STATUS_INFO: Record<string, { stage: StageKey; label: string; detail: string }> = {
  CREATED: { stage: 'intake', label: 'Draft', detail: 'Case created. Documents have not been submitted yet.' },
  DRAFT: { stage: 'intake', label: 'Draft', detail: 'Case created. Documents have not been submitted yet.' },
  UPLOADED: { stage: 'intake', label: 'Uploaded', detail: 'Documents are attached and waiting to be submitted.' },
  SUBMITTED: { stage: 'redacting', label: 'Submitted', detail: 'Queued for automatic redaction.' },
  PROCESSING: { stage: 'redacting', label: 'Processing', detail: 'LexaVault is detecting and redacting sensitive content.' },
  REPROCESSING: { stage: 'redacting', label: 'Reprocessing', detail: 'Running a new export cycle on the retained original.' },
  PENDING_REVIEW: { stage: 'review', label: 'Needs review', detail: 'Uncertain findings need a person to decide.' },
  IN_REVIEW: { stage: 'review', label: 'In review', detail: 'A reviewer is working on this case.' },
  PENDING_FINAL_APPROVAL: { stage: 'approval', label: 'Awaiting approval', detail: 'Redaction is done. Delivery needs a final sign-off.' },
  COMPLETED: { stage: 'delivered', label: 'Delivered', detail: 'Processing and delivery completed.' },
  EXPORTED: { stage: 'delivered', label: 'Delivered', detail: 'Processing and delivery completed.' },
  DELIVERED: { stage: 'delivered', label: 'Delivered', detail: 'Processing and delivery completed.' },
  FAILED: { stage: 'stopped', label: 'Stopped', detail: 'Automatic processing stopped. Recovery depends on temporary retention and incident status.' },
  REJECTED: { stage: 'stopped', label: 'Rejected', detail: 'The source was rejected and will not be processed.' },
};

export const STAGES: { key: StageKey; label: string }[] = (
  ['intake', 'redacting', 'review', 'approval', 'delivered', 'stopped', 'unknown'] as StageKey[]
).map((key) => ({ key, label: STAGE_NAMES[key] }));

export const LIFECYCLE: StageKey[] = ['intake', 'redacting', 'review', 'approval', 'delivered'];

export function stageOf(status: string): StageKey {
  return STATUS_INFO[status]?.stage ?? 'unknown';
}

export function statusInfo(status: string, reprocessing = false) {
  if (reprocessing) return STATUS_INFO.REPROCESSING;
  return STATUS_INFO[status] ?? { stage: 'unknown' as StageKey, label: humanize(status || 'Unknown'), detail: 'LexaVault reported a status this workspace does not recognise. No actions are offered until it changes.' };
}

export function stageLabel(key: StageKey): string {
  return STAGES.find((s) => s.key === key)?.label ?? key;
}

export const SOURCE_LABEL: Record<string, string> = {
  MANUAL_UPLOAD: 'Upload',
  SCANNER_INTAKE: 'Scan',
  API_SUBMISSION: 'API',
  FOLDER_INTAKE: 'Folder',
};

export const CAP = {
  desk: 'DASHBOARD_ACCESS',
  intake: 'DOCUMENT_INTAKE',
  review: 'MANUAL_REVIEW',
  approve: 'PACKAGE_APPROVE_DELIVERY',
  reprocess: 'PACKAGE_REPROCESS',
  technicalView: 'TECHNICAL_EXCEPTION_VIEW',
  technicalRetry: 'TECHNICAL_RETRY',
  reportExecutive: 'REPORT_EXECUTIVE_VIEW',
  reportOperational: 'REPORT_OPERATIONAL_VIEW',
  reportExport: 'REPORT_EXPORT',
  policy: 'TENANT_POLICY_MANAGE',
  identifiers: 'TENANT_IDENTIFIER_MANAGE',
  integrations: 'TENANT_INTEGRATION_MANAGE',
  auth: 'TENANT_AUTH_MANAGE',
  technicalAdmin: 'TECHNICAL_ADMIN_VIEW',
  platformOps: 'PLATFORM_OPERATIONS',
  audit: 'AUDIT_VIEW',
} as const;

/** Capabilities that grant at least one Admin area. Each area re-checks its own capability. */
export const ADMIN_CAPS: string[] = [CAP.policy, CAP.identifiers, CAP.integrations, CAP.auth, CAP.technicalAdmin, CAP.audit];

/** A case needs the current user when they hold the capability to move it forward. */
export function needsYou(pkg: PackageSummary, caps: string[]): boolean {
  if (pkg.status === 'PENDING_REVIEW' || pkg.status === 'IN_REVIEW') return caps.includes(CAP.review);
  if (pkg.status === 'PENDING_FINAL_APPROVAL') return caps.includes(CAP.approve);
  if (pkg.status === 'FAILED') return caps.includes(CAP.technicalRetry) || caps.includes(CAP.review);
  return false;
}

export type CaseActionKind = 'review' | 'approve' | 'technical-retry' | 'manual-fallback' | 'reprocess' | 'technical-view';

export interface CaseAction {
  kind: CaseActionKind;
  label: string;
  primary: boolean;
}

/**
 * Mirrors the real PackageDetailPage eligibility: capability AND status AND lifecycle.
 * Reprocess is only offered on COMPLETED with lifecycle.canReprocess, matching production.
 * The backend still enforces every rule; this only hides actions that would be refused.
 */
export function caseActions(status: string, caps: string[], life: PackageLifecycle | undefined, reprocessing: boolean, openFindings: number): CaseAction[] {
  if (reprocessing) return [];
  const has = (cap: string) => caps.includes(cap);
  const actions: CaseAction[] = [];
  switch (status) {
    case 'PENDING_REVIEW':
    case 'IN_REVIEW':
      if (has(CAP.review) && openFindings > 0) actions.push({ kind: 'review', label: `Review ${openFindings} finding${openFindings === 1 ? '' : 's'}`, primary: true });
      break;
    case 'PENDING_FINAL_APPROVAL':
      if (has(CAP.approve)) actions.push({ kind: 'approve', label: 'Approve delivery', primary: true });
      break;
    case 'FAILED':
      if (has(CAP.technicalRetry)) actions.push({ kind: 'technical-retry', label: 'Retry processing', primary: true });
      if (has(CAP.review)) actions.push({ kind: 'manual-fallback', label: 'Continue manually', primary: !has(CAP.technicalRetry) });
      break;
    case 'COMPLETED':
      if (has(CAP.reprocess) && life?.canReprocess) actions.push({ kind: 'reprocess', label: 'Process again', primary: false });
      break;
    default:
      break;
  }
  return actions;
}

/** Production treats SUBMITTED/PROCESSING after a prior export as a reprocessing cycle. */
export function isReprocessing(status: string, life: PackageLifecycle | undefined): boolean {
  return Boolean(life && life.successfulExportCycles > 0 && (status === 'SUBMITTED' || status === 'PROCESSING'));
}

export function caseTitle(ids: BusinessIdentifier[]): { primary: string; secondary: string } {
  const sorted = [...ids].sort((a, b) => a.displayOrder - b.displayOrder);
  const primary = sorted.find((i) => i.primary) ?? sorted[0];
  const rest = sorted.filter((i) => i !== primary && i.displayable && i.value);
  return { primary: primary?.value || 'Untitled case', secondary: rest.map((i) => i.value).join(' · ') };
}

const ACRONYMS = new Set(['SSN', 'DOB', 'ID', 'EIN', 'ITIN', 'IBAN', 'PII', 'TIN', 'VIN', 'NPI', 'ABA']);

export function humanize(token: string): string {
  return token
    .split(/[_\s]+/)
    .filter(Boolean)
    .map((w) => (ACRONYMS.has(w.toUpperCase()) ? w.toUpperCase() : w.charAt(0).toUpperCase() + w.slice(1).toLowerCase()))
    .join(' ');
}

export function relativeTime(iso: string, now = Date.now()): string {
  const diff = Math.round((now - new Date(iso).getTime()) / 1000);
  if (diff < 45) return 'just now';
  const mins = Math.round(diff / 60);
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.round(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.round(hours / 24);
  if (days < 7) return `${days}d ago`;
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

export function formatBytes(bytes: number): string {
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(1)} GB`;
  if (bytes >= 1024 ** 2) return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${bytes} B`;
}

export function errorMessage(error: unknown, fallback = 'Something went wrong.'): string {
  if (error && typeof error === 'object' && 'message' in error && typeof (error as { message: unknown }).message === 'string') {
    return (error as { message: string }).message;
  }
  return fallback;
}

/* ---------- SWR hooks ---------- */

/* Every hook checks the shape of the answer: an answer this screen does not understand is an error
   (shown as a "couldn't load" card), never data that crashes the screen. */

export function usePackages(enabled: boolean) {
  return useSWR(enabled ? 'packages' : null,
    () => checked('GET /packages', apiClient.listPackages(), isArrayOf((p) => isRecord(p) && typeof p.packageId === 'string' && Array.isArray(p.businessIdentifiers))),
    { refreshInterval: 4000 });
}

export function usePackage(packageId: string | undefined) {
  return useSWR(packageId ? ['package', packageId] : null,
    () => checked<PackageDetail>(`GET /packages/${packageId}`, apiClient.getPackage(packageId as string), (v) => isRecord(v) && typeof v.packageId === 'string'),
    { refreshInterval: 3000 });
}

export function usePackageLifecycle(packageId: string | undefined, enabled: boolean) {
  return useSWR(packageId && enabled ? ['lifecycle', packageId] : null,
    () => checked(`GET /packages/${packageId}/lifecycle`, apiClient.getPackageLifecycle(packageId as string),
      (v) => isRecord(v) && typeof v.successfulExportCycles === 'number' && typeof v.maximumExportCycles === 'number'),
    { refreshInterval: 5000 });
}

/** Production only loads derived deliveries for users who can approve delivery. */
export function useDerivedDeliveries(packageId: string | undefined, enabled: boolean) {
  return useSWR(packageId && enabled ? ['derived-deliveries', packageId] : null,
    () => checked(`GET /packages/${packageId}/derived-deliveries`, apiClient.listDerivedDeliveries(packageId as string), isArrayOf(isRecord)),
    { refreshInterval: 8000 });
}

export function useTechnicalException(packageId: string | undefined, enabled: boolean) {
  return useSWR(packageId && enabled ? ['technical-exception', packageId] : null,
    () => checked(`GET /technical-exceptions/${packageId}`, apiClient.getTechnicalException(packageId as string), (v) => isRecord(v) && typeof v.explanation === 'string'),
    { revalidateOnFocus: false, shouldRetryOnError: false });
}

export function useReviewQueue(enabled: boolean) {
  return useSWR(enabled ? 'review-queue' : null,
    () => checked<FocusedReviewQueue>('GET /review-items', apiClient.listReviewItems(500), (v) => isRecord(v) && Array.isArray(v.items)),
    { refreshInterval: 5000 });
}

/** The field definitions need DOCUMENT_INTAKE; pass enabled=false for people without it. */
export function useIdentifierDefinitions(enabled = true) {
  return useSWR(enabled ? 'identifier-definitions' : null,
    () => checked<IdentifierDefinition[]>('GET /intake/identifier-definitions', apiClient.listIdentifierDefinitions(), isArrayOf((d) => isRecord(d) && typeof d.label === 'string')),
    { revalidateOnFocus: false });
}

export function useScannerStatus(enabled: boolean) {
  return useSWR(enabled ? 'scanner-status' : null, () => scanBridgeClient.getScannerStatus(), { refreshInterval: 5000 });
}

/**
 * The configured case fields, for labels such as search placeholders: the definitions when the person may read
 * them, otherwise the labels the cases themselves carry. Never a hard-coded field name.
 */
export function useCaseFields(caps: string[], packages: PackageSummary[] | undefined): { label: string; primary: boolean; searchable: boolean }[] {
  const { data: definitions } = useIdentifierDefinitions(caps.includes(CAP.intake));
  if (definitions?.length) return definitions;
  const seen = new Map<string, { label: string; primary: boolean; searchable: boolean }>();
  for (const pkg of packages ?? []) {
    for (const id of pkg.businessIdentifiers) if (!seen.has(id.label)) seen.set(id.label, { label: id.label, primary: id.primary, searchable: id.searchable });
  }
  return [...seen.values()];
}

export function openFindingsByPackage(items: FocusedReviewItem[] | undefined): Map<string, FocusedReviewItem[]> {
  const map = new Map<string, FocusedReviewItem[]>();
  for (const item of items ?? []) {
    const list = map.get(item.packageId) ?? [];
    list.push(item);
    map.set(item.packageId, list);
  }
  return map;
}
