import { useDeferredValue, useState } from 'react';
import useSWR from 'swr';
import { apiClient } from '../../api/client';
import type { AuditRecordResult } from '../../types';
import { errorMessage } from '../data';
import { Spinner } from '../atoms';
import { useToast } from '../toast';
import { Loading, humanize, when } from './shared';

export default function AuditArea() {
  const toast = useToast();
  const [query, setQuery] = useState('');
  const [outcome, setOutcome] = useState('ALL');
  const [exporting, setExporting] = useState(false);
  const deferred = useDeferredValue(query.trim().toLowerCase());

  const { data, error, mutate } = useSWR<AuditRecordResult[]>('audit', () => apiClient.listAuditRecords(), { revalidateOnFocus: false });

  async function exportCsv() {
    setExporting(true);
    try {
      const blob = await apiClient.exportAuditRecords(outcome === 'ALL' ? {} : { outcome }, 'csv');
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'lexavault-audit.csv';
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      toast(errorMessage(e, 'Export failed.'), 'danger');
    } finally {
      setExporting(false);
    }
  }

  if (!data) return <Loading error={error} retry={() => mutate()} label="Audit trail" />;

  const rows = data.filter((r) =>
    (outcome === 'ALL' || r.outcome === outcome) &&
    (!deferred || [r.action, r.actorId, r.resourceType, r.resourceId, r.detail ?? ''].some((v) => v.toLowerCase().includes(deferred))),
  );

  return (
    <section className="cf-ad-card" aria-label="Audit records">
      <div className="cf-ad-toolbar">
        <input type="search" className="cf-ad-search" placeholder="Filter by action, person or resource" aria-label="Filter audit records" value={query} onChange={(e) => setQuery(e.target.value)} />
        <div className="cf-segment" role="radiogroup" aria-label="Outcome">
          {['ALL', 'SUCCESS', 'FAILURE'].map((o) => (
            <button key={o} type="button" role="radio" aria-checked={outcome === o} className={outcome === o ? 'is-active' : ''} onClick={() => setOutcome(o)}>{o === 'ALL' ? 'All' : humanize(o)}</button>
          ))}
        </div>
        <button type="button" className="cf-btn" data-size="sm" onClick={exportCsv} disabled={exporting}>{exporting && <Spinner label="Exporting" />}Export</button>
      </div>
      <p className="cf-count" aria-live="polite">{rows.length} of {data.length} records</p>
      {rows.length === 0 ? <p className="cf-muted">No records match.</p> : (
        <ol className="cf-ad-audit">
          {rows.slice(0, 200).map((r) => (
            <li key={r.id} data-outcome={r.outcome}>
              <time dateTime={r.occurredAt} className="cf-mono">{when(r.occurredAt)}</time>
              <div className="cf-ad-audit-main">
                <span className="cf-ad-audit-action">{humanize(r.action)}</span>
                <span className="cf-muted"> by {r.actorId}</span>
                {r.detail && <p className="cf-ad-hint">{r.detail}</p>}
              </div>
              <span className="cf-ad-audit-res cf-mono" title={r.correlationId}>{humanize(r.resourceType)} · {r.resourceId.slice(0, 12)}</span>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
