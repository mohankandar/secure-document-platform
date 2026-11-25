import React, { lazy, Suspense } from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import { getAccessToken, isLocalSecurityMode } from './api/auth';
import { takeApprovalLinkFromAddress } from './studio/approvalLinkStore';
import { isHelpPath } from './studio/help/guideRules';
import './fonts';
import './tokens.css';
import './index.css';

takeApprovalLinkFromAddress();

// Task 0082: the error code guide is static text. In a tab without a session it is shown by itself: no sign-in, no API call.
const HelpAlone = lazy(() => import('./studio/help/HelpAlone'));
const caseFile = import.meta.env.VITE_CASE_FILE_UI_ENABLED === 'true';
const helpAlone = isHelpPath(window.location.pathname) && (!caseFile || (!isLocalSecurityMode() && !getAccessToken()));

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      {helpAlone ? <Suspense fallback={null}><HelpAlone /></Suspense> : <App />}
    </BrowserRouter>
  </React.StrictMode>
);
