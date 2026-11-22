import { useState } from 'react';
import { useSWRConfig } from 'swr';
import { NavLink } from 'react-router-dom';
import { apiClient } from '../../api/client';
import { CAP, errorMessage, useExportAlert, useExportDestinations, useExportNeedsIt } from '../data';
import { Notice, Spinner } from '../atoms';
import { LoadErrorCard, TechnicalDetails } from '../failure';
import { useToast } from '../toast';
import {
  SUPPORT_EXPORT_WORDS as W, DESTINATION_CODE_LABEL, checkStillFailing, destinationCodesOf, destinationKind, destinationLabel, destinationStateWords, destinationsNote,
  isSignInGroup, itCasesLine, itGroupTitle, itReason, reachAlertWords, reasonLabel, retryAllLabel, signInBody, signInTitle, supportBadgeCount, triedLine, waitingParts,
  type DestinationView, type ExportTimings, type ItGroup,
} from '../exportRules';
import './support.css';

/** Support's two places: the processing problems and the export (task 0029). */
export function SupportTabs({ caps }: { caps: string[] }) {
  const { data } = useExportNeedsIt(caps.includes(CAP.technicalView), true);
  const { data: alert } = useExportAlert(caps.includes(CAP.technicalView), true);
  const groups = supportBadgeCount(data, alert);
  return (
    <nav className="cf-segment cf-sp-tabs" aria-label="Support">
      <NavLink to="/support/incidents" end className={({ isActive }) => (isActive ? 'is-active' : '')}>{W.tabProblems}</NavLink>
      <NavLink to="/support/export" className={({ isActive }) => (isActive ? 'is-active' : '')}>
        {W.tabExport}{groups > 0 && <span className="cf-sp-tab-count" aria-label={`${groups} need IT`}>{groups}</span>}
      </NavLink>
    </nav>
  );
}

/**
 * Task 0029, board "Support (IT Admin): destinations and export errors". The IT alerts grouped per destination and
 * code with one "Retry all N" (a sign-in failure: "Set back to working"), the destinations with their state and
 * "Check now" for a paused one, and the "LexaVault can't reach its destinations" alert when it is raised.
 */
