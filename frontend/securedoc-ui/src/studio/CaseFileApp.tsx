import { useEffect, useState } from 'react';
import { Navigate, NavLink, Route, Routes, useLocation, useParams } from 'react-router-dom';
import { Moon, Search, Sun } from 'lucide-react';
import { useTheme } from './theme';
import { SWRConfig } from 'swr';
import type { CurrentUser, TenantSwitchActions } from '../types';
import EntitySwitch from './EntitySwitch';
import { ADMIN_CAPS, CAP, needsYou, usePackages, useReviewQueue } from './data';
import { useBranding } from '../branding/BrandingContext';
import { ROLE_OPTIONS } from '../security/uiAuthorization';
import { Kbd } from './atoms';
import { ToastProvider } from './toast';
import CommandPalette from './CommandPalette';
import { ErrorBoundary } from './failure';
import { NAV } from './words';
import Desk from './Desk';
import Intake from './Intake';
import Review from './Review';
import Reports from './reports/Reports';
import Admin from './admin/Admin';
import SupportIncidentsPage from '../pages/SupportIncidentsPage';
import SupportIncidentDetailPage from '../pages/SupportIncidentDetailPage';
import TechnicalIssueDetailPage from '../pages/TechnicalIssueDetailPage';
import ManualFallbackPage from '../pages/ManualFallbackPage';
import './studio.css';

const MOD_KEY = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform) ? '⌘' : 'Ctrl';

function ThemeToggle() {
  const [theme, setTheme] = useTheme();
  const next = theme === 'dark' ? 'light' : 'dark';
  return (
    <button
      type="button"
      className="cf-theme-toggle"
      onClick={() => setTheme(next)}
      aria-label={`Switch to ${next} theme`}
      title={`Switch to ${next} theme`}
    >
      {theme === 'dark' ? <Sun size={18} aria-hidden="true" /> : <Moon size={18} aria-hidden="true" />}
    </button>
  );
}

export default function CaseFileApp({ me, onSignOut, localMode, selectedRole, onRoleChange, tenantSwitch }: { me: CurrentUser; onSignOut: () => void; localMode: boolean; selectedRole: string; onRoleChange: (role: string) => void; tenantSwitch?: TenantSwitchActions }) {
  // The production App owns OIDC validation, session expiry and principal capabilities.
  // App keys this component by organization, so a switch unmounts it and everything below it.
  const [swrCache] = useState(() => new Map());
  const caps = me.capabilities ?? [];
  if (![CAP.desk, CAP.intake, CAP.review, CAP.reportExecutive, CAP.technicalView, ...ADMIN_CAPS].some((c) => caps.includes(c))) return <NoAccess me={me} onSignOut={onSignOut} />;

  return (
    <div className="cf">
      {/* A private data cache per mount (that is, per organization): nothing cached for one organization
          can be shown in another. SWR's default cache is global and its keys carry no organization. */}
      <SWRConfig value={{ provider: () => swrCache }}>
        <ToastProvider>
          <Workspace me={me} caps={caps} onSignOut={onSignOut} localMode={localMode} selectedRole={selectedRole} onRoleChange={onRoleChange} tenantSwitch={tenantSwitch} />
        </ToastProvider>
      </SWRConfig>
    </div>
  );
}

