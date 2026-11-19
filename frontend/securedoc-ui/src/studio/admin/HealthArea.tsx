import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
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
import { watchedFolderStatus } from './integrationRules';
import { SECTION_LABELS, intervalText, serviceName, serviceProduct, serviceStatus, servicesTitle } from './platformRules';

type Section = 'overview' | 'storage' | 'licensing' | 'branding' | 'telemetry';
const SECTIONS = (Object.keys(SECTION_LABELS) as Section[]).map((k) => [k, SECTION_LABELS[k]] as const);

export default function HealthArea({ canOperate }: { canOperate: boolean }) {
  const [params, setParams] = useSearchParams();
  const requested = params.get('section');
  const section: Section = canOperate && SECTIONS.some(([k]) => k === requested) ? (requested as Section) : 'overview';

  return (
    <div className="cf-ad-stack">
      {canOperate && (
        <div className="cf-ad-subnav">
          <div className="cf-ad-subtabs" role="group" aria-label="Platform operations sections">
            {SECTIONS.map(([key, label]) => (
              <button key={key} type="button" aria-pressed={section === key} onClick={() => setParams(key === 'overview' ? {} : { section: key }, { replace: true })}>
                {label}
              </button>
            ))}
          </div>
        </div>
      )}
      {section === 'overview' && <Overview canOperate={canOperate} />}
      {section === 'storage' && <StorageSection />}
      {section === 'licensing' && <LicensingSection />}
      {section === 'branding' && <BrandingSection />}
      {section === 'telemetry' && <TelemetrySection />}
    </div>
  );
}

