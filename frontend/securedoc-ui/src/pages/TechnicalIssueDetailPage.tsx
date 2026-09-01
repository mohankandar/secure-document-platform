import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { apiClient } from '../api/client';
import { hasCurrentCapability } from '../api/config';
import type { ApiError, PackageDetail, TechnicalExceptionDetail } from '../types';
import Breadcrumbs, { intakeBreadcrumbLabel } from '../components/Breadcrumbs';

function friendlyStage(stage: string) {
  return stage.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase());
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

      <header className="technical-issue-header">
        <div>
          <p className="eyebrow">Technical Attention</p>
          <h1>Technical Issue Details</h1>
          {primaryIdentifier && <p>{primaryIdentifier.label}: <strong>{primaryIdentifier.value}</strong></p>}
        </div>
        <span className="platform-status platform-status-unhealthy">Attention</span>
      </header>

      {error && <div className="error-msg">{error}</div>}
      {notice && <div className="success-msg">{notice}</div>}

      {detail && (
        <>
          <section className="card technical-issue-summary">
            <h2>What happened</h2>
            <p className="technical-issue-explanation">{detail.explanation}</p>
            <table>
              <tbody>
                <tr><td><strong>Failure category</strong></td><td>{friendlyStage(detail.failureCategory)}</td></tr>
                <tr><td><strong>Failed stage</strong></td><td>{friendlyStage(detail.failedStage)}</td></tr>
                <tr><td><strong>Document</strong></td><td>{failedDocument?.fileName || (detail.documentId ? 'Affected document' : 'Package-level operation')}</td></tr>
                <tr><td><strong>Retryable</strong></td><td>{detail.retryable ? 'Yes' : 'No'}</td></tr>
                <tr><td><strong>Failed at</strong></td><td>{new Date(detail.failedAt).toLocaleString()}</td></tr>
                <tr><td><strong>Original retained until</strong></td><td>{detail.originalRetainedUntil ? new Date(detail.originalRetainedUntil).toLocaleString() : '—'}</td></tr>
                <tr><td><strong>Correlation ID</strong></td><td><code>{detail.correlationId || '—'}</code></td></tr>
              </tbody>
            </table>
          </section>

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
              Retry is not available for this failure. Use the correlation ID when escalating to tenant IT or platform operations.
            </div>
          )}
        </>
      )}
    </div>
  );
}
