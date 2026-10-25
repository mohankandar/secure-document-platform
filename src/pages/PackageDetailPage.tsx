import { useEffect, useMemo, useState, useRef } from 'react';
import type { ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { apiClient } from '../api/client';
import { hasCurrentCapability } from '../api/config';
import type { PackageDetail, PackageLifecycleSummary, ApiError, DerivedDelivery } from '../types';
import { intakeBreadcrumbLabel } from '../components/Breadcrumbs';
import PageScaffold from '../components/ui/PageScaffold';
import StatusBadge from '../components/ui/StatusBadge';
import HorizontalTabs from '../components/ui/HorizontalTabs';
import WorkflowStepper, { type WorkflowStep } from '../components/ui/WorkflowStepper';
import Skeleton from '../components/ui/Skeleton';
import EmptyState from '../components/ui/EmptyState';
import Icon from '../components/ui/Icon';
import './PackageDetailPage.css';

type DetailTab = 'documents' | 'delivery' | 'routing';

const formatTime = (value?: string | null) => value ? new Date(value).toLocaleString() : '—';
const formatBytes = (bytes: number) => {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
};
const relativeDays = (value?: string | null) => {
  if (!value) return null;
  const days = Math.round((new Date(value).getTime() - Date.now()) / 86_400_000);
  if (days === 0) return 'today';
  return days > 0 ? `in ${days} day${days === 1 ? '' : 's'}` : `${Math.abs(days)} day${days === -1 ? '' : 's'} ago`;
};

function lifecycleSteps(status: string, reprocessing: boolean, lifecycle: PackageLifecycleSummary | null): WorkflowStep[] {
  const position: Record<string, number> = {
    CREATED: 0, SUBMITTED: 1, PROCESSING: 1, FAILED: 1,
    PENDING_REVIEW: 2, IN_REVIEW: 2, REJECTED: 2,
    PENDING_FINAL_APPROVAL: 3, COMPLETED: 5,
  };
  const current = reprocessing ? 1 : position[status] ?? 1;
  const approvalNote = lifecycle && !lifecycle.finalApprovalRequired ? 'Not required by policy' : 'Release decision';
  const notes = [
    'Identifiers and PDFs',
    status === 'FAILED' ? 'Stopped — needs attention' : reprocessing ? 'New export cycle' : 'Analysis and redaction',
    status === 'REJECTED' ? 'Rejected by reviewer' : 'Human decisions only',
    approvalNote,
    lifecycle?.lastExportedAt ? `Exported ${new Date(lifecycle.lastExportedAt).toLocaleDateString()}` : 'Destination export',
  ];
  return ['Intake', 'Processing', 'Review', 'Approval', 'Delivered'].map((label, index) => ({
    label,
    description: notes[index],
    state: index < current ? 'complete' : index === current ? 'current' : 'upcoming',
  }));
}

function ActionCard({ tone, eyebrow, title, children, actions }: { tone: 'info' | 'warning' | 'success' | 'danger'; eyebrow: string; title: string; children: ReactNode; actions?: ReactNode }) {
  return (
    <section className={`ui-card pd-action tone-${tone}`} aria-labelledby="pd-action-title">
      <div className="pd-action-copy">
        <span className="pd-eyebrow">{eyebrow}</span>
        <h2 id="pd-action-title">{title}</h2>
        <div className="pd-action-body">{children}</div>
      </div>
      {actions && <div className="pd-action-buttons">{actions}</div>}
    </section>
  );
}

function Fact({ label, value, detail }: { label: string; value: ReactNode; detail?: ReactNode }) {
  return (
    <div className="pd-fact">
      <dt>{label}</dt>
      <dd><strong>{value}</strong>{detail && <small>{detail}</small>}</dd>
    </div>
  );
}

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
  const [activeTab, setActiveTab] = useState<DetailTab>('documents');
  const [selectedUploadName, setSelectedUploadName] = useState<string | null>(null);
  const [documentQuery, setDocumentQuery] = useState('');
  const [sendingDeliveryId, setSendingDeliveryId] = useState<string | null>(null);
  const [actionPending, setActionPending] = useState(false);
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
    setActionPending(true);
    try {
      await apiClient.uploadDocument(packageId, file);
      setSuccessMsg('Document uploaded successfully.');
      setSelectedUploadName(null);
      if (fileInputRef.current) fileInputRef.current.value = '';
      loadPackage();
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message || 'Upload failed');
    } finally {
      setActionPending(false);
    }
  };

  const handleSubmit = async () => {
    if (!packageId) return;
    setError(null);
    setSuccessMsg(null);
    setActionPending(true);
    try {
      await apiClient.submitPackage(packageId);
      setSuccessMsg('Documents submitted. Automatic processing has run; the status below reflects the current outcome.');
      loadPackage();
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message || 'Submit failed');
    } finally {
      setActionPending(false);
    }
  };

  const handleApproveDelivery = async () => {
    if (!packageId) return;
    setError(null);
    setSuccessMsg(null);
    setActionPending(true);
    try {
      await apiClient.approvePackageDelivery(packageId);
      setSuccessMsg('Final delivery approved. Export resumed automatically.');
      await loadPackage();
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message || 'Unable to approve final delivery');
    } finally {
      setActionPending(false);
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
    setError(null); setSuccessMsg(null); setSendingDeliveryId(deliveryId);
    try { await apiClient.sendDerivedDelivery(packageId, deliveryId); setSuccessMsg('Derived document delivery completed.'); await loadPackage(); }
    catch (err) { const apiErr = err as ApiError; setError(apiErr.message || 'Unable to send derived document'); }
    finally { setSendingDeliveryId(null); }
  };

  const primaryIdentifier = pkg?.businessIdentifiers
    ?.filter((identifier) => identifier.displayable)
    .sort((a, b) => Number(b.primary) - Number(a.primary) || a.displayOrder - b.displayOrder)[0];
  const intakeLabel = intakeBreadcrumbLabel(primaryIdentifier);
  const displayedPackageStatus = reprocessingActive ? 'REPROCESSING' : pkg?.status;

  const secondaryIdentifiers = pkg?.businessIdentifiers
    ?.filter((identifier) => identifier.displayable && identifier.key !== primaryIdentifier?.key)
    .sort((a, b) => a.displayOrder - b.displayOrder) || [];

  const visibleDocuments = useMemo(() => {
    const query = documentQuery.trim().toLowerCase();
    const documents = pkg?.documents || [];
    if (!query) return documents;
    return documents.filter((doc) => `${doc.fileName || ''} ${doc.status} ${doc.documentId}`.toLowerCase().includes(query));
  }, [pkg?.documents, documentQuery]);

  const routingSummary = useMemo(() => ({
    delivered: derivedDeliveries.filter((delivery) => delivery.status === 'DELIVERED').length,
    pending: derivedDeliveries.filter((delivery) => ['PENDING', 'DELIVERING', 'RETRY_PENDING'].includes(delivery.status)).length,
    attention: derivedDeliveries.filter((delivery) => delivery.status === 'ATTENTION_REQUIRED').length,
  }), [derivedDeliveries]);

  const renderCurrentAction = () => {
    if (!pkg) return null;
    if (pkg.status === 'CREATED') {
      return (
        <ActionCard tone="info" eyebrow="Draft intake" title="Add documents and submit"
          actions={<>
            <button className="ui-button secondary" type="button" onClick={() => void handleUpload()} disabled={!selectedUploadName || actionPending}>Upload PDF</button>
            <button className="ui-button primary" type="button" onClick={() => void handleSubmit()} disabled={pkg.documentCount === 0 || actionPending}>Submit Documents</button>
          </>}>
          <p>This intake exists but processing has not started. Upload each PDF, then submit once.</p>
          <div className="pd-upload-picker">
            <input
              className="pd-upload-input"
              type="file"
              ref={fileInputRef}
              accept=".pdf"
              aria-label="Choose PDF to upload"
              onChange={(event) => setSelectedUploadName(event.target.files?.[0]?.name || null)}
            />
            <button className="ui-button secondary" type="button" onClick={() => fileInputRef.current?.click()}>
              <Icon name="upload" /> Browse PDF
            </button>
            <span className={selectedUploadName ? 'has-file' : ''} aria-live="polite">{selectedUploadName || 'No PDF selected'}</span>
          </div>
        </ActionCard>
      );
    }
    if (pkg.status === 'PENDING_REVIEW' || pkg.status === 'IN_REVIEW') {
      return (
        <ActionCard tone="warning" eyebrow="Human decision required"
          title={manualFallbackActive ? 'Manual processing in progress' : 'Manual review required'}
          actions={hasCurrentCapability('MANUAL_REVIEW') ? (
            <Link className="ui-button primary" to={manualFallbackActive
              ? `/packages/${packageId}/manual-processing`
              : `/review?packageId=${packageId}`}>
              {manualFallbackActive ? 'Continue manual processing' : 'Open manual review'}
            </Link>
          ) : <span className="pd-action-waiting">Waiting for an authorized reviewer</span>}>
          <p>{manualFallbackActive
            ? 'Automatic analysis could not complete. Review every page and mark sensitive areas before delivery.'
            : 'Only findings that need a human decision are waiting. High-confidence findings continue automatically.'}</p>
        </ActionCard>
      );
    }
    if (pkg.status === 'PENDING_FINAL_APPROVAL') {
      return (
        <ActionCard tone="warning" eyebrow="Delivery approval" title="Ready for final delivery approval"
          actions={hasCurrentCapability('PACKAGE_APPROVE_DELIVERY')
            ? <button className="ui-button primary" type="button" disabled={actionPending} onClick={() => void handleApproveDelivery()}>{actionPending ? 'Approving…' : 'Approve & Deliver'}</button>
            : <span className="pd-action-waiting">Waiting for an authorized approver</span>}>
          <p>Redaction and verification are complete. Tenant policy requires an approver before delivery.</p>
        </ActionCard>
      );
    }
    if (reprocessingActive) {
      return (
        <ActionCard tone="info" eyebrow="New export cycle" title="Reprocessing retained original">
          <p>The package is applying current tenant configuration to a new processing/export cycle. This page refreshes automatically.</p>
        </ActionCard>
      );
    }
    if (pkg.status === 'COMPLETED') {
      return (
        <ActionCard tone="success" eyebrow="Business complete" title="Processing and delivery completed"
          actions={lifecycle?.canReprocess && hasCurrentCapability('PACKAGE_REPROCESS') ? (
            <button className="ui-button secondary" type="button" onClick={() => setConfirmReprocess(true)}>
              <Icon name="refresh" /> Reprocess retained original
            </button>
          ) : undefined}>
          <p>{lifecycle?.lastExportedAt ? `Last exported ${formatTime(lifecycle.lastExportedAt)}.` : 'The package reached its completed business state.'}</p>
        </ActionCard>
      );
    }
    if (pkg.status === 'FAILED') {
      return (
        <ActionCard tone="danger" eyebrow="Technical attention" title="Automatic processing stopped"
          actions={(hasCurrentCapability('MANUAL_REVIEW') || hasCurrentCapability('TECHNICAL_EXCEPTION_VIEW')) ? <>
            {hasCurrentCapability('TECHNICAL_EXCEPTION_VIEW') && <Link className="ui-button secondary" to={`/technical-exceptions/${packageId}`}>View technical issue</Link>}
            {hasCurrentCapability('MANUAL_REVIEW') && <button className="ui-button primary" type="button" onClick={() => void handleManualFallback()}>Continue manually</button>}
          </> : <span className="pd-action-waiting">Waiting for technical support</span>}>
          <p>Business can continue manually when the PDF is still safe to render and redact.</p>
        </ActionCard>
      );
    }
    return null;
  };

  if (loading && !pkg) {
    return (
      <PageScaffold className="pd-page" breadcrumbs={[{ label: 'Intake Details' }]} title="Intake Details" eyebrow="Intake details">
        <div className="pd-loading" aria-busy="true">
          <Skeleton className="pd-skeleton-band" />
          <Skeleton className="pd-skeleton-stepper" />
          <div className="pd-layout">
            <Skeleton className="pd-skeleton-panel" />
            <Skeleton className="pd-skeleton-rail" />
          </div>
        </div>
      </PageScaffold>
    );
  }

  if (!pkg) {
    return (
      <PageScaffold className="pd-page" breadcrumbs={[{ label: 'Intake Details' }]} title="Intake Details" eyebrow="Intake details">
        <EmptyState className="pd-load-error" role="alert">
          <Icon name="inbox" className="pd-load-error-icon" />
          <strong>{error ? 'This intake could not be loaded' : 'No intake selected'}</strong>
          <p>{error || 'Open an intake from the Dashboard to see its details.'}</p>
          <div className="pd-load-error-actions">
            <Link className="ui-button secondary" to="/">Back to Dashboard</Link>
            {packageId && <button className="ui-button primary" type="button" onClick={() => void loadPackage()}>Try again</button>}
          </div>
        </EmptyState>
      </PageScaffold>
    );
  }

  const tabs: Array<[DetailTab, string, number | null]> = [
    ['documents', 'Documents', pkg.documentCount],
    ['delivery', 'Delivery & retention', null],
    ['routing', 'Derived routing', derivedDeliveries.length || null],
  ];

  return (
    <PageScaffold className="pd-page" breadcrumbs={[{ label: intakeLabel }]}
      title={primaryIdentifier ? `${primaryIdentifier.label} ${primaryIdentifier.value}` : 'Intake Details'}
      eyebrow="Intake details" description="Review package identity, lifecycle state, documents, delivery, and routing."
      actions={<button className="ui-button secondary" type="button" onClick={() => void loadPackage()} disabled={loading}>
        <Icon name="refresh" className={loading ? 'pd-spin' : undefined} /> {loading ? 'Refreshing…' : 'Refresh'}
      </button>}>

      <div className="pd-messages" aria-live="polite">
        {error && <div className="ui-alert tone-danger" role="alert">{error}</div>}
        {successMsg && <div className="ui-alert tone-success" role="status">{successMsg}</div>}
      </div>

      <section className="ui-card pd-identity" aria-label="Intake identity">
        <div className="pd-identity-status">
          <StatusBadge value={displayedPackageStatus || pkg.status} />
          <span>{pkg.packageType.replace(/_/g, ' ').toLowerCase()}</span>
        </div>
        <dl className="pd-identity-facts">
          {secondaryIdentifiers.map((identifier) => (
            <div key={identifier.key}><dt>{identifier.label}</dt><dd>{identifier.value}</dd></div>
          ))}
          <div><dt>Documents</dt><dd>{pkg.documentCount}</dd></div>
          <div><dt>Created</dt><dd>{formatTime(pkg.createdAt)}</dd></div>
          <div><dt>Last updated</dt><dd>{formatTime(pkg.updatedAt)}</dd></div>
        </dl>
      </section>

      <WorkflowStepper ariaLabel="Intake lifecycle" steps={lifecycleSteps(pkg.status, reprocessingActive, lifecycle)} />

      <div className="pd-layout">
        <div className="pd-main">
          {renderCurrentAction()}

          <section className="ui-card pd-workspace">
            <HorizontalTabs ariaLabel="Intake detail sections" className="pd-tabs">
              {tabs.map(([key, label, count]) => (
                <button key={key} type="button" className={activeTab === key ? 'active' : ''} aria-pressed={activeTab === key} onClick={() => setActiveTab(key)}>
                  {label}{count !== null && <span className="pd-tab-count">{count}</span>}
                </button>
              ))}
            </HorizontalTabs>

            {activeTab === 'documents' && (
              <div className="pd-panel">
                <div className="pd-panel-head">
                  <div>
                    <h2>Documents</h2>
                    <p>Each uploaded PDF remains an independently tracked document.</p>
                  </div>
                  {(pkg.documents?.length || 0) > 6 && (
                    <label className="pd-search">
                      <span className="sr-only">Filter documents</span>
                      <Icon name="search" />
                      <input type="search" value={documentQuery} onChange={(event) => setDocumentQuery(event.target.value)} placeholder="Filter by name or status" />
                    </label>
                  )}
                </div>
                {pkg.documents?.length ? (
                  visibleDocuments.length ? (
                    <div className="pd-table-wrap">
                      <table className="pd-table">
                        <thead><tr><th scope="col">Document</th><th scope="col">Type</th><th scope="col" className="is-numeric">Size</th><th scope="col">Status</th></tr></thead>
                        <tbody>
                          {visibleDocuments.map((doc) => (
                            <tr key={doc.documentId}>
                              <td>
                                <div className="pd-doc-name">
                                  <span className="pd-doc-icon" aria-hidden="true">PDF</span>
                                  <div><strong title={doc.fileName || 'Document'}>{doc.fileName || 'Document'}</strong><small>ID {doc.documentId.slice(0, 8)}</small></div>
                                </div>
                              </td>
                              <td>{doc.documentType || doc.mimeType}</td>
                              <td className="is-numeric">{formatBytes(doc.sizeBytes)}</td>
                              <td><StatusBadge value={doc.status} /></td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  ) : (
                    <EmptyState className="pd-empty">
                      <strong>No documents match “{documentQuery}”</strong>
                      <button className="ui-button tertiary" type="button" onClick={() => setDocumentQuery('')}>Clear filter</button>
                    </EmptyState>
                  )
                ) : (
                  <EmptyState className="pd-empty">
                    <Icon name="inbox" />
                    <strong>No documents attached yet</strong>
                    <span>{pkg.status === 'CREATED' ? 'Upload a PDF above to start this intake.' : 'This intake has no tracked documents.'}</span>
                  </EmptyState>
                )}
              </div>
            )}

            {activeTab === 'delivery' && (
              <div className="pd-panel">
                <div className="pd-panel-head"><div><h2>Delivery & retention</h2><p>Delivery outcomes and temporary internal-artifact retention windows.</p></div></div>
                {lifecycle ? (
                  <ol className="pd-timeline">
                    {[
                      { label: 'Intake created', at: pkg.createdAt, done: true },
                      { label: 'Last exported', at: lifecycle.lastExportedAt, done: Boolean(lifecycle.lastExportedAt), detail: lifecycle.lastDestinationType ? `to ${lifecycle.lastDestinationType}` : undefined },
                      ...(lifecycle.technicalHoldUntil ? [{ label: 'Technical hold ends', at: lifecycle.technicalHoldUntil, done: false, warn: true }] : []),
                      { label: 'Internal redacted copy deleted', at: lifecycle.redactedPurgedAt || lifecycle.redactedDeleteAt, done: Boolean(lifecycle.redactedPurgedAt) },
                      { label: 'Original deleted', at: lifecycle.originalPurgedAt || lifecycle.originalDeleteAt, done: Boolean(lifecycle.originalPurgedAt) },
                      ...(lifecycle.purgedAt ? [{ label: 'Internal artifacts purged', at: lifecycle.purgedAt, done: true }] : []),
                    ].map((event) => (
                      <li key={event.label} className={`${event.done ? 'is-done' : 'is-scheduled'}${'warn' in event && event.warn ? ' is-warning' : ''}`}>
                        <span className="pd-timeline-dot" aria-hidden="true">{event.done && <Icon name="check" />}</span>
                        <div>
                          <strong>{event.label}</strong>
                          <span>{event.at ? `${formatTime(event.at)}${!event.done ? ` · scheduled ${relativeDays(event.at)}` : ''}` : 'Not yet'}{'detail' in event && event.detail ? ` · ${event.detail}` : ''}</span>
                        </div>
                      </li>
                    ))}
                  </ol>
                ) : <EmptyState className="pd-empty"><strong>Lifecycle information is not available yet.</strong></EmptyState>}
              </div>
            )}

            {activeTab === 'routing' && (
              <div className="pd-panel">
                <div className="pd-panel-head">
                  <div><h2>Derived document routing</h2><p>Page-subset PDFs route independently from the full master delivery.</p></div>
                  {derivedDeliveries.length > 0 && (
                    <ul className="pd-routing-summary" aria-label="Routing summary">
                      <li><StatusBadge value="DELIVERED" label={`${routingSummary.delivered} delivered`} /></li>
                      {routingSummary.pending > 0 && <li><StatusBadge value="RETRY_PENDING" label={`${routingSummary.pending} pending`} /></li>}
                      {routingSummary.attention > 0 && <li><StatusBadge value="ATTENTION_REQUIRED" label={`${routingSummary.attention} need attention`} /></li>}
                    </ul>
                  )}
                </div>
                {derivedDeliveries.length > 0 ? (
                  <div className="pd-table-wrap">
                    <table className="pd-table">
                      <thead><tr><th scope="col">Destination</th><th scope="col">Marker</th><th scope="col">Pages</th><th scope="col" className="is-numeric">Attempts</th><th scope="col">Status</th><th scope="col"><span className="sr-only">Action</span></th></tr></thead>
                      <tbody>
                        {derivedDeliveries.map((delivery) => (
                          <tr key={delivery.derivedDeliveryId}>
                            <td><strong>{delivery.destinationLabel || 'Routing attention'}</strong>{delivery.failureCategory && <small className="pd-cell-note">{delivery.failureCategory}</small>}</td>
                            <td className="pd-mono">{delivery.markerValue || '—'}</td>
                            <td>{delivery.pageNumbers.length ? delivery.pageNumbers.join(', ') : '—'}</td>
                            <td className="is-numeric">{delivery.attemptCount}</td>
                            <td><StatusBadge value={delivery.status} /></td>
                            <td className="pd-row-action">
                              {delivery.status !== 'ATTENTION_REQUIRED' && (
                                <button className="ui-button secondary" type="button" disabled={sendingDeliveryId !== null}
                                  aria-label={`${delivery.status === 'DELIVERED' ? 'Resend' : 'Send'} to ${delivery.destinationLabel || 'destination'}`}
                                  onClick={() => void handleDerivedSend(delivery.derivedDeliveryId)}>
                                  {sendingDeliveryId === delivery.derivedDeliveryId ? 'Sending…' : delivery.status === 'DELIVERED' ? 'Resend' : 'Send'}
                                </button>
                              )}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <EmptyState className="pd-empty">
                    <strong>No derived-document deliveries</strong>
                    <span>Routing markers found during processing will create page-subset deliveries here.</span>
                  </EmptyState>
                )}
              </div>
            )}
          </section>
        </div>

        {lifecycle && (
          <aside className="ui-card pd-rail" aria-label="Lifecycle summary">
            <h2>Lifecycle</h2>
            <dl>
              <Fact label="Export cycles" value={`${lifecycle.successfulExportCycles} of ${lifecycle.maximumExportCycles}`}
                detail={<span className="pd-meter" aria-hidden="true"><i style={{ width: `${Math.min(100, (lifecycle.successfulExportCycles / Math.max(1, lifecycle.maximumExportCycles)) * 100)}%` }} /></span>} />
              <Fact label="Final approval" value={lifecycle.finalApprovalRequired ? (lifecycle.finalApprovalGranted ? 'Granted' : 'Required') : 'Not required'} detail="Tenant lifecycle policy" />
              <Fact label="Last destination" value={lifecycle.lastDestinationType || '—'} detail={lifecycle.lastExportedAt ? formatTime(lifecycle.lastExportedAt) : 'Not exported yet'} />
              <Fact label="Original retained" value={lifecycle.originalPurgedAt ? 'Purged' : relativeDays(lifecycle.originalDeleteAt) ? `Deletes ${relativeDays(lifecycle.originalDeleteAt)}` : '—'} detail={formatTime(lifecycle.originalPurgedAt || lifecycle.originalDeleteAt)} />
            </dl>
          </aside>
        )}
      </div>

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
