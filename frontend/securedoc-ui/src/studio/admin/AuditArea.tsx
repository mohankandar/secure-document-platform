import { Fragment, useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import useSWR from 'swr';
import { Search } from 'lucide-react';
import { apiClient } from '../../api/client';
import type { ApiError, AuditRecordResult } from '../../types';
import { errorMessage } from '../data';
import { Notice, Spinner } from '../atoms';
import { useToast } from '../toast';
import { SelectField, TextField, when } from './shared';
import {
  AUDIT_TYPES, PERIODS, SYSTEM_ACTOR_ID, actionOptions, caseId, caseSearch, changeLine, periodStart, personName,
  recordedFields, resultWord, shortId, whatHappened, type AuditTypeKey, type PeriodKey,
} from './auditRules';

/** UI task U011 (finding 29): rows read as sentences; everyday filters up front, exact ones under More filters. */

const ROUTINE_ACTION = 'WATCHED_FOLDER_SCAN_TRIGGERED';
const TIMELINE_LIMIT = 50;
const ACTIONS = actionOptions();

interface Criteria {
  action: string;
  outcome: string;
  resourceType: string;
  resourceId: string;
  actorId: string;
  correlationId: string;
  from: string;
  to: string;
  includeRoutine: boolean;
}

const EMPTY: Criteria = { action: '', outcome: '', resourceType: '', resourceId: '', actorId: '', correlationId: '', from: '', to: '', includeRoutine: false };
const MORE_KEYS: Array<keyof Criteria> = ['action', 'outcome', 'resourceType', 'resourceId', 'actorId', 'correlationId', 'from', 'to', 'includeRoutine'];

function loadError(e: unknown) {
  return (e as ApiError)?.errorCode === 'ACCESS_DENIED'
    ? 'Access denied: your current role cannot view audit records.'
    : errorMessage(e, 'Failed to load audit records.');
}

export default function AuditArea() {
  const toast = useToast();
  const [type, setType] = useState<AuditTypeKey>('MEANINGFUL');
  const [period, setPeriod] = useState<PeriodKey>('7d');
  const [anchor, setAnchor] = useState(() => Date.now());
  const [caseText, setCaseText] = useState('');
  const [caseFilter, setCaseFilter] = useState('');
  const [caseError, setCaseError] = useState('');
  const [draft, setDraft] = useState<Criteria>(EMPTY);
  const [applied, setApplied] = useState<Criteria>(EMPTY);
  const [open, setOpen] = useState<string | null>(null);
  const [exporting, setExporting] = useState<'' | 'csv' | 'json'>('');

  const exact = Boolean(applied.from || applied.to);
  const filters = useMemo(() => {
    const f: Record<string, string> = { category: type };
    if (applied.action) f.action = applied.action;
    if (applied.outcome && type !== 'FAILURES_DENIED') f.outcome = applied.outcome;
    if (applied.resourceType.trim()) f.resourceType = applied.resourceType.trim();
    const resourceId = caseFilter || applied.resourceId.trim();
    if (resourceId) f.resourceId = resourceId;
    if (applied.actorId.trim()) f.actorId = applied.actorId.trim();
    if (applied.correlationId.trim()) f.correlationId = applied.correlationId.trim();
    if (exact) {
      if (applied.from) f.from = new Date(applied.from).toISOString();
      if (applied.to) f.to = new Date(applied.to).toISOString();
    } else {
      const from = periodStart(period, anchor);
      if (from) f.from = from;
    }
    if (applied.includeRoutine) f.includeRoutineActivity = 'true';
    return f;
  }, [type, applied, caseFilter, exact, period, anchor]);

  const { data, error, isValidating, mutate } = useSWR<AuditRecordResult[]>(
    ['audit', JSON.stringify(filters)],
    () => apiClient.listAuditRecords(filters),
    { revalidateOnFocus: false, keepPreviousData: true },
  );

  const set = <K extends keyof Criteria>(key: K) => (v: Criteria[K]) => setDraft((d) => ({ ...d, [key]: v }));
  const rangeInvalid = Boolean(draft.from && draft.to && new Date(draft.from) > new Date(draft.to));
  const dirty = JSON.stringify(draft) !== JSON.stringify(applied);
  const moreCount = MORE_KEYS.filter((k) => Boolean(applied[k])).length;
  const anyFilter = moreCount > 0 || Boolean(caseFilter) || type !== 'MEANINGFUL' || period !== '7d';

  /* Person: the system, or anyone seen in the results (the records hold no names yet). */
  const people = useMemo(() => {
    const ids = new Set<string>();
    for (const r of data ?? []) if (r.actorId && r.actorId !== SYSTEM_ACTOR_ID) ids.add(r.actorId);
    if (applied.actorId && applied.actorId !== SYSTEM_ACTOR_ID) ids.add(applied.actorId);
    return [...ids].sort();
  }, [data, applied.actorId]);

  function choosePerson(actorId: string) {
    setDraft((d) => ({ ...d, actorId }));
    setApplied((a) => ({ ...a, actorId }));
  }

  function choosePeriod(next: string) {
    if (next === 'exact') return;
    setPeriod(next as PeriodKey);
    setAnchor(Date.now());
    setDraft((d) => ({ ...d, from: '', to: '' }));
    setApplied((a) => ({ ...a, from: '', to: '' }));
  }

  function searchCase(e: FormEvent) {
    e.preventDefault();
    const r = caseSearch(caseText);
    setCaseError(r.error ?? '');
    if (!r.error) setCaseFilter(r.caseId ?? '');
  }

  function apply(e: FormEvent) {
    e.preventDefault();
    if (rangeInvalid) return;
    if (dirty) setApplied(draft);
    else { setAnchor(Date.now()); void mutate(); }
  }

  function clearAll() {
    setType('MEANINGFUL');
    setPeriod('7d');
    setAnchor(Date.now());
    setCaseText('');
    setCaseFilter('');
    setCaseError('');
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
      toast(`Exported ${format.toUpperCase()} with these filters.`);
    } catch (e) {
      toast(errorMessage(e, 'Audit export failed.'), 'danger');
    } finally {
      setExporting('');
    }
  }

  const records = data ?? [];
  const countLine = records.length >= TIMELINE_LIMIT
    ? `The latest ${records.length} are shown. Narrow the filters, or export to get up to 5,000.`
    : `${records.length} shown.`;
  const routineNote = !applied.includeRoutine && applied.action !== ROUTINE_ACTION ? ' Routine folder checks that found nothing are left out.' : '';

  return (
    <section className="cf-ad-audit" aria-label="Audit records" aria-busy={isValidating || undefined}>

      <div className="cf-ad-audit-bar">
        <div className="cf-segment cf-ad-audit-cats" role="radiogroup" aria-label="Type">
          {AUDIT_TYPES.map((t) => (
            <button key={t.key} type="button" role="radio" aria-checked={type === t.key} className={type === t.key ? 'is-active' : ''} onClick={() => setType(t.key)}>{t.label}</button>
          ))}
        </div>
        <label className="cf-ad-audit-pick">
          <span className="cf-sr">Person</span>
          <select aria-label="Person" value={applied.actorId} onChange={(e) => choosePerson(e.target.value)}>
            <option value="">Person: anyone</option>
            <option value={SYSTEM_ACTOR_ID}>LexaVault (automatic)</option>
            {people.map((id) => <option key={id} value={id}>{personName(id)}</option>)}
          </select>
        </label>
        <label className="cf-ad-audit-pick">
          <span className="cf-sr">Period</span>
          <select aria-label="Period" value={exact ? 'exact' : period} onChange={(e) => choosePeriod(e.target.value)}>
            {PERIODS.map((p) => <option key={p.key} value={p.key}>{p.label}</option>)}
            {exact && <option value="exact">Exact time range</option>}
          </select>
        </label>
        <form className="cf-ad-audit-find" role="search" onSubmit={searchCase}>
          <label className="cf-search" data-invalid={caseError ? true : undefined}>
            <Search size={15} aria-hidden="true" />
            <span className="cf-sr">Find a case</span>
            <input value={caseText} placeholder="Loan number or case ID" aria-invalid={caseError ? true : undefined}
              aria-describedby={caseError ? 'cf-audit-case-error' : undefined}
              onChange={(e) => { setCaseText(e.target.value); setCaseError(''); if (!e.target.value.trim()) setCaseFilter(''); }} />
          </label>
        </form>
        <ExportMenu busy={exporting} disabled={Boolean(error)} onPick={exportRecords} />
      </div>
      {caseError && <p id="cf-audit-case-error" className="cf-ad-audit-error" role="alert">{caseError}</p>}

      <details className="cf-disclosure cf-ad-audit-more">
        <summary>More filters{moreCount ? ` (${moreCount})` : ''}</summary>
        <form onSubmit={apply} noValidate>
          <div className="cf-ad-audit-grid">
            <SelectField label="Action" value={draft.action} onChange={set('action')} options={[['', 'Any action'], ...ACTIONS]} />
            <SelectField label="Result" value={type === 'FAILURES_DENIED' ? '' : draft.outcome} onChange={set('outcome')}
              disabled={type === 'FAILURES_DENIED'}
              hint={type === 'FAILURES_DENIED' ? 'Refused already shows only failed and refused actions.' : undefined}
              options={[['', 'Any result'], ['SUCCESS', 'Success'], ['FAILURE', 'Failed'], ['DENIED', 'Refused']]} />
            <TextField label="Item type" value={draft.resourceType} onChange={set('resourceType')} placeholder="e.g. DocumentPackage" mono />
            <TextField label="Item ID" value={caseFilter || draft.resourceId} onChange={set('resourceId')} placeholder="Exact item ID" mono
              disabled={Boolean(caseFilter)} hint={caseFilter ? 'Set by the case search above.' : undefined} />
            <TextField label="Person ID" value={draft.actorId} onChange={set('actorId')} placeholder="UUID" mono />
            <TextField label="Reference" value={draft.correlationId} onChange={set('correlationId')} placeholder="Correlation ID (UUID)" mono />
            <TextField label="From" type="datetime-local" value={draft.from} onChange={set('from')} invalid={rangeInvalid} />
            <TextField label="To" type="datetime-local" value={draft.to} onChange={set('to')} invalid={rangeInvalid}
              hint={rangeInvalid ? 'The end must be after the start.' : 'An exact range replaces the period above.'} />
          </div>
          <label className="cf-ad-audit-routine">
            <input type="checkbox" checked={draft.includeRoutine} onChange={(e) => set('includeRoutine')(e.target.checked)} />
            <span>Include routine watched-folder checks that found nothing</span>
          </label>
          <div className="cf-ad-card-foot">
            <button type="button" className="cf-btn" data-variant="ghost" onClick={clearAll} disabled={!anyFilter && !dirty}>Clear filters</button>
            <button type="submit" className="cf-btn" data-variant="primary" disabled={rangeInvalid || isValidating}>
              {isValidating && <Spinner label="Loading" />}{dirty ? 'Apply filters' : 'Refresh'}
            </button>
          </div>
        </form>
      </details>

      <p className="cf-ad-audit-count" aria-live="polite">{data ? `${countLine}${routineNote}` : 'Loading…'}</p>

      {error ? (
        <div className="cf-ad-stack">
          <Notice tone="danger">{loadError(error)}</Notice>
          <div><button type="button" className="cf-btn" data-size="sm" onClick={() => mutate()}>Retry</button></div>
        </div>
      ) : !data ? (
        <p className="cf-muted"><Spinner label="Loading audit records" /> Loading audit records…</p>
      ) : records.length === 0 ? (
        <p className="cf-muted">Nothing matches these filters.</p>
      ) : (
        <div className="cf-ad-table-wrap">
          <table className="cf-ad-table cf-ad-table-left cf-ad-audit-table">
            <thead>
              <tr><th scope="col">When</th><th scope="col">What happened</th><th scope="col">Who</th><th scope="col">Case</th><th scope="col">Result</th></tr>
            </thead>
            <tbody>
              {records.map((r) => {
                const expanded = open === r.id;
                const change = changeLine(r);
                const c = caseId(r);
                const result = resultWord(r.outcome);
                return (
                  <Fragment key={r.id}>
                    <tr data-outcome={r.outcome} data-open={expanded || undefined}>
                      <td className="cf-ad-audit-when"><time dateTime={r.occurredAt}>{when(r.occurredAt)}</time></td>
                      <td>
                        <button type="button" className="cf-ad-audit-what" aria-expanded={expanded} aria-controls={`cf-audit-${r.id}`}
                          onClick={() => setOpen(expanded ? null : r.id)}>
                          {whatHappened(r)}
                        </button>
                        {change && <span className="cf-ad-audit-change">{change}</span>}
                      </td>
                      <td>{personName(r.actorId)}</td>
                      <td>{c ? <code className="cf-mono" title={c}>{shortId(c)}</code> : <span className="cf-muted">—</span>}</td>
                      <td><span className="cf-ad-audit-result" data-tone={result.tone}>{result.word}</span></td>
                    </tr>
                    {expanded && (
                      <tr className="cf-ad-audit-detail" id={`cf-audit-${r.id}`}>
                        <td colSpan={5}>
                          <dl className="cf-ad-audit-ids">
                            {recordedFields(r).map(([k, v]) => (
                              <div key={k}><dt>{k}</dt><dd><code className="cf-mono">{v || '—'}</code></dd></div>
                            ))}
                          </dl>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function ExportMenu({ busy, disabled, onPick }: { busy: '' | 'csv' | 'json'; disabled: boolean; onPick: (f: 'csv' | 'json') => void }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false); };
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', close);
    document.addEventListener('keydown', esc);
    return () => { document.removeEventListener('mousedown', close); document.removeEventListener('keydown', esc); };
  }, [open]);
  const pick = (f: 'csv' | 'json') => { setOpen(false); onPick(f); };
  return (
    <div className="cf-ad-audit-export" ref={ref}>
      <button type="button" className="cf-btn" aria-haspopup="menu" aria-expanded={open} disabled={disabled || Boolean(busy)} onClick={() => setOpen((o) => !o)}>
        {busy && <Spinner label="Exporting" />}Export
      </button>
      {open && (
        <div role="menu" aria-label="Export" className="cf-ad-audit-menu">
          <button type="button" role="menuitem" onClick={() => pick('csv')}>CSV<span>for spreadsheets</span></button>
          <button type="button" role="menuitem" onClick={() => pick('json')}>JSON<span>every field, for tools</span></button>
        </div>
      )}
    </div>
  );
}
