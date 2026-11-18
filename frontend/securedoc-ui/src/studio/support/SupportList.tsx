import { useMemo, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import useSWR from 'swr';
import { Search } from 'lucide-react';
import { apiClient } from '../../api/client';
import type { SupportIncidentSearchPage } from '../../types';
import { Spinner } from '../atoms';
import { LoadErrorCard, checked, isRecord } from '../failure';
import { PROBLEM_REASONS, PROCESSING_STAGE_WORDS, problemReason, processingStageWords } from '../words';
import { SelectField, TextField } from '../admin/shared';
import {
  STATUS_CHIPS, SUPPORT_CAPS, SUPPORT_PAGE_SIZE, blockingCount, chipCounts, impactChip, inChip, lastSeen, rowWhat, rowWhere,
  searchPlaceholder, statusWord, supportSearch, type StatusChip,
} from './supportRules';
import './support.css';

/** UI task U012 (finding 10): Support as Case File rows; Open / Resolved / All and one search box; the rest under More filters. */

interface Criteria {
  status: string;
  stage: string;
  reasonCode: string;
  businessContinuityUsed: string;
  retryable: string;
  correlationId: string;
  packageId: string;
  documentId: string;
  from: string;
  to: string;
  tenantId: string;
}

const EMPTY: Criteria = { status: '', stage: '', reasonCode: '', businessContinuityUsed: '', retryable: '', correlationId: '', packageId: '', documentId: '', from: '', to: '', tenantId: '' };

const STATUS_OPTIONS: Array<[string, string]> = [
  ['', 'Any'], ['OPEN', 'Open'], ['RETRY_SCHEDULED', 'Open: trying again'], ['INVESTIGATING', 'Open: IT looking into it'],
  ['ESCALATED', 'Open: passed on for more help'], ['BUSINESS_CONTINUED', 'Watching (continued manually)'],
  ['RESOLVED', 'Resolved'], ['AUTO_CLOSED', 'Resolved automatically'],
];
const STAGE_OPTIONS: Array<[string, string]> = [['', 'Any stage'], ...Object.keys(PROCESSING_STAGE_WORDS).map((k): [string, string] => {
  const w = processingStageWords(k);
  return [k, w.charAt(0).toUpperCase() + w.slice(1)];
})];
const REASON_OPTIONS: Array<[string, string]> = [['', 'Any reason'], ...Object.keys(PROBLEM_REASONS).map((k): [string, string] => [k, problemReason(k).title])];
const YES_NO: Array<[string, string]> = [['', 'Either'], ['true', 'Yes'], ['false', 'No']];

const bool = (v: string) => (v === 'true' ? true : v === 'false' ? false : undefined);

export default function SupportList({ caps, entityName }: { caps: string[]; entityName?: string }) {
  const canCrossOrganization = caps.includes(SUPPORT_CAPS.platformOps);
  const [chip, setChip] = useState<StatusChip>('open');
  const [text, setText] = useState('');
  const [search, setSearch] = useState<{ incidentId?: string; businessIdentifier?: string }>({});
  const [draft, setDraft] = useState<Criteria>(EMPTY);
  const [applied, setApplied] = useState<Criteria>(EMPTY);
  const [now] = useState(() => Date.now());

  const params = useMemo(() => ({
    ...search,
    status: applied.status || undefined,
    stage: applied.stage || undefined,
    reasonCode: applied.reasonCode || undefined,
    businessContinuityUsed: bool(applied.businessContinuityUsed),
    retryable: bool(applied.retryable),
    correlationId: applied.correlationId.trim() || undefined,
    packageId: applied.packageId.trim() || undefined,
    documentId: applied.documentId.trim() || undefined,
    from: applied.from ? new Date(applied.from).toISOString() : undefined,
    to: applied.to ? new Date(applied.to).toISOString() : undefined,
    tenantId: canCrossOrganization && applied.tenantId.trim() ? applied.tenantId.trim() : undefined,
    page: 0,
    size: SUPPORT_PAGE_SIZE,
  }), [search, applied, canCrossOrganization]);

  const request = 'GET /support/incidents';
  const { data, error, isValidating, mutate } = useSWR<SupportIncidentSearchPage>(
    ['support-incidents', JSON.stringify(params)],
    () => checked(request, apiClient.searchSupportIncidents(params), (v) => isRecord(v) && Array.isArray(v.items) && typeof v.total === 'number'),
    { revalidateOnFocus: false, keepPreviousData: true, refreshInterval: 15000 },
  );

  const set = <K extends keyof Criteria>(key: K) => (v: Criteria[K]) => setDraft((d) => ({ ...d, [key]: v }));
  const rangeInvalid = Boolean(draft.from && draft.to && new Date(draft.from) > new Date(draft.to));
  const dirty = JSON.stringify(draft) !== JSON.stringify(applied);
  const moreCount = (Object.keys(EMPTY) as Array<keyof Criteria>).filter((k) => Boolean(applied[k])).length;

  function find(e: FormEvent) {
    e.preventDefault();
    setSearch(supportSearch(text));
  }

  function apply(e: FormEvent) {
    e.preventDefault();
    if (rangeInvalid) return;
    if (dirty) setApplied(draft);
    else void mutate();
  }

  function clearAll() {
    setDraft(EMPTY);
    setApplied(EMPTY);
    setText('');
    setSearch({});
  }

  const items = data?.items ?? [];
  const counts = chipCounts(items);
  const blocking = blockingCount(items);
  const shown = items.filter((r) => inChip(r, chip));
  const otherOrganization = canCrossOrganization && Boolean(params.tenantId);
  const suffix = otherOrganization ? `?tenantId=${encodeURIComponent(params.tenantId as string)}` : '';

  return (
    <div className="cf-sp">
      <header className="cf-page-head">
        <div>
          <h1 className="cf-title">Support</h1>
          <p className="cf-lede">
            Processing problems{otherOrganization ? ' in another organization' : entityName ? ` in ${entityName}` : ''}
            {blocking > 0 && <> · <mark className="cf-mark-text">{blocking} {blocking === 1 ? 'is' : 'are'} blocking a case</mark></>}
          </p>
        </div>
      </header>

      <div className="cf-sp-bar">
        <div className="cf-segment" role="radiogroup" aria-label="Show">
          {STATUS_CHIPS.map((c) => (
            <button key={c.key} type="button" role="radio" aria-checked={chip === c.key} onClick={() => setChip(c.key)}>
              {c.label} <span>{data ? counts[c.key] : '·'}</span>
            </button>
          ))}
        </div>
        <span className="cf-sp-grow" />
        <form className="cf-sp-find" role="search" onSubmit={find}>
          <label className="cf-search">
            <Search size={15} aria-hidden="true" />
            <span className="cf-sr">Find a problem</span>
            <input value={text} placeholder={searchPlaceholder(items)}
              onChange={(e) => { setText(e.target.value); if (!e.target.value.trim()) setSearch({}); }} />
          </label>
        </form>
      </div>

      <details className="cf-disclosure cf-sp-more">
        <summary>More filters{moreCount ? ` (${moreCount})` : ''}</summary>
        <form onSubmit={apply} noValidate>
          <div className="cf-sp-grid">
            <SelectField label="Problem status" value={draft.status} onChange={set('status')} options={STATUS_OPTIONS} />
            <SelectField label="Where it stopped" value={draft.stage} onChange={set('stage')} options={STAGE_OPTIONS} />
            <SelectField label="Reason" value={draft.reasonCode} onChange={set('reasonCode')} options={REASON_OPTIONS} />
            <SelectField label="Continued manually" value={draft.businessContinuityUsed} onChange={set('businessContinuityUsed')} options={YES_NO} />
            <SelectField label="Can be retried" value={draft.retryable} onChange={set('retryable')} options={YES_NO} />
            <TextField label="Reference" value={draft.correlationId} onChange={set('correlationId')} placeholder="Correlation ID (UUID)" mono />
            <TextField label="Case ID" value={draft.packageId} onChange={set('packageId')} placeholder="UUID" mono />
            <TextField label="Document ID" value={draft.documentId} onChange={set('documentId')} placeholder="UUID" mono />
            <TextField label="Seen from" type="datetime-local" value={draft.from} onChange={set('from')} invalid={rangeInvalid} />
            <TextField label="Seen to" type="datetime-local" value={draft.to} onChange={set('to')} invalid={rangeInvalid}
              hint={rangeInvalid ? 'The end must be after the start.' : undefined} />
            {canCrossOrganization && (
              <TextField label="Organization ID" value={draft.tenantId} onChange={set('tenantId')} placeholder="Another organization (UUID)" mono
                hint="Platform Operations only. Problems there can be read, not acted on." />
            )}
          </div>
          <div className="cf-ad-card-foot">
            <button type="button" className="cf-btn" data-variant="ghost" onClick={clearAll} disabled={!moreCount && !dirty && !search.incidentId && !search.businessIdentifier}>Clear filters</button>
            <button type="submit" className="cf-btn" data-variant="solid" disabled={rangeInvalid || isValidating}>
              {isValidating && <Spinner label="Loading" />}{dirty ? 'Apply filters' : 'Refresh'}
            </button>
          </div>
        </form>
      </details>

      {error && !data ? (
        <LoadErrorCard title="Support problems couldn't be loaded" error={error} request={request} onRetry={() => void mutate()} />
      ) : !data ? (
        <p className="cf-muted"><Spinner label="Loading problems" /> Loading problems…</p>
      ) : shown.length === 0 ? (
        <div className="cf-empty">
          <p className="cf-empty-title">{chip === 'open' && !moreCount && !search.incidentId && !search.businessIdentifier ? 'No open problems' : 'Nothing matches'}</p>
          <p className="cf-empty-text">{chip === 'open' && !moreCount && !search.incidentId && !search.businessIdentifier ? 'LexaVault has no processing problem waiting for IT.' : 'Try another filter or search.'}</p>
        </div>
      ) : (
        <ul className="cf-cases cf-sp-list" aria-label="Problems">
          {shown.map((r) => {
            const what = rowWhat(r);
            const impact = impactChip(r.businessImpact);
            const blocked = impact.stage === 'stopped' && statusWord(r.status) !== 'Resolved';
            return (
              <li key={r.incidentId}>
                <Link to={`/support/incidents/${r.incidentId}${suffix}`} className="cf-sp-row" data-blocked={blocked || undefined}>
                  <span className="cf-case-flag" aria-hidden="true" />
                  <span className="cf-case-id">
                    <span className="cf-case-primary cf-mono">{r.primaryIdentifierValue || 'Case'}</span>
                    <span className="cf-case-secondary">{rowWhere(r)}</span>
                  </span>
                  <span className="cf-sp-what" data-muted={statusWord(r.status) === 'Resolved' || undefined}>
                    {what.text}{what.note && <span className="cf-sp-note"> · {what.note}</span>}
                  </span>
                  <span><span className="cf-stage" data-stage={impact.stage}><span className="cf-stage-dot" aria-hidden="true" />{impact.label}</span></span>
                  <span className="cf-muted">{statusWord(r.status)}</span>
                  <span className="cf-case-time"><time dateTime={r.lastObservedAt}>{lastSeen(r.lastObservedAt, now)}</time></span>
                  <span className="cf-case-act"><span className="cf-btn" data-size="sm">Open</span></span>
                </Link>
              </li>
            );
          })}
        </ul>
      )}
      {data && data.total > items.length && (
        <p className="cf-sp-count">The latest {items.length} of {data.total} problems are counted and shown. Narrow the search to see older ones.</p>
      )}
      {error && data && <p className="cf-danger-text" role="alert">The list couldn&apos;t be refreshed. It shows the last answer.</p>}
    </div>
  );
}
