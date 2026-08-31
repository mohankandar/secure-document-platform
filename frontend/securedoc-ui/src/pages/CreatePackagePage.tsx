import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { apiClient } from '../api/client';
import type { ApiError, IdentifierDefinition } from '../types';

export default function CreatePackagePage() {
  const navigate = useNavigate();
  const [definitions, setDefinitions] = useState<IdentifierDefinition[]>([]);
  const [values, setValues] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);
  const [loadingConfig, setLoadingConfig] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    apiClient.listIdentifierDefinitions()
      .then((defs) => setDefinitions([...defs].sort((a, b) => a.displayOrder - b.displayOrder)))
      .catch((err: ApiError) => setError(err.message || 'Unable to load intake configuration'))
      .finally(() => setLoadingConfig(false));
  }, []);

  const missingRequired = useMemo(
    () => definitions.some((d) => d.required && !(values[d.key] || '').trim()),
    [definitions, values]
  );

  const handleCreate = async () => {
    setLoading(true);
    setError(null);
    try {
      const businessIdentifiers = Object.fromEntries(
        definitions
          .map((d) => [d.key, (values[d.key] || '').trim()] as const)
          .filter(([, value]) => value.length > 0)
      );
      const result = await apiClient.createPackage({
        packageType: 'MANUAL_UPLOAD',
        businessIdentifiers,
      });
      navigate(`/packages/${result.packageId}`);
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message || 'Unable to start intake');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <h1>Upload Documents</h1>
      <p style={{ color: '#636e72', marginBottom: '1rem' }}>
        Enter the required business identifiers first. You can upload one or more PDFs on the next screen.
      </p>

      {error && <div className="error-msg">{error}</div>}

      <div className="card" style={{ maxWidth: 720 }}>
        {loadingConfig ? (
          <p>Loading intake fields...</p>
        ) : definitions.length === 0 ? (
          <p>No business identifier fields are configured for this tenant.</p>
        ) : (
          <>
            {definitions.map((definition) => (
              <div className="form-group" key={definition.key}>
                <label>
                  {definition.label}
                  {definition.required && <span aria-label="required"> *</span>}
                  {definition.primary && (
                    <span style={{ marginLeft: 8, fontSize: '0.72rem', color: '#636e72' }}>Primary</span>
                  )}
                </label>
                <input
                  value={values[definition.key] || ''}
                  onChange={(e) => setValues((current) => ({ ...current, [definition.key]: e.target.value }))}
                  required={definition.required}
                  autoComplete="off"
                />
              </div>
            ))}
            <button className="primary" onClick={handleCreate} disabled={loading || missingRequired}>
              {loading ? 'Starting...' : 'Continue to Documents'}
            </button>
          </>
        )}
      </div>
    </div>
  );
}