function Overview({ canOperate }: { canOperate: boolean }) {
  const toast = useToast();
  const [checking, setChecking] = useState(false);
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

  if (!data) return <Loading error={error} retry={() => mutate()} label="Platform overview" />;
  const now = Date.now();
  const wf = data.watchedFolder;
  const rs = data.resilience;
  const folder = watchedFolderStatus(wf, now);
  const unchecked = data.providers.some((p) => serviceStatus(p.status).tone === 'neutral');

  return (
    <>
      <section className="cf-ad-card" aria-labelledby="cf-pl-runtime">
        <header className="cf-ad-card-head">
          <div>
            <h2 id="cf-pl-runtime">{servicesTitle(data.providers.map((p) => p.status))}</h2>
            <p>Updated {when(data.generatedAt)}. No passwords, keys or document content are shown.{unchecked && canOperate ? ' Run health checks to check every service now.' : ''}</p>
          </div>
          <div className="cf-ad-inline-actions">
            <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" onClick={() => mutate()} disabled={isValidating || checking}>Refresh</button>
            {canOperate && <button type="button" className="cf-btn" data-size="sm" onClick={check} disabled={checking}>{checking && <Spinner label="Checking" />}Run health checks</button>}
          </div>
        </header>
        <ul className="cf-ad-services">
          {data.providers.map((p) => {
            const s = serviceStatus(p.status);
            const product = serviceProduct(p);
            return (
              <li key={p.key} className="cf-ad-service">
                <span className="cf-ad-health" data-h={s.tone} aria-hidden="true" />
                <span className="cf-ad-service-name">{serviceName(p)}</span>
                {product && <span className="cf-ad-service-product">{product}</span>}
                <span className="cf-ad-status" data-tone={s.tone}>{[s.word, ...s.rest].join(' · ')}</span>
              </li>
            );
          })}
        </ul>
        {canOperate && (
          <details className="cf-disclosure">
            <summary>Technical details</summary>
            <div className="cf-ad-table-wrap">
              <table className="cf-ad-table cf-ad-table-left">
                <thead><tr><th scope="col">Check</th><th scope="col">Provider</th><th scope="col">Status</th><th scope="col">Detail</th></tr></thead>
                <tbody>
                  {data.providers.map((p) => (
                    <tr key={p.key}>
                      <th scope="row" className="cf-ad-mono">{p.key}</th>
                      <td className="cf-ad-mono">{p.provider || '—'}</td>
                      <td className="cf-ad-mono">{p.status}</td>
                      <td>{p.detail}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </details>
        )}
      </section>

      <section className="cf-ad-card" aria-labelledby="cf-pl-wf">
        <header className="cf-ad-card-head">
          <div>
            <h2 id="cf-pl-wf">Watched folder</h2>
            {wf.rootPath && <p>{wf.rootPath}{wf.enabled && wf.schedulingEnabled ? ` · checked ${intervalText(wf.scanIntervalMs)}` : ''}</p>}
          </div>
          {/* Canvas .st2: the folder's state as a soft pill at the top right; the counts go in the line below. */}
          <span className="cf-ad-pill" data-tone={folder.tone}><span className="cf-ad-health" data-h={folder.tone} aria-hidden="true" />{[folder.word, ...folder.rest.slice(0, 1)].join(' · ')}</span>
        </header>
        <p className="cf-ad-hint">{folder.rest.length > 1 && `${folder.rest.slice(1).join(' · ')}. `}Scan now and the folder's settings are on the Integrations tab, for the IT Administrator.</p>
        {canOperate && (
          <details className="cf-disclosure">
            <summary>Technical details</summary>
            <dl className="cf-ad-facts cf-ad-facts-compact">
              <div><dt>Coordination</dt><dd>{wf.coordinationEnabled ? 'Multi-node' : 'Single-node'}</dd></div>
              <div><dt>This node</dt><dd className="cf-ad-mono">{wf.nodeId || 'Not configured'} · {wf.scannerRole || 'UNKNOWN'}</dd></div>
              <div><dt>Lease owner</dt><dd className="cf-ad-mono">{wf.leaseOwner ? wf.leaseOwner.split('/')[0] : 'None'}</dd></div>
              <div><dt>Fencing generation</dt><dd className="cf-ad-mono">{wf.fencingToken ?? '—'}</dd></div>
              <div><dt>Lease expiry</dt><dd>{wf.leaseExpiresAt ? new Date(wf.leaseExpiresAt).toLocaleTimeString() : '—'}</dd></div>
              <div><dt>Takeovers</dt><dd>{wf.takeoverCount ?? 0}</dd></div>
              <div><dt>Ready file</dt><dd className="cf-ad-mono">{wf.triggerFileName}</dd></div>
              <div><dt>Incomplete visibility</dt><dd>{wf.incompleteVisibilityMinutes} min</dd></div>
              <div><dt>Accepted / rejected</dt><dd className="cf-ad-mono">{wf.acceptedBehavior} / {wf.rejectedBehavior}</dd></div>
              <div><dt>Last outcome</dt><dd className="cf-ad-mono">{wf.lastScanOutcome}</dd></div>
              {wf.lastScanResult && <div><dt>Last scan</dt><dd>{wf.lastScanResult.discovered} found · {wf.lastScanResult.accepted} accepted · {wf.lastScanResult.rejected} rejected · {wf.lastScanResult.duplicates} duplicates</dd></div>}
              <div><dt>Retry attempts</dt><dd>{rs.maxAttempts} · first wait {rs.initialBackoffMs} ms</dd></div>
              <div><dt>Processing attempts</dt><dd>{rs.packageProcessingMaxAttempts}</dd></div>
              <div><dt>AI failover</dt><dd className="cf-ad-mono">{rs.aiSecondaryEnabled ? `${rs.aiSecondaryProvider}${rs.aiSecondaryModel ? ` · ${rs.aiSecondaryModel}` : ''}` : 'Disabled'}</dd></div>
              <div><dt>Delivery</dt><dd className="cf-ad-mono">{data.integrations.deliveryType} · SDV {data.integrations.sdvConfigured ? 'configured' : 'not configured'}</dd></div>
              <div><dt>Identity mode</dt><dd className="cf-ad-mono">{data.integrations.identityMode}{data.integrations.productionIdentityConfigured ? '' : ' · local/demo'}</dd></div>
            </dl>
          </details>
        )}
      </section>

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
                <span className="cf-ad-tag">Technical monitoring only</span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  );
}
