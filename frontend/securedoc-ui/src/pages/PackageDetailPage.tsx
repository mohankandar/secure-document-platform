import { useEffect, useState, useRef } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { apiClient } from '../api/client';
import { hasCurrentCapability } from '../api/config';
import type { PackageDetail, PackageLifecycleSummary, ApiError, DerivedDelivery } from '../types';
import { intakeBreadcrumbLabel } from '../components/Breadcrumbs';
import PageScaffold from '../components/ui/PageScaffold';
import StatusBadge from '../components/ui/StatusBadge';
import HorizontalTabs from '../components/ui/HorizontalTabs';
import InfoGrid from '../components/ui/InfoGrid';

export default function PackageDetailPage() {
  const { packageId } = useParams<{ packageId: string }>();
  const navigate = useNavigate();
  const [pkg, setPkg] = useState<PackageDetail | null>(null);
  const [lifecycle, setLifecycle] = useState<PackageLifecycleSummary | null>(null);
  const [derivedDeliveries, setDerivedDeliveries] = useState<DerivedDelivery[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);
  const [manualFallbackActive, setManualFallbackActive] = useState(false);
  const [confirmReprocess, setConfirmReprocess] = useState(false);
  const [reprocessSubmitting, setReprocessSubmitting] = useState(false);
  const [activeTab, setActiveTab] = useState<'overview' | 'documents' | 'delivery' | 'routing'>('overview');
  const [selectedUploadName, setSelectedUploadName] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const loadPackage = async () => {
    if (!packageId) return;
    setLoading(true);
    setError(null);
    try {
      const [data, lifecycleData, derivedData] = await Promise.all([
        apiClient.getPackage(packageId),
        apiClient.getPackageLifecycle(packageId),
        hasCurrentCapability('PACKAGE_APPROVE_DELIVERY') ? apiClient.listDerivedDeliveries(packageId) : Promise.resolve([]),
      ]);
      setPkg(data);
      setLifecycle(lifecycleData);
      setDerivedDeliveries(derivedData);
      setManualFallbackActive(false);
      if (data.status === 'IN_REVIEW' && hasCurrentCapability('MANUAL_REVIEW')) {
        try {
          await apiClient.getManualFallback(packageId);
          setManualFallbackActive(true);
        } catch {
          // Normal finding-focused Manual Review also uses IN_REVIEW; this package is not in manual fallback.
        }
      }
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message || 'Failed to load package');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadPackage(); }, [packageId]);

  const reprocessingActive = Boolean(
    pkg && lifecycle
      && lifecycle.successfulExportCycles > 0
      && (pkg.status === 'SUBMITTED' || pkg.status === 'PROCESSING')
  );

  useEffect(() => {
    if (!reprocessingActive) return;
    const timer = window.setTimeout(() => { void loadPackage(); }, 3000);
    return () => window.clearTimeout(timer);
  }, [reprocessingActive, pkg?.status, lifecycle?.successfulExportCycles, packageId]);

  useEffect(() => {
    if (!confirmReprocess) return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !reprocessSubmitting) setConfirmReprocess(false);
    };
    document.addEventListener('keydown', closeOnEscape);
    return () => document.removeEventListener('keydown', closeOnEscape);
  }, [confirmReprocess, reprocessSubmitting]);

  const handleUpload = async () => {
    const file = fileInputRef.current?.files?.[0];
    if (!file || !packageId) return;
    setSuccessMsg(null);
    setError(null);
    try {
      await apiClient.uploadDocument(packageId, file);
      setSuccessMsg('Document uploaded successfully.');
      setSelectedUploadName(null);
      if (fileInputRef.current) fileInputRef.current.value = '';
      loadPackage();
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message || 'Upload failed');
    }
  };

  const handleSubmit = async () => {
    if (!packageId) return;
    setError(null);
    setSuccessMsg(null);
    try {
      await apiClient.submitPackage(packageId);
      setSuccessMsg('Documents submitted. Automatic processing has run; the status below reflects the current outcome.');
      loadPackage();
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message || 'Submit failed');
    }
  };

  const handleApproveDelivery = async () => {
    if (!packageId) return;
    setError(null);
    setSuccessMsg(null);
    try {
      await apiClient.approvePackageDelivery(packageId);
      setSuccessMsg('Final delivery approved. Export resumed automatically.');
      await loadPackage();
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message || 'Unable to approve final delivery');
    }
  };

  const handleReprocess = async () => {
    if (!packageId || reprocessSubmitting) return;
    setReprocessSubmitting(true);
    setError(null);
    setSuccessMsg(null);
    try {
      const result = await apiClient.reprocessPackage(packageId);
      setConfirmReprocess(false);
      setPkg((current) => current ? { ...current, status: result.status } : current);
      setLifecycle((current) => current ? { ...current, canReprocess: false } : current);
      setSuccessMsg(`Reprocessing started for export cycle ${result.nextExportCycle}.`);
      await loadPackage();
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message || 'Unable to reprocess this intake');
    } finally {
      setReprocessSubmitting(false);
    }
  };

  const handleManualFallback = async () => {
    if (!packageId) return;
    setError(null);
    setSuccessMsg(null);
    try {
      await apiClient.startManualFallback(packageId);
      navigate(`/packages/${packageId}/manual-processing`);
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message || 'Unable to continue with manual processing');
    }
  };

  const handleDerivedSend = async (deliveryId: string) => {
    if (!packageId) return;
    setError(null); setSuccessMsg(null);
    try { await apiClient.sendDerivedDelivery(packageId, deliveryId); setSuccessMsg('Derived document delivery completed.'); await loadPackage(); }
    catch (err) { const apiErr = err as ApiError; setError(apiErr.message || 'Unable to send derived document'); }
  };

  const formatTime = (value?: string | null) => value ? new Date(value).toLocaleString() : '—';

  const primaryIdentifier = pkg?.businessIdentifiers
    ?.filter((identifier) => identifier.displayable)
    .sort((a, b) => Number(b.primary) - Number(a.primary) || a.displayOrder - b.displayOrder)[0];
  const intakeLabel = intakeBreadcrumbLabel(primaryIdentifier);
  const displayedPackageStatus = reprocessingActive ? 'REPROCESSING' : pkg?.status;

  const secondaryIdentifiers = pkg?.businessIdentifiers
    ?.filter((identifier) => identifier.displayable && identifier.key !== primaryIdentifier?.key)
    .sort((a, b) => a.displayOrder - b.displayOrder) || [];

  const renderCurrentAction = () => {
    if (!pkg) return null;
    if (pkg.status === 'CREATED') {
      return (
        <section className="ui-card package-action-card tone-info">
          <div>
            <span className="package-action-eyebrow">Draft intake</span>
            <h2>Add documents and submit</h2>
            <p>This intake exists but processing has not started.</p>
          </div>
          <div className="package-created-upload">
            <input
              className="package-upload-input"
              type="file"
              ref={fileInputRef}
              accept=".pdf"
              aria-label="Choose PDF to upload"
              onChange={(event) => setSelectedUploadName(event.target.files?.[0]?.name || null)}
            />
            <div className="package-upload-picker">
              <button className="ui-button secondary" type="button" onClick={() => fileInputRef.current?.click()}>Browse PDF</button>
              <span className={selectedUploadName ? 'has-file' : ''} aria-live="polite">{selectedUploadName || 'No PDF selected'}</span>
            </div>
            <div className="package-upload-actions">
              <button className="ui-button secondary" type="button" onClick={handleUpload} disabled={!selectedUploadName}>Upload PDF</button>
              <button className="ui-button primary" type="button" onClick={handleSubmit} disabled={pkg.documentCount === 0}>Submit Documents</button>
            </div>
          </div>
        </section>
      );
    }
    if (pkg.status === 'PENDING_REVIEW' || pkg.status === 'IN_REVIEW') {
      return (
        <section className="ui-card package-action-card tone-warning">
          <div>
            <span className="package-action-eyebrow">Human decision required</span>
            <h2>{manualFallbackActive ? 'Manual processing in progress' : 'Manual review required'}</h2>
            <p>{manualFallbackActive
              ? 'Automatic analysis could not complete. Review every page and mark sensitive areas before delivery.'
              : 'Only findings that need a human decision are waiting. High-confidence findings continue automatically.'}</p>
          </div>
          {hasCurrentCapability('MANUAL_REVIEW') ? (
            <Link className="ui-button primary" to={manualFallbackActive
              ? `/packages/${packageId}/manual-processing`
              : `/review?packageId=${packageId}`}>
              {manualFallbackActive ? 'Continue manual processing' : 'Open manual review'}
            </Link>
          ) : <span className="package-action-waiting">Waiting for an authorized reviewer</span>}
        </section>
      );
    }
    if (pkg.status === 'PENDING_FINAL_APPROVAL') {
      return (
        <section className="ui-card package-action-card tone-warning">
          <div>
            <span className="package-action-eyebrow">Delivery approval</span>
            <h2>Ready for final delivery approval</h2>
            <p>Redaction and verification are complete. Tenant policy requires an approver before delivery.</p>
          </div>
          {hasCurrentCapability('PACKAGE_APPROVE_DELIVERY')
            ? <button className="ui-button primary" onClick={() => void handleApproveDelivery()}>Approve & Deliver</button>
            : <span className="package-action-waiting">Waiting for an authorized approver</span>}
        </section>
      );
    }
    if (reprocessingActive) {
      return (
        <section className="ui-card package-action-card tone-info">
          <div>
            <span className="package-action-eyebrow">New export cycle</span>
            <h2>Reprocessing retained original</h2>
            <p>The package is applying current tenant configuration to a new processing/export cycle.</p>
          </div>
        </section>
      );
    }
    if (pkg.status === 'COMPLETED') {
      return (
        <section className="ui-card package-action-card tone-success">
          <div>
            <span className="package-action-eyebrow">Business complete</span>
            <h2>Processing and delivery completed</h2>
            <p>{lifecycle?.lastExportedAt ? `Last exported ${formatTime(lifecycle.lastExportedAt)}.` : 'The package reached its completed business state.'}</p>
          </div>
          {lifecycle?.canReprocess && hasCurrentCapability('PACKAGE_REPROCESS') && (
            <button className="ui-button secondary" type="button" onClick={() => setConfirmReprocess(true)}>Reprocess retained original</button>
          )}
        </section>
      );
    }
    if (pkg.status === 'FAILED') {
      return (
        <section className="ui-card package-action-card tone-danger">
          <div>
            <span className="package-action-eyebrow">Technical attention</span>
            <h2>Automatic processing stopped</h2>
            <p>Business can continue manually when the PDF is still safe to render and redact.</p>
          </div>
          <div className="inline-actions">
            {hasCurrentCapability('MANUAL_REVIEW') && <button className="ui-button primary" onClick={() => void handleManualFallback()}>Continue manually</button>}
            {hasCurrentCapability('TECHNICAL_EXCEPTION_VIEW') && <Link className="ui-button secondary" to={`/technical-exceptions/${packageId}`}>View technical issue</Link>}
          </div>
        </section>
      );
    }
    return null;
  };

  if (loading && !pkg) {
    return (
      <PageScaffold className="package-detail-page" breadcrumbs={[{ label: 'Intake Details' }]} title="Intake Details">
        <div className="ui-loading-state">Loading intake details…</div>
      </PageScaffold>
    );
  }

  return (
    <PageScaffold className="package-detail-page" breadcrumbs={[{ label: intakeLabel }]}
      title={primaryIdentifier ? `${primaryIdentifier.label} ${primaryIdentifier.value}` : 'Intake Details'}
      eyebrow="Intake details" description="Review package identity, lifecycle state, documents, delivery, and routing."
      actions={<button className="ui-button secondary" onClick={() => void loadPackage()}>Refresh</button>}>

      {error && <div className="ui-alert tone-danger">{error}</div>}
      {successMsg && <div className="ui-alert tone-success">{successMsg}</div>}

      {pkg && (
        <>
          <section className="ui-card package-summary-card">
            <div className="package-summary-main">
              <div className="package-summary-status"><StatusBadge value={displayedPackageStatus || pkg.status} /></div>
              <div className="package-summary-copy">
                <span>{pkg.packageType.replace(/_/g, ' ')}</span>
                <strong>{pkg.documentCount} document{pkg.documentCount === 1 ? '' : 's'}</strong>
                <small>Created {new Date(pkg.createdAt).toLocaleString()}</small>
              </div>
            </div>
            {secondaryIdentifiers.length > 0 && (
              <div className="package-identifier-chips">
                {secondaryIdentifiers.map((identifier) => (
                  <span key={identifier.key}><small>{identifier.label}</small><strong>{identifier.value}</strong></span>
                ))}
              </div>
            )}
          </section>

          {renderCurrentAction()}

          <HorizontalTabs ariaLabel="Intake detail sections" className="package-tabs">
            {([
              ['overview', 'Overview'],
              ['documents', `Documents (${pkg.documentCount})`],
              ['delivery', 'Delivery & retention'],
              ['routing', `Derived routing${derivedDeliveries.length ? ` (${derivedDeliveries.length})` : ''}`],
            ] as const).map(([key, label]) => (
              <button key={key} type="button" className={activeTab === key ? 'active' : ''} onClick={() => setActiveTab(key)}>{label}</button>
            ))}
          </HorizontalTabs>

          {activeTab === 'overview' && (
            <section className="ui-card package-tab-panel">
              <div className="package-section-heading">
                <div><span className="ui-eyebrow">Business context</span><h2>Intake overview</h2><p>Identifiers and current operational state for this package.</p></div>
              </div>
              <InfoGrid className="package-overview-grid" items={[
                ...(pkg.businessIdentifiers?.filter((identifier) => identifier.displayable).map((identifier) => ({ label: identifier.label, value: identifier.value, emphasis: identifier.primary })) || []),
                { label: 'Intake source', value: pkg.packageType.replace(/_/g, ' ') },
                { label: 'Current status', value: <StatusBadge value={displayedPackageStatus || pkg.status} /> },
                { label: 'Documents', value: pkg.documentCount },
                { label: 'Created', value: new Date(pkg.createdAt).toLocaleString() },
                { label: 'Last updated', value: new Date(pkg.updatedAt).toLocaleString() },
              ]} />
              {lifecycle && (
                <div className="package-mini-metrics">
                  <div><span>Successful exports</span><strong>{lifecycle.successfulExportCycles}</strong><small>of {lifecycle.maximumExportCycles} allowed</small></div>
                  <div><span>Final approval</span><strong>{lifecycle.finalApprovalRequired ? (lifecycle.finalApprovalGranted ? 'Granted' : 'Required') : 'Not required'}</strong><small>Tenant lifecycle policy</small></div>
                  <div><span>Last destination</span><strong>{lifecycle.lastDestinationType || '—'}</strong><small>{formatTime(lifecycle.lastExportedAt)}</small></div>
                </div>
              )}
            </section>
          )}

          {activeTab === 'documents' && (
            <section className="ui-card package-tab-panel">
              <div className="package-section-heading"><div><span className="ui-eyebrow">Package contents</span><h2>Documents</h2><p>Each uploaded PDF remains an independently tracked document.</p></div></div>
              {pkg.documents?.length ? (
                <div className="package-document-list">
                  {pkg.documents.map((doc) => (
                    <article className="ui-card package-document-card" key={doc.documentId}>
                      <div className="package-document-icon">PDF</div>
                      <div className="package-document-copy">
                        <strong>{doc.fileName || 'Document'}</strong>
                        <span>{doc.documentType || 'PDF'} · {doc.mimeType}</span>
                        <small>{(doc.sizeBytes / 1024).toFixed(1)} KB · ID {doc.documentId.slice(0, 8)}</small>
                      </div>
                      <StatusBadge value={doc.status} />
                    </article>
                  ))}
                </div>
              ) : <div className="ui-empty-state">No documents are attached to this intake.</div>}
            </section>
          )}

          {activeTab === 'delivery' && (
            <section className="ui-card package-tab-panel">
              <div className="package-section-heading"><div><span className="ui-eyebrow">Lifecycle</span><h2>Delivery & retention</h2><p>Delivery outcomes and temporary internal-artifact retention windows.</p></div></div>
              {lifecycle ? (
                <InfoGrid items={[
                  { label: 'Successful exports', value: `${lifecycle.successfulExportCycles} of ${lifecycle.maximumExportCycles}`, emphasis: true },
                  { label: 'Last destination', value: lifecycle.lastDestinationType || '—' },
                  { label: 'Last exported', value: formatTime(lifecycle.lastExportedAt) },
                  { label: 'Original retained until', value: formatTime(lifecycle.originalDeleteAt) },
                  { label: 'Internal redacted copy until', value: formatTime(lifecycle.redactedDeleteAt) },
                  ...(lifecycle.technicalHoldUntil ? [{ label: 'Technical hold until', value: formatTime(lifecycle.technicalHoldUntil) }] : []),
                  ...(lifecycle.purgedAt ? [{ label: 'Internal artifacts purged', value: formatTime(lifecycle.purgedAt) }] : []),
                ]} />
              ) : <div className="ui-empty-state">Lifecycle information is not available yet.</div>}
            </section>
          )}

          {activeTab === 'routing' && (
            <section className="ui-card package-tab-panel">
              <div className="package-section-heading"><div><span className="ui-eyebrow">External routing</span><h2>Derived document routing</h2><p>Page-subset PDFs route independently from the full master delivery.</p></div></div>
              {derivedDeliveries.length > 0 ? (
                <div className="derived-delivery-list">
                  {derivedDeliveries.map((delivery) => (
                    <article className="ui-card derived-delivery-card" key={delivery.derivedDeliveryId}>
                      <div>
                        <strong>{delivery.destinationLabel || 'Routing attention'}</strong>
                        <span>{delivery.markerValue || delivery.failureCategory || 'No marker detail'}</span>
                        <small>Pages {delivery.pageNumbers.length ? delivery.pageNumbers.join(', ') : '—'} · Attempt {delivery.attemptCount}</small>
                      </div>
                      <StatusBadge value={delivery.status} />
                      {delivery.status !== 'ATTENTION_REQUIRED' && (
                        <button className="ui-button secondary" onClick={() => void handleDerivedSend(delivery.derivedDeliveryId)}>{delivery.status === 'DELIVERED' ? 'Resend' : 'Send'}</button>
                      )}
                    </article>
                  ))}
                </div>
              ) : <div className="ui-empty-state">No derived-document deliveries exist for this package.</div>}
            </section>
          )}
        </>
      )}

      {confirmReprocess && pkg && (
        <div
          className="dashboard-modal-backdrop"
          role="presentation"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget && !reprocessSubmitting) setConfirmReprocess(false);
          }}
        >
          <section className="dashboard-confirm-modal" role="dialog" aria-modal="true" aria-labelledby="reprocess-package-title">
            <div className="dashboard-confirm-modal-handle" aria-hidden="true" />
            <h2 id="reprocess-package-title">Reprocess from retained original?</h2>
            {primaryIdentifier && (
              <p className="dashboard-confirm-identifier">
                <span>{primaryIdentifier.label}</span>
                <strong>{primaryIdentifier.value}</strong>
              </p>
            )}
            <p>
              This package will leave Completed status and start a new processing/export cycle from the retained original.
              Existing processing rules and the current tenant configuration will be applied.
            </p>
            <div className="dashboard-confirm-actions">
              <button className="ui-button secondary dashboard-confirm-cancel" type="button" disabled={reprocessSubmitting} onClick={() => setConfirmReprocess(false)}>Cancel</button>
              <button className="ui-button primary reprocess-confirm-submit" type="button" disabled={reprocessSubmitting} onClick={() => void handleReprocess()}>{reprocessSubmitting ? 'Starting…' : 'Reprocess'}</button>
            </div>
          </section>
        </div>
      )}
    </PageScaffold>
  );
}
