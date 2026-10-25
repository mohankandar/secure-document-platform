import { useState } from 'react';
import useSWR from 'swr';
import { apiClient } from '../../api/client';
import type { IntegrationConnection } from '../../types';
import { errorMessage } from '../data';
import { Spinner } from '../atoms';
import { useToast } from '../toast';
import { Loading, humanize, when } from './shared';

const GROUPS: { purpose: IntegrationConnection['purpose']; title: string; note: string }[] = [
  { purpose: 'INGESTION_SOURCE', title: 'Intake sources', note: 'Where cases come from' },
  { purpose: 'SDV_DELIVERY', title: 'Delivery destinations', note: 'Where redacted cases are sent' },
  { purpose: 'DERIVED_DOCUMENT_DELIVERY', title: 'Derived documents', note: 'Extracted outputs sent downstream' },
];

export default function IntegrationsArea() {
  const { data, error, mutate } = useSWR<IntegrationConnection[]>('integrations', () => apiClient.listIntegrationConnections(), { revalidateOnFocus: false });
  if (!data) return <Loading error={error} retry={() => mutate()} label="Integrations" />;

  return (
    <div className="cf-ad-stack">
      {GROUPS.map((g) => {
        const items = data.filter((c) => c.purpose === g.purpose);
        if (!items.length) return null;
        return (
          <section key={g.purpose} className="cf-ad-card" aria-label={g.title}>
            <header className="cf-ad-card-head"><div><h2>{g.title}</h2><p>{g.note}</p></div></header>
            <ul className="cf-ad-conns">
              {items.map((c) => <Connection key={c.connectionId} c={c} onChanged={() => mutate()} />)}
            </ul>
          </section>
        );
      })}
    </div>
  );
}

function Connection({ c, onChanged }: { c: IntegrationConnection; onChanged: () => void }) {
  const toast = useToast();
  const [busy, setBusy] = useState<null | 'test' | 'poll'>(null);
  const polls = c.purpose === 'INGESTION_SOURCE' && !!c.pollingScheduleType;
  const testable = c.purpose !== 'INGESTION_SOURCE';
  const health = !c.enabled ? 'off' : c.healthStatus === 'HEALTHY' ? 'ok' : 'warn';

  async function run(kind: 'test' | 'poll') {
    setBusy(kind);
    try {
      if (kind === 'poll') {
        const r = await apiClient.runPollingNow(c.connectionId);
        toast(`Poll ${r.status.toLowerCase()}: ${r.packagesDiscovered} case${r.packagesDiscovered === 1 ? '' : 's'} found.`, r.status === 'SUCCESS' ? undefined : 'danger');
      } else {
        const r = c.purpose === 'SDV_DELIVERY' ? await apiClient.testSdvConnection(c.connectionId) : await apiClient.testDerivedConnection(c.connectionId);
        toast(r.healthStatus === 'HEALTHY' ? `${c.name} responded.` : `${c.name} is ${humanize(r.healthStatus).toLowerCase()}.`, r.healthStatus === 'HEALTHY' ? undefined : 'danger');
      }
      onChanged();
    } catch (e) {
      toast(errorMessage(e, 'The check could not run.'), 'danger');
    } finally {
      setBusy(null);
    }
  }

  return (
    <li className="cf-ad-conn">
      <span className="cf-ad-health" data-h={health} aria-hidden="true" />
      <div className="cf-ad-conn-main">
        <div className="cf-ad-conn-name">
          {c.name}
          <span className="cf-ad-tag">{humanize(c.providerType)}</span>
          {!c.enabled && <span className="cf-ad-tag">Disabled</span>}
        </div>
        <div className="cf-ad-conn-meta">
          <span>{c.enabled ? humanize(c.healthStatus) : 'Not running'}</span>
          <span>Last success {when(c.lastSuccessAt)}</span>
          {c.lastFailureAt && <span className="cf-danger-text">Failed {when(c.lastFailureAt)}{c.lastFailureCategory ? ` · ${humanize(c.lastFailureCategory)}` : ''}</span>}
          {polls && <span>{c.pollingScheduleType === 'INTERVAL' ? `Every ${c.pollingIntervalMinutes} min` : `Daily at ${c.pollingLocalTime}`}</span>}
          {!c.secretConfigured && c.authenticationType !== 'NONE' && <span className="cf-danger-text">Credential missing</span>}
        </div>
        {c.endpoint && <div className="cf-ad-conn-endpoint cf-mono">{c.endpoint}</div>}
      </div>
      <div className="cf-ad-conn-actions">
        {testable && <button type="button" className="cf-btn" data-size="sm" disabled={!c.enabled || !!busy} onClick={() => run('test')}>{busy === 'test' && <Spinner label="Testing" />}Test</button>}
        {polls && <button type="button" className="cf-btn" data-size="sm" disabled={!c.enabled || !!busy} onClick={() => run('poll')}>{busy === 'poll' && <Spinner label="Polling" />}Poll now</button>}
      </div>
    </li>
  );
}
