import { useState } from 'react';
import useSWR from 'swr';
import { apiClient } from '../../api/client';
import type { BulkSource, BulkSources } from '../../types';
import { errorMessage } from '../data';
import { useToast } from '../toast';
import { BULK_WORDS, bulkProgressText, bulkStateText } from '../words';
import { ConfirmDialog, TextField, Toggle, when } from './shared';

/** Task 0079: a source marked as a migration (Bulk): its window and how far it is. Tenant IT. */
export default function SourceBulk({ source, name }: { source: string; name: string }) {
  const toast = useToast();
  const { data, mutate } = useSWR<BulkSources>('bulk-sources', () => apiClient.getBulkSources(), { refreshInterval: 60_000 });
  const [saving, setSaving] = useState(false);
  const [ending, setEnding] = useState(false);
  const [draft, setDraft] = useState<{ from: string; to: string; weekends: boolean } | null>(null);
  if (!data?.sources) return null;
  const bulk: BulkSource | undefined = data.sources.find((s) => s.source === source);

  async function run(work: () => Promise<BulkSources>, done: string) {
    setSaving(true);
    try {
      await mutate(await work(), { revalidate: false });
      setDraft(null);
      setEnding(false);
      toast(done);
    } catch (e) {
      toast(errorMessage(e, "The migration setting couldn't be saved."), 'danger');
    } finally {
      setSaving(false);
    }
  }

  const w = draft ?? (bulk ? { from: bulk.from.slice(0, 5), to: bulk.to.slice(0, 5), weekends: bulk.weekends } : null);
  return (
    <div data-testid="source-bulk">
      <Toggle label={BULK_WORDS.toggle} hint={BULK_WORDS.hint} checked={!!bulk} disabled={saving}
        onChange={(on) => (on ? void run(() => apiClient.markBulkSource(source, {}), BULK_WORDS.on) : setEnding(true))} />
      {bulk && w && (
        <div className="cf-ad-block">
          <p className="cf-ad-hint">{bulkStateText(bulk)}</p>
          <p data-testid="bulk-progress">{bulkProgressText(bulk)}{bulk.expectedFinish && ` · ${BULK_WORDS.expected} ${when(bulk.expectedFinish)}`}</p>
          <div className="cf-ad-grid">
            <TextField label={BULK_WORDS.from} type="time" required value={w.from} onChange={(v) => setDraft({ ...w, from: v })} />
            <TextField label={BULK_WORDS.to} type="time" required value={w.to} onChange={(v) => setDraft({ ...w, to: v })} />
          </div>
          <Toggle label={BULK_WORDS.weekends} checked={w.weekends} onChange={(v) => setDraft({ ...w, weekends: v })} />
          {draft && (
            <button type="button" className="cf-btn" data-size="sm" data-variant="primary" disabled={saving || !draft.from || !draft.to}
              onClick={() => void run(() => apiClient.markBulkSource(source, draft), BULK_WORDS.saved)}>{BULK_WORDS.save}</button>
          )}
        </div>
      )}
      <ConfirmDialog open={ending} title={`${BULK_WORDS.endTitle} ${name}?`} facts={[]} consequence={BULK_WORDS.endBody} confirmLabel={BULK_WORDS.endConfirm} busy={saving}
        onCancel={() => setEnding(false)} onConfirm={() => void run(() => apiClient.unmarkBulkSource(source), BULK_WORDS.off)} />
    </div>
  );
}
