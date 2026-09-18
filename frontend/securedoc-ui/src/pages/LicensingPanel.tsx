import { FormEvent, useCallback, useEffect, useMemo, useState } from 'react';
import { apiClient } from '../api/client';
import type { LicenseHistoryItem, LicensingOverview, TenantLicenseStatus } from '../types';

function messageFrom(error: unknown, fallback: string) {
  const maybe = error as { message?: string; errorCode?: string };
  return maybe?.message || maybe?.errorCode || fallback;
}

function displayStatus(value?: string | null) {
  return (value || 'UNKNOWN').replace(/_/g, ' ');
}

function statusClass(status?: string | null) {
  const normalized = (status || 'unknown').toLowerCase().replace(/[^a-z0-9]+/g, '-');
  return `platform-status platform-status-${normalized}`;
}

function expirationInfo(expiresAt?: string | null, nowMs = Date.now()) {
  if (!expiresAt) return { label: 'No expiration', tone: 'neutral' as const, remainingMs: null as number | null };
  const expires = new Date(expiresAt);
  const remainingMs = expires.getTime() - nowMs;
  if (remainingMs <= 0) return { label: `Expired ${expires.toLocaleString()}`, tone: 'danger' as const, remainingMs };

  const minuteMs = 60_000;
  const hourMs = 3_600_000;
  const dayMs = 86_400_000;
  if (remainingMs < minuteMs) {
    return { label: `Expires in less than 1 minute · ${expires.toLocaleTimeString()}`, tone: 'warning' as const, remainingMs };
  }
  if (remainingMs < hourMs) {
    const minutes = Math.ceil(remainingMs / minuteMs);
    return { label: `Expires in ${minutes} minute${minutes === 1 ? '' : 's'} · ${expires.toLocaleTimeString()}`, tone: 'warning' as const, remainingMs };
  }
  if (remainingMs < dayMs) {
    const hours = Math.ceil(remainingMs / hourMs);
    return { label: `Expires in ${hours} hour${hours === 1 ? '' : 's'} · ${expires.toLocaleString()}`, tone: 'warning' as const, remainingMs };
  }

  const days = Math.ceil(remainingMs / dayMs);
  if (days <= 7) return { label: `Expires in ${days} day${days === 1 ? '' : 's'} · ${expires.toLocaleDateString()}`, tone: 'warning' as const, remainingMs };
  if (days <= 30) return { label: `Expires in ${days} days · ${expires.toLocaleDateString()}`, tone: 'notice' as const, remainingMs };
  return { label: `Expires ${expires.toLocaleDateString()}`, tone: 'neutral' as const, remainingMs };
}

function needsAttention(tenant: TenantLicenseStatus, enforcementEnabled: boolean, nowMs: number) {
  if (!enforcementEnabled || tenant.reasonCode === 'LICENSING_NOT_ENFORCED') return false;
  if (!tenant.processingAllowed) return true;
  const expiry = expirationInfo(tenant.claims?.expiresAt, nowMs);
  return expiry.remainingMs !== null && expiry.remainingMs > 0 && expiry.remainingMs <= 30 * 86_400_000;
}

function processingReason(tenant: TenantLicenseStatus) {
  if (tenant.processingAllowed) return 'Document processing permitted';
  switch (tenant.reasonCode) {
    case 'LICENSE_MISSING': return 'License required';
    case 'LICENSE_EXPIRED': return 'License expired';
    case 'DOCUMENT_PROCESSING_ENTITLEMENT_MISSING': return 'DOCUMENT_PROCESSING entitlement missing';
    case 'LICENSE_INVALID_SIGNATURE': return 'Installed signature invalid';
    case 'LICENSE_TENANT_MISMATCH': return 'Installed license belongs to another tenant';
    case 'LICENSE_CONFIGURATION_ERROR': return 'Verification key is not configured';
    case 'LICENSING_NOT_ENFORCED': return 'Licensing is not enforced in this runtime';
    default: return displayStatus(tenant.reasonCode);
  }
}

