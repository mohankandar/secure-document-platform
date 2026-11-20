import { lazy, Suspense, useEffect, useMemo, useState, type ReactNode } from 'react';
import { Routes, Route, NavLink, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { getCurrentRoles, setCurrentRoles, setCurrentCapabilities } from './api/config';
import { apiClient } from './api/client';
import { selectedOidcTenant, setSelectedOidcTenant, TENANT_CONTEXT_CHANGED_EVENT } from './api/tenantContext';
import { beginOidcLogin, clearOidcSession, endOidcSession, getAccessToken, handleOidcCallback, isLocalSecurityMode, OIDC_SESSION_EXPIRED_EVENT, signedInIdentity } from './api/auth';
import type { CurrentUser, TenantChoice } from './types';
import DashboardPage from './pages/DashboardPage';
import CreatePackagePage from './pages/CreatePackagePage';
import ScanIntakePage from './pages/ScanIntakePage';
import PackageDetailPage from './pages/PackageDetailPage';
import ReviewPage from './pages/ReviewPage';
import AuditPage from './pages/AuditPage';
import BusinessAdminPage from './pages/BusinessAdminPage';
import ReportsPage from './pages/ReportsPage';
import PlatformAdminPage from './pages/PlatformAdminPage';
import AuthenticationAdminPage from './pages/AuthenticationAdminPage';
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
import AdministrationTabs from './components/AdministrationTabs';
import Button from './components/ui/Button';
const CaseFileApp = lazy(() => import('./studio/CaseFileApp'));
// The "no access" screen in the Case File design (UI task U013); loaded only when someone is refused.
const NoAccess = lazy(() => import('./studio/NoAccess'));

/** The server lists no organization for this account: a refusal, the same as a 403 from /me (owner's decision, U013). */
class NoOrganizationError extends Error {}

/**
 * U027 (WCAG 1.3.1, 2.4.2): the sign-in and entity chooser screens, markup only. One `main` landmark, the product
 * name, and a page title per screen. No sign-in or session logic lives here.
 */
const PRODUCT_NAME = 'LexaVault';
const SIGN_IN_TITLE = `Sign in · ${PRODUCT_NAME}`;
const CHOOSE_ENTITY_TITLE = `Choose an entity · ${PRODUCT_NAME}`;

function AuthScreen({ title, children }: { title: string; children: ReactNode }) {
  useEffect(() => { document.title = title; }, [title]);
  return <main className="auth-shell"><div className="auth-card">{children}</div></main>;
}

function PermissionDenied() {
  return (
    <div>
      <Breadcrumbs items={[{ label: 'Permission required' }]} />
      <div className="ui-card ui-state-card">
        <h1>Permission required</h1>
        <p>Your current role does not allow this operation.</p>
      </div>
    </div>
  );
}

type NavEntry = { to: string; label: string; icon: IconName; show: boolean; end?: boolean; activeWhen?: string[] };

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
  const location = useLocation();
  if (!visible.length) return null;
  return (
    <div className="app-nav-group">
      <div className="app-nav-group-title">{title}</div>
      {visible.map((entry) => (
        <NavLink key={entry.to} to={entry.to} end={entry.end} onClick={onNavigate} className={({ isActive }) => {
          const groupedActive = entry.activeWhen?.some((prefix) => location.pathname.startsWith(prefix)) ?? false;
          return `app-nav-link ${isActive || groupedActive ? 'active' : ''}`;
        }}>
          <Icon name={entry.icon} />
          <span className="app-nav-label">{entry.label}</span>
        </NavLink>
      ))}
    </div>
  );
}

