import { useEffect } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import ErrorCodes from './ErrorCodes';
import { HELP_PATH } from './guideRules';
import { screenTitle } from '../words';
import '../theme';
import '../studio.css';

/**
 * Task 0082: the guide in a tab that has no session (a code's link opens one, and the session stays in the tab where the
 * work is). It calls nothing and reads no session: the same static text as Help › Error codes, with the brand only.
 */
export default function HelpAlone() {
  useEffect(() => { document.title = screenTitle(HELP_PATH, 'LexaVault'); }, []);
  return (
    <div className="cf">
      <div className="cf-shell">
        <a className="cf-skip" href="#cf-main">Skip to content</a>
        <header className="cf-top">
          <span className="cf-brand">
            <span className="cf-brand-mark" aria-hidden="true"><span /><span /><span /></span>
            <span className="cf-brand-word">LexaVault</span>
          </span>
        </header>
        <main className="cf-main" id="cf-main">
          <Routes>
            <Route path={HELP_PATH} element={<ErrorCodes />} />
            <Route path={`${HELP_PATH}/:code`} element={<ErrorCodes />} />
            <Route path="*" element={<Navigate to={HELP_PATH} replace />} />
          </Routes>
        </main>
      </div>
    </div>
  );
}
