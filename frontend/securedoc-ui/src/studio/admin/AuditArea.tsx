import { useMemo, useState, type FormEvent } from 'react';
import useSWR from 'swr';
import { apiClient } from '../../api/client';
import type { ApiError, AuditRecordResult } from '../../types';
import { errorMessage } from '../data';
import { Notice, Spinner } from '../atoms';
import { useToast } from '../toast';
import { SelectField, TextField, humanize, when } from './shared';

type AuditCategory = 'MEANINGFUL' | 'SECURITY' | 'ADMINISTRATION' | 'DOCUMENTS' | 'EXPORTS' | 'FAILURES_DENIED' | 'SYSTEM';

const CATEGORIES: Array<[AuditCategory, string]> = [
  ['MEANINGFUL', 'All meaningful'],
  ['SECURITY', 'Security & sign-in'],
  ['ADMINISTRATION', 'Administration'],
  ['DOCUMENTS', 'Documents'],
  ['EXPORTS', 'Downloads & exports'],
  ['FAILURES_DENIED', 'Failures / denied'],
  ['SYSTEM', 'System'],
];

/* Mirrors the production AuditPage action list exactly. */
const ACTIONS = [
  'ENTERPRISE_IDENTITY_DRAFT_SAVED',
  'ENTERPRISE_IDENTITY_CONNECTION_TESTED',
  'ENTERPRISE_IDENTITY_CONNECTION_ACTIVATED',
  'ENTERPRISE_IDENTITY_CONNECTION_ROLLED_BACK',
  'INTEGRATION_CONNECTION_CREATED',
  'INTEGRATION_CONNECTION_UPDATED',
  'PROCESSING_STORAGE_PROVIDER_ACTIVATED',
  'BRANDING_CONFIGURATION_CHANGED',
  'PACKAGE_CREATED',
  'PACKAGE_APPROVED',
  'DOCUMENT_UPLOADED',
  'DOCUMENT_REDACTION_COMPLETED',
  'DOCUMENT_PROCESSING_FAILED',
  'DOCUMENT_DOWNLOADED',
  'REPORT_CSV_EXPORTED',
  'AUDIT_EXPORTED',
  'AUTHORIZATION_FAILED',
  'WATCHED_FOLDER_SCAN_TRIGGERED',
] as const;

const ROUTINE_ACTION = 'WATCHED_FOLDER_SCAN_TRIGGERED';

interface Criteria {
  action: string;
  outcome: string;
  resourceType: string;
  resourceId: string;
  actorId: string;
  correlationId: string;
  from: string;
  to: string;
}

const EMPTY: Criteria = { action: '', outcome: '', resourceType: '', resourceId: '', actorId: '', correlationId: '', from: '', to: '' };

function buildFilters(category: AuditCategory, c: Criteria, includeRoutine: boolean) {
  const filters: Record<string, string> = { category };
  if (c.action) filters.action = c.action;
  if (c.outcome) filters.outcome = c.outcome;
  if (c.resourceType.trim()) filters.resourceType = c.resourceType.trim();
  if (c.resourceId.trim()) filters.resourceId = c.resourceId.trim();
  if (c.actorId.trim()) filters.actorId = c.actorId.trim();
  if (c.correlationId.trim()) filters.correlationId = c.correlationId.trim();
  if (c.from) filters.from = new Date(c.from).toISOString();
  if (c.to) filters.to = new Date(c.to).toISOString();
  if (includeRoutine) filters.includeRoutineActivity = 'true';
  return filters;
}

function loadError(e: unknown) {
  return (e as ApiError)?.errorCode === 'ACCESS_DENIED'
    ? 'Access denied: your current role cannot view audit records.'
    : errorMessage(e, 'Failed to load audit records.');
}

