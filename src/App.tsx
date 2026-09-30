import { useEffect, useMemo, useState } from 'react';
import { Routes, Route, NavLink, Navigate } from 'react-router-dom';
import { getCurrentRoles, setCurrentRoles, setCurrentCapabilities } from './api/config';
import { apiClient } from './api/client';
import { beginOidcLogin, clearOidcSession, endOidcSession, getAccessToken, handleOidcCallback, isLocalSecurityMode } from './api/auth';
import type { CurrentUser } from './types';
import DashboardPage from './pages/DashboardPage';
import CreatePackagePage from './pages/CreatePackagePage';
import ScanIntakePage from './pages/ScanIntakePage';
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
import Icon, { type IconName } from './components/ui/Icon';
import { BrandingProvider, useBranding } from './branding/BrandingContext';
import useMediaQuery from './components/ui/useMediaQuery';

function PermissionDenied() {
  return (
    <div>
      <Breadcrumbs items={[{ label: 'Permission required' }]} />
      <div className="card ui-state-card">
        <h1>Permission required</h1>
        <p>Your current role does not allow this operation.</p>
      </div>
    </div>
  );
}

type NavEntry = { to: string; label: string; icon: IconName; show: boolean; end?: boolean };

function BrandMark({ compact = false }: { compact?: boolean }) {
  const { branding } = useBranding();
  const primary = branding.primaryLogoDataUrl;
  const compactLogo = branding.compactLogoDataUrl;
  const logo = compact ? (compactLogo || primary) : (primary || compactLogo);
  const usesCompactAsset = compact || (!primary && Boolean(compactLogo));
  if (logo) return <img className={usesCompactAsset ? 'brand-logo compact' : 'brand-logo'} src={logo} alt={`${branding.applicationName} logo`} />;
  const label = (branding.shortName || branding.applicationName || 'L').trim();
  return <span className="brand-lettermark" aria-hidden="true">{label.slice(0, 1).toUpperCase()}</span>;
}

function NavigationGroup({ title, entries, onNavigate }: { title: string; entries: NavEntry[]; onNavigate: () => void }) {
  const visible = entries.filter((entry) => entry.show);
  if (!visible.length) return null;
  return (
    <div className="app-nav-group">
      <div className="app-nav-group-title">{title}</div>
      {visible.map((entry) => (
        <NavLink key={entry.to} to={entry.to} end={entry.end} onClick={onNavigate} className={({ isActive }) => `app-nav-link ${isActive ? 'active' : ''}`}>
          <Icon name={entry.icon} />
          <span className="app-nav-label">{entry.label}</span>
        </NavLink>
      ))}
    </div>
  );
}

