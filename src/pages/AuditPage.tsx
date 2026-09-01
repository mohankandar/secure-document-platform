import { useState } from 'react';
import { apiClient } from '../api/client';
import type { AuditRecordResult, ApiError } from '../types';
import Breadcrumbs from '../components/Breadcrumbs';

export default function AuditPage() {
  const [records, setRecords] = useState<AuditRecordResult[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

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

  return (
    <div>
      <Breadcrumbs items={[{ label: 'Administration' }, { label: 'Audit' }]} />
      <h1>Audit Timeline</h1>

      {error && <div className="error-msg">{error}</div>}

      <div className="card">
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr 1fr', gap: '0.5rem', marginBottom: '0.75rem' }}>
          <div className="form-group" style={{ margin: 0 }}>
            <label>Action</label>
            <select value={action} onChange={(e) => setAction(e.target.value)}>
              <option value="">All</option>
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
              <option value="ACCESS_REQUESTED">ACCESS_REQUESTED</option>
              <option value="ACCESS_APPROVED">ACCESS_APPROVED</option>
              <option value="ACCESS_DENIED">ACCESS_DENIED</option>
            </select>
          </div>
          <div className="form-group" style={{ margin: 0 }}>
            <label>Outcome</label>
            <select value={outcome} onChange={(e) => setOutcome(e.target.value)}>
              <option value="">All</option>
              <option value="SUCCESS">SUCCESS</option>
              <option value="FAILURE">FAILURE</option>
              <option value="DENIED">DENIED</option>
            </select>
          </div>
          <div className="form-group" style={{ margin: 0 }}>
            <label>Resource Type</label>
            <select value={resourceType} onChange={(e) => setResourceType(e.target.value)}>
              <option value="">All</option>
              <option value="DocumentPackage">DocumentPackage</option>
              <option value="Document">Document</option>
              <option value="Finding">Finding</option>
              <option value="ReviewTask">ReviewTask</option>
              <option value="Artifact">Artifact</option>
              <option value="VaultEntry">VaultEntry</option>
              <option value="AccessRequest">AccessRequest</option>
            </select>
          </div>
          <div className="form-group" style={{ margin: 0 }}>
            <label>Correlation ID</label>
            <input value={correlationId} onChange={(e) => setCorrelationId(e.target.value)} placeholder="UUID" />
          </div>
        </div>
        <button className="primary" onClick={loadRecords} disabled={loading}>
          {loading ? 'Loading...' : 'Load Audit Records'}
        </button>
      </div>

      {records.length > 0 && (
        <div className="card">
          <table>
            <thead>
              <tr>
                <th>Time</th>
                <th>Action</th>
                <th>Outcome</th>
                <th>Resource</th>
                <th>Actor</th>
                <th>Correlation</th>
                <th>Detail</th>
              </tr>
            </thead>
            <tbody>
              {records.map((r) => (
                <tr key={r.id}>
                  <td style={{ whiteSpace: 'nowrap', fontSize: '0.75rem' }}>
                    {new Date(r.occurredAt).toLocaleString()}
                  </td>
                  <td><span className="badge badge-created" style={{ fontSize: '0.7rem' }}>{r.action}</span></td>
                  <td>
                    <span className={`badge ${r.outcome === 'SUCCESS' ? 'badge-approved' : r.outcome === 'DENIED' ? 'badge-rejected' : 'badge-failed'}`}>
                      {r.outcome}
                    </span>
                  </td>
                  <td style={{ fontSize: '0.75rem' }}>
                    {r.resourceType}: {r.resourceId.length > 8 ? r.resourceId.substring(0, 8) + '...' : r.resourceId}
                  </td>
                  <td style={{ fontSize: '0.75rem' }}>{r.actorId.substring(0, 8)}...</td>
                  <td style={{ fontSize: '0.75rem' }}>{r.correlationId.substring(0, 8)}...</td>
                  <td style={{ fontSize: '0.75rem', maxWidth: '200px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {r.detail || '-'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {!loading && records.length === 0 && !error && (
        <div className="card">
          <p>Click "Load Audit Records" to view the audit timeline. Requires Platform Admin role.</p>
        </div>
      )}
    </div>
  );
}