function AuthenticatedApplication({ localMode, selectedRole, currentUser, roles, hasRole, hasCapability, onRoleChange, onSignOut, onSwitchTenant }: {
  localMode: boolean;
  selectedRole: string;
  currentUser: CurrentUser | null;
  roles: string[];
  hasRole: (role: string) => boolean;
  hasCapability: (capability: string) => boolean;
  onRoleChange: (value: string) => void;
  onSignOut: () => void;
  onSwitchTenant: () => Promise<boolean>;
}) {
  const { branding } = useBranding();
  const [tenantSwitchError, setTenantSwitchError] = useState(false);
  const [switchingTenant, setSwitchingTenant] = useState(false);
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
    if (!drawerOpen) return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setDrawerOpen(false);
    };
    window.addEventListener('keydown', closeOnEscape);
    return () => window.removeEventListener('keydown', closeOnEscape);
  }, [drawerOpen]);

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
  const canViewAnyAdministration = hasCapability('TENANT_POLICY_MANAGE') || hasCapability('TENANT_INTEGRATION_MANAGE')
    || hasCapability('TENANT_AUTH_MANAGE') || hasCapability('TECHNICAL_ADMIN_VIEW');
  const admin: NavEntry[] = [
    {
      to: '/administration',
      label: 'Administration',
      icon: 'admin',
      show: canViewAnyAdministration,
      activeWhen: ['/business-admin', '/integrations', '/authentication-admin', '/platform-admin'],
    },
  ];
  const defaultAdministrationPath = hasCapability('TENANT_POLICY_MANAGE') ? '/business-admin'
    : hasCapability('TENANT_INTEGRATION_MANAGE') ? '/integrations'
      : hasCapability('TENANT_AUTH_MANAGE') ? '/authentication-admin'
        : hasCapability('TECHNICAL_ADMIN_VIEW') ? '/platform-admin' : '/';
  const administrationTabs = <AdministrationTabs
    business={hasCapability('TENANT_POLICY_MANAGE')}
    integrations={hasCapability('TENANT_INTEGRATION_MANAGE')}
    authentication={hasCapability('TENANT_AUTH_MANAGE')}
    technical={hasCapability('TECHNICAL_ADMIN_VIEW')}
    technicalLabel={hasRole('PLATFORM_OPERATIONS_ADMIN') ? 'Technical' : 'Tenant IT'}
  />;
  const support: NavEntry[] = [
    { to: '/support/incidents', label: 'Support Incidents', icon: 'support', show: hasCapability('TECHNICAL_EXCEPTION_VIEW') },
    { to: '/audit', label: 'Audit', icon: 'audit', show: hasCapability('AUDIT_VIEW') },
  ];

  const initials = (currentUser?.username || 'User').split(/[.@\s_-]+/).filter(Boolean).slice(0, 2).map((part) => part[0]?.toUpperCase()).join('') || 'U';

  return (
    <div className={`modern-app-shell ${effectiveCollapsed ? 'sidebar-collapsed' : ''}`} data-ui-design-system="1">
      <a className="ui-skip-link" href="#main-content">Skip to main content</a>
      <header className="mobile-app-bar">
        <Button className="mobile-nav-trigger" variant="secondary" iconOnly type="button" onClick={() => setDrawerOpen(true)} aria-label="Open navigation" aria-expanded={drawerOpen} aria-controls="primary-navigation-drawer"><span className="mobile-menu-glyph" aria-hidden="true"><span /><span /><span /></span></Button>
        <div className="mobile-brand"><BrandMark compact /><strong>{branding.shortName || branding.applicationName}</strong></div>
        <div className="mobile-user-avatar" title={currentUser?.username || 'User'}>{initials}</div>
      </header>

      {drawerOpen && <button className="nav-drawer-backdrop" aria-label="Close navigation" onClick={() => setDrawerOpen(false)} />}
      <aside id="primary-navigation-drawer" className={`modern-sidebar ${drawerOpen ? 'drawer-open' : ''}`}>
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
          {!localMode && selectedOidcTenant() && (
            <div>
              <button className="sidebar-signout-modern" type="button" disabled={switchingTenant}
                onClick={() => {
                  setTenantSwitchError(false);
                  setSwitchingTenant(true);
                  void onSwitchTenant().then((ok) => {
                    if (!ok) setTenantSwitchError(true);
                  }).finally(() => setSwitchingTenant(false));
                }}>
                <span>{switchingTenant ? 'Checking organizations…' : 'Switch organization'}</span>
              </button>
              {tenantSwitchError && <p role="alert">Could not load your organizations. Please try again.</p>}
            </div>
          )}
          {localMode ? (
            <label className="demo-role-control">
              <span>Demo role</span>
              <select value={selectedRole} onChange={(event) => onRoleChange(event.target.value)}>
                {ROLE_OPTIONS.map((role) => <option key={role.value} value={role.value}>{role.label}</option>)}
              </select>
            </label>
          ) : (
            <button className="sidebar-signout-modern" onClick={onSignOut} aria-label="Sign out" title={sidebarCompact ? 'Sign out' : undefined}><Icon name="logout" /><span className="sidebar-signout-label">Sign out</span></button>
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

      <main id="main-content" className="modern-main-content" tabIndex={-1}>
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
          <Route path="/administration" element={canViewAnyAdministration ? <Navigate to={defaultAdministrationPath} replace /> : <PermissionDenied />} />
          <Route path="/business-admin" element={hasCapability('TENANT_POLICY_MANAGE') ? <BusinessAdminPage administrationTabs={administrationTabs} /> : <PermissionDenied />} />
          <Route path="/platform-admin" element={hasCapability('TECHNICAL_ADMIN_VIEW') ? <PlatformAdminPage administrationTabs={administrationTabs} canManagePlatformOperations={hasCapability('PLATFORM_OPERATIONS')} /> : <PermissionDenied />} />
          <Route path="/authentication-admin" element={hasCapability('TENANT_AUTH_MANAGE') ? <AuthenticationAdminPage administrationTabs={administrationTabs} /> : <PermissionDenied />} />
          <Route path="/integrations" element={hasCapability('TENANT_INTEGRATION_MANAGE') ? <IntegrationsPage administrationTabs={administrationTabs} /> : <PermissionDenied />} />
          <Route path="/technical-exceptions/:packageId" element={hasCapability('TECHNICAL_EXCEPTION_VIEW') ? <TechnicalIssueDetailPage /> : <PermissionDenied />} />
          <Route path="/support/incidents" element={hasCapability('TECHNICAL_EXCEPTION_VIEW') ? <SupportIncidentsPage /> : <PermissionDenied />} />
          <Route path="/support/incidents/:incidentId" element={hasCapability('TECHNICAL_EXCEPTION_VIEW') ? <SupportIncidentDetailPage /> : <PermissionDenied />} />
        </Routes>
      </main>
    </div>
  );
}

export default function App() {
  const navigate = useNavigate();
  const localMode = isLocalSecurityMode();
  const [selectedRole, setSelectedRole] = useState(getCurrentRoles());
  const [currentUser, setCurrentUser] = useState<CurrentUser | null>(null);
  const [authLoading, setAuthLoading] = useState(!localMode);
  const [authError, setAuthError] = useState('');
  const [authDenied, setAuthDenied] = useState(false);
  const [tenantChoices, setTenantChoices] = useState<TenantChoice[]>([]);
  const [switchingTenant, setSwitchingTenant] = useState(false);

  useEffect(() => {
    if (localMode) return;
    const onTenantChange = () => {
      setCurrentUser(null);
      setCurrentCapabilities([]);
      setCurrentRoles('DOCUMENT_PROCESSOR');
    };
    window.addEventListener(TENANT_CONTEXT_CHANGED_EVENT, onTenantChange);
    return () => window.removeEventListener(TENANT_CONTEXT_CHANGED_EVENT, onTenantChange);
  }, [localMode]);

  useEffect(() => {
    if (localMode) return;
    const onSessionExpired = () => {
      setCurrentCapabilities([]);
      setCurrentRoles('DOCUMENT_PROCESSOR');
      setCurrentUser(null);
      setTenantChoices([]);
      setAuthDenied(false);
      setAuthError('Your organization session has expired. Sign in again to continue.');
    };
    window.addEventListener(OIDC_SESSION_EXPIRED_EVENT, onSessionExpired);
    return () => window.removeEventListener(OIDC_SESSION_EXPIRED_EVENT, onSessionExpired);
  }, [localMode]);

  useEffect(() => {
    if (localMode) return;
    let active = true;
    const load = async () => {
      try {
        console.info('[LexaVault OIDC] Starting production-session verification.');
        await handleOidcCallback();
        if (!getAccessToken()) {
          console.info('[LexaVault OIDC] No access token is available after callback processing.');
          if (active) setAuthLoading(false);
          return;
        }
        console.info('[LexaVault OIDC] Access token is present; validating it with /api/v1/me. Token value is intentionally not logged.');
        // Discovery can run before tenant selection. Older single-tenant servers
        // reject this optional endpoint, in which case existing /me behavior remains.
        try {
          const choices = await apiClient.getAvailableOidcTenants();
          // A run that is no longer the active one (e.g. the first React.StrictMode mount) must not
          // change the tenant selection: that would make the active run's in-flight requests stale.
          if (!active) return;
          if (choices.length === 0) {
            throw new NoOrganizationError('No authorized ACTIVE organization is available for this account.');
          }
          const currentSelection = selectedOidcTenant();
          if (currentSelection && !choices.some((choice) => choice.tenantId === currentSelection)) {
            setSelectedOidcTenant(null);
          }
          if (choices.length === 1) setSelectedOidcTenant(choices[0].tenantId);
          else if (!selectedOidcTenant()) {
            if (active) setTenantChoices(choices);
            return;
          }
        } catch (discoveryError) {
          // Only an unsupported discovery route can fall back to the legacy /me flow.
          // Network failures, invalid responses and authorization failures must not
          // silently enter a workspace with an unverified tenant selection.
          const status = typeof discoveryError === 'object' && discoveryError !== null && 'httpStatus' in discoveryError
            ? Number((discoveryError as { httpStatus?: number }).httpStatus) : undefined;
          if (discoveryError instanceof NoOrganizationError || (status !== 403 && status !== 404)) throw discoveryError;
        }
        const user = await apiClient.getCurrentUser();
        console.info('[LexaVault OIDC] Production session verified.', { roles: user.roles, capabilityCount: (user.capabilities || []).length });
        if (active) {
          setCurrentUser(user);
          setCurrentRoles(user.roles.join(','));
          setCurrentCapabilities(user.capabilities || []);
        }
      } catch (error) {
        // Only the active run may clear the session or report a failure.
        if (!active) return;
        console.error('[LexaVault OIDC] Production-session verification failed.', error);
        const status = typeof error === 'object' && error !== null && 'httpStatus' in error ? Number((error as { httpStatus?: number }).httpStatus) : undefined;
        if (status === 403 || error instanceof NoOrganizationError) {
          if (active) { setAuthDenied(true); setAuthError(''); }
        } else {
          clearOidcSession();
          if (active) { setAuthDenied(false); setAuthError(status === 401 ? 'Your organization session has expired or could not be verified. Please sign in again.' : 'Sign-in could not be completed. Please try again.'); }
        }
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

  // One implementation of changing organization, shared by the sign-in chooser, the older sidebar switch and the
  // newest design's switch. The server is asked first, so a refused choice changes nothing on screen.
  const listSwitchableTenants = async (): Promise<TenantChoice[] | null> => {
    const choices = await apiClient.getAvailableOidcTenants();
    if (choices.length < 2 || !choices.some((choice) => choice.tenantId === selectedOidcTenant())) return null;
    return choices;
  };

  const applyUser = (user: CurrentUser) => {
    setCurrentUser(user);
    setCurrentRoles(user.roles.join(','));
    setCurrentCapabilities(user.capabilities || []);
  };

  const enterTenant = async (tenantId: string): Promise<boolean> => {
    const previous = selectedOidcTenant();
    try {
      await apiClient.getCurrentUserForTenant(tenantId);
    } catch {
      return false; // refused or unreachable: nothing has changed
    }
    setSwitchingTenant(true);
    try {
      // Changing the selection bumps the tenant-context revision, so the API client discards responses to any
      // request started for the previous organization, and TENANT_CONTEXT_CHANGED_EVENT clears the principal.
      // The server revalidates the new selection on this and every later request.
      setSelectedOidcTenant(tenantId);
      applyUser(await apiClient.getCurrentUser());
      setTenantChoices([]);
      navigate('/', { replace: true });
      return true;
    } catch {
      setSelectedOidcTenant(previous);
      if (previous) {
        try { applyUser(await apiClient.getCurrentUser()); } catch { /* normal session handling takes over */ }
      }
      return false;
    } finally {
      setSwitchingTenant(false);
    }
  };

  if (!localMode && authLoading) {
    return <AuthScreen title={SIGN_IN_TITLE}><h1>{PRODUCT_NAME}</h1><p>Verifying your session…</p></AuthScreen>;
  }

  if (!localMode && switchingTenant && !currentUser) {
    return <AuthScreen title={CHOOSE_ENTITY_TITLE}><h1>{PRODUCT_NAME}</h1><p role="status">Switching organization…</p></AuthScreen>;
  }

  if (!localMode && !currentUser && tenantChoices.length > 1 && getAccessToken()) {
    const chooseTenant = async (tenantId: string) => {
      setAuthError('');
      const entered = await enterTenant(tenantId);
      if (!entered) setAuthError('Unable to enter that tenant. Contact your administrator.');
    };
    return (
      <AuthScreen title={CHOOSE_ENTITY_TITLE}>
        <h1>Select organization</h1>
        <p>Select an organization that your identity is authorized to access.</p>
        {authError && <div className="ui-alert tone-danger">{authError}</div>}
        {tenantChoices.map((choice) => (
          <button className="ui-button secondary" key={choice.tenantId} type="button"
            onClick={() => void chooseTenant(choice.tenantId)}>
            {choice.name}
          </button>
        ))}
      </AuthScreen>
    );
  }

  if (!localMode && !currentUser && authDenied) {
    // Signed in, but refused: the Case File no-access screen (UI spec finding 21, task U013). Only the look and the
    // words changed; the refusal and the session handling are as before.
    return (
      <Suspense fallback={<div className="auth-shell"><div className="auth-card"><p>Loading…</p></div></div>}>
        <NoAccess who={signedInIdentity()} onSignOut={() => void endOidcSession()} />
      </Suspense>
    );
  }

  if (!localMode && !currentUser) {
    return (
      <AuthScreen title={SIGN_IN_TITLE}><h1>{PRODUCT_NAME}</h1><p>Sign in with your organization identity to continue.</p>
        {authError && <div className="ui-alert tone-danger">{authError}</div>}
        <button className="ui-button primary" onClick={() => void beginOidcLogin(authDenied)}>{authDenied ? 'Sign in with a different account' : 'Sign in'}</button>
      </AuthScreen>
    );
  }

  // Older sidebar: returns to the chooser screen, which enters the chosen organization through enterTenant.
  const switchTenant = async (): Promise<boolean> => {
    try {
      const choices = await listSwitchableTenants();
      if (!choices) return false;
      setTenantChoices(choices);
      setAuthError('');
      navigate('/', { replace: true });
      setSelectedOidcTenant(null);
      // Changing the tenant also clears the principal/capabilities via TENANT_CONTEXT_CHANGED_EVENT.
      return true;
    } catch {
      return false;
    }
  };

  const signOut = () => {
    setCurrentCapabilities([]);
    setCurrentRoles('DOCUMENT_PROCESSOR');
    setCurrentUser(null);
    if (localMode) { clearOidcSession(); return; }
    void endOidcSession();
  };

  // Explicit preview flag only. Legacy production routes remain the default until parity is verified.
  const caseFilePreviewEnabled = import.meta.env.VITE_CASE_FILE_UI_ENABLED === 'true';

  return (
    <BrandingProvider key={localMode ? 'local' : selectedOidcTenant() || 'oidc-unselected'}>
      {caseFilePreviewEnabled && currentUser
        ? <Suspense fallback={<div className="auth-shell"><div className="auth-card"><p>Loading workspace…</p></div></div>}>
            {/* Keyed by organization: a switch unmounts the whole workspace, including its private data cache. */}
            <CaseFileApp key={localMode ? 'local' : selectedOidcTenant() || 'oidc-unselected'} me={currentUser} onSignOut={signOut} localMode={localMode}
              selectedRole={selectedRole} onRoleChange={handleRoleChange}
              tenantSwitch={localMode ? undefined : { list: listSwitchableTenants, enter: enterTenant }} />
          </Suspense>
        : <AuthenticatedApplication key={localMode ? 'local' : selectedOidcTenant() || 'oidc-default'} localMode={localMode} selectedRole={selectedRole} currentUser={currentUser} roles={roles}
            hasRole={hasRole} hasCapability={hasCapability} onRoleChange={handleRoleChange} onSignOut={signOut} onSwitchTenant={switchTenant} />}
    </BrandingProvider>
  );
}
