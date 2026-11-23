import { useEffect, useState, type ReactNode } from 'react';
import PageScaffold from '../components/ui/PageScaffold';
import { apiClient } from '../api/client';
import type { EnterpriseIdentityOverview } from '../types';
import './AuthenticationAdminPage.css';

/**
 * Task 0041: read-only. Sign-in connections are set through setup (platform-identity's idn_login_connection); the
 * server has no draft, test, activate or roll-back step any more, so this page only shows the ACTIVE connection.
 */
const SET_BY_SETUP = 'Sign-in connections are set through setup.';

export default function AuthenticationAdminPage({ administrationTabs }: { administrationTabs?: ReactNode }) {
  const [overview, setOverview] = useState<EnterpriseIdentityOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    apiClient.getEnterpriseIdentityOverview().then(setOverview)
      .catch((reason: unknown) => setError(reason instanceof Error ? reason.message : 'Could not load enterprise authentication configuration.'))
      .finally(() => setLoading(false));
  }, []);

  const scaffold = { className: 'identity-admin-page', breadcrumbs: [{ label: 'Administration' }, { label: 'Authentication' }],
    title: 'Enterprise Authentication', eyebrow: 'Tenant identity', description: `The trusted sign-in connection, read-only. ${SET_BY_SETUP}` };
  if (loading) return <PageScaffold {...scaffold} primaryNavigation={administrationTabs}><div className="ui-card">Loading authentication configuration…</div></PageScaffold>;
  const active = overview?.active ?? null;
  return <PageScaffold {...scaffold} primaryNavigation={administrationTabs}>
    {error && <div className="ui-alert tone-danger">{error}</div>}
    <section className="ui-card identity-panel-layout">
      <div className="identity-panel-heading"><div><span>Current configuration</span><h2>ACTIVE connection</h2></div><strong className="identity-status">{active?.status ?? 'Unavailable'}</strong></div>
      <p className="identity-boundary">{SET_BY_SETUP} Nothing on this page changes sign-in.</p>
      {active ? <div className="identity-grid">
        <div><span>Provider</span><strong>{active.displayName}</strong></div>
        <div><span>Type</span><strong>{active.providerType}</strong></div>
        <div><span>Issuer</span><strong>{active.issuerUri}</strong></div>
        <div><span>Browser client</span><strong>{active.browserClientId ?? 'Not configured'}</strong></div>
        <div><span>API audience</span><strong>{active.apiAudience}</strong></div>
        <div><span>Version</span><strong>{active.configurationVersion}</strong></div>
      </div> : <p>No ACTIVE sign-in connection is stored for this tenant. Run setup with its sign-in file.</p>}
    </section>
  </PageScaffold>;
}