export default function SupportExport({ caps }: { caps: string[] }) {
  const canRetry = caps.includes(CAP.technicalRetry);
  const needsIt = useExportNeedsIt(true);
  const destinations = useExportDestinations(true);
  const alert = useExportAlert(true);
  const [now] = useState(() => Date.now());
  const { mutate: refreshKey } = useSWRConfig();
  const refresh = () => Promise.all([needsIt.mutate(), destinations.mutate(), alert.mutate(), refreshKey('export-needs-it-badge'), refreshKey('export-alert-badge')]);
  const alertWords = reachAlertWords(destinations.data);
  const openOf = (id: string | null) => destinations.data?.find((d) => d.destinationId === id)?.openJobs ?? 0;
  // Task 0036: a working destination answers its health check, so a group there is these cases, not the destination.
  const answers = (id: string | null) => destinations.data?.find((d) => d.destinationId === id)?.state === 'WORKING';
  const timings = needsIt.data?.timings ?? null;

  return (
    <div className="cf-sp cf-sx">
      <header className="cf-page-head">
        <div><h1 className="cf-title">{W.title}</h1></div>
        <SupportTabs caps={caps} />
      </header>

      {alert.data?.raised && (
        <section className="cf-sx-alert" role="alert" aria-labelledby="cf-sx-alert">
          <h2 id="cf-sx-alert">{alertWords.title}</h2>
          {alertWords.line && <p>{alertWords.line}</p>}
          <p className="cf-muted">{reasonLabel(alert.data.lvCode ?? 'LV-DLV-001')}</p>
        </section>
      )}

      <section className="cf-sx-section" aria-labelledby="cf-sx-it">
        <h2 id="cf-sx-it" className="cf-sx-h2">{W.needsIt}</h2>
        {needsIt.error && !needsIt.data && <LoadErrorCard title="The export errors couldn't be loaded" error={needsIt.error} request="GET /support/export-jobs/needs-it" onRetry={() => void needsIt.mutate()} />}
        {!needsIt.data && !needsIt.error && <Spinner label="Loading export errors" />}
        {needsIt.data && needsIt.data.groups.length === 0 && <p className="cf-muted">{W.nothingForIt}</p>}
        {needsIt.data?.groups.map((group) => (
          <ItCard key={`${group.destinationId}|${group.lvCode}`} group={group} now={now} canRetry={canRetry} waiting={openOf(group.destinationId)}
            destinationAnswers={answers(group.destinationId)} timings={timings} onDone={refresh} />
        ))}
      </section>

      <section className="cf-sx-section cf-sx-card" aria-labelledby="cf-sx-dest">
        <div className="cf-sx-card-head">
          <h2 id="cf-sx-dest" className="cf-sx-h2">{W.destinations}</h2>
          <p className="cf-muted">{destinationsNote(timings)}</p>
        </div>
        {destinations.error && !destinations.data && <LoadErrorCard title="The destinations couldn't be loaded" error={destinations.error} request="GET /support/export-destinations" onRetry={() => void destinations.mutate()} />}
        {destinations.data && (
          <table className="cf-sx-table">
            <thead><tr><th scope="col">{W.columnDestination}</th><th scope="col">{W.columnState}</th><th scope="col">{W.columnWaiting}</th><th scope="col"><span className="cf-sr">Action</span></th></tr></thead>
            <tbody>
              {destinations.data.map((d) => <DestinationRow key={d.destinationId} d={d} now={now} canRetry={canRetry} timings={timings} onDone={refresh} />)}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}

function ItCard({ group, now, canRetry, waiting, destinationAnswers, timings, onDone }: {
  group: ItGroup; now: number; canRetry: boolean; waiting: number; destinationAnswers: boolean; timings: ExportTimings | null; onDone: () => Promise<unknown>;
}) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const signIn = isSignInGroup(group);
  const tried = triedLine(group.lvCode, timings);
  const title = signIn ? signInTitle(group.destinationName) : itGroupTitle(group, now, destinationAnswers);
  const reason = itReason(group.lvCode, destinationAnswers);
  const casesLine = itCasesLine(group.cases);

  async function act() {
    setBusy(true);
    setProblem(null);
    try {
      if (signIn && group.destinationId) {
        await apiClient.setExportDestinationWorking(group.destinationId);
        // Its jobs that went to IT with the code go back in the queue as well.
        await apiClient.retryExportGroup(group.destinationId, group.lvCode).catch(() => undefined);
        toast(`${destinationLabel(group.destinationName)} is set back to working.`);
      } else {
        const results = await apiClient.retryExportGroup(group.destinationId, group.lvCode);
        toast(`${results.length} ${results.length === 1 ? 'case' : 'cases'} queued again for ${destinationLabel(group.destinationName)}.`);
      }
      await onDone();
    } catch (e) {
      setProblem(errorMessage(e, 'This could not be done.'));
    } finally {
      setBusy(false);
    }
  }

  const codes = destinationCodesOf(group.jobs ?? []);
  return (
    <article className="cf-sx-it" aria-label={title} data-testid="it-group">
      <div className="cf-sx-it-text">
        <p className="cf-sx-it-title">{title}</p>
        <p>{reason.text} · <code className="cf-code">{reason.code}</code></p>
        <p className="cf-muted cf-small">
          {signIn ? signInBody(Math.max(waiting, group.count)) : tried}
          {!signIn && casesLine && <>{tried ? ' ' : ''}<span data-testid="it-cases">{casesLine}</span></>}
        </p>
        {codes.length > 0 && (
          <TechnicalDetails>
            <ul className="cf-sx-codes" data-testid="it-destination-codes">
              {codes.map((code) => <li key={code}>{DESTINATION_CODE_LABEL} <code className="cf-code">{code}</code></li>)}
            </ul>
          </TechnicalDetails>
        )}
        {problem && <Notice tone="danger">{problem}</Notice>}
      </div>
      {canRetry && (
        <button type="button" className="cf-btn" data-variant="solid" onClick={act} disabled={busy}>
          {busy && <Spinner label="Working" />}{signIn ? W.setBackToWorking : retryAllLabel(group.count)}
        </button>
      )}
    </article>
  );
}

function DestinationRow({ d, now, canRetry, timings, onDone }: { d: DestinationView; now: number; canRetry: boolean; timings: ExportTimings | null; onDone: () => Promise<unknown> }) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const words = destinationStateWords(d, now);

  async function checkNow() {
    setBusy(true);
    setProblem(null);
    try {
      const result = await apiClient.checkExportDestinationNow(d.destinationId);
      toast(`${d.name} ${result.passed ? W.checkPassed : checkStillFailing(timings)}`, result.passed ? 'neutral' : 'danger');
      await onDone();
    } catch (e) {
      setProblem(errorMessage(e, 'The check could not be run.'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <tr data-tone={words.tone} data-testid="export-destination">
      <th scope="row"><span className="cf-sx-name">{d.name}</span> <span className="cf-sx-kind">· {destinationKind(d.purpose)}</span></th>
      <td>
        <span className="cf-sx-state"><span className="cf-sx-dot" aria-hidden="true" />{words.state}</span>
        {words.detail && <span className="cf-sx-detail">{words.detail}</span>}
        {problem && <Notice tone="danger">{problem}</Notice>}
      </td>
      <td data-testid="export-waiting">
        {waitingParts(d.state, d.openJobs, d.waiting).map((part, i) => (
          <span key={part.text}>{i > 0 && ' · '}{part.tone === 'it' ? <span className="cf-sx-waiting-it">{part.text}</span> : part.text}</span>
        ))}
      </td>
      <td>
        {canRetry && d.state === 'PAUSED' && (
          <button type="button" className="cf-btn" data-size="sm" onClick={checkNow} disabled={busy} aria-label={`${W.checkNow}: ${d.name}`}>
            {busy && <Spinner label="Checking" />}{W.checkNow}
          </button>
        )}
      </td>
    </tr>
  );
}
