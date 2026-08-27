import { useState, useEffect } from 'react';
import { apiClient } from '../api/client';
import type { AccessRequestResult, ApiError } from '../types';

export default function VaultPage() {
  const [output, setOutput] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [accessRequests, setAccessRequests] = useState<AccessRequestResult[]>([]);

  // Vault entry fields
  const [vPackageId, setVPackageId] = useState('');
  const [vDocumentId, setVDocumentId] = useState('');
  const [vArtifactId, setVArtifactId] = useState('');

  // Access request fields
  const [arVaultEntryId, setArVaultEntryId] = useState('');
  const [arArtifactId, setArArtifactId] = useState('');
  const [arJustification, setArJustification] = useState('');
  const [arRequestId, setArRequestId] = useState('');
  const [arReason, setArReason] = useState('');

  // Download fields
  const [dlDocumentId, setDlDocumentId] = useState('');

  const exec = async (label: string, fn: () => Promise<unknown>) => {
    setError(null);
    setOutput(null);
    try {
      const result = await fn();
      setOutput(`${label}: ${JSON.stringify(result, null, 2)}`);
    } catch (err) {
      const apiErr = err as ApiError;
      setError(`${label} failed: ${apiErr.message || 'Unknown error'}${apiErr.correlationId ? ` (${apiErr.correlationId})` : ''}`);
    }
  };

  const loadAccessRequests = async () => {
    try {
      const data = await apiClient.listAccessRequests();
      setAccessRequests(data);
    } catch {
      // silently fail on list
    }
  };

  useEffect(() => { loadAccessRequests(); }, []);

  const handleDownload = async (type: 'redacted' | 'original') => {
    setError(null);
    setOutput(null);
    try {
      const blob = type === 'redacted'
        ? await apiClient.downloadRedactedArtifact(dlDocumentId)
        : await apiClient.downloadOriginalArtifact(dlDocumentId);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `document-${dlDocumentId}-${type}.pdf`;
      a.click();
      URL.revokeObjectURL(url);
      setOutput(`Downloaded ${type} artifact for document ${dlDocumentId}`);
    } catch (err) {
      const apiErr = err as ApiError;
      setError(`Download failed: ${apiErr.message || 'Unknown error'}`);
    }
  };

  return (
    <div>
      <h1>Vault / Access</h1>

      {error && <div className="error-msg">{error}</div>}
      {output && <div className="success-msg" style={{ whiteSpace: 'pre-wrap', fontFamily: 'monospace', fontSize: '0.8rem' }}>{output}</div>}

      <div className="card">
        <h2 style={{ fontSize: '1rem', marginBottom: '0.75rem' }}>Register Vault Entry</h2>
        <div className="form-group">
          <label>Package ID</label>
          <input value={vPackageId} onChange={(e) => setVPackageId(e.target.value)} placeholder="UUID" />
        </div>
        <div className="form-group">
          <label>Document ID</label>
          <input value={vDocumentId} onChange={(e) => setVDocumentId(e.target.value)} placeholder="UUID" />
        </div>
        <div className="form-group">
          <label>Artifact ID</label>
          <input value={vArtifactId} onChange={(e) => setVArtifactId(e.target.value)} placeholder="UUID" />
        </div>
        <button className="primary" onClick={() => exec('Register', () => apiClient.registerVaultEntry(vPackageId, vDocumentId, vArtifactId))}>
          Register Vault Entry
        </button>
      </div>

      <div className="card">
        <h2 style={{ fontSize: '1rem', marginBottom: '0.75rem' }}>Request Original Access</h2>
        <div className="form-group">
          <label>Vault Entry ID</label>
          <input value={arVaultEntryId} onChange={(e) => setArVaultEntryId(e.target.value)} placeholder="UUID" />
        </div>
        <div className="form-group">
          <label>Artifact ID</label>
          <input value={arArtifactId} onChange={(e) => setArArtifactId(e.target.value)} placeholder="UUID" />
        </div>
        <div className="form-group">
          <label>Justification</label>
          <input value={arJustification} onChange={(e) => setArJustification(e.target.value)} placeholder="Required" />
        </div>
        <button className="primary" onClick={() => exec('Request Access', () => apiClient.requestArtifactAccess(arVaultEntryId, arArtifactId, arJustification))}>
          Request Access
        </button>
      </div>

      <div className="card">
        <h2 style={{ fontSize: '1rem', marginBottom: '0.75rem' }}>Approve / Deny Request</h2>
        <div className="form-group">
          <label>Request ID</label>
          <input value={arRequestId} onChange={(e) => setArRequestId(e.target.value)} placeholder="UUID" />
        </div>
        <div className="form-group">
          <label>Reason</label>
          <input value={arReason} onChange={(e) => setArReason(e.target.value)} placeholder="Required for deny" />
        </div>
        <div className="inline-actions">
          <button className="primary" onClick={() => { exec('Approve', () => apiClient.approveAccessRequest(arRequestId, arReason)); }}>Approve</button>
          <button className="secondary" onClick={() => { exec('Deny', () => apiClient.denyAccessRequest(arRequestId, arReason)); }}>Deny</button>
          <button className="secondary" onClick={loadAccessRequests}>Refresh List</button>
        </div>
      </div>

      {accessRequests.length > 0 && (
        <div className="card">
          <h2 style={{ fontSize: '1rem', marginBottom: '0.75rem' }}>Access Requests</h2>
          <table>
            <thead>
              <tr>
                <th>ID</th>
                <th>Status</th>
                <th>Artifact</th>
                <th>Requested</th>
                <th>Expires</th>
              </tr>
            </thead>
            <tbody>
              {accessRequests.map((req) => (
                <tr key={req.id}>
                  <td>{req.id.substring(0, 8)}...</td>
                  <td><span className={`badge badge-${req.status.toLowerCase()}`}>{req.status}</span></td>
                  <td>{req.artifactId.substring(0, 8)}...</td>
                  <td>{new Date(req.requestedAt).toLocaleString()}</td>
                  <td>{new Date(req.expiresAt).toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="card">
        <h2 style={{ fontSize: '1rem', marginBottom: '0.75rem' }}>Download Artifact</h2>
        <div className="form-group">
          <label>Document ID</label>
          <input value={dlDocumentId} onChange={(e) => setDlDocumentId(e.target.value)} placeholder="UUID" />
        </div>
        <div className="inline-actions">
          <button className="primary" onClick={() => handleDownload('redacted')}>Download Redacted</button>
          <button className="secondary" onClick={() => handleDownload('original')}>Download Original</button>
        </div>
      </div>
    </div>
  );
}
