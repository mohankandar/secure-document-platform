import { useEffect } from 'react';
import type { TenantChoice } from '../types';
import './theme';
import './studio.css';

/**
 * The entity chooser at sign-in, in the Case File design (task 0022 A3). Like NoAccess it loads ./theme, so before
 * sign-in it already follows the computer's light or dark setting (the older auth-card screen did not). The words, the
 * one main landmark and the page title are those of U027/U028. No sign-in or session logic lives here.
 */
export default function EntityChooser({ title, choices, error, onChoose }: {
  title: string;
  choices: TenantChoice[];
  error?: string;
  onChoose: (tenantId: string) => void;
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
      <main className="cf-scope cf-chooser">
        <h1>Choose an entity</h1>
        <p>Choose an entity to continue.</p>
        {error && <div className="cf-notice" data-tone="danger" role="alert">{error}</div>}
        <ul className="cf-chooser-list">
          {choices.map((choice) => (
            <li key={choice.tenantId}>
              <button type="button" className="cf-btn" onClick={() => onChoose(choice.tenantId)}>{choice.name}</button>
            </li>
          ))}
        </ul>
      </main>
    </div>
  );
}
