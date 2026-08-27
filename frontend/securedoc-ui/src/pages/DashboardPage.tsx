import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { apiClient } from '../api/client';
import type { PackageSummary, ApiError } from '../types';

export default function DashboardPage() {
  const [packages, setPackages] = useState<PackageSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadPackages = async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await apiClient.listPackages();
      setPackages(data);
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message || 'Failed to load packages');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadPackages(); }, []);

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h1>Packages</h1>
        <div className="inline-actions">
          <button className="secondary" onClick={loadPackages} disabled={loading}>
            Refresh
          </button>
          <Link to="/packages/new">
            <button className="primary">Create Package</button>
          </Link>
        </div>
      </div>

      {error && <div className="error-msg">{error}</div>}

      <div className="card">
        {loading && packages.length === 0 ? (
          <p>Loading...</p>
        ) : packages.length === 0 ? (
          <p>No packages found. Create one to get started.</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Package ID</th>
                <th>Type</th>
                <th>Status</th>
                <th>Documents</th>
                <th>Created</th>
              </tr>
            </thead>
            <tbody>
              {packages.map((pkg) => (
                <tr key={pkg.packageId}>
                  <td>
                    <Link to={`/packages/${pkg.packageId}`}>
                      {pkg.packageId.substring(0, 8)}...
                    </Link>
                  </td>
                  <td>{pkg.packageType}</td>
                  <td>
                    <span className={`badge badge-${pkg.status.toLowerCase()}`}>
                      {pkg.status}
                    </span>
                  </td>
                  <td>{pkg.documentCount}</td>
                  <td>{new Date(pkg.createdAt).toLocaleDateString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
