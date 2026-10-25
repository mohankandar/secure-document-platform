import { useState } from 'react';
import useSWR from 'swr';
import { apiClient } from '../../api/client';
import type { EnterpriseIdentityConnection, EnterpriseIdentityDraftRequest, EnterpriseIdentityOverview } from '../../types';
import { errorMessage } from '../data';
import { Notice, Spinner } from '../atoms';
import { useToast } from '../toast';
import { Loading, when } from './shared';

const EMPTY: EnterpriseIdentityDraftRequest = { providerId: '', displayName: '', issuerUri: '', browserClientId: '', apiAudience: '' };
const toDraft = (c: EnterpriseIdentityConnection | null): EnterpriseIdentityDraftRequest =>
  c ? { providerId: c.providerId, displayName: c.displayName, issuerUri: c.issuerUri, browserClientId: c.browserClientId ?? '', apiAudience: c.apiAudience } : EMPTY;

export default function IdentityArea() {
  const { data, error, mutate } = useSWR<EnterpriseIdentityOverview>('enterprise-identity', () => apiClient.getEnterpriseIdentityOverview(), { revalidateOnFocus: false });
  if (!data) return <Loading error={error} retry={() => mutate()} label="Sign-in" />;
  return <IdentityBody data={data} replace={(next) => mutate(next, { revalidate: false })} reload={() => mutate()} />;
}

