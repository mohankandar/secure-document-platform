import useSWR from 'swr';
import { apiClient } from '../../api/client';
import type { EnterpriseIdentityOverview } from '../../types';
import { HelpTip } from './HelpTip';

/**
 * Admin › Sign-in (UI task U009, owner's decision B). Sign-in is set at the login hub, by the sign-in config file and
 * the setup command, so this tab only says so. Below that, read-only, are the details the server already returns: the
 * login system's name and address. Nothing here can be edited, tested or switched on.
 *
 * The server returns no "last used successfully" time (only a last test of a staged draft), so none is shown.
 *
 * Task 0041: the connection shown is the ACTIVE one in platform-identity (idn_login_connection), set through setup. The
 * server has no draft, test, activate or roll-back step any more; nothing here changes sign-in.
 */
export const SIGN_IN_SET_BY_SETUP = 'Sign-in connections are set through setup.';

export default function IdentityArea() {
  const { data } = useSWR<EnterpriseIdentityOverview>('enterprise-identity', () => apiClient.getEnterpriseIdentityOverview(), { revalidateOnFocus: false });
  const active = data?.active ?? null;

  return (
    <div className="cf-ad-stack">
      <section className="cf-ad-card" aria-label="Login hub">
        <header className="cf-ad-card-head">
          <div>
            <div className="cf-ad-title"><h2>Sign-in</h2><HelpTip topic="signIn" /></div>
            <p>Your sign-in is managed through the login hub. To change it, contact LexaVault support.</p>
            <p className="cf-muted" data-testid="signin-managed-by">{SIGN_IN_SET_BY_SETUP}</p>
          </div>
        </header>
        {active && (
          <dl className="cf-facts" aria-label="Login system">
            {active.displayName && <div><dt>Login system</dt><dd>{active.displayName}</dd></div>}
            {active.issuerUri && <div><dt>Address</dt><dd className="cf-mono">{active.issuerUri}</dd></div>}
          </dl>
        )}
      </section>
    </div>
  );
}
