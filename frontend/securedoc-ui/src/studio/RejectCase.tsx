import { useState } from 'react';
import { useSWRConfig } from 'swr';
import { apiClient } from '../api/client';
import { ConfirmDialog } from './admin/shared';
import { errorMessage } from './data';
import { REJECTION_CODES, REJECT_WORDS, rejectionName } from './rejectRules';
import { useToast } from './toast';
import './admin/admin.css';

/** Task 0071: the "Reject case" button and its confirmation, the same in the case panel and in Review. */
export function RejectCase({ packageId, caseName, step, onRejected, small }: {
  packageId: string; caseName: string; step: 'review' | 'approval'; onRejected?: () => Promise<unknown> | void; small?: boolean;
}) {
  const { mutate } = useSWRConfig();
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function reject() {
    if (!reason || busy) return;
    setBusy(true);
    setError(null);
    try {
      await apiClient.rejectCase(packageId, step, reason);
      setOpen(false);
      toast(REJECT_WORDS.done, 'mark');
      await Promise.all([onRejected?.(), mutate('packages'), mutate('review-queue'), mutate(['package', packageId]), mutate(['lifecycle', packageId])]);
    } catch (err) {
      setError(errorMessage(err, REJECT_WORDS.failed));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <button type="button" className="cf-btn" data-variant="ghost" data-size={small ? 'sm' : undefined}
        onClick={() => { setReason(''); setError(null); setOpen(true); }}>{REJECT_WORDS.action}</button>
      <ConfirmDialog open={open} title={`Reject case ${caseName}?`} danger busy={busy} error={error}
        facts={[['Case', caseName], [REJECT_WORDS.reason, reason ? rejectionName(reason) : REJECT_WORDS.choose]]}
        consequence={<>
          <div className="cf-field">
            <label htmlFor="cf-reject-reason"><span>{REJECT_WORDS.reason}</span></label>
            <select id="cf-reject-reason" value={reason} disabled={busy} onChange={(e) => setReason(e.target.value)}>
              <option value="">{REJECT_WORDS.choose}</option>
              {REJECTION_CODES.map((code) => <option key={code} value={code}>{rejectionName(code)}</option>)}
            </select>
            {reason === 'LV-REV-014' && <span className="cf-field-hint">{REJECT_WORDS.otherHint}</span>}
          </div>
          <p>{REJECT_WORDS.consequence}</p>
        </>}
        confirmLabel={REJECT_WORDS.confirm} confirmDisabled={!reason} onConfirm={() => void reject()} onCancel={() => { if (!busy) setOpen(false); }} />
    </>
  );
}