function IdentityBody({ data, replace, reload }: { data: EnterpriseIdentityOverview; replace: (d: EnterpriseIdentityOverview) => void; reload: () => void }) {
  const toast = useToast();
  const source = data.staged ?? data.active;
  const [form, setForm] = useState(() => toDraft(source));
  const [busy, setBusy] = useState<null | 'save' | 'test' | 'activate' | string>(null);
  const [error, setError] = useState<string | null>(null);

  const staged = data.staged;
  const dirty = JSON.stringify(form) !== JSON.stringify(toDraft(source));
  const httpsIssuer = /^https:\/\//.test(form.issuerUri);
  const complete = !!(form.providerId && form.displayName && form.issuerUri && form.apiAudience);
  const testFresh = staged?.lastTestStatus === 'PASSED' && staged.lastTestedAt && (Date.now() - new Date(staged.lastTestedAt).getTime()) / 1000 <= data.activationMaxTestAgeSeconds;

  async function act(kind: string, fn: () => Promise<EnterpriseIdentityOverview>, ok: string) {
    setBusy(kind);
    setError(null);
    try {
      const next = await fn();
      replace(next);
      if (kind === 'save' || kind.startsWith('rollback')) setForm(toDraft(next.staged ?? next.active));
      toast(ok);
    } catch (e) {
      setError(errorMessage(e, 'The change could not be applied.'));
      reload();
    } finally {
      setBusy(null);
    }
  }

  const field = (key: keyof EnterpriseIdentityDraftRequest, label: string, hint?: string, invalid?: boolean) => (
    <div className="cf-field" data-invalid={invalid || undefined}>
      <label htmlFor={`idp-${key}`}>{label}</label>
      <input id={`idp-${key}`} value={form[key]} spellCheck={false} onChange={(e) => setForm({ ...form, [key]: e.target.value })} aria-invalid={invalid || undefined} />
      <span className="cf-field-hint">{invalid ? 'The issuer must be an HTTPS URL.' : hint}</span>
    </div>
  );

  return (
    <div className="cf-ad-stack">
      <section className="cf-ad-card" aria-label="Active provider">
        <header className="cf-ad-card-head"><div><h2>Active provider</h2><p>People sign in through this provider today.</p></div></header>
        {data.active ? (
          <dl className="cf-facts">
            <div><dt>Name</dt><dd>{data.active.displayName}</dd></div>
            <div><dt>Issuer</dt><dd className="cf-mono">{data.active.issuerUri}</dd></div>
            <div><dt>Type</dt><dd>{data.active.providerType}</dd></div>
            <div><dt>Version</dt><dd>v{data.active.configurationVersion} · since {when(data.active.updatedAt)}</dd></div>
          </dl>
        ) : <p className="cf-muted">No enterprise provider is active. Local sign-in is in use.</p>}
      </section>

      <section className="cf-ad-card" aria-label="Change provider">
        <header className="cf-ad-card-head">
          <div><h2>{staged ? 'Staged change' : 'Stage a change'}</h2><p>Save a draft, test it against the provider, then activate. The current provider stays live until you do.</p></div>
          {staged && <span className="cf-ad-tag" data-tone={staged.lastTestStatus === 'PASSED' ? 'ok' : staged.lastTestStatus === 'FAILED' ? 'danger' : undefined}>{staged.lastTestStatus === 'PASSED' ? 'Test passed' : staged.lastTestStatus === 'FAILED' ? 'Test failed' : 'Not tested'}</span>}
        </header>
        <ol className="cf-ad-steps" aria-label="Activation steps">
          <li data-done={(!!staged && !dirty) || undefined}>Save draft</li>
          <li data-done={(!!testFresh && !dirty) || undefined}>Test</li>
          <li>Activate</li>
        </ol>
        <div className="cf-ad-grid">
          {field('displayName', 'Display name', 'Shown on the sign-in button')}
          {field('providerId', 'Provider ID')}
          {field('issuerUri', 'Issuer URL', 'OIDC discovery is read from this URL', !!form.issuerUri && !httpsIssuer)}
          {field('browserClientId', 'Browser client ID')}
          {field('apiAudience', 'API audience')}
        </div>
        {staged?.lastTestMessage && <Notice tone={staged.lastTestStatus === 'PASSED' ? 'neutral' : 'danger'}>{staged.lastTestMessage} <span className="cf-muted">· {when(staged.lastTestedAt)}</span></Notice>}
        {staged?.lastTestStatus === 'PASSED' && !testFresh && <Notice tone="mark">The last test is too old to activate. Test again.</Notice>}
        {error && <Notice tone="danger">{error}</Notice>}
        <footer className="cf-ad-card-foot">
          <button type="button" className="cf-btn" data-size="sm" disabled={!dirty || !complete || !httpsIssuer || !!busy} onClick={() => act('save', () => apiClient.saveEnterpriseIdentityDraft(form), 'Draft saved. Test it before activating.')}>
            {busy === 'save' && <Spinner label="Saving" />}Save draft
          </button>
          <button type="button" className="cf-btn" data-size="sm" disabled={!staged || dirty || !!busy} onClick={() => act('test', () => apiClient.testEnterpriseIdentityDraft(), 'Connection test finished.')}>
            {busy === 'test' && <Spinner label="Testing" />}Test connection
          </button>
          <button type="button" className="cf-btn" data-variant="solid" data-size="sm" disabled={!staged || dirty || !testFresh || !data.activationSupported || !!busy}
            onClick={() => staged && act('activate', () => apiClient.activateEnterpriseIdentityDraft(staged.connectionId, staged.configurationVersion), `${staged.displayName} is now the sign-in provider.`)}>
            {busy === 'activate' && <Spinner label="Activating" />}Activate
          </button>
        </footer>
        {!data.activationSupported && <p className="cf-ad-hint">Activation is not available in this deployment.</p>}
      </section>

      {data.rollbackCandidates.length > 0 && (
        <section className="cf-ad-card" aria-label="Previous providers">
          <header className="cf-ad-card-head"><div><h2>Previous providers</h2><p>Roll back if the active provider misbehaves.</p></div></header>
          <ul className="cf-ad-conns">
            {data.rollbackCandidates.map((c) => (
              <li key={c.connectionId} className="cf-ad-conn">
                <div className="cf-ad-conn-main">
                  <div className="cf-ad-conn-name">{c.displayName}<span className="cf-ad-tag">v{c.configurationVersion}</span></div>
                  <div className="cf-ad-conn-meta"><span className="cf-mono">{c.issuerUri}</span><span>Retired {when(c.updatedAt)}</span></div>
                </div>
                <div className="cf-ad-conn-actions">
                  <button type="button" className="cf-btn" data-size="sm" disabled={!!busy}
                    onClick={() => act(`rollback-${c.connectionId}`, () => apiClient.rollbackEnterpriseIdentity(c.connectionId, data.active?.configurationVersion ?? 0), `Rolled back to ${c.displayName}.`)}>
                    {busy === `rollback-${c.connectionId}` && <Spinner label="Rolling back" />}Roll back
                  </button>
                </div>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
