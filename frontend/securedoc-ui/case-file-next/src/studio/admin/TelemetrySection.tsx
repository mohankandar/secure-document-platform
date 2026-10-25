import useSWR from 'swr';
import { apiClient } from '../../api/client';
import type { ProductTelemetryStatus } from '../../types';
import { Loading, humanize } from './shared';

export default function TelemetrySection() {
  const { data, error, mutate, isValidating } = useSWR<ProductTelemetryStatus>('platform-telemetry', () => apiClient.getProductTelemetryStatus(), { revalidateOnFocus: false });
  if (!data) return <Loading error={error} retry={() => mutate()} label="Product telemetry" />;

  return (
    <>
      <section className="cf-ad-card" aria-labelledby="cf-te-head">
        <header className="cf-ad-card-head">
          <div>
            <h2 id="cf-te-head">Privacy-safe product telemetry</h2>
            <p>Product-improvement events use an explicit allow-list and exclude customer document and business content.</p>
          </div>
          <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={isValidating} onClick={() => mutate()}>Refresh</button>
        </header>
        <dl className="cf-ad-defs">
          <div><dt>Mode</dt><dd>{data.mode}</dd></div>
          <div><dt>Emission</dt><dd>{data.enabled ? 'Enabled' : 'Disabled'}</dd></div>
          <div><dt>Installation ID</dt><dd>{data.installationIdConfigured ? 'Pseudonymous ID configured' : 'Not configured'}</dd></div>
          <div><dt>Privacy contract</dt><dd className="cf-ad-mono">{data.privacyContract}</dd></div>
        </dl>
        <p className="cf-ad-hint">OFF emits nothing. BASIC emits pseudonymous installation/tenant operational outcomes. ENHANCED may also include a pseudonymous package identifier. No document content is permitted.</p>
      </section>

      <div className="cf-ad-pair">
        <section className="cf-ad-card" aria-labelledby="cf-te-allow">
          <header className="cf-ad-card-head"><div><h2 id="cf-te-allow">Allowed events</h2><p>Only these product event families are emitted.</p></div></header>
          <ul className="cf-ad-list">{data.eventAllowList.map((e) => <li key={e}><code className="cf-ad-mono">{e}</code></li>)}</ul>
        </section>
        <section className="cf-ad-card" aria-labelledby="cf-te-deny">
          <header className="cf-ad-card-head"><div><h2 id="cf-te-deny">Explicitly prohibited</h2><p>These data classes must never enter the product telemetry stream.</p></div></header>
          <ul className="cf-ad-list">{data.prohibitedData.map((d) => <li key={d}>{d.includes('_') ? humanize(d) : d}</li>)}</ul>
        </section>
      </div>
    </>
  );
}
