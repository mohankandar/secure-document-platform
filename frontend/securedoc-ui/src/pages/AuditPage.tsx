import { useState } from 'react';
import { apiClient } from '../api/client';
import type { AuditRecordResult, ApiError } from '../types';
import PageScaffold from '../components/ui/PageScaffold';
import StatusBadge from '../components/ui/StatusBadge';

type AuditCategory = 'MEANINGFUL' | 'SECURITY' | 'ADMINISTRATION' | 'DOCUMENTS' | 'EXPORTS' | 'FAILURES_DENIED' | 'SYSTEM';

const categories: Array<[AuditCategory, string]> = [
  ['MEANINGFUL', 'All meaningful activity'],
  ['SECURITY', 'Security & Authentication'],
  ['ADMINISTRATION', 'Administration'],
  ['DOCUMENTS', 'Documents'],
  ['EXPORTS', 'Downloads & Exports'],
  ['FAILURES_DENIED', 'Failures / Denied'],
  ['SYSTEM', 'System actions'],
];

export default function AuditPage() {
  const [records, setRecords] = useState<AuditRecordResult[]>([]);
  const [loading, setLoading] = useState(false);
  const [exporting, setExporting] = useState<'' | 'csv' | 'json'>('');
  const [error, setError] = useState<string | null>(null);
  const [showRoutineActivity, setShowRoutineActivity] = useState(false);

  const [category, setCategory] = useState<AuditCategory>('MEANINGFUL');
  const [action, setAction] = useState('');
  const [outcome, setOutcome] = useState('');
  const [resourceType, setResourceType] = useState('');
  const [resourceId, setResourceId] = useState('');
  const [actorId, setActorId] = useState('');
  const [correlationId, setCorrelationId] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');

  const buildFilters = (includeRoutineOverride = showRoutineActivity) => {
    const filters: Record<string, string> = { category };
    if (action) filters.action = action;
    if (outcome) filters.outcome = outcome;
    if (resourceType) filters.resourceType = resourceType;
    if (resourceId) filters.resourceId = resourceId;
    if (actorId) filters.actorId = actorId;
    if (correlationId) filters.correlationId = correlationId;
    if (from) filters.from = new Date(from).toISOString();
    if (to) filters.to = new Date(to).toISOString();
    if (includeRoutineOverride) filters.includeRoutineActivity = 'true';
    return filters;
  };

  const loadRecords = async (includeRoutineOverride = showRoutineActivity) => {
    setLoading(true);
    setError(null);
    try {
      setRecords(await apiClient.listAuditRecords(buildFilters(includeRoutineOverride)));
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.errorCode === 'ACCESS_DENIED'
        ? 'Access denied: your current role cannot view audit records.'
        : apiErr.message || 'Failed to load audit records');
      setRecords([]);
    } finally { setLoading(false); }
  };

  const exportRecords = async (format: 'csv' | 'json') => {
    setExporting(format); setError(null);
    try {
      const blob = await apiClient.exportAuditRecords(buildFilters(), format);
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url; link.download = `lexavault-audit.${format}`;
      document.body.appendChild(link); link.click(); link.remove(); URL.revokeObjectURL(url);
    } catch (err) {
      setError((err as ApiError).message || 'Audit export failed');
    } finally { setExporting(''); }
  };

  const chooseCategory = (next: AuditCategory) => {
    setCategory(next);
    if (next === 'FAILURES_DENIED') setOutcome('');
  };

  return (
    <PageScaffold className="audit-page" breadcrumbs={[{ label: 'Administration' }, { label: 'Audit' }]} title="Audit Timeline"
      eyebrow="Governance"
      description="Review meaningful tenant-scoped activity with precise filters and immutable event detail."
      actions={<button className="ui-button primary" onClick={() => void loadRecords()} disabled={loading}>{loading ? 'Loading…' : 'Load records'}</button>}>

      {error && <div className="ui-alert tone-danger">{error}</div>}

      <section className="ui-card audit-filter-panel">
        <div className="audit-filter-heading">
          <div><h2>Investigation filters</h2><p>Start with a meaningful category, then narrow with exact identifiers when needed.</p></div>
          <label className="audit-routine-toggle"><input type="checkbox" checked={showRoutineActivity} onChange={(e) => { const checked = e.target.checked; setShowRoutineActivity(checked); void loadRecords(checked); }} /><span>Show historical routine activity</span></label>
        </div>

        <div className="audit-category-presets" aria-label="Audit categories">
          {categories.map(([key, label]) => <button type="button" key={key} className={category === key ? 'active' : ''} onClick={() => chooseCategory(key)}>{label}</button>)}
        </div>

        <details className="audit-advanced" open>
          <summary>Advanced filters</summary>
          <div className="audit-filter-grid">
            <label className="ui-field">Action
              <select value={action} onChange={(e) => setAction(e.target.value)}>
                <option value="">All actions in category</option>
                <option value="ENTERPRISE_IDENTITY_DRAFT_SAVED">ENTERPRISE_IDENTITY_DRAFT_SAVED</option>
                <option value="ENTERPRISE_IDENTITY_CONNECTION_TESTED">ENTERPRISE_IDENTITY_CONNECTION_TESTED</option>
                <option value="ENTERPRISE_IDENTITY_CONNECTION_ACTIVATED">ENTERPRISE_IDENTITY_CONNECTION_ACTIVATED</option>
                <option value="ENTERPRISE_IDENTITY_CONNECTION_ROLLED_BACK">ENTERPRISE_IDENTITY_CONNECTION_ROLLED_BACK</option>
                <option value="INTEGRATION_CONNECTION_CREATED">INTEGRATION_CONNECTION_CREATED</option>
                <option value="INTEGRATION_CONNECTION_UPDATED">INTEGRATION_CONNECTION_UPDATED</option>
                <option value="PROCESSING_STORAGE_PROVIDER_ACTIVATED">PROCESSING_STORAGE_PROVIDER_ACTIVATED</option>
                <option value="BRANDING_CONFIGURATION_CHANGED">BRANDING_CONFIGURATION_CHANGED</option>
                <option value="PACKAGE_CREATED">PACKAGE_CREATED</option>
                <option value="PACKAGE_APPROVED">PACKAGE_APPROVED</option>
                <option value="DOCUMENT_UPLOADED">DOCUMENT_UPLOADED</option>
                <option value="DOCUMENT_REDACTION_COMPLETED">DOCUMENT_REDACTION_COMPLETED</option>
                <option value="DOCUMENT_PROCESSING_FAILED">DOCUMENT_PROCESSING_FAILED</option>
                <option value="DOCUMENT_DOWNLOADED">DOCUMENT_DOWNLOADED</option>
                <option value="REPORT_CSV_EXPORTED">REPORT_CSV_EXPORTED</option>
                <option value="AUDIT_EXPORTED">AUDIT_EXPORTED</option>
                <option value="AUTHORIZATION_FAILED">AUTHORIZATION_FAILED</option>
                <option value="WATCHED_FOLDER_SCAN_TRIGGERED">WATCHED_FOLDER_SCAN_TRIGGERED</option>
              </select>
            </label>
            <label className="ui-field">Outcome
              <select value={outcome} onChange={(e) => setOutcome(e.target.value)}>
                <option value="">All outcomes</option><option value="SUCCESS">SUCCESS</option><option value="FAILURE">FAILURE</option><option value="DENIED">DENIED</option>
              </select>
            </label>
            <label className="ui-field">Resource type<input value={resourceType} onChange={(e) => setResourceType(e.target.value)} placeholder="e.g. EnterpriseIdentityConnection" /></label>
            <label className="ui-field">Resource ID<input value={resourceId} onChange={(e) => setResourceId(e.target.value)} placeholder="Exact resource identifier" /></label>
            <label className="ui-field">Actor ID<input value={actorId} onChange={(e) => setActorId(e.target.value)} placeholder="UUID" /></label>
            <label className="ui-field">Correlation ID<input value={correlationId} onChange={(e) => setCorrelationId(e.target.value)} placeholder="UUID" /></label>
            <label className="ui-field">From<input type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} /></label>
            <label className="ui-field">To<input type="datetime-local" value={to} onChange={(e) => setTo(e.target.value)} /></label>
          </div>
        </details>
      </section>

      <section className="audit-results-section">
        <div className="audit-results-heading">
          <div><h2>Audit events</h2><p>{records.length} shown{!showRoutineActivity && action !== 'WATCHED_FOLDER_SCAN_TRIGGERED' ? ' · routine no-op scans excluded' : ''}</p></div>
          <div className="audit-export-actions">
            <button className="ui-button secondary" onClick={() => void exportRecords('csv')} disabled={Boolean(exporting)}>{exporting === 'csv' ? 'Exporting…' : 'Export CSV'}</button>
            <button className="ui-button secondary" onClick={() => void exportRecords('json')} disabled={Boolean(exporting)}>{exporting === 'json' ? 'Exporting…' : 'Export JSON'}</button>
          </div>
        </div>
        {records.length > 0 ? <div className="audit-record-list">
          {records.map((record) => (
            <article className="ui-card audit-record-layout" key={record.id}>
              <div className="audit-record-top"><div><strong>{record.action.replace(/_/g, ' ')}</strong><span>{new Date(record.occurredAt).toLocaleString()}</span></div><StatusBadge value={record.outcome} /></div>
              <div className="audit-record-grid">
                <div><span>Resource</span><strong>{record.resourceType}</strong><code>{record.resourceId}</code></div>
                <div><span>Actor</span><code>{record.actorId}</code></div>
                <div><span>Correlation</span><code>{record.correlationId}</code></div>
              </div>
              {record.detail && <div className="audit-record-detail"><strong>Investigation detail</strong><span>{record.detail}</span></div>}
            </article>
          ))}
        </div> : !loading && !error ? <div className="ui-card ui-empty-state">Choose filters if needed, then load audit records.</div> : null}
      </section>
    </PageScaffold>
  );
}
