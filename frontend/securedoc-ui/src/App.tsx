import { useEffect, useMemo, useState } from 'react';
import { Routes, Route, NavLink } from 'react-router-dom';
import { getCurrentRoles, setCurrentRoles } from './api/config';
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

const ROLE_OPTIONS = [
  'ADMIN', 'USER', 'REVIEWER', 'APPROVER', 'BUSINESS_ADMIN', 'PLATFORM_ADMIN',
  'USER,REVIEWER', 'USER,APPROVER', 'USER,BUSINESS_ADMIN', 'USER,PLATFORM_ADMIN',
];

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
          // Reuse the existing UI role hints from trusted /me data. In OIDC mode these
          // values are never sent as identity headers; the backend remains authoritative.
          setCurrentRoles(user.roles.join(','));
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

  const roles = useMemo(() => {
    if (localMode) return selectedRole.split(',').map((role) => role.trim()).filter(Boolean);
    return currentUser?.roles ?? [];
  }, [localMode, selectedRole, currentUser]);

  const hasRole = (role: string) => roles.includes('ADMIN') || roles.includes(role);

  const handleRoleChange = (value: string) => {
    setSelectedRole(value);
    setCurrentRoles(value);
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
          <NavLink to="/" end>Dashboard</NavLink>
          <NavLink to="/packages/new">Upload Documents</NavLink>
          {hasRole('REVIEWER') && <NavLink to="/review">Manual Review</NavLink>}
          <NavLink to="/reports">Reports</NavLink>
          {(hasRole('BUSINESS_ADMIN') || hasRole('PLATFORM_ADMIN')) && (
            <div className="sidebar-section-label">Administration</div>
          )}
          {hasRole('BUSINESS_ADMIN') && <NavLink to="/business-admin">Business Admin</NavLink>}
          {hasRole('PLATFORM_ADMIN') && <NavLink to="/platform-admin">IT / Platform Admin</NavLink>}
          {hasRole('PLATFORM_ADMIN') && <NavLink to="/audit">Audit</NavLink>}
        </nav>
        {localMode ? (
          <div style={{ padding: '1rem', borderTop: '1px solid #636e72', marginTop: '1rem' }}>
            <label style={{ fontSize: '0.7rem', color: '#fdcb6e', textTransform: 'uppercase' }}>Demo Role</label>
            <select value={selectedRole} onChange={(e) => handleRoleChange(e.target.value)}
              style={{ width: '100%', marginTop: '0.25rem', fontSize: '0.8rem', padding: '0.3rem' }}>
              {ROLE_OPTIONS.map((role) => <option key={role} value={role}>{role}</option>)}
            </select>
          </div>
        ) : (
          <button className="sidebar-signout" onClick={() => { clearOidcSession(); setCurrentRoles('USER'); setCurrentUser(null); }}>Sign out</button>
        )}
      </aside>
      <main className="main-content">
        <Routes>
          <Route path="/" element={<DashboardPage />} />
          <Route path="/packages/new" element={<CreatePackagePage />} />
          <Route path="/packages/:packageId" element={<PackageDetailPage />} />
          <Route path="/review" element={<ReviewPage />} />
          <Route path="/reports" element={<ReportsPage />} />
          <Route path="/audit" element={<AuditPage />} />
          <Route path="/business-admin" element={<BusinessAdminPage />} />
          <Route path="/platform-admin" element={<PlatformAdminPage />} />
        </Routes>
      </main>
    </div>
  );
}
