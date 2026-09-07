import { useEffect, useMemo, useState } from 'react';
import { Routes, Route, NavLink, Navigate } from 'react-router-dom';
import { getCurrentRoles, setCurrentRoles, setCurrentCapabilities } from './api/config';
import { apiClient } from './api/client';
import { beginOidcLogin, clearOidcSession, getAccessToken, handleOidcCallback, isLocalSecurityMode } from './api/auth';
import type { CurrentUser } from './types';
import DashboardPage from './pages/DashboardPage';
import CreatePackagePage from './pages/CreatePackagePage';
import PackageDetailPage from './pages/PackageDetailPage';
import ReviewPage from './pages/ReviewPage';
import AuditPage from './pages/AuditPage';
import BusinessAdminPage from './pages/BusinessAdminPage';
import ReportsPage from './pages/ReportsPage';
import PlatformAdminPage from './pages/PlatformAdminPage';
import TechnicalIssueDetailPage from './pages/TechnicalIssueDetailPage';
import SupportIncidentsPage from './pages/SupportIncidentsPage';
import SupportIncidentDetailPage from './pages/SupportIncidentDetailPage';
import ManualFallbackPage from './pages/ManualFallbackPage';
import IntegrationsPage from './pages/IntegrationsPage';
import Breadcrumbs from './components/Breadcrumbs';
import { ROLE_OPTIONS } from './security/uiAuthorization';

function PermissionDenied() {
  return (
    <div>
      <Breadcrumbs items={[{ label: 'Permission required' }]} />
      <div className="card">
        <h1>Permission required</h1>
        <p>Your current role does not allow this operation.</p>
      </div>
    </div>
  );
}

