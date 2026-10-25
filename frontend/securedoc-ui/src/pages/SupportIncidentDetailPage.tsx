import { useEffect, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { apiClient } from '../api/client';
import type { SupportIncidentDetail } from '../types';
import PageScaffold from '../components/ui/PageScaffold';
import StatusBadge from '../components/ui/StatusBadge';
import { hasCurrentCapability } from '../api/config';

export default function SupportIncidentDetailPage() {
  const { incidentId = '' } = useParams();
  const [searchParams] = useSearchParams();
  const tenantId = searchParams.get('tenantId') || undefined;
  const [data, setData] = useState<SupportIncidentDetail | null>(null);
  const [error, setError] = useState('');
  const [actionBusy, setActionBusy] = useState(false);
  const [actionMessage, setActionMessage] = useState('');

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

  const runAction = async (action: string, holdDays?: number, resolutionCode?: string) => {
    if (!incidentId) return;
    setActionBusy(true);
    setError('');
    setActionMessage('');
    try {
      await apiClient.actOnSupportIncident(incidentId, { action, holdDays, resolutionCode }, tenantId);
      const refreshed = await apiClient.getSupportIncident(incidentId, tenantId);
      setData(refreshed);
      setActionMessage(`Action accepted: ${action.replace(/_/g, ' ')}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Unable to update support incident.');
    } finally {
      setActionBusy(false);
    }
  };

  if (error) return <PageScaffold breadcrumbs={[{ label: 'Support Incidents', to: supportListPath }, { label: 'Incident' }]} title="Incident Details"><div className="ui-alert tone-danger">{error}</div></PageScaffold>;
  if (!data) return <PageScaffold breadcrumbs={[{ label: 'Support Incidents', to: supportListPath }, { label: 'Incident' }]} title="Incident Details"><div className="ui-loading-state">Loading incident…</div></PageScaffold>;

  const businessCompleteTechnicalOpen = data.businessOutcome === 'COMPLETED' && data.status !== 'RESOLVED';

  return (
    <PageScaffold breadcrumbs={[{ label: 'Support Incidents', to: supportListPath }, { label: 'Incident Details' }]}
      eyebrow="Support case"
      title={data.primaryIdentifierValue || data.incidentId}
      description={`${data.primaryIdentifierLabel || 'Incident ID'} · ${data.incidentId}`}
      actions={!tenantId ? <Link className="ui-button secondary" to={`/packages/${data.packageId}`}>Open intake</Link> : undefined}>

      <div className="support-case-summary-strip">
        <div><span>Incident</span><StatusBadge value={data.status} /></div>
        <div><span>Business impact</span><StatusBadge value={data.businessImpact === 'BLOCKED' ? 'BLOCKED' : data.businessImpact} label={data.businessImpact.replace(/_/g, ' ')} /></div>
        <div><span>Business outcome</span><strong>{data.businessOutcome?.replace(/_/g, ' ') || 'In progress'}</strong></div>
        <div><span>Last observed</span><strong>{new Date(data.lastObservedAt).toLocaleString()}</strong></div>
      </div>

      {businessCompleteTechnicalOpen && (
        <div className="ui-alert tone-warning support-banner-layout">
          <strong>Business completed using continuity.</strong>
          <span>The technical incident is still unresolved and remains available for investigation.</span>
        </div>
      )}
      {data.businessImpact === 'BLOCKED' && (
        <div className="ui-alert tone-danger support-banner-layout">
          <strong>Business processing is currently blocked.</strong>
          <span>Prioritize safe recovery or escalation according to the reason code and retry policy.</span>
        </div>
      )}
      {data.artifactAvailability === 'PURGED' && (
        <div className="ui-alert tone-warning support-banner-layout">
          <strong>Processing artifacts are no longer available.</strong>
          <span>The retention lifecycle purged temporary processing content; the package outcome, incident timeline, audit and reporting history remain available.</span>
        </div>
      )}

      <div className="support-detail-grid">
        <section className="ui-card">
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
            <div><dt>Processing artifacts</dt><dd>{data.artifactAvailability?.replace(/_/g, ' ') || 'Unknown'}</dd></div>
            <div><dt>Retained until</dt><dd>{data.technicalAttentionRetainedUntil ? new Date(data.technicalAttentionRetainedUntil).toLocaleString() : '—'}</dd></div>
            <div><dt>Artifacts purged</dt><dd>{data.artifactsPurgedAt ? new Date(data.artifactsPurgedAt).toLocaleString() : 'No'}</dd></div>
          </dl>
        </section>

        <section className="ui-card">
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

      {hasCurrentCapability('TECHNICAL_HOLD_MANAGE') && data.packageStatus === 'FAILED' && !['RESOLVED', 'AUTO_CLOSED'].includes(data.status) && (
        <section className="ui-card">
          <h2>Exceptional IT actions</h2>
          <p>Normal business continuity is automatic. Use these controls only when Tenant IT is investigating an exceptional failure.</p>
          {actionMessage && <div className="success-message">{actionMessage}</div>}
          <div className="integration-card-actions">
            {hasCurrentCapability('TECHNICAL_RETRY') && (
              <button className="ui-button primary" disabled={actionBusy} onClick={() => void runAction('REPROCESS')}>Reprocess after fix</button>
            )}
            <button className="ui-button secondary" disabled={actionBusy} onClick={() => void runAction('PRESERVE_FOR_INVESTIGATION', 7)}>Preserve 7 days</button>
            <button className="ui-button secondary" disabled={actionBusy} onClick={() => void runAction('PRESERVE_FOR_INVESTIGATION', 14)}>Preserve 14 days</button>
            <button className="ui-button secondary" disabled={actionBusy} onClick={() => void runAction('PRESERVE_FOR_INVESTIGATION', 30)}>Preserve 30 days</button>
            <button className="ui-button secondary" disabled={actionBusy} onClick={() => void runAction('RELEASE_HOLD')}>Release hold</button>
            <button className="ui-button secondary" disabled={actionBusy} onClick={() => void runAction('RESOLVE_NO_REPROCESS')}>Resolve — no reprocess</button>
            <button className="ui-button secondary" disabled={actionBusy} onClick={() => void runAction('SUPERSEDED')}>Mark superseded</button>
            <button className="ui-button secondary" disabled={actionBusy} onClick={() => void runAction('DUPLICATE')}>Mark duplicate</button>
          </div>
          <p className="support-subtle">Actions preserve the failed package outcome, incident timeline, audit and reporting history. Retention controls processing-artifact availability only.</p>
        </section>
      )}

      <section className="ui-card">
        <h2>Processing fingerprint</h2>
        {Object.keys(data.processingFingerprint).length === 0 ? <p>No fingerprint fields were captured.</p> : (
          <dl className="platform-definition-list">
            {Object.entries(data.processingFingerprint).map(([key, value]) => <div key={key}><dt>{key}</dt><dd>{value}</dd></div>)}
          </dl>
        )}
      </section>

      <section className="ui-card">
        <h2>Safe diagnostic metadata</h2>
        {Object.keys(data.safeMetadata).length === 0 ? <p>No additional safe diagnostic metadata was captured.</p> : (
          <dl className="platform-definition-list">
            {Object.entries(data.safeMetadata).map(([key, value]) => <div key={key}><dt>{key}</dt><dd>{value}</dd></div>)}
          </dl>
        )}
      </section>

      <section className="ui-card">
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
    </PageScaffold>
  );
}
