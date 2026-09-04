import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { apiClient } from '../api/client';
import type { PlatformAdminOverview, PlatformHealthItem } from '../types';
import Breadcrumbs from '../components/Breadcrumbs';

function statusClass(status: string) {
  const normalized = status.toLowerCase().replace(/[^a-z0-9]+/g, '-');
  return `platform-status platform-status-${normalized}`;
}

function ProviderCard({ item }: { item: PlatformHealthItem }) {
  return (
    <div className="platform-provider-card">
      <div className="platform-provider-top">
        <div>
          <div className="platform-provider-category">{item.category}</div>
          <h3>{item.label}</h3>
        </div>
        <span className={statusClass(item.status)}>{item.status.replace(/_/g, ' ')}</span>
      </div>
      <div className="platform-provider-meta">
        <span>Provider</span><strong>{item.provider || 'Not selected'}</strong>
      </div>
      <p>{item.detail}</p>
    </div>
  );
}

export default function PlatformAdminPage() {
  const [data, setData] = useState<PlatformAdminOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [runningHealth, setRunningHealth] = useState(false);
  const [scanning, setScanning] = useState(false);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    try {
      setError('');
      setData(await apiClient.getPlatformAdminOverview());
    } catch (err) {
      setError((err as { message?: string }).message || 'Unable to load platform administration');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const runHealth = async () => {
    setRunningHealth(true);
    try {
      setError('');
      setData(await apiClient.runPlatformHealthCheck());
    } catch (err) {
      setError((err as { message?: string }).message || 'Health checks failed');
    } finally {
      setRunningHealth(false);
    }
  };

  const scan = async () => {
    setScanning(true);
    try {
      setError('');
      await apiClient.scanPlatformWatchedFolder();
      await load();
    } catch (err) {
      setError((err as { message?: string }).message || 'Watched-folder scan failed');
    } finally {
      setScanning(false);
    }
  };

  if (loading) {
    return (
      <div className="platform-admin-page">
        <Breadcrumbs items={[{ label: 'Administration' }, { label: 'Technical Administration' }]} />
        <p>Loading IT / Platform Admin…</p>
      </div>
    );
  }

  return (
    <div className="platform-admin-page">
      <Breadcrumbs items={[{ label: 'Administration' }, { label: 'Technical Administration' }]} />
      <div className="platform-admin-header">
        <div>
          <div className="platform-eyebrow">Administration</div>
          <h1>Technical Administration</h1>
          <p>Technical providers, intake connections, resilience, integrations and operational health.</p>
        </div>
        <div className="platform-header-actions">
          <button className="secondary" onClick={() => void load()}>Refresh</button>
          <button className="primary" disabled={runningHealth} onClick={() => void runHealth()}>
            {runningHealth ? 'Checking…' : 'Run health checks'}
          </button>
        </div>
      </div>

      {error && <div className="platform-error">{error}</div>}

      {data && (
        <>
          <section className="platform-section">
            <div className="platform-section-heading">
              <div><h2>Runtime health</h2><p>Connection status without exposing credentials, tokens or document content.</p></div>
              <span className="platform-generated">Updated {new Date(data.generatedAt).toLocaleString()}</span>
            </div>
            <div className="platform-provider-grid">
              {data.providers.map((item) => <ProviderCard key={item.key} item={item} />)}
            </div>
          </section>

          <div className="platform-two-column">
            <section className="platform-section platform-panel">
              <div className="platform-section-heading compact">
                <div><h2>Watched-folder intake</h2><p>Unit 20 intake semantics with Unit 24 operational scheduling.</p></div>
                <button className="secondary" disabled={scanning || !data.watchedFolder.enabled} onClick={() => void scan()}>
                  {scanning ? 'Scanning…' : 'Scan now'}
                </button>
              </div>
              <dl className="platform-definition-list">
                <div><dt>Connection</dt><dd>{data.watchedFolder.enabled ? 'Enabled' : 'Disabled'}</dd></div>
                <div><dt>Automatic scan</dt><dd>{data.watchedFolder.schedulingEnabled ? `Every ${Math.round(data.watchedFolder.scanIntervalMs / 1000)}s` : 'Disabled'}</dd></div>
                <div><dt>Trigger</dt><dd>{data.watchedFolder.triggerFileName}</dd></div>
                <div><dt>Incomplete visibility</dt><dd>{data.watchedFolder.incompleteVisibilityMinutes} min</dd></div>
                <div><dt>Accepted / rejected</dt><dd>{data.watchedFolder.acceptedBehavior} / {data.watchedFolder.rejectedBehavior}</dd></div>
                <div><dt>Last scan</dt><dd>{data.watchedFolder.lastScanAt ? new Date(data.watchedFolder.lastScanAt).toLocaleString() : 'Not run in this process'}</dd></div>
                <div><dt>Outcome</dt><dd>{data.watchedFolder.lastScanOutcome.replace(/_/g, ' ')}</dd></div>
              </dl>
              {data.watchedFolder.lastScanResult && (
                <div className="platform-scan-summary">
                  <span>{data.watchedFolder.lastScanResult.discovered} discovered</span>
                  <span>{data.watchedFolder.lastScanResult.accepted} accepted</span>
                  <span>{data.watchedFolder.lastScanResult.rejected} rejected</span>
                  <span>{data.watchedFolder.lastScanResult.duplicates} duplicates</span>
                </div>
              )}
            </section>

            <section className="platform-section platform-panel">
              <div className="platform-section-heading compact"><div><h2>Resilience & integrations</h2><p>Active deployment policy and safe configuration state.</p></div></div>
              <dl className="platform-definition-list">
                <div><dt>Retry attempts</dt><dd>{data.resilience.maxAttempts}</dd></div>
                <div><dt>Initial backoff</dt><dd>{data.resilience.initialBackoffMs} ms</dd></div>
                <div><dt>AI failover</dt><dd>{data.resilience.aiSecondaryEnabled ? `${data.resilience.aiSecondaryProvider}${data.resilience.aiSecondaryModel ? ` · ${data.resilience.aiSecondaryModel}` : ''}` : 'Disabled'}</dd></div>
                <div><dt>Delivery</dt><dd>{data.integrations.deliveryType}</dd></div>
                <div><dt>SDV configured</dt><dd>{data.integrations.sdvConfigured ? 'Yes' : 'No'}</dd></div>
                <div><dt>Identity mode</dt><dd>{data.integrations.identityMode.replace(/_/g, ' ')}</dd></div>
              </dl>
              {!data.integrations.productionIdentityConfigured && (
                <p className="platform-boundary-note">Production OIDC identity is not active in this local/demo session.</p>
              )}
            </section>
          </div>

          <section className="platform-section">
            <div className="platform-section-heading">
              <div><h2>Technical Exceptions</h2><p>Unresolved deterministic/transient failures stay separate from business Manual Review.</p></div>
              <span className="platform-count">{data.technicalExceptions.length}</span>
            </div>
            {data.technicalExceptions.length === 0 ? (
              <div className="platform-empty">No unresolved technical exceptions for this tenant.</div>
            ) : (
              <div className="platform-exception-list">
                {data.technicalExceptions.map((item) => (
                  <div className="platform-exception-row" key={item.packageId}>
                    <div>
                      <strong>Technical processing failure</strong>
                      <div className="platform-exception-meta">{item.documentCount} document{item.documentCount === 1 ? '' : 's'} · {new Date(item.failedAt).toLocaleString()}</div>
                      {item.technicalHoldUntil && <div className="platform-exception-meta">Technical hold until {new Date(item.technicalHoldUntil).toLocaleString()}</div>}
                    </div>
                    <div className="platform-exception-actions">
                      <span className="platform-status platform-status-unhealthy">Attention</span>
                      <Link to={`/technical-exceptions/${item.packageId}`}>View issue details</Link>
                      <Link to={`/packages/${item.packageId}`}>Open intake</Link>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </section>
        </>
      )}
    </div>
  );
}