function AuthenticatedApplication({ localMode, selectedRole, currentUser, roles, hasRole, hasCapability, onRoleChange, onSignOut }: {
  localMode: boolean;
  selectedRole: string;
  currentUser: CurrentUser | null;
  roles: string[];
  hasRole: (role: string) => boolean;
  hasCapability: (capability: string) => boolean;
  onRoleChange: (value: string) => void;
  onSignOut: () => void;
}) {
  const { branding } = useBranding();
  const [collapsed, setCollapsed] = useState(() => localStorage.getItem('lexavault.ui.sidebarCollapsed') === 'true');
  const [tabletExpanded, setTabletExpanded] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const tabletLandscape = useMediaQuery('(min-width: 900px) and (max-width: 1199px)');
  const drawerMode = useMediaQuery('(max-width: 899px)');
  const effectiveCollapsed = tabletLandscape ? !tabletExpanded : collapsed;
  const sidebarCompact = effectiveCollapsed && !drawerMode;
  const hasPrimaryLogo = Boolean(branding.primaryLogoDataUrl);

  useEffect(() => {
    const close = () => setDrawerOpen(false);
    window.addEventListener('resize', close);
    return () => window.removeEventListener('resize', close);
  }, []);

  useEffect(() => {
    if (!tabletLandscape) setTabletExpanded(false);
  }, [tabletLandscape]);

  const toggleCollapsed = () => {
    if (tabletLandscape) {
      setTabletExpanded((current) => !current);
      return;
    }
    setCollapsed((current) => {
      const next = !current;
      localStorage.setItem('lexavault.ui.sidebarCollapsed', String(next));
      return next;
    });
  };

  const work: NavEntry[] = [
    { to: '/', label: 'Dashboard', icon: 'dashboard', show: hasCapability('DASHBOARD_ACCESS'), end: true },
    { to: '/packages/new', label: 'Upload Documents', icon: 'upload', show: hasCapability('DOCUMENT_INTAKE') },
    { to: '/scanner-intake', label: 'Scan Documents', icon: 'scan', show: hasCapability('DOCUMENT_INTAKE') },
    { to: '/review', label: 'Manual Review', icon: 'review', show: hasCapability('MANUAL_REVIEW') },
  ];
  const reports: NavEntry[] = [
    { to: '/reports', label: 'Reports', icon: 'reports', show: hasCapability('REPORT_EXECUTIVE_VIEW') },
  ];
  const admin: NavEntry[] = [
    { to: '/business-admin', label: 'Business Administration', icon: 'admin', show: hasCapability('TENANT_POLICY_MANAGE') },
    { to: '/integrations', label: 'Integrations', icon: 'integrations', show: hasCapability('TENANT_INTEGRATION_MANAGE') },
    { to: '/platform-admin', label: hasRole('PLATFORM_OPERATIONS_ADMIN') ? 'Technical Administration' : 'Tenant IT Administration', icon: 'admin', show: hasCapability('TECHNICAL_ADMIN_VIEW') },
  ];
  const support: NavEntry[] = [
    { to: '/support/incidents', label: 'Support Incidents', icon: 'support', show: hasCapability('TECHNICAL_EXCEPTION_VIEW') },
    { to: '/audit', label: 'Audit', icon: 'audit', show: hasCapability('AUDIT_VIEW') },
  ];

  const initials = (currentUser?.username || 'User').split(/[.@\s_-]+/).filter(Boolean).slice(0, 2).map((part) => part[0]?.toUpperCase()).join('') || 'U';

  return (
    <div className={`modern-app-shell ${effectiveCollapsed ? 'sidebar-collapsed' : ''}`} data-ui-design-system="1">
      <header className="mobile-app-bar">
        <button className="shell-icon-button" type="button" onClick={() => setDrawerOpen(true)} aria-label="Open navigation"><Icon name="menu" /></button>
        <div className="mobile-brand"><BrandMark compact /><strong>{branding.shortName || branding.applicationName}</strong></div>
        <div className="mobile-user-avatar" title={currentUser?.username || 'User'}>{initials}</div>
      </header>

      {drawerOpen && <button className="nav-drawer-backdrop" aria-label="Close navigation" onClick={() => setDrawerOpen(false)} />}
      <aside className={`modern-sidebar ${drawerOpen ? 'drawer-open' : ''}`}>
        <div className="sidebar-brand-row">
          <div className={`sidebar-brand ${hasPrimaryLogo && !sidebarCompact ? 'primary-logo-only' : ''}`} title={`${branding.applicationName}${branding.organizationName ? ` · ${branding.organizationName}` : ''}`}>
            <BrandMark compact={sidebarCompact} />
            {(!hasPrimaryLogo || sidebarCompact) && (
              <div className="sidebar-brand-copy">
                <strong>{branding.applicationName}</strong>
                <span>{branding.organizationName || (localMode ? 'Local / Demo' : 'Enterprise')}</span>
              </div>
            )}
          </div>
          <button className="sidebar-collapse-button" type="button" onClick={toggleCollapsed} aria-label={effectiveCollapsed ? 'Expand navigation' : 'Collapse navigation'}>
            <Icon name={effectiveCollapsed ? 'chevronRight' : 'chevronLeft'} />
          </button>
        </div>

        <nav className="modern-sidebar-nav" aria-label="Primary navigation">
          <NavigationGroup title="Work" entries={work} onNavigate={() => setDrawerOpen(false)} />
          <NavigationGroup title="Reports" entries={reports} onNavigate={() => setDrawerOpen(false)} />
          <NavigationGroup title="Administration" entries={admin} onNavigate={() => setDrawerOpen(false)} />
          <NavigationGroup title="Support" entries={support} onNavigate={() => setDrawerOpen(false)} />
        </nav>

        <div className="sidebar-footer">
          {localMode ? (
            <label className="demo-role-control">
              <span>Demo role</span>
              <select value={selectedRole} onChange={(event) => onRoleChange(event.target.value)}>
                {ROLE_OPTIONS.map((role) => <option key={role.value} value={role.value}>{role.label}</option>)}
              </select>
            </label>
          ) : (
            <button className="sidebar-signout-modern" onClick={onSignOut}>Sign out</button>
          )}
          <div className="sidebar-identity">
            <div className="sidebar-avatar">{initials}</div>
            <div className="sidebar-identity-copy">
              <strong>{currentUser?.username || (roles[0] ?? 'Local user')}</strong>
              <span>{currentUser?.tenantName || 'Local Demo Tenant'}</span>
            </div>
          </div>
        </div>
      </aside>

      <main className="modern-main-content">
        <Routes>
          <Route path="/" element={hasCapability('DASHBOARD_ACCESS') ? <DashboardPage /> : hasCapability('REPORT_EXECUTIVE_VIEW') ? <Navigate to="/reports" replace /> : <PermissionDenied />} />
          <Route path="/packages/new" element={hasCapability('DOCUMENT_INTAKE') ? <CreatePackagePage /> : <PermissionDenied />} />
          <Route path="/scanner-intake" element={hasCapability('DOCUMENT_INTAKE') ? <ScanIntakePage /> : <PermissionDenied />} />
          <Route path="/packages/:packageId" element={hasCapability('DASHBOARD_ACCESS') ? <PackageDetailPage /> : <PermissionDenied />} />
          <Route path="/review" element={hasCapability('MANUAL_REVIEW') ? <ReviewPage /> : <PermissionDenied />} />
          <Route path="/packages/:packageId/manual-processing" element={hasCapability('MANUAL_REVIEW') ? <ManualFallbackPage /> : <PermissionDenied />} />
          <Route path="/reports" element={hasCapability('REPORT_EXECUTIVE_VIEW')
            ? <ReportsPage canViewOperations={hasCapability('REPORT_OPERATIONAL_VIEW')} canViewBusiness={hasCapability('TENANT_IDENTIFIER_MANAGE')} canExport={hasCapability('REPORT_EXPORT')} canDrillDown={hasCapability('DASHBOARD_ACCESS')} />
            : <PermissionDenied />} />
          <Route path="/audit" element={hasCapability('AUDIT_VIEW') ? <AuditPage /> : <PermissionDenied />} />
          <Route path="/business-admin" element={hasCapability('TENANT_POLICY_MANAGE') ? <BusinessAdminPage /> : <PermissionDenied />} />
          <Route path="/platform-admin" element={hasCapability('TECHNICAL_ADMIN_VIEW') ? <PlatformAdminPage canManagePlatformOperations={hasCapability('PLATFORM_OPERATIONS')} /> : <PermissionDenied />} />
          <Route path="/integrations" element={hasCapability('TENANT_INTEGRATION_MANAGE') ? <IntegrationsPage /> : <PermissionDenied />} />
          <Route path="/technical-exceptions/:packageId" element={hasCapability('TECHNICAL_EXCEPTION_VIEW') ? <TechnicalIssueDetailPage /> : <PermissionDenied />} />
          <Route path="/support/incidents" element={hasCapability('TECHNICAL_EXCEPTION_VIEW') ? <SupportIncidentsPage /> : <PermissionDenied />} />
          <Route path="/support/incidents/:incidentId" element={hasCapability('TECHNICAL_EXCEPTION_VIEW') ? <SupportIncidentDetailPage /> : <PermissionDenied />} />
        </Routes>
      </main>
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
        if (!getAccessToken()) { if (active) setAuthLoading(false); return; }
        const user = await apiClient.getCurrentUser();
        if (active) {
          setCurrentUser(user);
          setCurrentRoles(user.roles.join(','));
          setCurrentCapabilities(user.capabilities || []);
        }
      } catch {
        clearOidcSession();
        if (active) setAuthError('Your production session could not be verified. Please sign in again.');
      } finally { if (active) setAuthLoading(false); }
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
        if (active) { setCurrentUser(user); setCurrentCapabilities(user.capabilities || []); }
      } catch { if (active) setCurrentUser(null); }
    };
    void loadLocalPrincipal();
    return () => { active = false; };
  }, [localMode, selectedRole]);

  const roles = useMemo(() => localMode
    ? selectedRole.split(',').map((role) => role.trim()).filter(Boolean)
    : currentUser?.roles ?? [], [localMode, selectedRole, currentUser]);
  const hasRole = (role: string) => roles.includes('ADMIN') || roles.includes(role);
  const capabilities = currentUser?.capabilities ?? [];
  const hasCapability = (capability: string) => capabilities.includes(capability);

  const handleRoleChange = (value: string) => {
    setCurrentCapabilities([]); setCurrentUser(null); setCurrentRoles(value); setSelectedRole(value);
  };

  if (!localMode && authLoading) {
    return <div className="auth-shell"><div className="auth-card"><h1>Secure Document Platform</h1><p>Verifying your session…</p></div></div>;
  }

  if (!localMode && !currentUser) {
    return (
      <div className="auth-shell"><div className="auth-card"><h1>Secure Document Platform</h1><p>Sign in with your organization identity to continue.</p>
        {authError && <div className="error-message">{authError}</div>}
        <button className="primary-button" onClick={() => void beginOidcLogin()}>Sign in</button>
      </div></div>
    );
  }

  const signOut = () => {
    setCurrentCapabilities([]);
    setCurrentRoles('DOCUMENT_PROCESSOR');
    setCurrentUser(null);
    if (localMode) { clearOidcSession(); return; }
    void endOidcSession();
  };

  return (
    <BrandingProvider>
      <AuthenticatedApplication localMode={localMode} selectedRole={selectedRole} currentUser={currentUser} roles={roles}
        hasRole={hasRole} hasCapability={hasCapability} onRoleChange={handleRoleChange} onSignOut={signOut} />
    </BrandingProvider>
  );
}
