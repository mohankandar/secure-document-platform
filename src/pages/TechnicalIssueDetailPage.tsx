import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { apiClient } from '../api/client';
import { hasCurrentCapability } from '../api/config';
import type { ApiError, PackageDetail, TechnicalExceptionDetail } from '../types';
import Breadcrumbs, { intakeBreadcrumbLabel } from '../components/Breadcrumbs';
import PageHeader from '../components/ui/PageHeader';
import StatusBadge from '../components/ui/StatusBadge';
import InfoGrid from '../components/ui/InfoGrid';

function friendlyStage(stage: string) {
  return stage.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase());
}

export default function TechnicalIssueDetailPage() {
  const { packageId } = useParams<{ packageId: string }>();
  const [detail, setDetail] = useState<TechnicalExceptionDetail | null>(null);
  const [pkg, setPkg] = useState<PackageDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [retrying, setRetrying] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = async () => {
    if (!packageId) return;
    setLoading(true);
    setError(null);
    try {
      const [issue, packageDetail] = await Promise.all([
        apiClient.getTechnicalException(packageId),
        apiClient.getPackage(packageId),
      ]);
      setDetail(issue);
      setPkg(packageDetail);
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message || 'Unable to load technical issue details');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, [packageId]);

  const primaryIdentifier = useMemo(() => pkg?.businessIdentifiers?.find((item) => item.primary)
    || pkg?.businessIdentifiers?.find((item) => item.displayable), [pkg]);
  const breadcrumbIdentifier = useMemo(() => pkg?.businessIdentifiers
    ?.filter((item) => item.displayable)
    .sort((a, b) => Number(b.primary) - Number(a.primary) || a.displayOrder - b.displayOrder)[0], [pkg]);
  const failedDocument = useMemo(() => detail?.documentId
    ? pkg?.documents?.find((item) => item.documentId === detail.documentId)
    : null, [detail, pkg]);

  const retry = async () => {
    if (!packageId) return;
    setRetrying(true);
    setError(null);
    setNotice(null);
    try {
      await apiClient.retryTechnicalException(packageId);
      setNotice('Retry accepted. Processing has resumed in the background; the Dashboard will update automatically.');
      setDetail((current) => current ? { ...current, retryable: false, status: 'RETRY_SCHEDULED' } : current);
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message || 'Unable to retry this technical failure');
    } finally {
      setRetrying(false);
    }
  };

  const breadcrumbItems = [
    ...(packageId ? [{ label: intakeBreadcrumbLabel(breadcrumbIdentifier), to: `/packages/${packageId}` }] : []),
    { label: failedDocument?.fileName ? `Document ${failedDocument.fileName}` : 'Technical Issue' },
  ];

  if (loading) {
    return (
      <div className="technical-issue-page">
        <Breadcrumbs items={[{ label: 'Technical Issue' }]} />
        <p>Loading technical issue…</p>
      </div>
    );
  }

  return (
    <div className="technical-issue-page">
      <Breadcrumbs items={breadcrumbItems} />

      <PageHeader
        eyebrow="Technical attention"
        title="Technical Issue Details"
        description={primaryIdentifier ? `${primaryIdentifier.label}: ${primaryIdentifier.value}` : 'Safe operational diagnostics for this package.'}
        actions={<StatusBadge value="ATTENTION_REQUIRED" label="Attention" />}
      />

      {error && <div className="error-msg">{error}</div>}
      {notice && <div className="success-msg">{notice}</div>}

      {detail && (
        <>
          <section className="card technical-issue-summary">
            <h2>What happened</h2>
            <p className="technical-issue-explanation">{detail.explanation}</p>
            <InfoGrid items={[
              { label: 'Failure category', value: friendlyStage(detail.failureCategory), emphasis: true },
              { label: 'Failed stage', value: friendlyStage(detail.failedStage) },
              { label: 'Document', value: failedDocument?.fileName || (detail.documentId ? 'Affected document' : 'Package-level operation') },
              { label: 'Retryable', value: detail.retryable ? 'Yes' : 'No' },
              { label: 'Failed at', value: new Date(detail.failedAt).toLocaleString() },
              { label: 'Original retained until', value: detail.originalRetainedUntil ? new Date(detail.originalRetainedUntil).toLocaleString() : '—' },
              { label: 'Correlation ID', value: <code>{detail.correlationId || '—'}</code> },
            ]} />
          </section>


          {detail.incidentId && (
            <section className="card technical-incident-record">
              <div className="technical-incident-record-head">
                <div>
                  <p className="eyebrow">Structured incident</p>
                  <h2>Incident record</h2>
                </div>
                <span className="platform-status">{friendlyStage(detail.incidentStatus || 'OPEN')}</span>
              </div>
              <table>
                <tbody>
                  <tr><td><strong>Incident ID</strong></td><td><code>{detail.incidentId}</code></td></tr>
                  <tr><td><strong>Business continuity</strong></td><td>{detail.businessContinuityUsed ? `Used${detail.businessOutcome ? ` · ${friendlyStage(detail.businessOutcome)}` : ''}` : 'Not used'}</td></tr>
                </tbody>
              </table>

              {detail.processingFingerprint && Object.keys(detail.processingFingerprint).length > 0 && (
                <div className="technical-fingerprint">
                  <h3>Processing fingerprint</h3>
                  <p>Safe version/configuration identifiers captured when the incident occurred.</p>
                  <div className="technical-fingerprint-grid">
                    {Object.entries(detail.processingFingerprint).map(([key, value]) => (
                      <div key={key}><span>{friendlyStage(key)}</span><strong>{value}</strong></div>
                    ))}
                  </div>
                </div>
              )}

              {detail.incidentEvents && detail.incidentEvents.length > 0 && (
                <div className="technical-incident-history">
                  <h3>Incident history</h3>
                  <div className="technical-incident-history-list">
                    {detail.incidentEvents.map((event, index) => (
                      <div className="technical-incident-history-item" key={`${event.eventType}-${event.occurredAt}-${index}`}>
                        <div>
                          <strong>{friendlyStage(event.eventType)}</strong>
                          <span>{new Date(event.occurredAt).toLocaleString()}</span>
                        </div>
                        <div>
                          {event.attemptNumber ? <span>Attempt {event.attemptNumber}</span> : null}
                          {event.scope ? <span>{friendlyStage(event.scope)}</span> : null}
                          {event.reasonCode ? <code>{event.reasonCode}</code> : null}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </section>
          )}

          {detail.operatorDiagnostics && (
            <section className="card technical-operator-diagnostics">
              <h2>Platform operations diagnostics</h2>
              <p>Safe internal metadata for platform support. No document values, credentials, or stack traces are shown.</p>
              <table>
                <tbody>
                  <tr><td><strong>Dependency</strong></td><td>{detail.operatorDiagnostics.dependency}</td></tr>
                  <tr><td><strong>Internal error code</strong></td><td>{detail.operatorDiagnostics.internalErrorCode}</td></tr>
                  <tr><td><strong>Attempts</strong></td><td>{detail.operatorDiagnostics.attempts}</td></tr>
                  <tr><td><strong>Environment</strong></td><td>{detail.operatorDiagnostics.environment}</td></tr>
                </tbody>
              </table>
            </section>
          )}

          <div className="technical-issue-actions">
            {detail.retryable && hasCurrentCapability('TECHNICAL_RETRY') && (
              <button className="primary" disabled={retrying} onClick={() => void retry()}>
                {retrying ? 'Scheduling retry…' : 'Retry Processing'}
              </button>
            )}
            {packageId && <Link className="button-link secondary" to={`/packages/${packageId}`}>Open Intake</Link>}
            <Link className="button-link secondary" to="/">Dashboard</Link>
          </div>

          {!detail.retryable && (
            <div className="platform-boundary-note">
              Retry is not available for this failure. Use the incident ID or correlation ID when escalating to tenant IT or platform operations.
            </div>
          )}
        </>
      )}
    </div>
  );
}
