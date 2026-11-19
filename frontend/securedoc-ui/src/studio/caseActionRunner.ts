import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useSWRConfig } from 'swr';
import { apiClient } from '../api/client';
import { errorMessage, type CaseActionKind } from './data';
import { useToast } from './toast';

/** The confirmation words for each case action: the same in a Cases row and in the case panel. */
export const CONFIRM_COPY: Partial<Record<CaseActionKind, { title: string; body: string; confirm: string }>> = {
  approve: { title: 'Release redacted documents?', body: 'Delivery starts immediately and cannot be recalled from the destination.', confirm: 'Approve delivery' },
  reprocess: { title: 'Process this case again?', body: 'Redaction runs again on the kept original and the result is sent again. This uses one of the allowed runs.', confirm: 'Process again' },
  'technical-retry': { title: 'Retry automatic processing?', body: 'LexaVault runs the stopped step again on the kept original.', confirm: 'Retry' },
  'manual-fallback': { title: 'Continue this case manually?', body: 'You will place redactions yourself instead of waiting for automatic processing.', confirm: 'Continue manually' },
};

const DONE: Partial<Record<CaseActionKind, string>> = {
  approve: 'Delivery approved.', reprocess: 'Processing again.', 'technical-retry': 'Retry started.', 'manual-fallback': 'Manual review opened.',
};

/**
 * Runs a case action (after its confirmation) and refreshes everything that shows the case. Shared by the Cases
 * rows and the case panel.
 */
export function useCaseActionRunner(packageId: string, refresh?: () => Promise<unknown>) {
  const { mutate } = useSWRConfig();
  const navigate = useNavigate();
  const toast = useToast();
  const [busy, setBusy] = useState<CaseActionKind | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function run(kind: CaseActionKind): Promise<boolean> {
    if (kind === 'review') { navigate(`/review?case=${packageId}`); return true; }
    setBusy(kind);
    setError(null);
    try {
      if (kind === 'approve') await apiClient.approvePackageDelivery(packageId);
      else if (kind === 'reprocess') await apiClient.reprocessPackage(packageId);
      else if (kind === 'technical-retry') await apiClient.retryTechnicalException(packageId);
      else if (kind === 'manual-fallback') await apiClient.startManualFallback(packageId);
      await Promise.all([refresh?.(), mutate('packages'), mutate('review-queue'), mutate(['package', packageId]), mutate(['lifecycle', packageId])]);
      toast(DONE[kind] ?? 'Done.', 'mark');
      if (kind === 'manual-fallback') navigate(`/packages/${packageId}/manual-processing`);
      return true;
    } catch (err) {
      setError(errorMessage(err, 'That action could not be completed. Nothing was changed.'));
      await refresh?.();
      return false;
    } finally {
      setBusy(null);
    }
  }

  return { run, busy, error, clearError: () => setError(null) };
}