function TenantRow({ tenant, selected, onSelect, nowMs }: { tenant: TenantLicenseStatus; selected: boolean; onSelect: () => void; nowMs: number }) {
  const expiry = expirationInfo(tenant.claims?.expiresAt, nowMs);
  return (
    <button type="button" className={`licensing-tenant-row ${selected ? 'selected' : ''}`} onClick={onSelect}>
      <span className="licensing-tenant-identity">
        <strong>{tenant.tenantName}</strong>
        <small>{tenant.tenantSlug}</small>
      </span>
      <span><span className={statusClass(tenant.status)}>{displayStatus(tenant.status)}</span></span>
      <span className={tenant.processingAllowed ? 'licensing-allowed' : 'licensing-denied'}>{tenant.processingAllowed ? 'Allowed' : 'Blocked'}</span>
      <span>{tenant.claims?.edition || '—'}</span>
      <span className={`licensing-expiry licensing-expiry-${expiry.tone}`}>{expiry.label}</span>
    </button>
  );
}

export default function LicensingPanel() {
  const [overview, setOverview] = useState<LicensingOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [selectedTenantId, setSelectedTenantId] = useState('');
  const [history, setHistory] = useState<LicenseHistoryItem[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [licenseToken, setLicenseToken] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [nowMs, setNowMs] = useState(() => Date.now());

  const loadOverview = useCallback(async () => {
    try {
      setError('');
      const next = await apiClient.getLicensingOverview();
      setOverview(next);
      setSelectedTenantId((current) => current || next.tenants[0]?.tenantId || '');
    } catch (err) {
      setError(messageFrom(err, 'Unable to load tenant licensing status'));
    } finally {
      setLoading(false);
    }
  }, []);

  const loadHistory = useCallback(async (tenantId: string) => {
    if (!tenantId) {
      setHistory([]);
      return;
    }
    setHistoryLoading(true);
    try {
      setHistory(await apiClient.getTenantLicenseHistory(tenantId));
    } catch (err) {
      setError(messageFrom(err, 'Unable to load license history'));
      setHistory([]);
    } finally {
      setHistoryLoading(false);
    }
  }, []);

  useEffect(() => { void loadOverview(); }, [loadOverview]);
  useEffect(() => { void loadHistory(selectedTenantId); }, [loadHistory, selectedTenantId]);
  useEffect(() => {
    const timer = window.setInterval(() => setNowMs(Date.now()), 30_000);
    return () => window.clearInterval(timer);
  }, []);

  const selectedTenant = useMemo(
    () => overview?.tenants.find((tenant) => tenant.tenantId === selectedTenantId) || null,
    [overview, selectedTenantId],
  );

  const summary = useMemo(() => {
    const tenants = overview?.tenants || [];
    return {
      total: tenants.length,
      active: tenants.filter((tenant) => tenant.status === 'ACTIVE').length,
      allowed: tenants.filter((tenant) => tenant.processingAllowed).length,
      attention: tenants.filter((tenant) => needsAttention(tenant, overview?.enforcementEnabled ?? false, nowMs)).length,
    };
  }, [overview, nowMs]);

  const install = async (event: FormEvent) => {
    event.preventDefault();
    if (!selectedTenant || !licenseToken.trim()) {
      setError('Select a tenant and paste the complete signed license token.');
      return;
    }
    if (!window.confirm(`Install this signed license for ${selectedTenant.tenantName}? The token will be verified before it becomes active.`)) return;

    setBusy(true);
    setError('');
    setNotice('');
    try {
      const installed = await apiClient.installTenantLicense(selectedTenant.tenantId, licenseToken.trim());
      setLicenseToken('');
      setNotice(`${installed.tenantName} license installed. Effective status: ${displayStatus(installed.status)}.`);
      await loadOverview();
      await loadHistory(selectedTenant.tenantId);
    } catch (err) {
      setError(messageFrom(err, 'License installation failed'));
    } finally {
      setBusy(false);
    }
  };

  if (loading) return <div className="platform-section"><p>Loading tenant licensing…</p></div>;
  if (!overview) return <div className="platform-error">{error || 'Tenant licensing is unavailable.'}</div>;

  const selectedExpiry = expirationInfo(selectedTenant?.claims?.expiresAt, nowMs);

  return (
    <div className="licensing-page">
      {error && <div className="platform-error">{error}</div>}
      {notice && <div className="platform-success">{notice}</div>}

      <section className={`licensing-readiness ${overview.enforcementEnabled ? 'enforced' : 'not-enforced'}`}>
        <div>
          <div className="platform-eyebrow">Tenant licensing</div>
          <h2>{overview.enforcementEnabled ? 'License enforcement is active' : 'License enforcement is disabled'}</h2>
          <p>
            {overview.enforcementEnabled
              ? 'Each tenant must have a valid signed license with DOCUMENT_PROCESSING before intake or processing can continue.'
              : 'This runtime is operating in non-enforced mode. This is intended for LOCAL developer/demo use only.'}
          </p>
        </div>
        <div className="licensing-readiness-meta">
          <span className={overview.enforcementEnabled ? 'platform-status platform-status-healthy' : 'platform-status platform-status-disabled'}>
            {overview.enforcementEnabled ? 'ENFORCED' : 'NOT ENFORCED'}
          </span>
          <span className={overview.verificationKeyConfigured ? 'licensing-key-ok' : 'licensing-key-bad'}>
            Verification key {overview.verificationKeyConfigured ? 'configured' : 'missing'}
          </span>
          <button className="secondary" disabled={busy} onClick={() => void loadOverview()}>Refresh</button>
        </div>
      </section>

      <div className="licensing-summary-grid">
        <div><span>Provisioned tenants</span><strong>{summary.total}</strong></div>
        <div><span>Active licenses</span><strong>{summary.active}</strong></div>
        <div><span>Processing allowed</span><strong>{summary.allowed}</strong></div>
        <div className={summary.attention > 0 ? 'needs-attention' : ''}><span>Needs attention</span><strong>{summary.attention}</strong></div>
      </div>

      <section className="platform-section">
        <div className="platform-section-heading">
          <div><h2>Tenant license status</h2><p>Select a tenant to install/update its license, review entitlements and inspect immutable history.</p></div>
          <span className="platform-count">{overview.tenants.length}</span>
        </div>
        {overview.tenants.length === 0 ? (
          <div className="platform-empty">No provisioned tenants are available for licensing.</div>
        ) : (
          <div className="licensing-tenant-table">
            <div className="licensing-tenant-header"><span>Tenant</span><span>Status</span><span>Processing</span><span>Edition</span><span>Expiration</span></div>
            {overview.tenants.map((tenant) => (
              <TenantRow key={tenant.tenantId} tenant={tenant} selected={tenant.tenantId === selectedTenantId} onSelect={() => setSelectedTenantId(tenant.tenantId)} nowMs={nowMs} />
            ))}
          </div>
        )}
      </section>

      {selectedTenant && (
        <div className="licensing-two-column">
          <section className="platform-section licensing-detail-card">
            <div className="platform-section-heading compact">
              <div><h2>{selectedTenant.tenantName}</h2><p>{selectedTenant.tenantSlug} · {selectedTenant.tenantId}</p></div>
              <span className={statusClass(selectedTenant.status)}>{displayStatus(selectedTenant.status)}</span>
            </div>

            <div className={`licensing-processing-banner ${selectedTenant.processingAllowed ? 'allowed' : 'blocked'}`}>
              <strong>{selectedTenant.processingAllowed ? 'Document processing allowed' : 'Document processing blocked'}</strong>
              <span>{processingReason(selectedTenant)}</span>
            </div>

            {selectedTenant.claims ? (
              <>
                <dl className="platform-definition-list licensing-definition-list">
                  <div><dt>Customer</dt><dd>{selectedTenant.claims.customerName}</dd></div>
                  <div><dt>Edition</dt><dd>{selectedTenant.claims.edition}</dd></div>
                  <div><dt>License ID</dt><dd><code>{selectedTenant.claims.licenseId}</code></dd></div>
                  <div><dt>Issued</dt><dd>{new Date(selectedTenant.claims.issuedAt).toLocaleString()}</dd></div>
                  <div><dt>Not before</dt><dd>{selectedTenant.claims.notBefore ? new Date(selectedTenant.claims.notBefore).toLocaleString() : 'Immediately'}</dd></div>
                  <div><dt>Expiration</dt><dd className={`licensing-expiry licensing-expiry-${selectedExpiry.tone}`}>{selectedExpiry.label}</dd></div>
                </dl>
                <div className="licensing-entitlements">
                  <h3>Entitlements</h3>
                  <div className="licensing-chip-list">
                    {selectedTenant.claims.entitlements.length > 0
                      ? selectedTenant.claims.entitlements.map((item) => <span key={item} className={item === 'DOCUMENT_PROCESSING' ? 'licensing-chip primary' : 'licensing-chip'}>{displayStatus(item)}</span>)
                      : <span className="licensing-muted">No entitlements</span>}
                  </div>
                </div>
                <div className="licensing-entitlements">
                  <h3>Limits</h3>
                  {Object.keys(selectedTenant.claims.limits).length === 0 ? (
                    <span className="licensing-muted">No numeric limits in this license.</span>
                  ) : (
                    <div className="licensing-limit-grid">
                      {Object.entries(selectedTenant.claims.limits).map(([key, value]) => <div key={key}><span>{displayStatus(key)}</span><strong>{value}</strong></div>)}
                    </div>
                  )}
                </div>
              </>
            ) : (
              <div className="platform-empty licensing-no-license"><strong>No active license claims</strong><span>Install a signed LexaVault license for this tenant.</span></div>
            )}
          </section>

          <section className="platform-section licensing-install-card">
            <div className="platform-section-heading compact">
              <div><h2>Install / update license</h2><p>Paste the complete vendor-signed <code>LV1...</code> token.</p></div>
            </div>
            <form onSubmit={(event) => void install(event)}>
              <label className="licensing-token-field">
                <span>Signed license token</span>
                <textarea
                  value={licenseToken}
                  onChange={(event) => setLicenseToken(event.target.value)}
                  placeholder="LV1.eyJ...signature"
                  rows={7}
                  spellCheck={false}
                  autoComplete="off"
                />
              </label>
              <div className="licensing-security-note">
                The signed token is sent only to the licensing API for verification/storage. Platform overview and history never return the raw token.
              </div>
              <button className="primary" type="submit" disabled={busy || !licenseToken.trim()}>{busy ? 'Verifying…' : 'Verify & install'}</button>
            </form>

            <div className="licensing-connected-validation">
              <strong>Verification mode</strong>
              <span>Offline signed license · Ed25519 public-key verification</span>
              <small>Connected/online validation is not required for on-prem operation and is not configured in this candidate.</small>
            </div>
          </section>
        </div>
      )}

      {selectedTenant && (
        <section className="platform-section">
          <div className="platform-section-heading">
            <div><h2>License history</h2><p>Prior records remain visible for auditability. Raw signed tokens are never shown.</p></div>
            <button className="secondary" disabled={historyLoading} onClick={() => void loadHistory(selectedTenant.tenantId)}>{historyLoading ? 'Loading…' : 'Refresh history'}</button>
          </div>
          {historyLoading ? (
            <div className="platform-empty">Loading license history…</div>
          ) : history.length === 0 ? (
            <div className="platform-empty">No license history exists for this tenant.</div>
          ) : (
            <div className="licensing-history-list">
              {history.map((item) => {
                const expiry = expirationInfo(item.claims?.expiresAt, nowMs);
                return (
                  <article key={item.recordId} className={`licensing-history-row ${item.active ? 'active' : ''}`}>
                    <div className="licensing-history-main">
                      <div className="licensing-history-title">
                        <strong>{item.claims?.edition || 'Unknown edition'}</strong>
                        {item.active && <span className="processing-storage-chip">Current</span>}
                        {item.verificationEvidence === 'VERIFIED_AT_INSTALL' && <span className="licensing-history-evidence">Verified at install</span>}
                        <span className={statusClass(item.status)}>{displayStatus(item.status)}</span>
                      </div>
                      <span>Installed {new Date(item.installedAt).toLocaleString()}</span>
                      <code>{item.licenseId}</code>
                    </div>
                    <div className="licensing-history-meta">
                      <span className={`licensing-expiry licensing-expiry-${expiry.tone}`}>{expiry.label}</span>
                      <span>{item.claims?.entitlements.length || 0} entitlements</span>
                      <small>Installed by {item.installedBy}</small>
                    </div>
                  </article>
                );
              })}
            </div>
          )}
        </section>
      )}
    </div>
  );
}
