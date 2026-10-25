import { FormEvent, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { apiClient } from '../api/client';
import { hasCurrentCapability } from '../api/config';
import type { SupportIncidentSearchPage } from '../types';
import PageScaffold from '../components/ui/PageScaffold';
import StatusBadge from '../components/ui/StatusBadge';

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
  const [advancedOpen, setAdvancedOpen] = useState(false);

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
    setAdvancedOpen(false);
    setLoading(true);
    setError('');
    void apiClient.searchSupportIncidents({ page: 0, size: 25 })
      .then(setData)
      .catch((e) => setError(e instanceof Error ? e.message : 'Unable to load support incidents.'))
      .finally(() => setLoading(false));
  };

  const pages = Math.max(1, Math.ceil(data.total / data.size));
  const detailTenantSuffix = canOverrideTenant && tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : '';
  const advancedFilterCount = [
    canOverrideTenant ? tenantId : '', correlationId, packageId, documentId, stage, reasonCode,
    businessOutcome, businessContinuityUsed, retryable, from, to,
  ].filter(Boolean).length;

  return (
    <PageScaffold className="support-incidents-page" breadcrumbs={[{ label: 'Support Incidents' }]} title="Incident Search & Triage" eyebrow="Technical operations" description="Search, filter, and investigate processing incidents without crossing tenant or authorization boundaries.">

      <form className="ui-card support-filter-panel" onSubmit={submit}>
        <div className="support-primary-filters">
          <label>Primary business identifier
            <input value={businessIdentifier} onChange={(e) => setBusinessIdentifier(e.target.value)} placeholder="Contract, member or configured primary ID" />
          </label>
          <label>Incident ID
            <input value={incidentId} onChange={(e) => setIncidentId(e.target.value)} placeholder="UUID" />
          </label>
          <label>Status
            <select value={status} onChange={(e) => setStatus(e.target.value)}>
              <option value="">All statuses</option>
              <option value="OPEN">Open</option>
              <option value="RETRY_SCHEDULED">Retry Scheduled</option>
              <option value="INVESTIGATING">Investigating</option>
              <option value="BUSINESS_CONTINUED">Business Continued</option>
              <option value="RESOLVED">Resolved</option>
              <option value="AUTO_CLOSED">Auto Closed</option>
              <option value="ESCALATED">Escalated</option>
            </select>
          </label>
          <div className="support-filter-actions">
            <button className="ui-button primary" type="submit">Search</button>
            <button className="ui-button secondary" type="button" onClick={() => setAdvancedOpen((current) => !current)}>
              {advancedOpen ? 'Hide advanced' : `Advanced filters${advancedFilterCount ? ` (${advancedFilterCount})` : ''}`}
            </button>
            <button className="ui-button secondary" type="button" onClick={clear}>Clear</button>
          </div>
        </div>

        {advancedOpen && <div className="support-advanced-filters">
          {canOverrideTenant && (
            <label>Tenant ID override
              <input value={tenantId} onChange={(e) => setTenantId(e.target.value)} placeholder="Tenant UUID" />
            </label>
          )}
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
        </div>}
      </form>

      {error && <div className="ui-alert tone-danger">{error}</div>}

      <div className="ui-card">
        <div className="support-results-header">
          <div><h2>Incidents</h2><p>{data.total} matching incident{data.total === 1 ? '' : 's'}</p></div>
          <span>Page {data.page + 1} of {pages}</span>
        </div>
        {loading ? <p>Loading incidents…</p> : data.items.length === 0 ? (
          <div className="ui-empty-state">No incidents match the current tenant-scoped search.</div>
        ) : (
          <div className="table-scroll">
            <table className="support-table">
              <thead><tr>
                <th>Business</th><th>Impact</th><th>Stage / Reason</th><th>Status</th><th>Last observed</th><th></th>
              </tr></thead>
              <tbody>
                {data.items.map((item) => (
                  <tr key={item.incidentId}>
                    <td data-label="Business">
                      <strong>{item.primaryIdentifierValue || '—'}</strong>
                      <div className="support-subtle">{item.primaryIdentifierLabel || 'Business identifier'}</div>
                    </td>
                    <td data-label="Impact"><StatusBadge value={item.businessImpact} /></td>
                    <td data-label="Stage / Reason"><strong>{item.stage.replace(/_/g, ' ')}</strong><div className="support-subtle">{item.reasonCode}</div></td>
                    <td data-label="Status"><strong>{item.status.replace(/_/g, ' ')}</strong><div className="support-subtle">{item.businessOutcome?.replace(/_/g, ' ') || 'No business outcome'}</div></td>
                    <td data-label="Last observed">{new Date(item.lastObservedAt).toLocaleString()}</td>
                    <td data-label="Action"><Link className="ui-button secondary small" to={`/support/incidents/${item.incidentId}${detailTenantSuffix}`}>Open case</Link></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="support-pagination">
          <button className="ui-button secondary" disabled={data.page <= 0 || loading} onClick={() => void load(data.page - 1)}>Previous</button>
          <button className="ui-button secondary" disabled={data.page + 1 >= pages || loading} onClick={() => void load(data.page + 1)}>Next</button>
        </div>
      </div>
    </PageScaffold>
  );
}
