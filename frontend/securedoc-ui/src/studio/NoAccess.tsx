import './theme';
import './studio.css';
import { noAccessTitle } from './lvCodes';
import { LvText } from './help/LvCodeLink';

/**
 * Signed in, but no LexaVault role (UI spec finding 21). One screen for both places it can happen: the Case File
 * workspace (a role with no capability, U001) and sign-in itself (no organization or a refused /me, U013).
 * Task 0025: the heading is the code's title and the code only ("No access to LexaVault · LV-AUT-002"); the exact
 * reason stays in the server's log and audit for IT. Task 0026: "Signed in as <email or user name>" under it.
 */
export default function NoAccess({ who, code, onSignOut, onTryAgain = () => window.location.reload() }: {
  /** The signed-in email or user name; "your account" when it is not known. */
  who?: string | null;
  /** The LV code from the server's 403 body; LV-AUT-002 when the server gave none. */
  code?: string | null;
  onSignOut: () => void;
  onTryAgain?: () => void;
}) {
  return (
    <div className="cf cf-boot">
      {/* Canvas NoAccess: the top bar with the brand only (U015). */}
      <header className="cf-top">
        <span className="cf-brand">
          <span className="cf-brand-mark" aria-hidden="true"><span /><span /><span /></span>
          <span className="cf-brand-word">LexaVault</span>
        </span>
      </header>
      <div className="cf-scope" role="main">
        <h1><LvText>{noAccessTitle(code)}</LvText></h1>
        {/* Task 0026: the person's own sign-in, shown only to them (it helps when the wrong account was used).
            Display only: never logged, sent or audited. */}
        {who && <p className="cf-scope-signed-in" data-testid="no-access-who">Signed in as <span className="cf-scope-who">{who}</span></p>}
        <p>
          Ask your administrator for access and give them the code above, then sign in again. If this isn&apos;t the
          account you meant to use, sign out and sign in with the right one.
        </p>
        <div className="cf-scope-actions">
          <button type="button" className="cf-btn" data-variant="solid" onClick={onSignOut}>Sign out</button>
          <button type="button" className="cf-btn" onClick={onTryAgain}>Try again</button>
        </div>
      </div>
    </div>
  );
}
