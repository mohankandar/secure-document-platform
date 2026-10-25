import { useEffect, useState } from 'react';
import useSWR from 'swr';
import { apiClient } from '../../api/client';
import type { EnterpriseIdentityConnection, EnterpriseIdentityDraftRequest, EnterpriseIdentityOverview } from '../../types';
import { errorMessage } from '../data';
import { Notice, Spinner } from '../atoms';
import { useToast } from '../toast';
import { ConfirmDialog, Loading, when } from './shared';

const EMPTY: EnterpriseIdentityDraftRequest = { providerId: 'customer-oidc', displayName: 'Corporate identity provider', issuerUri: '', browserClientId: 'lexavault-web', apiAudience: 'lexavault-api' };
const toDraft = (c: EnterpriseIdentityConnection | null): EnterpriseIdentityDraftRequest =>
  c ? { providerId: c.providerId, displayName: c.displayName, issuerUri: c.issuerUri, browserClientId: c.browserClientId ?? '', apiAudience: c.apiAudience } : EMPTY;

type Pending = { kind: 'activate'; target: EnterpriseIdentityConnection } | { kind: 'rollback'; target: EnterpriseIdentityConnection };

export default function IdentityArea() {
  const { data, error, mutate } = useSWR<EnterpriseIdentityOverview>('enterprise-identity', () => apiClient.getEnterpriseIdentityOverview(), { revalidateOnFocus: false });
  if (!data) return <Loading error={error} retry={() => mutate()} label="Sign-in" />;
  return <IdentityBody data={data} replace={(next) => mutate(next, { revalidate: false })} reload={() => mutate()} />;
}

/** Re-evaluates test freshness on a timer so the activation window closes without a re-render. */
function useNow(intervalMs: number) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), intervalMs);
    return () => window.clearInterval(id);
  }, [intervalMs]);
  return now;
}

