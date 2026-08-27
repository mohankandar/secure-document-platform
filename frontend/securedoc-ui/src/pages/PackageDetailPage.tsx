import { useEffect, useState, useRef } from 'react';
import { useParams } from 'react-router-dom';
import { apiClient } from '../api/client';
import type { PackageDetail, ApiError } from '../types';

export default function PackageDetailPage() {
  const { packageId } = useParams<{ packageId: string }>();
  const [pkg, setPkg] = useState<PackageDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const loadPackage = async () => {
    if (!packageId) return;
    setLoading(true);
    setError(null);
    try {
      const data = await apiClient.getPackage(packageId);
      setPkg(data);
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
      const result = await apiClient.uploadDocument(packageId, file);
      setSuccessMsg(`Document uploaded: ${result.documentId}`);
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
      setSuccessMsg('Package submitted for processing');
      loadPackage();
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message || 'Submit failed');
    }
  };

  const handleProcess = async () => {
    if (!packageId) return;
    setError(null);
    setSuccessMsg(null);
    try {
      await apiClient.triggerProcessing(packageId);
      setSuccessMsg('Processing triggered. Refresh to see updated status.');
      loadPackage();
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message || 'Processing trigger failed');
    }
  };

  const handleFinalize = async () => {
    if (!packageId) return;
    setError(null);
    setSuccessMsg(null);
    try {
      const result = await apiClient.finalizePackage(packageId);
      setSuccessMsg(`Package finalized: ${result.status}`);
      loadPackage();
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message || 'Finalize failed');
    }
  };

  if (loading && !pkg) return <p>Loading...</p>;

  return (
    <div>
      <h1>Package Detail</h1>

      {error && <div className="error-msg">{error}</div>}
      {successMsg && <div className="success-msg">{successMsg}</div>}

      {pkg && (
        <>
          <div className="card">
            <table>
              <tbody>
                <tr><td><strong>ID</strong></td><td>{pkg.packageId}</td></tr>
                <tr><td><strong>Type</strong></td><td>{pkg.packageType}</td></tr>
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
                  Submit Package
                </button>
              </div>
            </div>
          )}

          {/* Process trigger — when SUBMITTED or continue redaction */}
          {(pkg.status === 'SUBMITTED' || pkg.status === 'PENDING_REVIEW' || pkg.status === 'IN_REVIEW') && (
            <div className="card">
              <h2 style={{ fontSize: '1.1rem', marginBottom: '0.5rem' }}>Processing</h2>
              <p style={{ fontSize: '0.85rem', color: '#636e72', marginBottom: '0.5rem' }}>
                {pkg.status === 'SUBMITTED'
                  ? 'Trigger document processing (extract text, detect sensitive data). Documents needing redaction will wait for bounding boxes from review.'
                  : 'Continue redaction for documents where findings have been confirmed and bounding boxes provided during review.'}
              </p>
              <button className="primary" onClick={handleProcess}>
                {pkg.status === 'SUBMITTED' ? 'Trigger Processing' : 'Continue Processing (Redact)'}
              </button>
            </div>
          )}

          {/* Finalize — when PENDING_REVIEW or IN_REVIEW */}
          {(pkg.status === 'PENDING_REVIEW' || pkg.status === 'IN_REVIEW') && (
            <div className="card">
              <h2 style={{ fontSize: '1.1rem', marginBottom: '0.5rem' }}>Finalize Package</h2>
              <p style={{ fontSize: '0.85rem', color: '#636e72', marginBottom: '0.5rem' }}>
                Verify all documents are processed and all findings resolved, then approve for vault.
              </p>
              <button className="primary" onClick={handleFinalize}>Finalize &amp; Approve</button>
            </div>
          )}

          {/* Documents table */}
          {pkg.documents && pkg.documents.length > 0 && (
            <div className="card">
              <h2 style={{ fontSize: '1.1rem', marginBottom: '0.5rem' }}>Documents</h2>
              <table>
                <thead>
                  <tr>
                    <th>Document ID</th>
                    <th>Status</th>
                    <th>Type</th>
                    <th>MIME</th>
                    <th>Size</th>
                  </tr>
                </thead>
                <tbody>
                  {pkg.documents.map((doc) => (
                    <tr key={doc.documentId}>
                      <td>{doc.documentId.substring(0, 8)}...</td>
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
