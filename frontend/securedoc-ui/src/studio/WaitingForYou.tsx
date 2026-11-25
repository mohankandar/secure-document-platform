import { useState } from 'react';
import { useSWRConfig } from 'swr';
import { Link } from 'react-router-dom';
import { ChevronRight } from 'lucide-react';
import { apiClient } from '../api/client';
import { caseTitle, errorMessage, useCaseBrief, useWaitingForYou } from './data';
import { CodeLabel, Notice, Spinner } from './atoms';
import { LoadErrorCard } from './failure';
import { Sheet } from './admin/shared';
import { useToast } from './toast';
import {
  RESEND_WORDS, WAITING_WORDS, dayAndTime, destinationLabel, groupCount, groupKind, isSplit, mostFrequentCode,
  resendAllLabel, resendConfirmLabel, resendReasonLines, resendTitle, resendToast, type ExportJobView, type WaitingGroup,
} from './exportRules';
import { LvCodeLink } from './help/LvCodeLink';

/**
 * Task 0029, board "Waiting for you (Processor)": the cases a destination refused, one group per destination with one
 * "Resend all N" in its header. No checkboxes and no buttons on the rows: the whole row opens the case, where one
 * "Resend this case" sends just that one.
 */
export default function WaitingForYou() {
  const { data, error, isLoading, mutate } = useWaitingForYou(true);
  const { mutate: refreshKey } = useSWRConfig();
  const [now] = useState(() => Date.now());
  const groups = (data?.destinations ?? []).filter((g) => g.total > 0 && g.jobs.length > 0);

  return (
    <div className="cf-wy">
      <header className="cf-page-head">
        <div>
          <h1 className="cf-title">{WAITING_WORDS.title}</h1>
          <p className="cf-lede cf-wy-intro">{WAITING_WORDS.intro}</p>
        </div>
      </header>

      {error && !data && <LoadErrorCard title="The refused cases couldn't be loaded" error={error} request="GET /export-jobs/waiting-for-you" onRetry={() => void mutate()} />}
      {isLoading && <div className="cf-center"><Spinner label="Loading refused cases" /></div>}
      {data && groups.length === 0 && (
        <div className="cf-empty">
          <p className="cf-empty-title">{WAITING_WORDS.empty}</p>
          <p className="cf-empty-text">{WAITING_WORDS.emptyText}</p>
        </div>
      )}

      {groups.map((group, i) => (
        <DestinationGroup key={group.destinationId ?? `g${i}`} group={group} index={i} now={now} primary={i === 0} onQueued={() => Promise.all([mutate(), refreshKey('export-waiting-for-you-badge')])} />
      ))}

      {data && <p className="cf-wy-foot">{WAITING_WORDS.footnoteBefore}<LvCodeLink code={WAITING_WORDS.footnoteCode} />{WAITING_WORDS.footnoteAfter}</p>}
    </div>
  );
}

function DestinationGroup({ group, index, now, primary, onQueued }: { group: WaitingGroup; index: number; now: number; primary: boolean; onQueued: () => unknown }) {
  const [confirming, setConfirming] = useState(false);
  const headingId = `cf-wy-g${index}`;
  const split = groupKind(group) === 'split by barcode';
  const often = mostFrequentCode(group.countsByCode);
  return (
    <section className="cf-wy-group" aria-labelledby={headingId} data-testid="waiting-group">
      <header className="cf-wy-head">
        <div className="cf-wy-head-text">
          <h2 id={headingId}>
            {destinationLabel(group.destinationName)}{' '}
            <span className="cf-wy-kind">· {groupKind(group)} · {groupCount(group)}</span>
          </h2>
          {often && <p className="cf-wy-often">{WAITING_WORDS.mostOften}: <CodeLabel code={often} /></p>}
        </div>
        <button type="button" className="cf-btn" data-variant={primary ? 'solid' : undefined} disabled={!group.destinationId}
          onClick={() => setConfirming(true)}>
          {resendAllLabel(group.total)}
        </button>
      </header>
      <div className="cf-wy-row cf-wy-cols" aria-hidden="true">
        <span>{split ? WAITING_WORDS.columnCaseDocument : WAITING_WORDS.columnCase}</span>
        <span>{WAITING_WORDS.columnWhy}</span>
        <span>{WAITING_WORDS.columnSince}</span>
        <span />
      </div>
      <ul className="cf-wy-rows">
        {group.jobs.map((job) => <WaitingRow key={job.jobId} job={job} now={now} />)}
      </ul>
      <ResendAllDialog open={confirming} group={group} onClose={() => setConfirming(false)} onQueued={onQueued} />
    </section>
  );
}


/** One refused case (or split document). The whole row is a link to the case. */
function WaitingRow({ job, now }: { job: ExportJobView; now: number }) {
  const { data: pkg } = useCaseBrief(job.packageId);
  const name = pkg ? caseTitle(pkg.businessIdentifiers).primary : `Case ${job.packageId.slice(0, 8)}`;
  const docIndex = isSplit(job.stage) && pkg && job.documentId ? pkg.documents.findIndex((d) => d.documentId === job.documentId) : -1;
  const label = isSplit(job.stage) ? `${name} · ${docIndex >= 0 ? `doc ${docIndex + 1}` : 'document'}` : name;
  return (
    <li>
      <Link to={`/case/${job.packageId}`} className="cf-wy-row cf-wy-link" data-testid="waiting-row">
        <span className="cf-mono cf-wy-case">{label}</span>
        <span><CodeLabel code={job.lvCode ?? ''} plain /></span>
        <span className="cf-muted"><time dateTime={job.since ?? undefined}>{dayAndTime(job.since, now)}</time></span>
        <ChevronRight size={16} aria-hidden="true" className="cf-muted" />
      </Link>
    </li>
  );
}

/** Board "Resend all: confirmation". After: the toast, and the group leaves the list as its cases go back in the queue. */
function ResendAllDialog({ open, group, onClose, onQueued }: { open: boolean; group: WaitingGroup; onClose: () => void; onQueued: () => unknown }) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  async function confirm() {
    if (!group.destinationId) return;
    setBusy(true);
    setProblem(null);
    try {
      const result = await apiClient.resendAllExportJobs(group.destinationId);
      toast(resendToast(result.queued, group.destinationName));
      onClose();
      await onQueued();
    } catch (e) {
      setProblem(errorMessage(e, 'The cases could not be sent again.'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Sheet open={open} title={resendTitle(group.total, group.destinationName)} onClose={() => { if (!busy) { setProblem(null); onClose(); } }}
      footer={<>
        <button type="button" className="cf-btn" data-variant="ghost" onClick={() => { setProblem(null); onClose(); }} disabled={busy}>{RESEND_WORDS.notNow}</button>
        <button type="button" className="cf-btn" data-variant="solid" onClick={confirm} disabled={busy}>
          {busy && <Spinner label="Sending" />}{resendConfirmLabel(group.total)}
        </button>
      </>}>
      <p className="cf-wy-dialog-text">{RESEND_WORDS.body}</p>
      <ul className="cf-wy-reasons" aria-label="Why they were refused">
        {resendReasonLines(group.countsByCode).map((line) => (
          <li key={line.code}>{line.lead} <strong>{line.what}</strong> · <LvCodeLink code={line.code} /></li>
        ))}
      </ul>
      <p className="cf-wy-dialog-text">{RESEND_WORDS.check}</p>
      {problem && <Notice tone="danger">{problem}</Notice>}
    </Sheet>
  );
}
