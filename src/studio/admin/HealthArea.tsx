import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import useSWR from 'swr';
import { apiClient } from '../../api/client';
import type { PlatformAdminOverview } from '../../types';
import { errorMessage } from '../data';
import { Spinner } from '../atoms';
import { useToast } from '../toast';
import { Loading, humanize, when } from './shared';
import StorageSection from './StorageSection';
import LicensingSection from './LicensingSection';
import BrandingSection from './BrandingSection';
import TelemetrySection from './TelemetrySection';

type Section = 'overview' | 'storage' | 'licensing' | 'branding' | 'telemetry';
const SECTIONS: [Section, string][] = [
  ['overview', 'Overview'],
  ['storage', 'Processing storage'],
  ['licensing', 'Licensing'],
  ['branding', 'Branding'],
  ['telemetry', 'Product telemetry'],
];

export default function HealthArea({ canOperate }: { canOperate: boolean }) {
  const [params, setParams] = useSearchParams();
  const requested = params.get('section');
  const section: Section = canOperate && SECTIONS.some(([k]) => k === requested) ? (requested as Section) : 'overview';

  return (
    <div className="cf-ad-stack">
      {canOperate && (
        <div className="cf-ad-subnav">
          <div className="cf-segment" role="group" aria-label="Platform operations sections">
            {SECTIONS.map(([key, label]) => (
              <button key={key} type="button" aria-pressed={section === key} onClick={() => setParams(key === 'overview' ? {} : { section: key }, { replace: true })}>
                {label}
              </button>
            ))}
          </div>
        </div>
      )}
      {section === 'overview' && <Overview />}
      {section === 'storage' && <StorageSection />}
      {section === 'licensing' && <LicensingSection />}
      {section === 'branding' && <BrandingSection />}
      {section === 'telemetry' && <TelemetrySection />}
    </div>
  );
}

