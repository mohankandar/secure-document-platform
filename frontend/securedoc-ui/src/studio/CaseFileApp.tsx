import { useEffect, useState } from 'react';
import { Navigate, NavLink, Route, Routes, useLocation, useNavigate, useParams } from 'react-router-dom';
import { Search } from 'lucide-react';
import { SWRConfig } from 'swr';
import type { CurrentUser, TenantSwitchActions } from '../types';
import AccountMenu from './AccountMenu';
import EntityMenu from './EntityMenu';
import { EntityZoneContext } from './EntityZoneContext';
import { ADMIN_CAPS, CAP, needsYou, useExportAlert, useExportNeedsIt, usePackages, useReviewQueue, useWaitingForYou } from './data';
import { showWaitingNav, supportBadgeCount } from './exportRules';
import WaitingForYou from './WaitingForYou';
import SupportExport from './support/SupportExport';
import { useBranding } from '../branding/BrandingContext';
import { ToastProvider } from './toast';
import CommandPalette from './CommandPalette';
import { ErrorBoundary } from './failure';
import { NAV, brandHomeName, screenTitle } from './words';
import Desk from './Desk';
import Intake from './Intake';
import Review from './Review';
import Reports from './reports/Reports';
import Admin from './admin/Admin';
import ApprovalLink from './ApprovalLink';
import { approvalLinkHeld } from './approvalLinkStore';
import NoAccess from './NoAccess';
import SupportStatus from './support/SupportStatus';
import SupportList from './support/SupportList';
import { CaseProblem, IncidentProblem } from './support/ProblemDetail';
import ManualCase from './support/ManualCase';
import './studio.css';

const MOD_KEY = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform) ? '⌘' : 'Ctrl';

export default function CaseFileApp({ me, onSignOut, localMode, selectedRole, onRoleChange, tenantSwitch }: { me: CurrentUser; onSignOut: () => void; localMode: boolean; selectedRole: string; onRoleChange: (role: string) => void; tenantSwitch?: TenantSwitchActions }) {
  // The production App owns OIDC validation, session expiry and principal capabilities.
  // App keys this component by organization, so a switch unmounts it and everything below it.
  const [swrCache] = useState(() => new Map());
  const caps = me.capabilities ?? [];
  if (![CAP.desk, CAP.intake, CAP.review, CAP.reportExecutive, CAP.technicalView, ...ADMIN_CAPS].some((c) => caps.includes(c))) return <NoAccess who={me.username} onSignOut={onSignOut} />;

  return (
    <div className="cf">
      {/* A private data cache per mount (that is, per organization): nothing cached for one organization
          can be shown in another. SWR's default cache is global and its keys carry no organization. */}
      <SWRConfig value={{ provider: () => swrCache }}>
        <ToastProvider>
          <EntityZoneContext.Provider value={me.timeZone ?? null}>
            <Workspace me={me} caps={caps} onSignOut={onSignOut} localMode={localMode} selectedRole={selectedRole} onRoleChange={onRoleChange} tenantSwitch={tenantSwitch} />
          </EntityZoneContext.Provider>
        </ToastProvider>
      </SWRConfig>
    </div>
  );
}

