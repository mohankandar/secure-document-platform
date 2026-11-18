import './theme';
import './studio.css';

/**
 * Signed in, but no LexaVault role (UI spec finding 21). One screen for both places it can happen: the Case File
 * workspace (a role with no capability, U001) and sign-in itself (no organization or a refused /me, U013).
 */
export default function NoAccess({ who, onSignOut, onTryAgain = () => window.location.reload() }: {
  /** The signed-in email or user name; "your account" when it is not known. */
  who?: string | null;
  onSignOut: () => void;
  onTryAgain?: () => void;
}) {
  return (
    <div className="cf cf-boot">
      <div className="cf-scope" role="main">
        <span className="cf-brand-mark" aria-hidden="true"><span /><span /><span /></span>
        <h1>You don&apos;t have access to anything in LexaVault yet</h1>
        <p>
          {who ? <>You&apos;re signed in as <span className="cf-scope-who">{who}</span>, but your account</> : <>You&apos;re signed in, but your account</>} has
          no LexaVault role. Ask your administrator to add you to a LexaVault group, then sign in again.
        </p>
        <div className="cf-scope-actions">
          <button type="button" className="cf-btn" data-variant="solid" onClick={onSignOut}>Sign out</button>
          <button type="button" className="cf-btn" onClick={onTryAgain}>Try again</button>
        </div>
      </div>
    </div>
  );
}
