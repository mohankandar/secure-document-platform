import { useEffect, useState } from 'react';
import { apiClient } from '../api/client';
import type { ProductTelemetryStatus } from '../types';

export default function ProductTelemetryPanel() {
  const [data, setData] = useState<ProductTelemetryStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      setData(await apiClient.getProductTelemetryStatus());
    } catch (err) {
      setError((err as { message?: string }).message || 'Unable to load product telemetry status.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  if (loading) return <section className="ui-card platform-section-layout"><p>Loading product telemetry…</p></section>;
  if (error) return <section className="ui-card platform-section-layout"><div className="ui-alert tone-danger">{error}</div></section>;
  if (!data) return null;

  return (
    <>
      <section className="ui-card platform-section-layout">
        <div className="platform-section-heading">
          <div>
            <h2>Privacy-safe product telemetry</h2>
            <p>Product-improvement events use an explicit allow-list and exclude customer document and business content.</p>
          </div>
          <button className="ui-button secondary" onClick={() => void load()}>Refresh</button>
        </div>
        <dl className="platform-definition-list">
          <div><dt>Mode</dt><dd>{data.mode}</dd></div>
          <div><dt>Emission</dt><dd>{data.enabled ? 'Enabled' : 'Disabled'}</dd></div>
          <div><dt>Installation ID</dt><dd>{data.installationIdConfigured ? 'Pseudonymous ID configured' : 'Not configured'}</dd></div>
          <div><dt>Privacy contract</dt><dd>{data.privacyContract.replace(/_/g, ' ')}</dd></div>
        </dl>
        <p className="platform-boundary-note">
          OFF emits nothing. BASIC emits pseudonymous installation/tenant operational outcomes. ENHANCED may also include a pseudonymous package identifier. No document content is permitted.
        </p>
      </section>

      <div className="platform-two-column">
        <section className="ui-card platform-section-layout platform-panel">
          <div className="platform-section-heading compact"><div><h2>Allowed events</h2><p>Only these product event families are emitted.</p></div></div>
          <ul>
            {data.eventAllowList.map((eventName) => <li key={eventName}><code>{eventName}</code></li>)}
          </ul>
        </section>
        <section className="ui-card platform-section-layout platform-panel">
          <div className="platform-section-heading compact"><div><h2>Explicitly prohibited</h2><p>These data classes must never enter the product telemetry stream.</p></div></div>
          <ul>
            {data.prohibitedData.map((item) => <li key={item}>{item}</li>)}
          </ul>
        </section>
      </div>
    </>
  );
}
