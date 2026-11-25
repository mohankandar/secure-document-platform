import { useState } from 'react';
import useSWR from 'swr';
import { apiClient } from '../../api/client';
import type { SupportStatusView } from '../../types';
import { CAP, errorMessage } from '../data';
import { Spinner } from '../atoms';
import { LoadErrorCard, checked, isRecord } from '../failure';
import { useToast } from '../toast';
import { when } from '../admin/shared';
import { SupportTabs } from './SupportExport';
import { checkName, sortedChecks, stateTone, stateWord, statusTitle } from './statusRules';
import { LvText } from '../help/LvCodeLink';
import '../admin/admin.css';
import './support.css';

/**
 * Support › Status (task 0057): what LexaVault depends on, as Working, Having problems or Down, with the code, the cause and
 * what to do; the last failures with their references; and, for the IT Admin, the diagnostic bundle.
 */
export default function SupportStatus({ caps }: { caps: string[] }) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const { data, error, mutate, isValidating } = useSWR<SupportStatusView>('support-status',
    () => checked('GET /support/status', apiClient.getSupportStatus(), (v) => isRecord(v) && Array.isArray(v.checks) && Array.isArray(v.failures)),
    { refreshInterval: 30_000 });

  async function download() {
    setBusy(true);
    try {
      const url = URL.createObjectURL(await apiClient.downloadDiagnosticBundle());
      const a = document.createElement('a');
      a.href = url;
      a.download = 'lexavault-diagnostic-bundle.zip';
      a.click();
      URL.revokeObjectURL(url);
      toast('Diagnostic bundle downloaded.');
    } catch (e) {
      toast(errorMessage(e, 'The diagnostic bundle could not be made.'), 'danger');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="cf-sp">
      <header className="cf-page-head">
        <div><h1 className="cf-title">Support</h1></div>
        <SupportTabs caps={caps} />
      </header>

      {error && !data && <LoadErrorCard title="The status couldn't be loaded" error={error} request="GET /support/status" onRetry={() => void mutate()} />}
      {!data && !error && <Spinner label="Loading status" />}
      {data && (
        <div className="cf-ad-stack">
          <section className="cf-ad-card" aria-labelledby="cf-st-head">
            <header className="cf-ad-card-head">
              <div>
                <h2 id="cf-st-head">{statusTitle(data)}</h2>
                <p>What LexaVault depends on. Each is checked by itself, about every minute.</p>
              </div>
              <div className="cf-ad-inline-actions">
                <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" onClick={() => void mutate()} disabled={isValidating}>Refresh</button>
                {caps.includes(CAP.auth) && (
                  <button type="button" className="cf-btn" data-size="sm" onClick={() => void download()} disabled={busy}>{busy && <Spinner label="Making the bundle" />}Download diagnostic bundle</button>
                )}
              </div>
            </header>
            <ul className="cf-ad-conns">
              {sortedChecks(data.checks).map((c) => (
                <li key={c.name} className="cf-ad-conn">
                  <span className="cf-ad-health" data-h={stateTone(c.state)} aria-hidden="true" />
                  <div className="cf-ad-conn-main">
                    <div className="cf-ad-conn-name">{checkName(c.name)}</div>
                    {c.state !== 'WORKING' && <div>{c.cause} · <LvText className="cf-code">{c.code}</LvText></div>}
                    {c.whatToDo && <div className="cf-ad-hint">What to do: <LvText>{c.whatToDo}</LvText></div>}
                  </div>
                  <span className="cf-ad-status" data-h={c.state === 'WORKING' ? 'HEALTHY' : c.state === 'DOWN' ? 'UNHEALTHY' : 'DEGRADED'}>{stateWord(c.state)}</span>
                </li>
              ))}
            </ul>
            {caps.includes(CAP.auth) && <p className="cf-ad-hint">The bundle holds versions, settings without secrets, these checks and the last failures. Never documents, case values or passwords.</p>}
          </section>

          <section className="cf-ad-card" aria-labelledby="cf-st-fail">
            <header className="cf-ad-card-head">
              <div>
                <h2 id="cf-st-fail">Last failures</h2>
                <p>Since this server started. Find one by the reference a person was shown.</p>
              </div>
            </header>
            {data.failures.length === 0 ? <p className="cf-ad-hint">No failures.</p> : (
              <ul className="cf-ad-conns">
                {data.failures.map((f) => (
                  <li key={f.reference + f.at + f.code} className="cf-ad-conn">
                    <div className="cf-ad-conn-main">
                      <div className="cf-ad-conn-name"><code className="cf-code">{f.reference}</code></div>
                      <div>{f.cause} · <LvText className="cf-code">{f.code}</LvText></div>
                      <div className="cf-ad-hint">What to do: <LvText>{f.whatToDo}</LvText></div>
                    </div>
                    <span className="cf-ad-hint">{when(f.at)}</span>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      )}
    </div>
  );
}
