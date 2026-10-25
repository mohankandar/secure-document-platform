import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import { setCurrentRoles } from './api/config';
import { ROLE_CAPABILITIES } from './demo/roles';
import { installDemoServer, storedDemoRole } from './demo/server';
import DemoControls from './demo/DemoControls';
import './tokens.css';
import './index.css';
import './styles/design-system.css';

// Prototype-only: every /api/v1 and scanner-bridge request is served in the browser from synthetic state.
installDemoServer();
const savedRole = storedDemoRole();
if (savedRole && ROLE_CAPABILITIES[savedRole]) setCurrentRoles(savedRole);

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
    <DemoControls />
  </React.StrictMode>
);