function Workspace({ me, caps, onSignOut, localMode, selectedRole, onRoleChange, tenantSwitch }: { me: CurrentUser; caps: string[]; onSignOut: () => void; localMode: boolean; selectedRole: string; onRoleChange: (role: string) => void; tenantSwitch?: TenantSwitchActions }) {
  const { branding } = useBranding();
  const location = useLocation();
  const [paletteOpen, setPaletteOpen] = useState(false);
  const canDesk = caps.includes(CAP.desk);
  const canIntake = caps.includes(CAP.intake);
  const canReview = caps.includes(CAP.review);
  const canReports = caps.includes(CAP.reportExecutive);
  const canAdmin = ADMIN_CAPS.some((c) => caps.includes(c));
  const canSupport = caps.includes(CAP.technicalView);

  const { data: packages } = usePackages(canDesk);
  const { data: queue } = useReviewQueue(canReview);
  const needsYouCount = (packages ?? []).filter((p) => needsYou(p, caps)).length;
  const openFindings = queue?.totalItems ?? 0;

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setPaletteOpen((open) => !open);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const home = canDesk ? '/' : canReview ? '/review' : canIntake ? '/intake' : canReports ? '/reports' : canAdmin ? '/admin' : '/support/incidents';

  return (
    <div className="cf-shell">
      <a className="cf-skip" href="#cf-main">Skip to content</a>
      <header className="cf-top">
        <NavLink to={home} className="cf-brand" aria-label={`${branding.applicationName} home`}>
          {branding.compactLogoDataUrl || branding.primaryLogoDataUrl
            ? <img src={branding.compactLogoDataUrl || branding.primaryLogoDataUrl || ''} alt="" className="cf-brand-image" style={{ maxHeight: 30, maxWidth: 110, objectFit: 'contain' }} />
            : <span className="cf-brand-mark" aria-hidden="true"><span /><span /><span /></span>}
          <span className="cf-brand-word">{branding.shortName || branding.applicationName}</span>
        </NavLink>

        <nav className="cf-nav" aria-label="Workspace">
          {canDesk && (
            <NavLink to="/" end className={({ isActive }) => `cf-nav-link${isActive || location.pathname.startsWith('/case/') ? ' is-active' : ''}`}>
              {NAV.cases}
              {needsYouCount > 0 && <span className="cf-nav-count" data-tone="mark" aria-label={`${needsYouCount} need you`}>{needsYouCount}</span>}
            </NavLink>
          )}
          {canIntake && (
            <NavLink to="/intake" className={({ isActive }) => `cf-nav-link${isActive ? ' is-active' : ''}`}>{NAV.newCase}</NavLink>
          )}
          {canReview && (
            <NavLink to="/review" className={({ isActive }) => `cf-nav-link${isActive ? ' is-active' : ''}`}>
              {NAV.review}
              {openFindings > 0 && <span className="cf-nav-count" aria-label={`${openFindings} open findings`}>{openFindings}</span>}
            </NavLink>
          )}
          {(canReports || canAdmin) && (canDesk || canIntake || canReview) && <span className="cf-nav-sep" aria-hidden="true" />}
          {canReports && (
            <NavLink to="/reports" className={({ isActive }) => `cf-nav-link${isActive ? ' is-active' : ''}`}>{NAV.reports}</NavLink>
          )}
          {canAdmin && (
            <NavLink to="/admin" className={({ isActive }) => `cf-nav-link${isActive ? ' is-active' : ''}`}>{NAV.admin}</NavLink>
          )}
          {canSupport && (
            <NavLink to="/support/incidents" className={({ isActive }) => `cf-nav-link${isActive ? ' is-active' : ''}`}>{NAV.support}</NavLink>
          )}
        </nav>

        <button type="button" className="cf-find" onClick={() => setPaletteOpen(true)}>
          <Search size={15} aria-hidden="true" />
          <span className="cf-find-label">Find a case or action</span>
          <span className="cf-find-keys" aria-hidden="true"><Kbd>{MOD_KEY}</Kbd><Kbd>K</Kbd></span>
        </button>

        <ThemeToggle />

        {localMode && (
          <label className="cf-local-role">
            <span className="cf-sr">Local test role</span>
            <select aria-label="Local test role" value={selectedRole} onChange={(event) => onRoleChange(event.target.value)}>
              {ROLE_OPTIONS.map((role) => <option key={role.value} value={role.value}>{role.label}</option>)}
            </select>
          </label>
        )}

        <div className="cf-me">
          <span className="cf-me-avatar" aria-hidden="true">{initials(me.username)}</span>
          <span className="cf-me-text">
            <span className="cf-me-name">{displayName(me.username)}</span>
            <EntitySwitch label={branding.organizationName || me.tenantName} tenantName={me.tenantName} actions={localMode ? undefined : tenantSwitch} />
          </span>
        </div>
        <button type="button" className="cf-theme-toggle" onClick={onSignOut} aria-label="Sign out" title="Sign out">Sign out</button>
      </header>

      <main className="cf-main" id="cf-main">
        {/* A failing screen shows its own card; the top bar above and every other screen keep working. */}
        <ErrorBoundary title="This screen couldn't be loaded" request={location.pathname} resetKey={location.pathname}>
        <Routes>
          <Route path="/" element={canDesk ? <Desk caps={caps} /> : <Navigate to={home} replace />} />
          <Route path="/case/:caseId" element={canDesk ? <Desk caps={caps} /> : <Navigate to={home} replace />} />
          <Route path="/intake" element={canIntake ? <Intake /> : <Navigate to={home} replace />} />
          <Route path="/review" element={canReview ? <Review caps={caps} /> : <Navigate to={home} replace />} />
          <Route path="/reports" element={canReports ? <Reports caps={caps} /> : <Navigate to={home} replace />} />
          <Route path="/admin" element={canAdmin ? <Admin caps={caps} me={me} /> : <Navigate to={home} replace />} />
          <Route path="/admin/:area" element={canAdmin ? <Admin caps={caps} me={me} /> : <Navigate to={home} replace />} />
          <Route path="/administration" element={<Navigate to={canAdmin ? '/admin' : home} replace />} />
          <Route path="/business-admin" element={<Navigate to={caps.includes(CAP.policy) ? '/admin/policy' : caps.includes(CAP.identifiers) ? '/admin/fields' : home} replace />} />
          <Route path="/integrations" element={<Navigate to={caps.includes(CAP.integrations) ? '/admin/integrations' : home} replace />} />
          <Route path="/authentication-admin" element={<Navigate to={caps.includes(CAP.auth) ? '/admin/identity' : home} replace />} />
          <Route path="/platform-admin" element={<Navigate to={caps.includes(CAP.technicalAdmin) ? '/admin/health' : home} replace />} />
          <Route path="/audit" element={<Navigate to={caps.includes(CAP.audit) ? '/admin/audit' : home} replace />} />
          <Route path="/support/incidents" element={canSupport ? <SupportIncidentsPage /> : <Navigate to={home} replace />} />
          <Route path="/support/incidents/:incidentId" element={canSupport ? <SupportIncidentDetailPage /> : <Navigate to={home} replace />} />
          <Route path="/technical-exceptions/:packageId" element={canSupport ? <TechnicalIssueDetailPage /> : <Navigate to={home} replace />} />
          <Route path="/packages/:packageId/manual-processing" element={canReview ? <ManualFallbackPage /> : <Navigate to={home} replace />} />
          <Route path="/packages/new" element={canIntake ? <Navigate to="/intake?via=upload" replace /> : <Navigate to={home} replace />} />
          <Route path="/scanner-intake" element={canIntake ? <Navigate to="/intake?via=scan" replace /> : <Navigate to={home} replace />} />
          <Route path="/packages/:caseId" element={<LegacyCaseRedirect />} />
          <Route path="*" element={<Navigate to={home} replace />} />
        </Routes>
        </ErrorBoundary>
      </main>

      <CommandPalette open={paletteOpen} onClose={() => setPaletteOpen(false)} caps={caps} />
    </div>
  );
}

