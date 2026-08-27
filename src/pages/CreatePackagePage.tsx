import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { apiClient } from '../api/client';
import type { ApiError } from '../types';

export default function CreatePackagePage() {
  const navigate = useNavigate();
  const [packageType, setPackageType] = useState('STANDARD');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [createdId, setCreatedId] = useState<string | null>(null);

  const handleCreate = async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await apiClient.createPackage({ packageType });
      setCreatedId(result.packageId);
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message || 'Failed to create package');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <h1>Create Package</h1>

      {error && <div className="error-msg">{error}</div>}
      {createdId && (
        <div className="success-msg">
          Package created: <strong>{createdId}</strong>{' '}
          <button className="secondary" onClick={() => navigate(`/packages/${createdId}`)}>
            View
          </button>
        </div>
      )}

      <div className="card">
        <div className="form-group">
          <label>Package Type</label>
          <select value={packageType} onChange={(e) => setPackageType(e.target.value)}>
            <option value="STANDARD">Standard</option>
            <option value="LEGAL">Legal</option>
            <option value="FINANCIAL">Financial</option>
            <option value="HEALTHCARE">Healthcare</option>
          </select>
        </div>

        <button className="primary" onClick={handleCreate} disabled={loading}>
          {loading ? 'Creating...' : 'Create Package'}
        </button>
      </div>
    </div>
  );
}
