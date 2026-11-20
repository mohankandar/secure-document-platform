import useSWR from 'swr';
import { apiClient } from '../../api/client';
import type { ProductTelemetryStatus } from '../../types';
import { Notice } from '../atoms';
import { Loading } from './shared';
import { USAGE_MODES, usageMode, usageSummary } from './platformRules';
import { HelpTip } from './HelpTip';

/** Admin › Platform operations › Usage data (was Product telemetry; UI task U010, finding 28). Read-only. */
export default function TelemetrySection() {
  const { data, error, mutate, isValidating } = useSWR<ProductTelemetryStatus>('platform-telemetry', () => apiClient.getProductTelemetryStatus(), { revalidateOnFocus: false });
  if (!data) return <Loading error={error} retry={() => mutate()} label="Usage data" />;
  const mode = usageMode(data.mode);
  const summary = usageSummary(data);

  return (
    <section className="cf-ad-card" aria-labelledby="cf-te-head">
      <header className="cf-ad-card-head">
        <div>
          <div className="cf-ad-title"><h2 id="cf-te-head">Usage data</h2><HelpTip topic="telemetry" /></div>
          <p>Anonymous counts that help improve LexaVault. Never document content, case values or names.</p>
        </div>
        <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={isValidating} onClick={() => mutate()}>Refresh</button>
      </header>

      <div className="cf-ad-usage-mode">
        <div className="cf-segment" role="group" aria-label="Usage data mode (set at installation)">
          {USAGE_MODES.map((m) => <button key={m} type="button" aria-pressed={mode.word === m} disabled>{m}</button>)}
        </div>
        <span className="cf-ad-hint">Set at installation. {mode.known ? `${mode.word}: ${mode.note}` : mode.note}</span>
      </div>
      {!mode.known && <Notice tone="danger">The mode the server reported isn't one LexaVault knows.</Notice>}

      <div className="cf-ad-pair">
        <div className="cf-ad-block">
          <h3 className="cf-ad-block-title">What is sent</h3>
          <p className="cf-ad-rule-sentence">{summary.sends}</p>
        </div>
        <div className="cf-ad-block">
          <h3 className="cf-ad-block-title">Never sent</h3>
          <ul className="cf-ad-list">{summary.never.map((d) => <li key={d}>{d}</li>)}</ul>
        </div>
      </div>

      <details className="cf-disclosure">
        <summary>Technical details</summary>
        <dl className="cf-ad-facts cf-ad-facts-compact">
          <div><dt>Mode</dt><dd className="cf-ad-mono">{data.mode}</dd></div>
          <div><dt>Emission</dt><dd>{data.enabled ? 'Enabled' : 'Disabled'}</dd></div>
          <div><dt>Installation ID</dt><dd>{data.installationIdConfigured ? 'Pseudonymous ID configured' : 'Not configured'}</dd></div>
          <div><dt>Privacy contract</dt><dd className="cf-ad-mono">{data.privacyContract}</dd></div>
          <div className="cf-ad-span"><dt>Allowed events</dt><dd className="cf-ad-mono">{data.eventAllowList.join(', ')}</dd></div>
          <div className="cf-ad-span"><dt>Prohibited data</dt><dd>{data.prohibitedData.join('; ')}</dd></div>
        </dl>
      </details>
    </section>
  );
}
