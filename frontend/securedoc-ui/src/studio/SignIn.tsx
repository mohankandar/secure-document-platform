import { useEffect } from 'react';
import './theme';
import './studio.css';

/**
 * The sign-in screen in the Case File design (task 0023 A2). Built like EntityChooser (0022 A3) and NoAccess: it loads
 * ./theme, so before sign-in it already follows the computer's light or dark setting (the older auth-card screen did
 * not). The words, the one main landmark, the product name heading and the page title are those of U027. Also used for
 * the "Verifying your session…" and "Switching entity…" moments, so they do not flash light on a dark computer.
 * No sign-in or session logic lives here.
 */
export default function SignIn({ title, message, status = false, error, action }: {
  title: string;
  message: string;
  /** The message is a live status line ("Switching entity…"). */
  status?: boolean;
  error?: string;
  action?: { label: string; onClick: () => void };
}) {
  useEffect(() => { document.title = title; }, [title]);
  return (
    <div className="cf cf-boot">
      <header className="cf-top">
        <span className="cf-brand">
          <span className="cf-brand-mark" aria-hidden="true"><span /><span /><span /></span>
          <span className="cf-brand-word">LexaVault</span>
        </span>
      </header>
      <main className="cf-scope">
        <h1>LexaVault</h1>
        <p role={status ? 'status' : undefined}>{message}</p>
        {error && <div className="cf-notice" data-tone="danger" role="alert">{error}</div>}
        {action && (
          <div className="cf-scope-actions">
            <button type="button" className="cf-btn" data-variant="solid" onClick={action.onClick}>{action.label}</button>
          </div>
        )}
      </main>
    </div>
  );
}
