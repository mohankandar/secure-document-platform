import useSWR from 'swr';
import { apiClient } from '../api/client';
import { setCurrentCapabilities } from '../api/config';
import { scanBridgeClient } from '../api/scanBridgeClient';
import type { BusinessIdentifier, FocusedReviewItem, PackageSummary } from '../types';

export type StageKey = 'intake' | 'redacting' | 'review' | 'approval' | 'delivered' | 'stopped';

export const STAGES: { key: StageKey; label: string; statuses: string[] }[] = [
  { key: 'intake', label: 'Intake', statuses: ['DRAFT', 'UPLOADED', 'SUBMITTED'] },
  { key: 'redacting', label: 'Redacting', statuses: ['PROCESSING'] },
  { key: 'review', label: 'Review', statuses: ['PENDING_REVIEW', 'IN_REVIEW'] },
  { key: 'approval', label: 'Approval', statuses: ['PENDING_FINAL_APPROVAL'] },
  { key: 'delivered', label: 'Delivered', statuses: ['COMPLETED', 'EXPORTED', 'DELIVERED'] },
  { key: 'stopped', label: 'Stopped', statuses: ['FAILED', 'REJECTED'] },
];

export const LIFECYCLE: StageKey[] = ['intake', 'redacting', 'review', 'approval', 'delivered'];

export function stageOf(status: string): StageKey {
  return STAGES.find((s) => s.statuses.includes(status))?.key ?? 'intake';
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
} as const;

/** A case needs the current user when they hold the capability to move it forward. */
export function needsYou(pkg: PackageSummary, caps: string[]): boolean {
  const stage = stageOf(pkg.status);
  if (stage === 'review') return caps.includes(CAP.review);
  if (stage === 'approval') return caps.includes(CAP.approve);
  if (stage === 'stopped') return pkg.status === 'FAILED' && caps.includes(CAP.reprocess);
  return false;
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

export const REASON_COPY: Record<string, string> = {
  LOW_CONFIDENCE: 'The model is unsure this text is sensitive.',
  LOCATION_UNCERTAIN: 'Sensitive text found, but its exact position is unclear.',
  POLICY_REVIEW: 'Your policy requires a person to confirm this type.',
};

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

export function useMe() {
  return useSWR('me', async () => {
    const me = await apiClient.getCurrentUser();
    setCurrentCapabilities(me.capabilities);
    return me;
  }, { revalidateOnFocus: false });
}

export function usePackages(enabled: boolean) {
  return useSWR(enabled ? 'packages' : null, () => apiClient.listPackages(), { refreshInterval: 4000 });
}

export function usePackage(packageId: string | undefined) {
  return useSWR(packageId ? ['package', packageId] : null, () => apiClient.getPackage(packageId as string), { refreshInterval: 3000 });
}

export function useReviewQueue(enabled: boolean) {
  return useSWR(enabled ? 'review-queue' : null, () => apiClient.listReviewItems(500), { refreshInterval: 5000 });
}

export function useIdentifierDefinitions() {
  return useSWR('identifier-definitions', () => apiClient.listIdentifierDefinitions(), { revalidateOnFocus: false });
}

export function useScannerStatus(enabled: boolean) {
  return useSWR(enabled ? 'scanner-status' : null, () => scanBridgeClient.getScannerStatus(), { refreshInterval: 5000 });
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
