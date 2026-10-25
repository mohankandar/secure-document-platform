import { useState } from 'react';
import useSWR from 'swr';
import { apiClient } from '../../api/client';
import type { PlatformAdminOverview } from '../../types';
import { errorMessage } from '../data';
import { Spinner } from '../atoms';
import { useToast } from '../toast';
import { Loading, humanize, when } from './shared';

export default function HealthArea() {
  const toast = useToast();
  const [checking, setChecking] = useState(false);
  const { data, error, mutate } = useSWR<PlatformAdminOverview>('platform-overview', () => apiClient.getPlatformAdminOverview(), { revalidateOnFocus: false });

  async function check() {
    setChecking(true);
    try {
      mutate(await apiClient.runPlatformHealthCheck(), { revalidate: false });
      toast('Health check finished.');
    } catch (e) {
      toast(errorMessage(e, 'The health check could not run.'), 'danger');
    } finally {
      setChecking(false);
    }
  }

  if (!data) return <Loading error={error} retry={() => mutate()} label="System health" />;
  const down = data.providers.filter((p) => p.status !== 'UP');

  return (
    <section className="cf-ad-card" aria-label="Dependencies">
      <header className="cf-ad-card-head">
        <div>
          <h2>{down.length ? `${down.length} ${down.length === 1 ? 'dependency needs' : 'dependencies need'} attention` : 'All dependencies responding'}</h2>
          <p>Checked {when(data.generatedAt)}</p>
        </div>
        <button type="button" className="cf-btn" data-size="sm" onClick={check} disabled={checking}>{checking && <Spinner label="Checking" />}Check now</button>
      </header>
      <ul className="cf-ad-conns">
        {data.providers.map((p) => (
          <li key={p.key} className="cf-ad-conn">
            <span className="cf-ad-health" data-h={p.status === 'UP' ? 'ok' : p.status === 'DOWN' ? 'danger' : 'warn'} aria-hidden="true" />
            <div className="cf-ad-conn-main">
              <div className="cf-ad-conn-name">{p.label}<span className="cf-ad-tag">{humanize(p.category)}</span></div>
              <div className="cf-ad-conn-meta"><span>{p.provider}</span><span className={p.status === 'UP' ? '' : 'cf-danger-text'}>{p.detail}</span></div>
            </div>
            <span className="cf-ad-status" data-h={p.status}>{humanize(p.status)}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}