export default function AuditArea() {
  const toast = useToast();
  const [category, setCategory] = useState<AuditCategory>('MEANINGFUL');
  const [includeRoutine, setIncludeRoutine] = useState(false);
  const [draft, setDraft] = useState<Criteria>(EMPTY);
  const [applied, setApplied] = useState<Criteria>(EMPTY);
  const [exporting, setExporting] = useState<'' | 'csv' | 'json'>('');

  const filters = useMemo(() => buildFilters(category, applied, includeRoutine), [category, applied, includeRoutine]);
  const { data, error, isValidating, mutate } = useSWR<AuditRecordResult[]>(
    ['audit', JSON.stringify(filters)],
    () => apiClient.listAuditRecords(filters),
    { revalidateOnFocus: false, keepPreviousData: true },
  );

  const set = (key: keyof Criteria) => (v: string) => setDraft((d) => ({ ...d, [key]: v }));
  const rangeInvalid = Boolean(draft.from && draft.to && new Date(draft.from) > new Date(draft.to));
  const dirty = JSON.stringify(draft) !== JSON.stringify(applied);
  const activeCount = Object.values(applied).filter(Boolean).length;

  function chooseCategory(next: AuditCategory) {
    setCategory(next);
    if (next === 'FAILURES_DENIED') {
      setDraft((d) => ({ ...d, outcome: '' }));
      setApplied((a) => ({ ...a, outcome: '' }));
    }
  }

  function apply(e: FormEvent) {
    e.preventDefault();
    if (rangeInvalid) return;
    if (dirty) setApplied(draft);
    else void mutate();
  }

  function clear() {
    setDraft(EMPTY);
    setApplied(EMPTY);
  }

  async function exportRecords(format: 'csv' | 'json') {
    setExporting(format);
    try {
      const blob = await apiClient.exportAuditRecords(filters, format);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `lexavault-audit.${format}`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
      toast(`Exported ${format.toUpperCase()} with the applied filters.`);
    } catch (e) {
      toast(errorMessage(e, 'Audit export failed.'), 'danger');
    } finally {
      setExporting('');
    }
  }

  const records = data ?? [];
  const routineNote = !includeRoutine && applied.action !== ROUTINE_ACTION ? ' · routine no-op scans excluded' : '';

  return (
    <>
      <section className="cf-ad-card" aria-labelledby="cf-audit-filters">
        <div className="cf-ad-card-head">
          <div>
            <h2 id="cf-audit-filters">Investigation filters</h2>
            <p>Start with a category, then narrow with exact identifiers. Filters are applied by the server, so exports match what you see.</p>
          </div>
          <label className="cf-ad-ack">
            <input type="checkbox" checked={includeRoutine} onChange={(e) => setIncludeRoutine(e.target.checked)} />
            <span>Show historical routine activity</span>
          </label>
        </div>

        <div className="cf-segment cf-ad-audit-cats" role="radiogroup" aria-label="Audit category">
          {CATEGORIES.map(([key, label]) => (
            <button key={key} type="button" role="radio" aria-checked={category === key} className={category === key ? 'is-active' : ''} onClick={() => chooseCategory(key)}>{label}</button>
          ))}
        </div>

        <form onSubmit={apply} noValidate>
          <div className="cf-ad-audit-grid">
            <div className="cf-field">
              <label htmlFor="cf-audit-action"><span>Action</span><span className="cf-field-opt">Optional</span></label>
              <select id="cf-audit-action" value={draft.action} onChange={(e) => set('action')(e.target.value)}>
                <option value="">All actions in category</option>
                {ACTIONS.map((a) => <option key={a} value={a}>{humanize(a)}</option>)}
              </select>
            </div>
            <SelectField label="Outcome" value={draft.outcome} onChange={set('outcome')}
              disabled={category === 'FAILURES_DENIED'}
              hint={category === 'FAILURES_DENIED' ? 'The Failures / denied category already limits outcomes.' : undefined}
              options={[['', 'All outcomes'], ['SUCCESS', 'Success'], ['FAILURE', 'Failure'], ['DENIED', 'Denied']]} />
            <TextField label="Resource type" value={draft.resourceType} onChange={set('resourceType')} placeholder="e.g. EnterpriseIdentityConnection" mono />
            <TextField label="Resource ID" value={draft.resourceId} onChange={set('resourceId')} placeholder="Exact resource identifier" mono />
            <TextField label="Actor ID" value={draft.actorId} onChange={set('actorId')} placeholder="UUID" mono />
            <TextField label="Correlation ID" value={draft.correlationId} onChange={set('correlationId')} placeholder="UUID" mono />
            <TextField label="From" type="datetime-local" value={draft.from} onChange={set('from')} invalid={rangeInvalid} />
            <TextField label="To" type="datetime-local" value={draft.to} onChange={set('to')} invalid={rangeInvalid}
              hint={rangeInvalid ? 'The end must be after the start.' : undefined} />
          </div>
          <div className="cf-ad-card-foot">
            <button type="button" className="cf-btn" data-variant="ghost" onClick={clear} disabled={!activeCount && !dirty}>Clear filters</button>
            <button type="submit" className="cf-btn" data-variant="primary" disabled={rangeInvalid || isValidating}>
              {isValidating && <Spinner label="Loading" />}{dirty ? 'Apply filters' : 'Refresh'}
            </button>
          </div>
        </form>
      </section>

      <section className="cf-ad-card" aria-labelledby="cf-audit-results" aria-busy={isValidating || undefined}>
        <div className="cf-ad-card-head">
          <div>
            <h2 id="cf-audit-results">Audit events</h2>
            <p aria-live="polite">
              {data ? `${records.length} shown${routineNote}` : 'Loading…'}
            </p>
          </div>
          <div className="cf-ad-toolbar">
            <button type="button" className="cf-btn" data-size="sm" onClick={() => exportRecords('csv')} disabled={Boolean(exporting) || Boolean(error)}>
              {exporting === 'csv' && <Spinner label="Exporting" />}Export CSV
            </button>
            <button type="button" className="cf-btn" data-size="sm" onClick={() => exportRecords('json')} disabled={Boolean(exporting) || Boolean(error)}>
              {exporting === 'json' && <Spinner label="Exporting" />}Export JSON
            </button>
          </div>
        </div>

        {error ? (
          <div className="cf-ad-stack">
            <Notice tone="danger">{loadError(error)}</Notice>
            <div><button type="button" className="cf-btn" data-size="sm" onClick={() => mutate()}>Retry</button></div>
          </div>
        ) : !data ? (
          <p className="cf-muted"><Spinner label="Loading audit records" /> Loading audit records…</p>
        ) : records.length === 0 ? (
          <p className="cf-muted">No audit events match these filters.</p>
        ) : (
          <ol className="cf-ad-audit">
            {records.map((r) => (
              <li key={r.id} data-outcome={r.outcome}>
                <time dateTime={r.occurredAt} className="cf-mono">{when(r.occurredAt)}</time>
                <div className="cf-ad-audit-main">
                  <span className="cf-ad-audit-action">{humanize(r.action)}</span>
                  <span className="cf-ad-tag" data-tone={r.outcome === 'SUCCESS' ? 'ok' : 'danger'}>{humanize(r.outcome)}</span>
                  {r.detail && <p className="cf-ad-hint">{r.detail}</p>}
                  <dl className="cf-ad-audit-ids">
                    <div><dt>Resource</dt><dd>{r.resourceType} <code className="cf-mono">{r.resourceId}</code></dd></div>
                    <div><dt>Actor</dt><dd><code className="cf-mono">{r.actorId}</code></dd></div>
                    <div><dt>Correlation</dt><dd><code className="cf-mono">{r.correlationId}</code></dd></div>
                  </dl>
                </div>
              </li>
            ))}
          </ol>
        )}
      </section>
    </>
  );
}