function LegacyCaseRedirect() {
  const { caseId } = useParams();
  return <Navigate to={`/case/${caseId}`} replace />;
}

/** Signed in, but no LexaVault role (UI spec finding 21). */
function NoAccess({ me, onSignOut }: { me: CurrentUser; onSignOut: () => void }) {
  return (
    <div className="cf cf-boot">
      <div className="cf-scope" role="main">
        <span className="cf-brand-mark" aria-hidden="true"><span /><span /><span /></span>
        <h1>You don&apos;t have access to anything in LexaVault yet</h1>
        <p>
          You&apos;re signed in as <span className="cf-scope-who">{me.username}</span>, but your account has no LexaVault role.
          Ask your administrator to add you to a LexaVault group, then sign in again.
        </p>
        <div className="cf-scope-actions">
          <button type="button" className="cf-btn" data-variant="solid" onClick={onSignOut}>Sign out</button>
          <button type="button" className="cf-btn" onClick={() => window.location.reload()}>Try again</button>
        </div>
      </div>
    </div>
  );
}

function displayName(username: string): string {
  const local = username.split('@')[0] ?? username;
  const parts = local.split(/[._-]+/).filter(Boolean);
  if (parts.length >= 2) return `${cap(parts[0])}. ${cap(parts[parts.length - 1])}`.replace(/^(\w)\w*\./, '$1.');
  return cap(local);
}

function initials(username: string): string {
  const parts = (username.split('@')[0] ?? '').split(/[._-]+/).filter(Boolean);
  return ((parts[0]?.[0] ?? '') + (parts[1]?.[0] ?? '')).toUpperCase() || 'U';
}

function cap(word: string): string {
  return word.charAt(0).toUpperCase() + word.slice(1);
}