function Workspace({ me, caps, onSignOut, localMode, selectedRole, onRoleChange, tenantSwitch }: { me: CurrentUser; caps: string[]; onSignOut: () => void; localMode: boolean; selectedRole: string; onRoleChange: (role: string) => void; tenantSwitch?: TenantSwitchActions }) {
  const { branding } = useBranding();
  const location = useLocation();
  const [paletteOpen, setPaletteOpen] = useState(false);
  // Task 0059: a link opened before sign-in (which lands on the start page) is shown once, right after it.
  const navigate = useNavigate();
  useEffect(() => {
    if (approvalLinkHeld() && window.location.pathname === '/') navigate('/approvals/link', { replace: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
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
  // Task 0029: "Waiting for you" (EXPORT_RESEND) shows only while a destination refused something; Support's badge
  // counts the export groups waiting for IT.
  const canResend = canDesk && caps.includes(CAP.exportResend);
  const { data: waiting } = useWaitingForYou(canResend, true);
  const waitingCount = waiting?.total ?? 0;
  const showWaiting = showWaitingNav(caps, waiting?.total) || location.pathname === '/waiting';
  const { data: needsIt } = useExportNeedsIt(canSupport, true);
  // Task 0037: the raised "can't reach" alert counts too, so Support shows its red count while it is raised.
  const { data: reachAlert } = useExportAlert(canSupport, true);
  const itGroups = supportBadgeCount(needsIt, reachAlert);

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

  // U026: each screen has its own page title. After the branding provider's own title effect, which runs later.
  const product = branding.pageTitle || branding.applicationName || 'LexaVault';
  useEffect(() => {
    queueMicrotask(() => { document.title = screenTitle(location.pathname, product); });
  }, [location.pathname, product]);

  const home = canDesk ? '/' : canReview ? '/review' : canIntake ? '/intake' : canReports ? '/reports' : canAdmin ? '/admin' : '/support/incidents';

  return (
    <div className="cf-shell">
      <a className="cf-skip" href="#cf-main">Skip to content</a>
      <header className="cf-top">
        <NavLink to={home} className="cf-brand" aria-label={brandHomeName(branding.applicationName)}>
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
          {canResend && showWaiting && (
            <NavLink to="/waiting" className={({ isActive }) => `cf-nav-link${isActive ? ' is-active' : ''}`}>
              {NAV.waiting}
              {waitingCount > 0 && <span className="cf-nav-count" data-tone="mark" aria-label={`${waitingCount} refused`}>{waitingCount}</span>}
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
            <NavLink to="/support/incidents" className={() => `cf-nav-link${location.pathname.startsWith('/support') || location.pathname.startsWith('/technical-exceptions') ? ' is-active' : ''}`}>
              {NAV.support}
              {itGroups > 0 && <span className="cf-nav-count" data-tone="danger" aria-label={`${itGroups} export ${itGroups === 1 ? 'problem needs' : 'problems need'} IT`}>{itGroups}</span>}
            </NavLink>
          )}
        </nav>

        {/* Below 1200 px the box becomes an icon button (UI spec finding 31); the name stays for screen readers. */}
        {/* U026 (WCAG 2.5.3): the name comes from the visible words, the shortcut included; the title names it when only the icon shows. */}
        <button type="button" className="cf-find" onClick={() => setPaletteOpen(true)} title="Find a case or action" aria-keyshortcuts={MOD_KEY === '⌘' ? 'Meta+K' : 'Control+K'}>
          <Search size={15} aria-hidden="true" />
          <span className="cf-find-label">Find a case or action</span>
          <span className="cf-find-keys">{MOD_KEY} K</span>
        </button>

        <EntityMenu name={me.tenantName} tenantSwitch={localMode ? undefined : tenantSwitch} />
        <AccountMenu me={me} onSignOut={onSignOut} localMode={localMode}
          selectedRole={selectedRole} onRoleChange={onRoleChange} />
      </header>

      <main className="cf-main" id="cf-main">
        {/* A failing screen shows its own card; the top bar above and every other screen keep working. */}
        <ErrorBoundary title="This screen couldn't be loaded" request={location.pathname} resetKey={location.pathname}>
        <Routes>
          <Route path="/" element={canDesk ? <Desk caps={caps} /> : <Navigate to={home} replace />} />
          <Route path="/case/:caseId" element={canDesk ? <Desk caps={caps} /> : <Navigate to={home} replace />} />
          <Route path="/intake" element={canIntake ? <Intake entityName={me.tenantName} /> : <Navigate to={home} replace />} />
          <Route path="/review" element={canReview ? <Review caps={caps} /> : <Navigate to={home} replace />} />
          <Route path="/reports" element={canReports ? <Reports caps={caps} entityName={me.tenantName} /> : <Navigate to={home} replace />} />
          <Route path="/admin" element={canAdmin ? <Admin caps={caps} me={me} /> : <Navigate to={home} replace />} />
          <Route path="/approvals/link/*" element={<ApprovalLink caps={caps} entityName={me.tenantName} />} />
          <Route path="/admin/:area" element={canAdmin ? <Admin caps={caps} me={me} /> : <Navigate to={home} replace />} />
          <Route path="/administration" element={<Navigate to={canAdmin ? '/admin' : home} replace />} />
          <Route path="/business-admin" element={<Navigate to={caps.includes(CAP.policy) ? '/admin/policy' : caps.includes(CAP.identifiers) ? '/admin/fields' : home} replace />} />
          <Route path="/integrations" element={<Navigate to={caps.includes(CAP.integrations) ? '/admin/integrations' : home} replace />} />
          <Route path="/authentication-admin" element={<Navigate to={caps.includes(CAP.auth) ? '/admin/identity' : home} replace />} />
          <Route path="/platform-admin" element={<Navigate to={caps.includes(CAP.technicalAdmin) ? '/admin/health' : home} replace />} />
          <Route path="/audit" element={<Navigate to={caps.includes(CAP.audit) ? '/admin/audit' : home} replace />} />
          {/* UI task U012: Case File screens; the legacy pages stay for the legacy app (App.tsx). */}
          <Route path="/waiting" element={canResend ? <WaitingForYou /> : <Navigate to={home} replace />} />
          <Route path="/support/status" element={canSupport ? <SupportStatus caps={caps} /> : <Navigate to={home} replace />} />
          <Route path="/support/export" element={canSupport ? <SupportExport caps={caps} /> : <Navigate to={home} replace />} />
          <Route path="/support/incidents" element={canSupport ? <SupportList caps={caps} entityName={me.tenantName} /> : <Navigate to={home} replace />} />
          <Route path="/support/incidents/:incidentId" element={canSupport ? <IncidentProblem caps={caps} /> : <Navigate to={home} replace />} />
          <Route path="/technical-exceptions/:packageId" element={canSupport ? <CaseProblem caps={caps} /> : <Navigate to={home} replace />} />
          <Route path="/packages/:packageId/manual-processing" element={canReview ? <ManualCase /> : <Navigate to={home} replace />} />
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

