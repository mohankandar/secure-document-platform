import { useState } from 'react';
import { resetState } from './state';
import { getDemoSettings, setDemoSettings, type DemoLatency } from './settings';
import { getCurrentRoles } from '../api/config';
import { ROLE_CAPABILITIES } from './roles';
import { storeDemoRole } from './server';
import './DemoControls.css';

const DEMO_ROLES: { value: string; label: string }[] = [
  { value: 'DOCUMENT_PROCESSOR', label: 'Document Processor' },
  { value: 'DOCUMENT_SUPERVISOR', label: 'Document Supervisor' },
  { value: 'BUSINESS_ADMIN', label: 'Business Administrator' },
  { value: 'REPORT_VIEWER', label: 'Report Viewer' },
  { value: 'TENANT_IT_ADMIN', label: 'Tenant IT Administrator' },
  { value: 'PLATFORM_OPERATIONS_ADMIN', label: 'Platform Operations Administrator' },
  { value: 'ADMIN', label: 'System Administrator' },
].filter((role) => ROLE_CAPABILITIES[role.value]);

function switchRole(role: string) {
  if (role === getCurrentRoles()) return;
  storeDemoRole(role);
  reload();
}

function reload() {
  window.location.assign(window.location.pathname === '/' ? '/' : window.location.pathname);
}

export default function DemoControls() {
  const [open, setOpen] = useState(false);
  const [settings, setSettings] = useState(getDemoSettings);

  const update = (patch: Parameters<typeof setDemoSettings>[0]) => setSettings(setDemoSettings(patch));
  const reset = (empty: boolean) => {
    const message = empty
      ? 'Clear all packages, review items, incidents and audit records to preview empty states?'
      : 'Restore the original sample data? Changes you made in this browser will be discarded.';
    if (!window.confirm(message)) return;
    resetState({ empty });
    reload();
  };

  return (
    <div className="demo-controls" data-open={open}>
      {open && (
        <section id="demo-controls-panel" className="demo-controls-panel" aria-label="Prototype demo controls">
          <header>
            <strong>Prototype demo</strong>
            <span>Synthetic data stored in this browser. No backend required.</span>
          </header>

          <div className="demo-controls-role">
            <label htmlFor="demo-role-select">Demo role</label>
            <select
              id="demo-role-select"
              value={getCurrentRoles()}
              onChange={(event) => switchRole(event.target.value)}
              aria-describedby="demo-role-hint"
            >
              {DEMO_ROLES.map((role) => (
                <option key={role.value} value={role.value}>{role.label}</option>
              ))}
            </select>
            <small id="demo-role-hint">Reloads with this role. Demo data is kept.</small>
          </div>

          <fieldset>
            <legend>Network latency</legend>
            <div className="demo-controls-segment">
              {(['off', 'normal', 'slow'] as DemoLatency[]).map((value) => (
                <button key={value} type="button" aria-pressed={settings.latency === value} onClick={() => update({ latency: value })}>
                  {value === 'off' ? 'Instant' : value === 'normal' ? 'Realistic' : 'Slow'}
                </button>
              ))}
            </div>
          </fieldset>

          <label className="demo-controls-toggle">
            <input type="checkbox" checked={settings.outage} onChange={(event) => update({ outage: event.target.checked })} />
            <span>
              <strong>Simulate service outage</strong>
              <small>API calls return 503 and the scanner bridge goes offline.</small>
            </span>
          </label>

          <label className="demo-controls-toggle">
            <input type="checkbox" checked={settings.scannerOffline} onChange={(event) => update({ scannerOffline: event.target.checked })} />
            <span>
              <strong>Scanner offline</strong>
              <small>The bridge responds, but the device cannot acquire pages.</small>
            </span>
          </label>

          <div className="demo-controls-actions">
            <button type="button" onClick={() => reset(false)}>Reset demo</button>
            <button type="button" onClick={() => reset(true)}>Load empty workspace</button>
          </div>
        </section>
      )}
      <button
        type="button"
        className="demo-controls-trigger"
        aria-expanded={open}
        aria-controls="demo-controls-panel"
        onClick={() => setOpen((value) => !value)}
      >
        <span className={`demo-controls-dot ${settings.outage ? 'is-outage' : ''}`} aria-hidden="true" />
        Demo
      </button>
    </div>
  );
}
