import { useEffect, useId, useState, type FormEvent } from 'react';
import useSWR from 'swr';
import { apiClient } from '../../api/client';
import type { LicenseHistoryItem, LicensingOverview, TenantLicenseStatus } from '../../types';
import { errorMessage } from '../data';
import { Notice, Spinner } from '../atoms';
import { useToast } from '../toast';
import { ConfirmDialog, Loading, humanize, when } from './shared';

type Tone = 'neutral' | 'notice' | 'warning' | 'danger';
const MIN = 60_000, HOUR = 3_600_000, DAY = 86_400_000;

function expiration(expiresAt: string | null | undefined, now: number): { label: string; tone: Tone; remaining: number | null } {
  if (!expiresAt) return { label: 'No expiration', tone: 'neutral', remaining: null };
  const at = new Date(expiresAt);
  const remaining = at.getTime() - now;
  if (remaining <= 0) return { label: `Expired ${at.toLocaleString()}`, tone: 'danger', remaining };
  if (remaining < MIN) return { label: `Expires in less than 1 minute · ${at.toLocaleTimeString()}`, tone: 'warning', remaining };
  if (remaining < HOUR) { const m = Math.ceil(remaining / MIN); return { label: `Expires in ${m} minute${m === 1 ? '' : 's'} · ${at.toLocaleTimeString()}`, tone: 'warning', remaining }; }
  if (remaining < DAY) { const h = Math.ceil(remaining / HOUR); return { label: `Expires in ${h} hour${h === 1 ? '' : 's'} · ${at.toLocaleString()}`, tone: 'warning', remaining }; }
  const d = Math.ceil(remaining / DAY);
  if (d <= 7) return { label: `Expires in ${d} day${d === 1 ? '' : 's'} · ${at.toLocaleDateString()}`, tone: 'warning', remaining };
  if (d <= 30) return { label: `Expires in ${d} days · ${at.toLocaleDateString()}`, tone: 'notice', remaining };
  return { label: `Expires ${at.toLocaleDateString()}`, tone: 'neutral', remaining };
}

function needsAttention(t: TenantLicenseStatus, enforced: boolean, now: number) {
  if (!enforced || t.reasonCode === 'LICENSING_NOT_ENFORCED') return false;
  if (!t.processingAllowed) return true;
  const e = expiration(t.claims?.expiresAt, now);
  return e.remaining !== null && e.remaining > 0 && e.remaining <= 30 * DAY;
}

const REASONS: Record<string, string> = {
  LICENSE_MISSING: 'License required',
  LICENSE_EXPIRED: 'License expired',
  DOCUMENT_PROCESSING_ENTITLEMENT_MISSING: 'DOCUMENT_PROCESSING entitlement missing',
  LICENSE_INVALID_SIGNATURE: 'Installed signature invalid',
  LICENSE_TENANT_MISMATCH: 'Installed license belongs to another tenant',
  LICENSE_CONFIGURATION_ERROR: 'Verification key is not configured',
  LICENSING_NOT_ENFORCED: 'Licensing is not enforced in this runtime',
};
const processingReason = (t: TenantLicenseStatus) => (t.processingAllowed ? 'Document processing permitted' : REASONS[t.reasonCode] || humanize(t.reasonCode || 'UNKNOWN'));

function Expiry({ value }: { value: ReturnType<typeof expiration> }) {
  return <span className="cf-ad-expiry" data-tone={value.tone}>{value.label}</span>;
}

