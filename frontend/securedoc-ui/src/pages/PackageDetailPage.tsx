import { useEffect, useState, useRef } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { apiClient } from '../api/client';
import { hasCurrentCapability } from '../api/config';
import type { PackageDetail, PackageLifecycleSummary, ApiError, DerivedDelivery } from '../types';
import Breadcrumbs, { intakeBreadcrumbLabel } from '../components/Breadcrumbs';

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

  const handleUpload = async () => {
    const file = fileInputRef.current?.files?.[0];
    if (!file || !packageId) return;
    setSuccessMsg(null);
    setError(null);
    try {
      await apiClient.uploadDocument(packageId, file);
      setSuccessMsg('Document uploaded successfully.');
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
    if (!packageId) return;
    setError(null);
    setSuccessMsg(null);
    try {
      const result = await apiClient.reprocessPackage(packageId);
      setSuccessMsg(`Reprocessing started for export cycle ${result.nextExportCycle}.`);
      await loadPackage();
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message || 'Unable to reprocess this intake');
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

  if (loading && !pkg) {
    return (
      <div>
        <Breadcrumbs items={[{ label: 'Intake Details' }]} />
        <p>Loading...</p>
      </div>
    );
  }

  return (
    <div>
      <Breadcrumbs items={[{ label: intakeLabel }]} />
      <h1>Intake Details</h1>

      {error && <div className="error-msg">{error}</div>}
      {successMsg && <div className="success-msg">{successMsg}</div>}

      {pkg && (
        <>
          <div className="card">
            <table>
              <tbody>
                {pkg.businessIdentifiers?.filter((i) => i.displayable).map((identifier) => (
                  <tr key={identifier.key}><td><strong>{identifier.label}</strong></td><td>{identifier.value}</td></tr>
                ))}
                <tr><td><strong>Intake Source</strong></td><td>{pkg.packageType}</td></tr>
                <tr>
                  <td><strong>Status</strong></td>
                  <td><span className={`badge badge-${pkg.status.toLowerCase()}`}>{pkg.status}</span></td>
                </tr>
                <tr><td><strong>Documents</strong></td><td>{pkg.documentCount}</td></tr>
                <tr><td><strong>Created</strong></td><td>{new Date(pkg.createdAt).toLocaleString()}</td></tr>
              </tbody>
            </table>
          </div>

          {/* Upload & Submit — only when CREATED */}
          {pkg.status === 'CREATED' && (
            <div className="card">
              <h2 style={{ fontSize: '1.1rem', marginBottom: '0.5rem' }}>Upload Document</h2>
              <div className="form-group">
                <input type="file" ref={fileInputRef} accept=".pdf" />
              </div>
              <div className="inline-actions">
                <button className="primary" onClick={handleUpload}>Upload</button>
                <button className="primary" onClick={handleSubmit} disabled={pkg.documentCount === 0}>
                  Submit Documents
                </button>
              </div>
            </div>
          )}

          {(pkg.status === 'PENDING_REVIEW' || pkg.status === 'IN_REVIEW') && (
            <div className="card">
              <h2 style={{ fontSize: '1.1rem', marginBottom: '0.5rem' }}>
                {manualFallbackActive ? 'Manual Processing in Progress' : 'Manual Review Required'}
              </h2>
              <p style={{ fontSize: '0.85rem', color: '#636e72', marginBottom: '0.75rem' }}>
                {manualFallbackActive
                  ? 'Automatic analysis could not complete. Review every page and mark all sensitive areas before delivery.'
                  : 'One or more findings need a human decision. High-confidence findings do not need to be re-reviewed.'}
              </p>
              {hasCurrentCapability('MANUAL_REVIEW') ? (
                <Link className="button-link primary" to={manualFallbackActive
                  ? `/packages/${packageId}/manual-processing`
                  : `/review?packageId=${packageId}`}>
                  {manualFallbackActive ? 'Continue Manual Processing' : 'Open Manual Review'}
                </Link>
              ) : (
                <p style={{ fontSize: '0.85rem', color: '#636e72', margin: 0 }}>Waiting for an authorized reviewer.</p>
              )}
            </div>
          )}

          {pkg.status === 'PENDING_FINAL_APPROVAL' && (
            <div className="card">
              <h2 style={{ fontSize: '1.1rem', marginBottom: '0.5rem' }}>Ready for Final Delivery Approval</h2>
              <p style={{ fontSize: '0.85rem', color: '#636e72', marginBottom: '0.75rem' }}>
                Redaction and verification are complete. Tenant policy requires an Approver before the package is delivered.
              </p>
              {hasCurrentCapability('PACKAGE_APPROVE_DELIVERY') ? (
                <button className="primary" onClick={() => void handleApproveDelivery()}>Approve & Deliver</button>
              ) : (
                <p style={{ fontSize: '0.85rem', color: '#636e72', margin: 0 }}>Waiting for an authorized approver.</p>
              )}
            </div>
          )}

          {pkg.status === 'COMPLETED' && (
            <div className="card">
              <h2 style={{ fontSize: '1.1rem', marginBottom: '0.5rem' }}>Completed</h2>
              <p style={{ fontSize: '0.85rem', color: '#636e72', marginBottom: lifecycle?.canReprocess ? '0.75rem' : 0 }}>
                Processing, verification, and delivery completed successfully.
              </p>
              {lifecycle?.canReprocess && hasCurrentCapability('PACKAGE_REPROCESS') && (
                <button className="secondary" onClick={() => void handleReprocess()}>Reprocess from Retained Original</button>
              )}
            </div>
          )}

          {pkg.status === 'FAILED' && (
            <div className="card">
              <h2 style={{ fontSize: '1.1rem', marginBottom: '0.5rem' }}>Technical Attention</h2>
              <p style={{ fontSize: '0.85rem', color: '#636e72', marginBottom: '0.75rem' }}>
                Automatic processing stopped because of a technical issue. Business can continue manually when the PDF is still safe to render and redact.
              </p>
              <div className="inline-actions">
                {hasCurrentCapability('MANUAL_REVIEW') && (
                  <button className="primary" onClick={() => void handleManualFallback()}>Continue Manually</button>
                )}
                {hasCurrentCapability('TECHNICAL_EXCEPTION_VIEW') && (
                  <Link className="button-link secondary" to={`/technical-exceptions/${packageId}`}>View Technical Issue</Link>
                )}
              </div>
            </div>
          )}

          {lifecycle && (
            <div className="card">
              <h2 style={{ fontSize: '1.1rem', marginBottom: '0.5rem' }}>Delivery & Retention</h2>
              <table>
                <tbody>
                  <tr><td><strong>Successful exports</strong></td><td>{lifecycle.successfulExportCycles} of {lifecycle.maximumExportCycles}</td></tr>
                  <tr><td><strong>Last destination</strong></td><td>{lifecycle.lastDestinationType || '—'}</td></tr>
                  <tr><td><strong>Last exported</strong></td><td>{formatTime(lifecycle.lastExportedAt)}</td></tr>
                  <tr><td><strong>Original retained until</strong></td><td>{formatTime(lifecycle.originalDeleteAt)}</td></tr>
                  <tr><td><strong>Internal redacted copy until</strong></td><td>{formatTime(lifecycle.redactedDeleteAt)}</td></tr>
                  {lifecycle.technicalHoldUntil && (
                    <tr><td><strong>Technical hold until</strong></td><td>{formatTime(lifecycle.technicalHoldUntil)}</td></tr>
                  )}
                  {lifecycle.purgedAt && (
                    <tr><td><strong>Internal artifacts purged</strong></td><td>{formatTime(lifecycle.purgedAt)}</td></tr>
                  )}
                </tbody>
              </table>
            </div>
          )}

          {derivedDeliveries.length > 0 && (
            <div className="card">
              <h2 style={{ fontSize: '1.1rem', marginBottom: '0.5rem' }}>Derived Document Routing</h2>
              <p style={{ fontSize: '0.85rem', color: '#636e72' }}>These page-subset PDFs are separate from the full master export. A routing failure does not undo the master delivery.</p>
              <table>
                <thead><tr><th>Destination</th><th>Marker</th><th>Pages</th><th>Status</th><th>Action</th></tr></thead>
                <tbody>{derivedDeliveries.map((d) => <tr key={d.derivedDeliveryId}>
                  <td>{d.destinationLabel || 'Routing attention'}</td>
                  <td>{d.markerValue || d.failureCategory || '—'}</td>
                  <td>{d.pageNumbers.length ? d.pageNumbers.join(', ') : '—'}</td>
                  <td><span className={`badge badge-${d.status.toLowerCase()}`}>{d.status}</span></td>
                  <td>{d.status !== 'ATTENTION_REQUIRED' && <button className="secondary" onClick={() => void handleDerivedSend(d.derivedDeliveryId)}>{d.status === 'DELIVERED' ? 'Resend' : 'Send'}</button>}</td>
                </tr>)}</tbody>
              </table>
            </div>
          )}

          {/* Documents table */}
          {pkg.documents && pkg.documents.length > 0 && (
            <div className="card">
              <h2 style={{ fontSize: '1.1rem', marginBottom: '0.5rem' }}>Documents</h2>
              <table>
                <thead>
                  <tr>
                    <th>Document</th>
                    <th>Status</th>
                    <th>Type</th>
                    <th>MIME</th>
                    <th>Size</th>
                  </tr>
                </thead>
                <tbody>
                  {pkg.documents.map((doc) => (
                    <tr key={doc.documentId}>
                      <td>{doc.fileName || 'Document'}</td>
                      <td>
                        <span className={`badge badge-${doc.status.toLowerCase()}`}>
                          {doc.status}
                        </span>
                      </td>
                      <td>{doc.documentType || '-'}</td>
                      <td>{doc.mimeType}</td>
                      <td>{(doc.sizeBytes / 1024).toFixed(1)} KB</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <div className="inline-actions">
            <button className="secondary" onClick={loadPackage}>Refresh</button>
          </div>
        </>
      )}
    </div>
  );
}
