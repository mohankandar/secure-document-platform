import { FormEvent, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { apiClient } from '../api/client';
import { hasCurrentCapability } from '../api/config';
import type { SupportIncidentSearchPage } from '../types';
import Breadcrumbs from '../components/Breadcrumbs';

const EMPTY: SupportIncidentSearchPage = { items: [], total: 0, page: 0, size: 25 };

function optionalBoolean(value: string): boolean | undefined {
  if (value === 'true') return true;
  if (value === 'false') return false;
  return undefined;
}

function optionalInstant(value: string): string | undefined {
  if (!value) return undefined;
  return new Date(value).toISOString();
}

export default function SupportIncidentsPage() {
  const canOverrideTenant = hasCurrentCapability('PLATFORM_OPERATIONS');
  const [searchParams] = useSearchParams();
  const [data, setData] = useState<SupportIncidentSearchPage>(EMPTY);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [businessIdentifier, setBusinessIdentifier] = useState('');
  const [incidentId, setIncidentId] = useState('');
  const [correlationId, setCorrelationId] = useState('');
  const [packageId, setPackageId] = useState('');
  const [documentId, setDocumentId] = useState('');
  const [stage, setStage] = useState('');
  const [reasonCode, setReasonCode] = useState('');
  const [status, setStatus] = useState('');
  const [businessOutcome, setBusinessOutcome] = useState('');
  const [businessContinuityUsed, setBusinessContinuityUsed] = useState('');
  const [retryable, setRetryable] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [tenantId, setTenantId] = useState(canOverrideTenant ? searchParams.get('tenantId') || '' : '');

  const load = async (page = 0) => {
    setLoading(true);
    setError('');
    try {
      if (from && to && new Date(from).getTime() > new Date(to).getTime()) {
        throw new Error('From time must be before or equal to To time.');
      }
      setData(await apiClient.searchSupportIncidents({
        businessIdentifier: businessIdentifier || undefined,
        incidentId: incidentId || undefined,
        correlationId: correlationId || undefined,
        packageId: packageId || undefined,
        documentId: documentId || undefined,
        stage: stage || undefined,
        reasonCode: reasonCode || undefined,
        status: status || undefined,
        businessOutcome: businessOutcome || undefined,
        businessContinuityUsed: optionalBoolean(businessContinuityUsed),
        retryable: optionalBoolean(retryable),
        from: optionalInstant(from),
        to: optionalInstant(to),
        tenantId: canOverrideTenant && tenantId ? tenantId : undefined,
        page,
        size: 25,
      }));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Unable to load support incidents.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(0); }, []); // initial tenant-scoped (or explicitly overridden) view

  const submit = (e: FormEvent) => {
    e.preventDefault();
    void load(0);
  };

  const clear = () => {
    setBusinessIdentifier('');
    setIncidentId('');
    setCorrelationId('');
    setPackageId('');
    setDocumentId('');
    setStage('');
    setReasonCode('');
    setStatus('');
    setBusinessOutcome('');
    setBusinessContinuityUsed('');
    setRetryable('');
    setFrom('');
    setTo('');
    setTenantId('');
    setLoading(true);
    setError('');
    void apiClient.searchSupportIncidents({ page: 0, size: 25 })
      .then(setData)
      .catch((e) => setError(e instanceof Error ? e.message : 'Unable to load support incidents.'))
      .finally(() => setLoading(false));
  };

  const pages = Math.max(1, Math.ceil(data.total / data.size));
  const detailTenantSuffix = canOverrideTenant && tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : '';

  return (
    <div>
      <Breadcrumbs items={[{ label: 'Support Incidents' }]} />
      <div className="page-heading">
        <div>
          <p className="eyebrow">Technical Support</p>
          <h1>Incident Search & Triage</h1>
          <p>Find durable processing incidents without exposing document or provider content.</p>
        </div>
      </div>

      <form className="card support-filter-grid" onSubmit={submit}>
        {canOverrideTenant && (
          <label>Tenant ID override
            <input value={tenantId} onChange={(e) => setTenantId(e.target.value)} placeholder="Tenant UUID" />
          </label>
        )}
        <label>Primary business identifier
          <input value={businessIdentifier} onChange={(e) => setBusinessIdentifier(e.target.value)} placeholder="Enter primary identifier value" />
        </label>
        <label>Incident ID
          <input value={incidentId} onChange={(e) => setIncidentId(e.target.value)} placeholder="UUID" />
        </label>
        <label>Correlation ID
          <input value={correlationId} onChange={(e) => setCorrelationId(e.target.value)} placeholder="UUID" />
        </label>
        <label>Package ID
          <input value={packageId} onChange={(e) => setPackageId(e.target.value)} placeholder="UUID" />
        </label>
        <label>Document ID
          <input value={documentId} onChange={(e) => setDocumentId(e.target.value)} placeholder="UUID" />
        </label>
        <label>Stage
          <input value={stage} onChange={(e) => setStage(e.target.value)} placeholder="AI_ANALYSIS" />
        </label>
        <label>Reason code
          <input value={reasonCode} onChange={(e) => setReasonCode(e.target.value)} placeholder="AI_INVALID_RESPONSE" />
        </label>
        <label>Status
          <select value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">All</option>
            <option value="OPEN">Open</option>
            <option value="RETRY_SCHEDULED">Retry Scheduled</option>
            <option value="BUSINESS_CONTINUED">Business Continued</option>
            <option value="RESOLVED">Resolved</option>
            <option value="ESCALATED">Escalated</option>
          </select>
        </label>
        <label>Business outcome
          <select value={businessOutcome} onChange={(e) => setBusinessOutcome(e.target.value)}>
            <option value="">All</option>
            <option value="IN_PROGRESS">In Progress</option>
            <option value="SUBMITTED">Submitted</option>
            <option value="COMPLETED">Completed</option>
          </select>
        </label>
        <label>Business continuity used
          <select value={businessContinuityUsed} onChange={(e) => setBusinessContinuityUsed(e.target.value)}>
            <option value="">All</option>
            <option value="true">Yes</option>
            <option value="false">No</option>
          </select>
        </label>
        <label>Retryable
          <select value={retryable} onChange={(e) => setRetryable(e.target.value)}>
            <option value="">All</option>
            <option value="true">Yes</option>
            <option value="false">No</option>
          </select>
        </label>
        <label>From
          <input type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label>To
          <input type="datetime-local" value={to} onChange={(e) => setTo(e.target.value)} />
        </label>
        <div className="support-filter-actions">
          <button className="primary-button" type="submit">Search</button>
          <button className="secondary-button" type="button" onClick={clear}>Clear</button>
        </div>
      </form>

      {error && <div className="error-message">{error}</div>}

      <div className="card">
        <div className="support-results-header">
          <div><h2>Incidents</h2><p>{data.total} matching incident{data.total === 1 ? '' : 's'}</p></div>
          <span>Page {data.page + 1} of {pages}</span>
        </div>
        {loading ? <p>Loading incidents…</p> : data.items.length === 0 ? (
          <div className="platform-empty">No incidents match the current tenant-scoped search.</div>
        ) : (
          <div className="table-scroll">
            <table className="support-table">
              <thead><tr>
                <th>Business</th><th>Impact</th><th>Stage / Reason</th><th>Status</th><th>Last observed</th><th></th>
              </tr></thead>
              <tbody>
                {data.items.map((item) => (
                  <tr key={item.incidentId}>
                    <td>
                      <strong>{item.primaryIdentifierValue || '—'}</strong>
                      <div className="support-subtle">{item.primaryIdentifierLabel || 'Business identifier'}</div>
                    </td>
                    <td><span className={`support-impact impact-${item.businessImpact.toLowerCase()}`}>{item.businessImpact.replace(/_/g, ' ')}</span></td>
                    <td><strong>{item.stage.replace(/_/g, ' ')}</strong><div className="support-subtle">{item.reasonCode}</div></td>
                    <td><strong>{item.status.replace(/_/g, ' ')}</strong><div className="support-subtle">{item.businessOutcome?.replace(/_/g, ' ') || 'No business outcome'}</div></td>
                    <td>{new Date(item.lastObservedAt).toLocaleString()}</td>
                    <td><Link to={`/support/incidents/${item.incidentId}${detailTenantSuffix}`}>Open case</Link></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="support-pagination">
          <button className="secondary-button" disabled={data.page <= 0 || loading} onClick={() => void load(data.page - 1)}>Previous</button>
          <button className="secondary-button" disabled={data.page + 1 >= pages || loading} onClick={() => void load(data.page + 1)}>Next</button>
        </div>
      </div>
    </div>
  );
}