function Overview() {
  const toast = useToast();
  const [checking, setChecking] = useState(false);
  const [scanning, setScanning] = useState(false);
  const { data, error, mutate, isValidating } = useSWR<PlatformAdminOverview>('platform-overview', () => apiClient.getPlatformAdminOverview(), { revalidateOnFocus: false });

  async function check() {
    setChecking(true);
    try {
      mutate(await apiClient.runPlatformHealthCheck(), { revalidate: false });
      toast('Health checks finished.');
    } catch (e) {
      toast(errorMessage(e, 'Health checks failed.'), 'danger');
    } finally {
      setChecking(false);
    }
  }

  async function scan() {
    setScanning(true);
    try {
      const r = await apiClient.scanPlatformWatchedFolder();
      await mutate();
      toast(`Scan finished: ${r.accepted} accepted, ${r.rejected} rejected, ${r.duplicates} duplicates.`);
    } catch (e) {
      toast(errorMessage(e, 'Watched-folder scan failed.'), 'danger');
    } finally {
      setScanning(false);
    }
  }

  if (!data) return <Loading error={error} retry={() => mutate()} label="Platform overview" />;
  const down = data.providers.filter((p) => p.status !== 'UP');
  const wf = data.watchedFolder;
  const rs = data.resilience;

  return (
    <>
      <section className="cf-ad-card" aria-labelledby="cf-pl-runtime">
        <header className="cf-ad-card-head">
          <div>
            <h2 id="cf-pl-runtime">{down.length ? `${down.length} ${down.length === 1 ? 'dependency needs' : 'dependencies need'} attention` : 'All dependencies responding'}</h2>
            <p>Connection status without exposing credentials, tokens or document content. Updated {when(data.generatedAt)}.</p>
          </div>
          <div className="cf-ad-inline-actions">
            <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" onClick={() => mutate()} disabled={isValidating || checking}>Refresh</button>
            <button type="button" className="cf-btn" data-size="sm" onClick={check} disabled={checking}>{checking && <Spinner label="Checking" />}Run health checks</button>
          </div>
        </header>
        <ul className="cf-ad-conns">
          {data.providers.map((p) => (
            <li key={p.key} className="cf-ad-conn">
              <span className="cf-ad-health" data-h={p.status === 'UP' ? 'ok' : p.status === 'DOWN' ? 'danger' : 'warn'} aria-hidden="true" />
              <div className="cf-ad-conn-main">
                <div className="cf-ad-conn-name">{p.label}<span className="cf-ad-tag">{humanize(p.category)}</span></div>
                <div className="cf-ad-conn-meta"><span>{p.provider || 'Not selected'}</span><span className={p.status === 'UP' ? '' : 'cf-danger-text'}>{p.detail}</span></div>
              </div>
              <span className="cf-ad-status" data-h={p.status}>{humanize(p.status)}</span>
            </li>
          ))}
        </ul>
      </section>

      <div className="cf-ad-pair">
        <section className="cf-ad-card" aria-labelledby="cf-pl-wf">
          <header className="cf-ad-card-head">
            <div>
              <h2 id="cf-pl-wf">Watched-folder intake</h2>
              <p>Folder scanning, trigger files and multi-node lease coordination.</p>
            </div>
            <button type="button" className="cf-btn" data-size="sm" onClick={scan} disabled={scanning || !wf.enabled}>{scanning && <Spinner label="Scanning" />}Scan now</button>
          </header>
          <dl className="cf-ad-defs">
            <div><dt>Connection</dt><dd>{wf.enabled ? 'Enabled' : 'Disabled'}</dd></div>
            <div><dt>Automatic scan</dt><dd>{wf.schedulingEnabled ? `Every ${Math.round(wf.scanIntervalMs / 1000)}s` : 'Disabled'}</dd></div>
            <div><dt>Coordination</dt><dd>{wf.coordinationEnabled ? 'Multi-node' : 'Single-node'}</dd></div>
            <div><dt>This node</dt><dd>{wf.nodeId || 'Not configured'} · {humanize(wf.scannerRole || 'UNKNOWN')}</dd></div>
            <div><dt>Lease owner</dt><dd>{wf.leaseOwner ? wf.leaseOwner.split('/')[0] : 'None'}</dd></div>
            <div><dt>Fencing generation</dt><dd className="cf-ad-mono">{wf.fencingToken ?? '—'}</dd></div>
            <div><dt>Lease expiry</dt><dd>{wf.leaseExpiresAt ? new Date(wf.leaseExpiresAt).toLocaleTimeString() : '—'}</dd></div>
            <div><dt>Takeovers</dt><dd>{wf.takeoverCount ?? 0}</dd></div>
            <div><dt>Trigger</dt><dd className="cf-ad-mono">{wf.triggerFileName}</dd></div>
            <div><dt>Incomplete visibility</dt><dd>{wf.incompleteVisibilityMinutes} min</dd></div>
            <div><dt>Accepted / rejected</dt><dd>{humanize(wf.acceptedBehavior)} / {humanize(wf.rejectedBehavior)}</dd></div>
            <div><dt>Last scan</dt><dd>{wf.lastScanAt ? when(wf.lastScanAt) : 'Not run in this process'}</dd></div>
            <div><dt>Outcome</dt><dd>{humanize(wf.lastScanOutcome)}</dd></div>
          </dl>
          {wf.lastScanResult && (
            <ul className="cf-ad-tally" aria-label="Last scan result">
              <li><strong>{wf.lastScanResult.discovered}</strong> discovered</li>
              <li><strong>{wf.lastScanResult.accepted}</strong> accepted</li>
              <li><strong>{wf.lastScanResult.rejected}</strong> rejected</li>
              <li><strong>{wf.lastScanResult.duplicates}</strong> duplicates</li>
            </ul>
          )}
        </section>

        <section className="cf-ad-card" aria-labelledby="cf-pl-res">
          <header className="cf-ad-card-head">
            <div>
              <h2 id="cf-pl-res">Resilience and integrations</h2>
              <p>Active deployment policy and safe configuration state.</p>
            </div>
          </header>
          <dl className="cf-ad-defs">
            <div><dt>Transient operation attempts</dt><dd>{rs.maxAttempts}</dd></div>
            <div><dt>Initial backoff</dt><dd>{rs.initialBackoffMs} ms</dd></div>
            <div><dt>Package processing attempts</dt><dd>{rs.packageProcessingMaxAttempts}</dd></div>
            <div><dt>AI failover</dt><dd>{rs.aiSecondaryEnabled ? `${rs.aiSecondaryProvider}${rs.aiSecondaryModel ? ` · ${rs.aiSecondaryModel}` : ''}` : 'Disabled'}</dd></div>
            <div><dt>Delivery</dt><dd>{data.integrations.deliveryType}</dd></div>
            <div><dt>SDV configured</dt><dd>{data.integrations.sdvConfigured ? 'Yes' : 'No'}</dd></div>
            <div><dt>Identity mode</dt><dd>{humanize(data.integrations.identityMode)}</dd></div>
          </dl>
          {!data.integrations.productionIdentityConfigured && (
            <p className="cf-ad-hint">Production OIDC identity is not active in this local/demo session.</p>
          )}
        </section>
      </div>

      <section className="cf-ad-card" aria-labelledby="cf-pl-inc">
        <header className="cf-ad-card-head">
          <div>
            <h2 id="cf-pl-inc">Technical incidents <span className="cf-ad-tag" data-tone={data.technicalExceptions.length ? 'danger' : undefined}>{data.technicalExceptions.length}</span></h2>
            <p>Recorded technical failures remain available for investigation even when business continues manually.</p>
          </div>
        </header>
        {data.technicalExceptions.length === 0 ? (
          <p className="cf-ad-hint">No recorded technical incidents for this tenant.</p>
        ) : (
          <ul className="cf-ad-conns">
            {data.technicalExceptions.map((item) => (
              <li key={item.packageId} className="cf-ad-conn">
                <span className="cf-ad-health" data-h="danger" aria-hidden="true" />
                <div className="cf-ad-conn-main">
                  <div className="cf-ad-conn-name">Technical processing failure<span className="cf-ad-tag">{humanize(item.reasonCode)}</span></div>
                  <div className="cf-ad-conn-meta">
                    <span>{item.documentCount} document{item.documentCount === 1 ? '' : 's'}</span>
                    <span>Failed {when(item.failedAt)}</span>
                    {item.technicalHoldUntil && <span>Technical hold until {when(item.technicalHoldUntil)}</span>}
                  </div>
                </div>
                <Link className="cf-btn" data-variant="ghost" data-size="sm" to={`/case/${item.packageId}`}>Open case</Link>
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  );
}
