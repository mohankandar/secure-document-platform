import { useEffect, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { apiClient } from '../api/client';
import type { SupportIncidentDetail } from '../types';
import Breadcrumbs from '../components/Breadcrumbs';

export default function SupportIncidentDetailPage() {
  const { incidentId = '' } = useParams();
  const [searchParams] = useSearchParams();
  const tenantId = searchParams.get('tenantId') || undefined;
  const [data, setData] = useState<SupportIncidentDetail | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    setError('');
    setData(null);
    void apiClient.getSupportIncident(incidentId, tenantId)
      .then((value) => { if (active) setData(value); })
      .catch((e) => { if (active) setError(e instanceof Error ? e.message : 'Unable to load incident.'); });
    return () => { active = false; };
  }, [incidentId, tenantId]);

  const supportListPath = tenantId ? `/support/incidents?tenantId=${encodeURIComponent(tenantId)}` : '/support/incidents';

  if (error) return <div><Breadcrumbs items={[{ label: 'Support Incidents', to: supportListPath }, { label: 'Incident' }]} /><div className="error-message">{error}</div></div>;
  if (!data) return <div><Breadcrumbs items={[{ label: 'Support Incidents', to: supportListPath }, { label: 'Incident' }]} /><p>Loading incident…</p></div>;

  const businessCompleteTechnicalOpen = data.businessOutcome === 'COMPLETED' && data.status !== 'RESOLVED';

  return (
    <div>
      <Breadcrumbs items={[{ label: 'Support Incidents', to: supportListPath }, { label: 'Incident Details' }]} />
      <div className="page-heading">
        <div>
          <p className="eyebrow">Support Case</p>
          <h1>{data.primaryIdentifierValue || data.incidentId}</h1>
          <p>{data.primaryIdentifierLabel || 'Incident ID'} · {data.incidentId}</p>
        </div>
        {!tenantId && <Link className="button-link secondary" to={`/packages/${data.packageId}`}>Open intake</Link>}
      </div>

      {businessCompleteTechnicalOpen && (
        <div className="support-banner">
          <strong>Business completed using continuity.</strong>
          <span>The technical incident is still unresolved and remains available for investigation.</span>
        </div>
      )}
      {data.businessImpact === 'BLOCKED' && (
        <div className="support-banner blocked">
          <strong>Business processing is currently blocked.</strong>
          <span>Prioritize safe recovery or escalation according to the reason code and retry policy.</span>
        </div>
      )}

      <div className="support-detail-grid">
        <section className="card">
          <h2>Summary</h2>
          <dl className="platform-definition-list">
            <div><dt>Incident status</dt><dd>{data.status.replace(/_/g, ' ')}</dd></div>
            <div><dt>Business outcome</dt><dd>{data.businessOutcome?.replace(/_/g, ' ') || 'Not completed'}</dd></div>
            <div><dt>Business impact</dt><dd>{data.businessImpact}</dd></div>
            <div><dt>Business continuity</dt><dd>{data.businessContinuityUsed ? 'Used' : 'Not used'}</dd></div>
            <div><dt>Tenant</dt><dd className="support-mono">{data.tenantId}</dd></div>
            <div><dt>Package</dt><dd className="support-mono">{data.packageId}</dd></div>
            <div><dt>Package status</dt><dd>{data.packageStatus?.replace(/_/g, ' ') || '—'}</dd></div>
            <div><dt>Document</dt><dd className="support-mono">{data.documentId || '—'}</dd></div>
            <div><dt>First observed</dt><dd>{new Date(data.firstObservedAt).toLocaleString()}</dd></div>
            <div><dt>Last observed</dt><dd>{new Date(data.lastObservedAt).toLocaleString()}</dd></div>
            <div><dt>Resolved</dt><dd>{data.resolvedAt ? new Date(data.resolvedAt).toLocaleString() : 'No'}</dd></div>
          </dl>
        </section>

        <section className="card">
          <h2>Failure details</h2>
          <dl className="platform-definition-list">
            <div><dt>Stage</dt><dd>{data.stage.replace(/_/g, ' ')}</dd></div>
            <div><dt>Component</dt><dd>{data.component}</dd></div>
            <div><dt>Category</dt><dd>{data.failureCategory.replace(/_/g, ' ')}</dd></div>
            <div><dt>Reason code</dt><dd>{data.reasonCode}</dd></div>
            <div><dt>Retryable</dt><dd>{data.retryable ? 'Yes' : 'No'}</dd></div>
            <div><dt>Attempts</dt><dd>{data.attemptCount}</dd></div>
            <div><dt>Correlation ID</dt><dd className="support-mono">{data.correlationId}</dd></div>
            <div><dt>Safe summary</dt><dd>{data.safeSummary}</dd></div>
          </dl>
        </section>
      </div>

      <section className="card">
        <h2>Processing fingerprint</h2>
        {Object.keys(data.processingFingerprint).length === 0 ? <p>No fingerprint fields were captured.</p> : (
          <dl className="platform-definition-list">
            {Object.entries(data.processingFingerprint).map(([key, value]) => <div key={key}><dt>{key}</dt><dd>{value}</dd></div>)}
          </dl>
        )}
      </section>

      <section className="card">
        <h2>Safe diagnostic metadata</h2>
        {Object.keys(data.safeMetadata).length === 0 ? <p>No additional safe diagnostic metadata was captured.</p> : (
          <dl className="platform-definition-list">
            {Object.entries(data.safeMetadata).map(([key, value]) => <div key={key}><dt>{key}</dt><dd>{value}</dd></div>)}
          </dl>
        )}
      </section>

      <section className="card">
        <h2>Incident timeline</h2>
        {data.events.length === 0 ? <p>No durable incident events recorded.</p> : (
          <div className="support-timeline">
            {data.events.map((event, index) => (
              <div className="support-timeline-item" key={`${event.eventType}-${event.occurredAt}-${index}`}>
                <div className="support-timeline-dot" />
                <div>
                  <strong>{event.eventType.replace(/_/g, ' ')}</strong>
                  <div>{new Date(event.occurredAt).toLocaleString()}</div>
                  <div className="support-subtle">Correlation {event.correlationId}</div>
                  {(event.reasonCode || event.scope || event.attemptNumber != null) && (
                    <div className="support-subtle">
                      {[event.reasonCode, event.scope, event.attemptNumber != null ? `attempt ${event.attemptNumber}` : null].filter(Boolean).join(' · ')}
                    </div>
                  )}
                  {Object.keys(event.safeMetadata).length > 0 && (
                    <div className="support-subtle">
                      {Object.entries(event.safeMetadata).map(([key, value]) => `${key}: ${value}`).join(' · ')}
                    </div>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </section>

      <p className="support-privacy-note">
        Support diagnostics intentionally exclude document text, OCR body, protected values, raw prompts and raw provider responses.
      </p>
    </div>
  );
}
