import { useEffect, useState } from 'react';
import { Navigate, NavLink, Route, Routes, useParams } from 'react-router-dom';
import { Search } from 'lucide-react';
import type { CurrentUser } from '../types';
import { CAP, needsYou, useMe, usePackages, useReviewQueue } from './data';
import { Kbd, Notice, Spinner } from './atoms';
import { ToastProvider } from './toast';
import CommandPalette from './CommandPalette';
import Desk from './Desk';
import Intake from './Intake';
import Review from './Review';
import './studio.css';

const MOD_KEY = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform) ? '⌘' : 'Ctrl';

export default function CaseFileApp() {
  const { data: me, error } = useMe();

  if (error) {
    return (
      <div className="cf cf-boot">
        <Notice tone="danger">We couldn&apos;t load your session. Refresh the page to try again.</Notice>
      </div>
    );
  }
  if (!me) {
    return (
      <div className="cf cf-boot">
        <Spinner label="Loading your workspace" />
      </div>
    );
  }

  const caps = me.capabilities;
  if (![CAP.desk, CAP.intake, CAP.review].some((c) => caps.includes(c))) return <OutOfScope me={me} />;

  return (
    <div className="cf">
      <ToastProvider>
        <Workspace me={me} caps={caps} />
      </ToastProvider>
    </div>
  );
}

function Workspace({ me, caps }: { me: CurrentUser; caps: string[] }) {
  const [paletteOpen, setPaletteOpen] = useState(false);
  const canDesk = caps.includes(CAP.desk);
  const canIntake = caps.includes(CAP.intake);
  const canReview = caps.includes(CAP.review);

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

  const home = canDesk ? '/' : canReview ? '/review' : '/intake';

  return (
    <div className="cf-shell">
      <a className="cf-skip" href="#cf-main">Skip to content</a>
      <header className="cf-top">
        <NavLink to={home} className="cf-brand" aria-label="LexaVault home">
          <span className="cf-brand-mark" aria-hidden="true"><span /><span /><span /></span>
          <span className="cf-brand-word">LexaVault</span>
        </NavLink>

        <nav className="cf-nav" aria-label="Workspace">
          {canDesk && (
            <NavLink to="/" end className={({ isActive }) => `cf-nav-link${isActive || location.pathname.startsWith('/case/') ? ' is-active' : ''}`}>
              Desk
              {needsYouCount > 0 && <span className="cf-nav-count" data-tone="mark" aria-label={`${needsYouCount} need you`}>{needsYouCount}</span>}
            </NavLink>
          )}
          {canIntake && (
            <NavLink to="/intake" className={({ isActive }) => `cf-nav-link${isActive ? ' is-active' : ''}`}>Intake</NavLink>
          )}
          {canReview && (
            <NavLink to="/review" className={({ isActive }) => `cf-nav-link${isActive ? ' is-active' : ''}`}>
              Review
              {openFindings > 0 && <span className="cf-nav-count" aria-label={`${openFindings} open findings`}>{openFindings}</span>}
            </NavLink>
          )}
        </nav>

        <button type="button" className="cf-find" onClick={() => setPaletteOpen(true)}>
          <Search size={15} aria-hidden="true" />
          <span className="cf-find-label">Find a case or action</span>
          <span className="cf-find-keys" aria-hidden="true"><Kbd>{MOD_KEY}</Kbd><Kbd>K</Kbd></span>
        </button>

        <div className="cf-me">
          <span className="cf-me-avatar" aria-hidden="true">{initials(me.username)}</span>
          <span className="cf-me-text">
            <span className="cf-me-name">{displayName(me.username)}</span>
            <span className="cf-me-org">{me.tenantName}</span>
          </span>
        </div>
      </header>

      <main className="cf-main" id="cf-main">
        <Routes>
          <Route path="/" element={canDesk ? <Desk caps={caps} /> : <Navigate to={home} replace />} />
          <Route path="/case/:caseId" element={canDesk ? <Desk caps={caps} /> : <Navigate to={home} replace />} />
          <Route path="/intake" element={canIntake ? <Intake /> : <Navigate to={home} replace />} />
          <Route path="/review" element={canReview ? <Review caps={caps} /> : <Navigate to={home} replace />} />
          <Route path="/packages/new" element={<Navigate to="/intake?via=upload" replace />} />
          <Route path="/scanner-intake" element={<Navigate to="/intake?via=scan" replace />} />
          <Route path="/packages/:caseId" element={<LegacyCaseRedirect />} />
          <Route path="*" element={<Navigate to={home} replace />} />
        </Routes>
      </main>

      <CommandPalette open={paletteOpen} onClose={() => setPaletteOpen(false)} caps={caps} />
    </div>
  );
}

function LegacyCaseRedirect() {
  const { caseId } = useParams();
  return <Navigate to={`/case/${caseId}`} replace />;
}

function OutOfScope({ me }: { me: CurrentUser }) {
  return (
    <div className="cf cf-boot">
      <div className="cf-scope">
        <span className="cf-brand-mark" aria-hidden="true"><span /><span /><span /></span>
        <h1>This workspace is for case operations</h1>
        <p>
          {displayName(me.username)} is signed in as {me.roles.join(', ').replace(/_/g, ' ').toLowerCase()}. The Case File prototype covers
          intake, review and delivery. Switch to a Processor or Supervisor role in Demo controls to explore it.
        </p>
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