export default function LicensingSection() {
  const toast = useToast();
  const { data, error, mutate, isValidating } = useSWR<LicensingOverview>('platform-licensing', () => apiClient.getLicensingOverview(), { revalidateOnFocus: false });
  const [picked, setPicked] = useState('');
  const [now, setNow] = useState(() => Date.now());
  const [token, setToken] = useState('');
  const [tokenError, setTokenError] = useState('');
  const [confirming, setConfirming] = useState(false);
  const [installing, setInstalling] = useState(false);
  const [installError, setInstallError] = useState<string | null>(null);
  const tokenId = useId();

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(timer);
  }, []);

  const selectedId = picked || data?.tenants[0]?.tenantId || '';
  const history = useSWR<LicenseHistoryItem[]>(selectedId ? ['platform-license-history', selectedId] : null, () => apiClient.getTenantLicenseHistory(selectedId), { revalidateOnFocus: false });

  if (!data) return <Loading error={error} retry={() => mutate()} label="Tenant licensing" />;
  const selected = data.tenants.find((t) => t.tenantId === selectedId) || null;
  const enforced = data.enforcementEnabled;
  const summary = {
    total: data.tenants.length,
    active: data.tenants.filter((t) => t.status === 'ACTIVE').length,
    allowed: data.tenants.filter((t) => t.processingAllowed).length,
    attention: data.tenants.filter((t) => needsAttention(t, enforced, now)).length,
  };

  function requestInstall(e: FormEvent) {
    e.preventDefault();
    if (!selected || !token.trim()) { setTokenError('Select a tenant and paste the complete signed license token.'); return; }
    setTokenError('');
    setInstallError(null);
    setConfirming(true);
  }

  async function install() {
    if (!selected) return;
    setInstalling(true);
    setInstallError(null);
    try {
      const installed = await apiClient.installTenantLicense(selected.tenantId, token.trim());
      setToken('');
      setConfirming(false);
      await Promise.all([mutate(), history.mutate()]);
      toast(`${installed.tenantName} license installed. Effective status: ${humanize(installed.status)}.`);
    } catch (err) {
      setInstallError(errorMessage(err, 'License installation failed.'));
    } finally {
      setInstalling(false);
    }
  }

  return (
    <>
      <section className="cf-ad-readiness" data-ready={enforced || undefined} aria-labelledby="cf-li-head">
        <div>
          <span className="cf-eyebrow">Tenant licensing</span>
          <h2 id="cf-li-head">{enforced ? 'License enforcement is active' : 'License enforcement is disabled'}</h2>
          <p>{enforced
            ? 'Each tenant must have a valid signed license with DOCUMENT_PROCESSING before intake or processing can continue.'
            : 'This runtime is operating in non-enforced mode. This is intended for local developer/demo use only.'}</p>
        </div>
        <div className="cf-ad-readiness-meta">
          <span className="cf-ad-tag" data-tone={enforced ? undefined : 'danger'}>{enforced ? 'Enforced' : 'Not enforced'}</span>
          <span className={data.verificationKeyConfigured ? '' : 'cf-danger-text'}>Verification key {data.verificationKeyConfigured ? 'configured' : 'missing'}</span>
          <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={installing || isValidating} onClick={() => mutate()}>Refresh</button>
        </div>
      </section>

      <dl className="cf-ad-stats">
        <div><dt>Provisioned tenants</dt><dd>{summary.total}</dd></div>
        <div><dt>Active licenses</dt><dd>{summary.active}</dd></div>
        <div><dt>Processing allowed</dt><dd>{summary.allowed}</dd></div>
        <div data-tone={summary.attention > 0 ? 'danger' : undefined}><dt>Needs attention</dt><dd>{summary.attention}</dd></div>
      </dl>

      <section className="cf-ad-card" aria-labelledby="cf-li-tenants">
        <header className="cf-ad-card-head">
          <div>
            <h2 id="cf-li-tenants">Tenant license status <span className="cf-ad-tag">{data.tenants.length}</span></h2>
            <p>Select a tenant to install or update its license, review entitlements and inspect immutable history.</p>
          </div>
        </header>
        {data.tenants.length === 0 ? (
          <p className="cf-ad-hint">No provisioned tenants are available for licensing.</p>
        ) : (
          <div className="cf-ad-table-wrap">
            <table className="cf-ad-table cf-ad-table-left">
              <thead><tr><th scope="col">Tenant</th><th scope="col">Status</th><th scope="col">Processing</th><th scope="col">Edition</th><th scope="col">Expiration</th></tr></thead>
              <tbody>
                {data.tenants.map((t) => (
                  <tr key={t.tenantId} data-selected={t.tenantId === selectedId || undefined}>
                    <th scope="row">
                      <button type="button" className="cf-ad-rowbtn" aria-pressed={t.tenantId === selectedId} onClick={() => setPicked(t.tenantId)}>
                        <span className="cf-ad-cell-title">{t.tenantName}</span>
                        <span className="cf-ad-cell-sub">{t.tenantSlug}</span>
                      </button>
                    </th>
                    <td>{humanize(t.status || 'UNKNOWN')}</td>
                    <td className={t.processingAllowed ? '' : 'cf-danger-text'}>{t.processingAllowed ? 'Allowed' : 'Blocked'}</td>
                    <td>{t.claims?.edition || '—'}</td>
                    <td><Expiry value={expiration(t.claims?.expiresAt, now)} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {selected && (
        <div className="cf-ad-pair">
          <section className="cf-ad-card" aria-labelledby="cf-li-detail">
            <header className="cf-ad-card-head">
              <div>
                <h2 id="cf-li-detail">{selected.tenantName}</h2>
                <p><span className="cf-ad-mono">{selected.tenantSlug} · {selected.tenantId}</span></p>
              </div>
              <span className="cf-ad-tag">{humanize(selected.status || 'UNKNOWN')}</span>
            </header>
            <Notice tone={selected.processingAllowed ? 'neutral' : 'danger'}>
              <strong>{selected.processingAllowed ? 'Document processing allowed.' : 'Document processing blocked.'}</strong> {processingReason(selected)}
            </Notice>
            {selected.claims ? (
              <>
                <dl className="cf-ad-defs">
                  <div><dt>Customer</dt><dd>{selected.claims.customerName}</dd></div>
                  <div><dt>Edition</dt><dd>{selected.claims.edition}</dd></div>
                  <div><dt>License ID</dt><dd className="cf-ad-mono">{selected.claims.licenseId}</dd></div>
                  <div><dt>Issued</dt><dd>{new Date(selected.claims.issuedAt).toLocaleString()}</dd></div>
                  <div><dt>Not before</dt><dd>{selected.claims.notBefore ? new Date(selected.claims.notBefore).toLocaleString() : 'Immediately'}</dd></div>
                  <div><dt>Expiration</dt><dd><Expiry value={expiration(selected.claims.expiresAt, now)} /></dd></div>
                </dl>
                <div className="cf-ad-fieldset">
                  <h3>Entitlements</h3>
                  {selected.claims.entitlements.length ? (
                    <ul className="cf-ad-tally">{selected.claims.entitlements.map((e) => <li key={e} data-strong={e === 'DOCUMENT_PROCESSING' || undefined}>{humanize(e)}</li>)}</ul>
                  ) : <p className="cf-ad-hint">No entitlements.</p>}
                </div>
                <div className="cf-ad-fieldset">
                  <h3>Limits</h3>
                  {Object.keys(selected.claims.limits).length === 0 ? (
                    <p className="cf-ad-hint">No numeric limits in this license.</p>
                  ) : (
                    <dl className="cf-ad-defs">{Object.entries(selected.claims.limits).map(([k, v]) => <div key={k}><dt>{humanize(k)}</dt><dd>{v}</dd></div>)}</dl>
                  )}
                </div>
              </>
            ) : (
              <p className="cf-ad-hint"><strong>No active license claims.</strong> Install a signed tenant license for this tenant.</p>
            )}
          </section>

          <section className="cf-ad-card" aria-labelledby="cf-li-install">
            <header className="cf-ad-card-head">
              <div>
                <h2 id="cf-li-install">Install or update license</h2>
                <p>Paste the complete vendor-signed <code className="cf-ad-mono">LV1...</code> token.</p>
              </div>
            </header>
            <form className="cf-ad-form" onSubmit={requestInstall} noValidate>
              <div className="cf-field cf-ad-token" data-invalid={!!tokenError || undefined}>
                <label htmlFor={tokenId}><span>Signed license token</span></label>
                <textarea id={tokenId} value={token} rows={6} spellCheck={false} autoComplete="off" placeholder="LV1.eyJ...signature"
                  aria-invalid={!!tokenError || undefined} onChange={(e) => setToken(e.target.value)} />
                <span className="cf-field-hint">The token is sent only to the licensing API for verification and storage. The overview and history never return the raw token.</span>
              </div>
              {tokenError && <Notice tone="danger">{tokenError}</Notice>}
              <div className="cf-ad-card-foot">
                <button type="submit" className="cf-btn" data-variant="solid" data-size="sm" disabled={installing || !token.trim()}>Verify and install</button>
              </div>
            </form>
            <dl className="cf-ad-defs">
              <div><dt>Verification mode</dt><dd>Offline signed license · Ed25519 public-key verification</dd></div>
            </dl>
            <p className="cf-ad-hint">Connected/online validation is not required for on-prem operation and is not configured in this candidate.</p>
          </section>
        </div>
      )}

      {selected && (
        <section className="cf-ad-card" aria-labelledby="cf-li-history">
          <header className="cf-ad-card-head">
            <div>
              <h2 id="cf-li-history">License history</h2>
              <p>Prior records remain visible for auditability. Raw signed tokens are never shown.</p>
            </div>
            <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={history.isValidating} onClick={() => history.mutate()}>
              {history.isValidating && <Spinner label="Loading history" />}Refresh history
            </button>
          </header>
          {history.error ? (
            <Loading error={history.error} retry={() => history.mutate()} label="License history" />
          ) : !history.data ? (
            <Loading retry={() => history.mutate()} label="License history" />
          ) : history.data.length === 0 ? (
            <p className="cf-ad-hint">No license history exists for this tenant.</p>
          ) : (
            <ul className="cf-ad-conns">
              {history.data.map((h) => (
                <li key={h.recordId} className="cf-ad-conn">
                  <span className="cf-ad-health" data-h={h.active ? 'ok' : undefined} aria-hidden="true" />
                  <div className="cf-ad-conn-main">
                    <div className="cf-ad-conn-name">
                      {h.claims?.edition || 'Unknown edition'}
                      {h.active && <span className="cf-ad-tag">Current</span>}
                      {h.verificationEvidence === 'VERIFIED_AT_INSTALL' && <span className="cf-ad-tag">Verified at install</span>}
                      <span className="cf-ad-tag">{humanize(h.status || 'UNKNOWN')}</span>
                    </div>
                    <div className="cf-ad-conn-meta">
                      <span>Installed {when(h.installedAt)} by {h.installedBy}</span>
                      <span>{h.claims?.entitlements.length || 0} entitlements</span>
                      <Expiry value={expiration(h.claims?.expiresAt, now)} />
                    </div>
                    <code className="cf-ad-conn-endpoint cf-ad-mono">{h.licenseId}</code>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}

      <ConfirmDialog
        open={confirming}
        title="Install signed license"
        facts={selected ? [['Tenant', selected.tenantName], ['Tenant ID', <span className="cf-ad-mono" key="t">{selected.tenantId}</span>], ['Current status', humanize(selected.status || 'UNKNOWN')]] : []}
        consequence="The token is verified before it becomes active. On success it replaces the current license; prior records stay in history."
        confirmLabel="Verify and install"
        busy={installing}
        error={installError}
        onConfirm={() => void install()}
        onCancel={() => setConfirming(false)}
      />
    </>
  );
}
