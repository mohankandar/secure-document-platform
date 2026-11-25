import { useEffect, useState } from 'react';
import useSWR, { useSWRConfig } from 'swr';
import { apiClient } from '../api/client';
import type { PackageSummary } from '../types';
import { ConfirmDialog } from './admin/shared';
import './admin/admin.css';
import { Notice } from './atoms';
import { BULK_WORDS, approveSelectedLabel, blockedReason, confirmTitle, keepSelectable, resultLines, type BulkOffer } from './bulkApproveRules';
import { caseTitle, errorMessage } from './data';

export interface BulkSelection { selected: string[]; toggle: (id: string) => void; blocked: (id: string) => string | null }

/** Task 0076: the selection on Cases › Approval; null while bulk approval is not offered. */
export function useBulkApprove(enabled: boolean, waiting: readonly PackageSummary[]) {
  const ids = enabled ? waiting.map((p) => p.packageId).sort() : [];
  const { data: offer, mutate: askAgain } = useSWR<BulkOffer>(ids.length > 0 ? ['bulk-approval', ...ids] : null, () => apiClient.bulkApprovalOffer(ids), { revalidateOnFocus: false, keepPreviousData: true });
  const [selected, setSelected] = useState<string[]>([]);
  const on = enabled && offer?.enabled === true && Array.isArray(offer.reviewedByYou);
  const kept = on ? keepSelectable(selected, ids, offer) : [];
  useEffect(() => { if (!enabled) setSelected([]); }, [enabled]);
  const selection: BulkSelection | null = on ? {
    selected: kept,
    blocked: (id) => blockedReason(id, kept, offer),
    toggle: (id) => setSelected(kept.includes(id) ? kept.filter((x) => x !== id) : [...kept, id]),
  } : null;
  return { selection, clear: () => { setSelected([]); void askAgain(); } };
}

export function BulkApproveBar({ selection, waiting, onDone }: { selection: BulkSelection; waiting: readonly PackageSummary[]; onDone: () => void }) {
  const { mutate } = useSWRConfig();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<{ done: string; failed: string[] } | null>(null);
  const names = new Map(waiting.map((p) => [p.packageId, caseTitle(p.businessIdentifiers).primary]));
  const name = (id: string) => names.get(id) ?? 'A case';
  const chosen = selection.selected;

  async function approve() {
    if (busy || chosen.length === 0) return;
    setBusy(true);
    setError(null);
    try {
      const outcomes = await apiClient.bulkApprove(chosen);
      setResult(resultLines(outcomes, name));
      setOpen(false);
      onDone();
    } catch (err) {
      setError(errorMessage(err, BULK_WORDS.failed));
    } finally {
      setBusy(false);
      await mutate('packages');
    }
  }

  return (
    <div className="cf-bulk-approve">
      <button type="button" className="cf-btn" data-variant="solid" disabled={chosen.length === 0} onClick={() => { setResult(null); setError(null); setOpen(true); }}>
        {approveSelectedLabel(chosen.length)}
      </button>
      {result && (
        <div role="status">
          <Notice tone={result.failed.length > 0 ? 'danger' : 'mark'}>
            {result.done}
            {result.failed.length > 0 && <ul>{result.failed.map((line) => <li key={line}>{line}</li>)}</ul>}
          </Notice>
        </div>
      )}
      <ConfirmDialog open={open} title={confirmTitle(chosen.length)} busy={busy} error={error}
        facts={[['Cases', <span className="cf-mono" key="c">{chosen.map(name).join(' · ')}</span>], ['Count', chosen.length]]}
        consequence={BULK_WORDS.consequence} confirmLabel={`Approve ${chosen.length}`}
        onConfirm={() => void approve()} onCancel={() => { if (!busy) setOpen(false); }} />
    </div>
  );
}