function IdentityBody({ data, replace, reload }: { data: EnterpriseIdentityOverview; replace: (d: EnterpriseIdentityOverview) => void; reload: () => void }) {
  const toast = useToast();
  const now = useNow(15_000);
  const source = data.staged ?? data.active;
  const [form, setForm] = useState(() => toDraft(source));
  const [busy, setBusy] = useState<null | 'save' | 'test' | 'activate' | 'rollback'>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState<Pending | null>(null);
  const [confirmError, setConfirmError] = useState<string | null>(null);

  const staged = data.staged;
  const dirty = JSON.stringify(form) !== JSON.stringify(toDraft(source));
  const httpsIssuer = /^https:\/\//.test(form.issuerUri.trim());
  const complete = !!(form.providerId.trim() && form.displayName.trim() && form.issuerUri.trim() && form.browserClientId.trim() && form.apiAudience.trim());
  const testAgeSeconds = staged?.lastTestedAt ? (now - new Date(staged.lastTestedAt).getTime()) / 1000 : Infinity;
  const testFresh = staged?.lastTestStatus === 'PASSED' && testAgeSeconds <= data.activationMaxTestAgeSeconds;
  const windowMinutes = Math.round(data.activationMaxTestAgeSeconds / 60);
  const canActivate = !!staged && !dirty && data.activationSupported && staged.status === 'VALIDATED' && testFresh;

  async function save() {
    setBusy('save'); setError(null);
    try {
      const next = await apiClient.saveEnterpriseIdentityDraft({
        providerId: form.providerId.trim(), displayName: form.displayName.trim(), issuerUri: form.issuerUri.trim(),
        browserClientId: form.browserClientId.trim(), apiAudience: form.apiAudience.trim(),
      });
      replace(next);
      setForm(toDraft(next.staged ?? next.active));
      toast('Draft saved. The active connection was not changed.');
    } catch (e) {
      setError(errorMessage(e, 'Could not save the authentication draft.'));
      reload();
    } finally { setBusy(null); }
  }

  async function test() {
    setBusy('test'); setError(null);
    try {
      const next = await apiClient.testEnterpriseIdentityDraft();
      replace(next);
      if (next.staged?.lastTestStatus === 'PASSED') toast(`Connection test passed. This version can be activated for ${windowMinutes} minutes.`);
      else setError(next.staged?.lastTestMessage ?? 'The OIDC connection test failed.');
    } catch (e) {
      setError(errorMessage(e, 'Could not test the authentication draft.'));
      reload();
    } finally { setBusy(null); }
  }

  async function confirm() {
    if (!pending) return;
    const { kind, target } = pending;
    setBusy(kind); setConfirmError(null);
    try {
      const next = kind === 'activate'
        ? await apiClient.activateEnterpriseIdentityDraft(target.connectionId, target.configurationVersion)
        : await apiClient.rollbackEnterpriseIdentity(target.connectionId, target.configurationVersion);
      replace(next);
      setForm(toDraft(next.staged ?? next.active));
      setPending(null);
      toast(kind === 'activate'
        ? 'Activated. The previous active configuration is kept as a known-good rollback target.'
        : `Rolled back to ${target.displayName} v${target.configurationVersion}.`);
    } catch (e) {
      setConfirmError(errorMessage(e, kind === 'activate' ? 'Could not activate this configuration.' : 'Could not roll back to this configuration.'));
      reload();
    } finally { setBusy(null); }
  }

  const field = (key: keyof EnterpriseIdentityDraftRequest, label: string, hint?: string, invalid?: string | false, placeholder?: string) => (
    <div className="cf-field" data-invalid={invalid ? true : undefined}>
      <label htmlFor={`idp-${key}`}>{label}</label>
      <input id={`idp-${key}`} value={form[key]} spellCheck={false} required placeholder={placeholder} disabled={busy === 'activate' || busy === 'rollback'}
        onChange={(e) => setForm({ ...form, [key]: e.target.value })} aria-invalid={invalid ? true : undefined} />
      <span className="cf-field-hint">{invalid || hint}</span>
    </div>
  );

  const activateBlocker = !staged ? 'Save a draft first.'
    : dirty ? 'Save your edits, then test them, before activating.'
    : !data.activationSupported ? 'Activation is not available in this deployment.'
    : staged.lastTestStatus !== 'PASSED' ? 'Run a successful connection test first.'
    : staged.status !== 'VALIDATED' ? 'Only a VALIDATED version can be activated.'
    : !testFresh ? `The last passing test is older than ${windowMinutes} minutes. Test again.`
    : null;

  return (
    <div className="cf-ad-stack">
      <section className="cf-ad-card" aria-label="Active connection">
        <header className="cf-ad-card-head">
          <div><h2>Active connection</h2><p>The trusted server-side OIDC binding people sign in through today.</p></div>
          <span className="cf-ad-tag" data-tone={data.active ? 'ok' : undefined}>{data.active?.status ?? 'Unavailable'}</span>
        </header>
        {data.active ? (
          <dl className="cf-facts">
            <div><dt>Provider</dt><dd>{data.active.displayName}</dd></div>
            <div><dt>Type</dt><dd>{data.active.providerType}</dd></div>
            <div><dt>Issuer</dt><dd className="cf-mono">{data.active.issuerUri}</dd></div>
            <div><dt>Browser client</dt><dd className="cf-mono">{data.active.browserClientId ?? 'Not configured'}</dd></div>
            <div><dt>API audience</dt><dd className="cf-mono">{data.active.apiAudience}</dd></div>
            <div><dt>Version</dt><dd>v{data.active.configurationVersion} · since {when(data.active.updatedAt)}</dd></div>
          </dl>
        ) : <p className="cf-muted">No active enterprise identity connection is available for this tenant. Automatic bootstrap will not overwrite existing administered identity records.</p>}
      </section>

      <section className="cf-ad-card" aria-label="Staged configuration">
        <header className="cf-ad-card-head">
          <div><h2>{staged ? `Staged draft · v${staged.configurationVersion}` : 'Stage a change'}</h2><p>Save a draft, test it against the provider, then activate. The active connection stays live until you do.</p></div>
          {staged && <span className="cf-ad-tag" data-tone={staged.status === 'VALIDATED' ? 'ok' : staged.lastTestStatus === 'FAILED' ? 'danger' : undefined}>{staged.status}</span>}
        </header>
        <ol className="cf-ad-steps" aria-label="Activation steps">
          <li data-done={(!!staged && !dirty) || undefined}>Save draft</li>
          <li data-done={(!!testFresh && !dirty) || undefined}>Test</li>
          <li>Activate</li>
        </ol>
        <p className="cf-ad-hint">Only the exact VALIDATED version with a passing test from the last {windowMinutes} minutes can be activated. Activation is transactional and keeps the prior active binding for rollback.</p>
        <div className="cf-ad-grid">
          {field('displayName', 'Display name', 'Shown on the sign-in button')}
          {field('providerId', 'Provider ID')}
          {field('issuerUri', 'Issuer URI', 'OIDC discovery is read from this URL', !!form.issuerUri.trim() && !httpsIssuer && 'The issuer must be an HTTPS URL.', 'https://login.example.com/tenant/v2.0')}
          {field('browserClientId', 'Browser client ID')}
          {field('apiAudience', 'API audience')}
        </div>
        {staged?.lastTestStatus && (
          <Notice tone={staged.lastTestStatus === 'PASSED' ? 'neutral' : 'danger'}>
            <strong>Last test: {staged.lastTestStatus === 'PASSED' ? 'passed' : 'failed'}</strong> · {staged.lastTestMessage} <span className="cf-muted">· {when(staged.lastTestedAt)}</span>
          </Notice>
        )}
        {staged?.lastTestStatus === 'PASSED' && !testFresh && !dirty && <Notice tone="mark">The last passing test is older than {windowMinutes} minutes. Test again before activating.</Notice>}
        {dirty && staged && <Notice tone="mark">You have unsaved edits. Activation uses the saved draft, so save and test again first.</Notice>}
        {error && <Notice tone="danger">{error}</Notice>}
        <footer className="cf-ad-card-foot">
          <button type="button" className="cf-btn" data-size="sm" disabled={!dirty || !complete || !httpsIssuer || !!busy} onClick={() => void save()}>
            {busy === 'save' && <Spinner label="Saving" />}Save draft
          </button>
          <button type="button" className="cf-btn" data-size="sm" disabled={!staged || dirty || !!busy} onClick={() => void test()}>
            {busy === 'test' && <Spinner label="Testing" />}Test connection
          </button>
          <button type="button" className="cf-btn" data-variant="solid" data-size="sm" disabled={!canActivate || !!busy}
            aria-describedby={activateBlocker ? 'idp-activate-blocker' : undefined}
            onClick={() => { if (staged) { setConfirmError(null); setPending({ kind: 'activate', target: staged }); } }}>
            Activate v{staged?.configurationVersion ?? '—'}
          </button>
        </footer>
        {activateBlocker && <p id="idp-activate-blocker" className="cf-ad-hint">{activateBlocker}</p>}
      </section>

      <section className="cf-ad-card" aria-label="Known-good rollback">
        <header className="cf-ad-card-head">
          <div><h2>Known-good rollback</h2><p>Only tenant-scoped configurations that were previously active. Rolling back retires the current binding and restores the selected version atomically.</p></div>
          <span className="cf-ad-tag">{data.rollbackCandidates.length} available</span>
        </header>
        {data.rollbackCandidates.length > 0 ? (
          <ul className="cf-ad-conns">
            {data.rollbackCandidates.map((c) => (
              <li key={`${c.connectionId}-${c.configurationVersion}`} className="cf-ad-conn">
                <div className="cf-ad-conn-main">
                  <div className="cf-ad-conn-name">{c.displayName}<span className="cf-ad-tag">v{c.configurationVersion}</span></div>
                  <div className="cf-ad-conn-meta"><span className="cf-mono">{c.issuerUri}</span><span>Retired {when(c.updatedAt)}</span></div>
                </div>
                <div className="cf-ad-conn-actions">
                  <button type="button" className="cf-btn" data-size="sm" disabled={!!busy}
                    onClick={() => { setConfirmError(null); setPending({ kind: 'rollback', target: c }); }}>
                    Roll back to v{c.configurationVersion}
                  </button>
                </div>
              </li>
            ))}
          </ul>
        ) : <p className="cf-muted">No previous known-good configuration is available yet.</p>}
      </section>

      <ConfirmDialog
        open={!!pending}
        title={pending?.kind === 'activate' ? `Activate version ${pending.target.configurationVersion}?` : `Roll back to version ${pending?.target.configurationVersion ?? ''}?`}
        facts={pending ? [
          ['Provider', pending.target.displayName],
          ['Issuer', <span key="i" className="cf-mono">{pending.target.issuerUri}</span>],
          ['Browser client', <span key="b" className="cf-mono">{pending.target.browserClientId ?? 'Not configured'}</span>],
          ['API audience', <span key="a" className="cf-mono">{pending.target.apiAudience}</span>],
          ['Version', `v${pending.target.configurationVersion}`],
          ['Replaces', data.active ? `${data.active.displayName} v${data.active.configurationVersion}` : 'No active connection'],
        ] : []}
        consequence={pending?.kind === 'activate'
          ? 'This changes the trusted server-side OIDC binding immediately. The current active configuration is kept as a known-good rollback target.'
          : 'This replaces the current active trusted binding immediately. The current configuration becomes a rollback target.'}
        acknowledge={pending?.kind === 'activate'
          ? 'The deployed browser/client OIDC settings already match this configuration.'
          : 'I understand people will sign in through this restored configuration immediately.'}
        confirmLabel={pending?.kind === 'activate' ? 'Activate' : 'Roll back'}
        danger={pending?.kind === 'rollback'}
        busy={busy === 'activate' || busy === 'rollback'}
        error={confirmError}
        onConfirm={() => void confirm()}
        onCancel={() => { if (!busy) setPending(null); }}
      />
    </div>
  );
}
