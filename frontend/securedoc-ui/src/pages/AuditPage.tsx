import { useState } from 'react';
import { apiClient } from '../api/client';
import type { AuditRecordResult, ApiError } from '../types';
import Breadcrumbs from '../components/Breadcrumbs';
import PageHeader from '../components/ui/PageHeader';
import StatusBadge from '../components/ui/StatusBadge';

export default function AuditPage() {
  const [records, setRecords] = useState<AuditRecordResult[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showRoutineActivity, setShowRoutineActivity] = useState(false);

  // Filters
  const [action, setAction] = useState('');
  const [outcome, setOutcome] = useState('');
  const [resourceType, setResourceType] = useState('');
  const [correlationId, setCorrelationId] = useState('');

  const loadRecords = async () => {
    setLoading(true);
    setError(null);
    try {
      const filters: Record<string, string> = {};
      if (action) filters.action = action;
      if (outcome) filters.outcome = outcome;
      if (resourceType) filters.resourceType = resourceType;
      if (correlationId) filters.correlationId = correlationId;
      const data = await apiClient.listAuditRecords(filters);
      setRecords(data);
    } catch (err) {
      const apiErr = err as ApiError;
      if (apiErr.errorCode === 'ACCESS_DENIED') {
        setError('Access denied: Platform Admin role required to view audit records.');
      } else {
        setError(apiErr.message || 'Failed to load audit records');
      }
      setRecords([]);
    } finally {
      setLoading(false);
    }
  };

  const isRoutineNoOpScan = (record: AuditRecordResult) => (
    record.action === 'WATCHED_FOLDER_SCAN_TRIGGERED'
    && /mode\s*=\s*SCHEDULED/i.test(record.detail || '')
    && /discovered\s*=\s*0(?:\D|$)/i.test(record.detail || '')
  );

  const routineHiddenCount = records.filter(isRoutineNoOpScan).length;
  const routineExplicitlyRequested = action === 'WATCHED_FOLDER_SCAN_TRIGGERED';
  const visibleRecords = showRoutineActivity || routineExplicitlyRequested ? records : records.filter((record) => !isRoutineNoOpScan(record));

  return (
    <div className="audit-page">
      <Breadcrumbs items={[{ label: 'Administration' }, { label: 'Audit' }]} />
      <PageHeader
        eyebrow="Governance"
        title="Audit Timeline"
        description="Search security and business audit events using safe identifiers and correlation metadata."
        actions={<button className="ui-button primary" onClick={() => void loadRecords()} disabled={loading}>{loading ? 'Loading…' : 'Load records'}</button>}
      />

      {error && <div className="error-msg">{error}</div>}

      <section className="card audit-filter-panel">
        <div className="audit-filter-heading">
          <div><h2>Filters</h2><p>Narrow the timeline without exposing protected document content.</p></div>
          <label className="audit-routine-toggle"><input type="checkbox" checked={showRoutineActivity} onChange={(e) => setShowRoutineActivity(e.target.checked)} /><span>Show routine activity</span></label>
        </div>
        <div className="audit-filter-grid">
          <label className="ui-field">Action
            <select value={action} onChange={(e) => setAction(e.target.value)}>
              <option value="">All actions</option>
              <option value="PACKAGE_CREATED">PACKAGE_CREATED</option>
              <option value="PACKAGE_SUBMITTED">PACKAGE_SUBMITTED</option>
              <option value="PACKAGE_PROCESSING_STARTED">PACKAGE_PROCESSING_STARTED</option>
              <option value="PACKAGE_PROCESSING_COMPLETED">PACKAGE_PROCESSING_COMPLETED</option>
              <option value="PACKAGE_APPROVED">PACKAGE_APPROVED</option>
              <option value="DOCUMENT_UPLOADED">DOCUMENT_UPLOADED</option>
              <option value="DOCUMENT_EXTRACTION_STARTED">DOCUMENT_EXTRACTION_STARTED</option>
              <option value="DOCUMENT_EXTRACTION_COMPLETED">DOCUMENT_EXTRACTION_COMPLETED</option>
              <option value="DOCUMENT_DETECTION_COMPLETED">DOCUMENT_DETECTION_COMPLETED</option>
              <option value="DOCUMENT_REDACTION_COMPLETED">DOCUMENT_REDACTION_COMPLETED</option>
              <option value="DOCUMENT_PROCESSING_FAILED">DOCUMENT_PROCESSING_FAILED</option>
              <option value="FINDING_DETECTED">FINDING_DETECTED</option>
              <option value="FINDING_CONFIRMED">FINDING_CONFIRMED</option>
              <option value="FINDING_DISMISSED">FINDING_DISMISSED</option>
              <option value="FINDING_REDACTED">FINDING_REDACTED</option>
              <option value="REVIEW_TASK_CREATED">REVIEW_TASK_CREATED</option>
              <option value="REVIEW_APPROVED">REVIEW_APPROVED</option>
              <option value="VAULT_ENTRY_REGISTERED">VAULT_ENTRY_REGISTERED</option>
              <option value="DOCUMENT_DOWNLOADED">DOCUMENT_DOWNLOADED</option>
              <option value="WATCHED_FOLDER_SCAN_TRIGGERED">WATCHED_FOLDER_SCAN_TRIGGERED</option>
              <option value="ACCESS_REQUESTED">ACCESS_REQUESTED</option>
              <option value="ACCESS_APPROVED">ACCESS_APPROVED</option>
              <option value="ACCESS_DENIED">ACCESS_DENIED</option>
            </select>
          </label>
          <label className="ui-field">Outcome
            <select value={outcome} onChange={(e) => setOutcome(e.target.value)}>
              <option value="">All outcomes</option>
              <option value="SUCCESS">SUCCESS</option>
              <option value="FAILURE">FAILURE</option>
              <option value="DENIED">DENIED</option>
            </select>
          </label>
          <label className="ui-field">Resource type
            <select value={resourceType} onChange={(e) => setResourceType(e.target.value)}>
              <option value="">All resources</option>
              <option value="DocumentPackage">DocumentPackage</option>
              <option value="Document">Document</option>
              <option value="Finding">Finding</option>
              <option value="ReviewTask">ReviewTask</option>
              <option value="Artifact">Artifact</option>
              <option value="VaultEntry">VaultEntry</option>
              <option value="AccessRequest">AccessRequest</option>
              <option value="WatchedFolder">WatchedFolder</option>
            </select>
          </label>
          <label className="ui-field">Correlation ID
            <input value={correlationId} onChange={(e) => setCorrelationId(e.target.value)} placeholder="UUID" />
          </label>
        </div>
      </section>

      {records.length > 0 ? (
        <section className="audit-results-section">
          <div className="audit-results-heading">
            <div>
              <h2>Audit events</h2>
              <p>{visibleRecords.length} shown{routineHiddenCount > 0 && !showRoutineActivity && !routineExplicitlyRequested ? ` · ${routineHiddenCount} routine scheduled scan${routineHiddenCount === 1 ? '' : 's'} hidden` : ''}</p>
            </div>
          </div>
          <div className="audit-record-list">
            {visibleRecords.map((record) => (
              <article className="audit-record-card" key={record.id}>
                <div className="audit-record-top">
                  <div><strong>{record.action.replace(/_/g, ' ')}</strong><span>{new Date(record.occurredAt).toLocaleString()}</span></div>
                  <StatusBadge value={record.outcome} />
                </div>
                <div className="audit-record-grid">
                  <div><span>Resource</span><strong>{record.resourceType}</strong><code>{record.resourceId}</code></div>
                  <div><span>Actor</span><code>{record.actorId}</code></div>
                  <div><span>Correlation</span><code>{record.correlationId}</code></div>
                </div>
                {record.detail && <div className="audit-record-detail">{record.detail}</div>}
              </article>
            ))}
            {visibleRecords.length === 0 && <div className="card ui-empty-state">Only routine no-op scheduled scans matched. Enable “Show routine activity” to inspect them.</div>}
          </div>
        </section>
      ) : !loading && !error ? (
        <div className="card ui-empty-state">Choose filters if needed, then load audit records.</div>
      ) : null}
    </div>
  );
}