export default function App() {
  const localMode = isLocalSecurityMode();
  const [selectedRole, setSelectedRole] = useState(getCurrentRoles());
  const [currentUser, setCurrentUser] = useState<CurrentUser | null>(null);
  const [authLoading, setAuthLoading] = useState(!localMode);
  const [authError, setAuthError] = useState('');

  useEffect(() => {
    if (localMode) return;
    let active = true;
    const load = async () => {
      try {
        await handleOidcCallback();
        if (!getAccessToken()) {
          if (active) setAuthLoading(false);
          return;
        }
        const user = await apiClient.getCurrentUser();
        if (active) {
          setCurrentUser(user);
          // Reuse trusted /me role/capability data for UI visibility. Backend authorization remains authoritative.
          setCurrentRoles(user.roles.join(','));
          setCurrentCapabilities(user.capabilities || []);
        }
      } catch {
        clearOidcSession();
        if (active) setAuthError('Your production session could not be verified. Please sign in again.');
      } finally {
        if (active) setAuthLoading(false);
      }
    };
    void load();
    return () => { active = false; };
  }, [localMode]);

  useEffect(() => {
    if (!localMode) return;
    let active = true;
    setCurrentCapabilities([]);
    const loadLocalPrincipal = async () => {
      try {
        const user = await apiClient.getCurrentUser();
        if (active) {
          setCurrentUser(user);
          setCurrentCapabilities(user.capabilities || []);
        }
      } catch {
        if (active) setCurrentUser(null);
      }
    };
    void loadLocalPrincipal();
    return () => { active = false; };
  }, [localMode, selectedRole]);

  const roles = useMemo(() => {
    if (localMode) return selectedRole.split(',').map((role) => role.trim()).filter(Boolean);
    return currentUser?.roles ?? [];
  }, [localMode, selectedRole, currentUser]);

  const hasRole = (role: string) => roles.includes('ADMIN') || roles.includes(role);
  const capabilities = currentUser?.capabilities ?? [];
  const hasCapability = (capability: string) => capabilities.includes(capability);

  const handleRoleChange = (value: string) => {
    setCurrentCapabilities([]);
    setCurrentUser(null);
    setCurrentRoles(value);
    setSelectedRole(value);
  };

  if (!localMode && authLoading) {
    return <div className="auth-shell"><div className="auth-card"><h1>SecureDoc</h1><p>Verifying your session…</p></div></div>;
  }

  if (!localMode && !currentUser) {
    return (
      <div className="auth-shell">
        <div className="auth-card">
          <h1>SecureDoc</h1>
          <p>Sign in with your organization identity to continue.</p>
          {authError && <div className="error-message">{authError}</div>}
          <button className="primary-button" onClick={() => void beginOidcLogin()}>Sign in</button>
        </div>
      </div>
    );
  }

  return (
    <div className="app-layout">
      <aside className="sidebar">
        <h2>SecureDoc</h2>
        <div className="demo-label">{localMode ? 'Local / Demo' : 'Enterprise Identity'}</div>
        {!localMode && currentUser && (
          <div className="identity-summary">
            <strong>{currentUser.username}</strong>
            <span>{currentUser.tenantName}</span>
          </div>
        )}
        <nav>
          {hasCapability('DASHBOARD_ACCESS') && <NavLink to="/" end>Dashboard</NavLink>}
          {hasCapability('DOCUMENT_INTAKE') && <NavLink to="/packages/new">Upload Documents</NavLink>}
          {hasCapability('MANUAL_REVIEW') && <NavLink to="/review">Manual Review</NavLink>}
          {hasCapability('REPORT_EXECUTIVE_VIEW') && <NavLink to="/reports">Reports</NavLink>}
          {(hasCapability('TENANT_POLICY_MANAGE') || hasCapability('TECHNICAL_ADMIN_VIEW') || hasCapability('AUDIT_VIEW')) && (
            <div className="sidebar-section-label">Administration</div>
          )}
          {hasCapability('TENANT_POLICY_MANAGE') && <NavLink to="/business-admin">Business Admin</NavLink>}
          {hasCapability('TENANT_INTEGRATION_MANAGE') && <NavLink to="/integrations">Integrations</NavLink>}
          {hasCapability('TECHNICAL_ADMIN_VIEW') && (
            <NavLink to="/platform-admin">{hasRole('PLATFORM_OPERATIONS_ADMIN') ? 'Platform Operations' : 'Tenant IT Admin'}</NavLink>
          )}
          {hasCapability('TECHNICAL_EXCEPTION_VIEW') && <NavLink to="/support/incidents">Support Incidents</NavLink>}
          {hasCapability('AUDIT_VIEW') && <NavLink to="/audit">Audit</NavLink>}
        </nav>
        {localMode ? (
          <div style={{ padding: '1rem', borderTop: '1px solid #636e72', marginTop: '1rem' }}>
            <label style={{ fontSize: '0.7rem', color: '#fdcb6e', textTransform: 'uppercase' }}>Demo Role</label>
            <select value={selectedRole} onChange={(e) => handleRoleChange(e.target.value)}
              style={{ width: '100%', marginTop: '0.25rem', fontSize: '0.8rem', padding: '0.3rem' }}>
              {ROLE_OPTIONS.map((role) => <option key={role.value} value={role.value}>{role.label}</option>)}
            </select>
          </div>
        ) : (
          <button className="sidebar-signout" onClick={() => { clearOidcSession(); setCurrentCapabilities([]); setCurrentRoles('DOCUMENT_PROCESSOR'); setCurrentUser(null); }}>Sign out</button>
        )}
      </aside>
      <main className="main-content">
        <Routes>
          <Route path="/" element={hasCapability('DASHBOARD_ACCESS') ? <DashboardPage /> : hasCapability('REPORT_EXECUTIVE_VIEW') ? <Navigate to="/reports" replace /> : <PermissionDenied />} />
          <Route path="/packages/new" element={hasCapability('DOCUMENT_INTAKE') ? <CreatePackagePage /> : <PermissionDenied />} />
          <Route path="/packages/:packageId" element={hasCapability('DASHBOARD_ACCESS') ? <PackageDetailPage /> : <PermissionDenied />} />
          <Route path="/review" element={hasCapability('MANUAL_REVIEW') ? <ReviewPage /> : <PermissionDenied />} />
          <Route path="/packages/:packageId/manual-processing" element={hasCapability('MANUAL_REVIEW') ? <ManualFallbackPage /> : <PermissionDenied />} />
          <Route path="/reports" element={hasCapability('REPORT_EXECUTIVE_VIEW')
            ? <ReportsPage canViewOperations={hasCapability('REPORT_OPERATIONAL_VIEW')} canExport={hasCapability('REPORT_EXPORT')} />
            : <PermissionDenied />} />
          <Route path="/audit" element={hasCapability('AUDIT_VIEW') ? <AuditPage /> : <PermissionDenied />} />
          <Route path="/business-admin" element={hasCapability('TENANT_POLICY_MANAGE') ? <BusinessAdminPage /> : <PermissionDenied />} />
          <Route path="/platform-admin" element={hasCapability('TECHNICAL_ADMIN_VIEW') ? <PlatformAdminPage /> : <PermissionDenied />} />
          <Route path="/integrations" element={hasCapability('TENANT_INTEGRATION_MANAGE') ? <IntegrationsPage /> : <PermissionDenied />} />
          <Route path="/technical-exceptions/:packageId" element={hasCapability('TECHNICAL_EXCEPTION_VIEW') ? <TechnicalIssueDetailPage /> : <PermissionDenied />} />
          <Route path="/support/incidents" element={hasCapability('TECHNICAL_EXCEPTION_VIEW') ? <SupportIncidentsPage /> : <PermissionDenied />} />
          <Route path="/support/incidents/:incidentId" element={hasCapability('TECHNICAL_EXCEPTION_VIEW') ? <SupportIncidentDetailPage /> : <PermissionDenied />} />
        </Routes>
      </main>
    </div>
  );
}
