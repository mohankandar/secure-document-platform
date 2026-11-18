import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import useSWR from 'swr';
import { ArrowRight, Check, FilePlus, FileText, RotateCcw, Wrench, X } from 'lucide-react';
import { apiClient } from '../api/client';
import type { FocusedReviewItem, PackageDetail, PackageLifecycleSummary, TechnicalExceptionDetail } from '../types';
import {
  CAP, LIFECYCLE, SOURCE_LABEL, caseActions, caseTitle, formatBytes, humanize, isReprocessing,
  relativeTime, stageLabel, stageOf, statusInfo, useDerivedDeliveries, usePackage,
  usePackageLifecycle, useTechnicalException, type CaseAction, type CaseActionKind, type StageKey,
} from './data';
import { Notice, Spinner, StageChip } from './atoms';
import { CONFIRM_COPY, useCaseActionRunner } from './caseActionRunner';
import { TechnicalDetails } from './failure';
import { CASE_SECTIONS, destinationName, runsLeft } from './words';

export default function CaseDrawer({ packageId, caps, openFindings, onClose }: {
  packageId: string; caps: string[]; openFindings: FocusedReviewItem[]; onClose: () => void;
}) {
  const { data: pkg, error, mutate } = usePackage(packageId);
  const canDesk = caps.includes(CAP.desk);
  const manualEligibility = useSWR(pkg?.status === 'FAILED' && caps.includes(CAP.review)
    ? ['manual-fallback-eligibility', packageId] : null,
    () => apiClient.getManualFallbackEligibility(packageId),
    { revalidateOnFocus: false, shouldRetryOnError: false });
  const { data: life, mutate: mutateLife } = usePackageLifecycle(packageId, canDesk);
  const technical = useTechnicalException(packageId, pkg?.status === 'FAILED' && caps.includes(CAP.technicalView));
  // The same runner and confirmation words as the action button on the Cases row (caseActionRunner).
  const runner = useCaseActionRunner(packageId, () => Promise.all([mutate(), mutateLife()]));
  const [confirming, setConfirming] = useState<CaseActionKind | null>(null);

  const run = async (kind: CaseActionKind) => {
    if (await runner.run(kind)) setConfirming(null);
  };

  const title = pkg ? caseTitle(pkg.businessIdentifiers) : null;
  const reprocessing = pkg ? isReprocessing(pkg.status, life) : false;
  const stage: StageKey | null = pkg ? (reprocessing ? 'redacting' : stageOf(pkg.status)) : null;
  const actions = pkg ? caseActions(pkg.status, caps, life, reprocessing, openFindings.length)
    .filter(action => action.kind !== 'manual-fallback' || manualEligibility.data?.eligible === true) : [];

  return (
    <aside className="cf-drawer" aria-label="Case panel">
      <div className="cf-drawer-head">
        <div className="cf-drawer-title">
          <span className="cf-eyebrow">Case</span>
          <h2 className="cf-mono">{title?.primary ?? '…'}</h2>
          {title?.secondary && <p>{title.secondary}</p>}
        </div>
        <button type="button" className="cf-btn" data-variant="ghost" data-size="icon" onClick={onClose} aria-label="Close case">
          <X size={18} />
        </button>
      </div>

      {error && <Notice tone="danger">This case could not be loaded. It may have been removed or you may no longer have access.</Notice>}
      {!pkg && !error && <div className="cf-center"><Spinner label="Opening case" /></div>}

      {pkg && stage && (
        <div className="cf-drawer-body">
          {stage !== 'unknown' && <Timeline stage={stage} status={pkg.status} />}

          <NextStep
            pkg={pkg}
            life={life}
            technical={technical.data}
            stage={stage}
            status={pkg.status}
            reprocessing={reprocessing}
            caps={caps}
            packageId={packageId}
            openFindings={openFindings.length}
            actions={actions}
            busy={runner.busy}
            confirming={confirming}
            onAction={(a) => (CONFIRM_COPY[a.kind] ? setConfirming(a.kind) : run(a.kind))}
            onConfirm={() => confirming && run(confirming)}
            onCancel={() => setConfirming(null)}
          />
          {runner.error && <Notice tone="danger">{runner.error}</Notice>}
          {pkg.status === 'FAILED' && caps.includes(CAP.review) && manualEligibility.data?.eligible === false && (
            <Notice tone="neutral">Continuing manually is not possible: {manualEligibility.data.reason}</Notice>
          )}
          {pkg.status === 'FAILED' && caps.includes(CAP.review) && manualEligibility.error && (
            <Notice tone="neutral">Whether this case can be continued manually could not be checked. Automatic recovery remains available.</Notice>
          )}

          {pkg.status === 'FAILED' && caps.includes(CAP.technicalView) && <TechnicalDetail data={technical.data} error={technical.error} isLoading={technical.isLoading} />}

          <DocumentsSection pkg={pkg} openFindings={openFindings} />

          {life && life.successfulExportCycles > 0 && life.lastExportedAt && (
            <details className="cf-disclosure">
              <summary>{CASE_SECTIONS.sentTo}</summary>
              <p className="cf-disclosure-lead">
                {capitalize(destinationName(life.lastDestinationType))} · <time dateTime={life.lastExportedAt}>{formatDate(life.lastExportedAt)}</time>
              </p>
            </details>
          )}

          {caps.includes(CAP.approve) && life && life.successfulExportCycles > 0 && <DeliveriesSection packageId={packageId} />}

          {life && <RetentionSection life={life} />}

          <details className="cf-disclosure">
            <summary>{CASE_SECTIONS.details}</summary>
            <dl className="cf-facts">
              {pkg.businessIdentifiers.map((id) => (
                <div key={id.key}>
                  <dt>{id.label}</dt>
                  <dd className={id.primary ? 'cf-mono' : undefined}>{id.value || '—'}</dd>
                </div>
              ))}
              <div><dt>Source</dt><dd>{SOURCE_LABEL[pkg.packageType] ?? humanize(pkg.packageType)}</dd></div>
              <div><dt>Opened</dt><dd><time dateTime={pkg.createdAt}>{relativeTime(pkg.createdAt)}</time></dd></div>
              <div><dt>Last activity</dt><dd><time dateTime={pkg.updatedAt}>{relativeTime(pkg.updatedAt)}</time></dd></div>
            </dl>
            <TechnicalDetails>
              <dl className="cf-tech-defs">
                <div><dt>Case ID</dt><dd className="cf-mono">{pkg.packageId}</dd></div>
                <div><dt>Status code</dt><dd className="cf-mono">{pkg.status}</dd></div>
              </dl>
            </TechnicalDetails>
          </details>
        </div>
      )}
    </aside>
  );
}

/** The stage bar. Every label is a full stage name ("Stopped", "Rejected"), never a technical status. */
function Timeline({ stage, status }: { stage: StageKey; status: string }) {
  const stopped = stage === 'stopped';
  const currentIndex = stopped ? (status === 'REJECTED' ? 0 : 1) : LIFECYCLE.indexOf(stage);
  return (
    <ol className="cf-timeline" aria-label="Stages">
      {LIFECYCLE.map((key, index) => {
        const state = index < currentIndex ? 'done' : index === currentIndex ? (stopped ? 'stopped' : key === 'delivered' ? 'done' : 'current') : 'next';
        return (
          <li key={key} data-stage={key} data-state={state} aria-current={index === currentIndex ? 'step' : undefined}>
            <span className="cf-timeline-dot" aria-hidden="true">{state === 'done' && <Check size={11} strokeWidth={3} />}</span>
            <span className="cf-timeline-label">{index === currentIndex && stopped ? statusInfo(status).label : stageLabel(key)}</span>
          </li>
        );
      })}
    </ol>
  );
}

function NextStep({ pkg, life, technical, stage, status, reprocessing, caps, packageId, openFindings, actions, busy, confirming, onAction, onConfirm, onCancel }: {
  pkg: PackageDetail; life: PackageLifecycleSummary | undefined; technical: TechnicalExceptionDetail | undefined;
  stage: StageKey; status: string; reprocessing: boolean; caps: string[]; packageId: string; openFindings: number;
  actions: CaseAction[]; busy: CaseActionKind | null; confirming: CaseActionKind | null;
  onAction: (a: CaseAction) => void; onConfirm: () => void; onCancel: () => void;
}) {
  const navigate = useNavigate();
  const info = statusInfo(status, reprocessing);
  const actionable = actions.some((a) => a.primary);
  const tone: 'mark' | 'neutral' | 'danger' = stage === 'stopped' ? 'danger' : actionable ? 'mark' : 'neutral';
  const settled = stage === 'delivered' || status === 'REJECTED';
  const heading = headingFor(stage, status, openFindings, actionable, reprocessing, life);
  const waitingOn = !actionable ? waitingCopy(stage, status, caps) : null;
  const body = bodyFor(status, info.detail, life, technical) + (waitingOn ? ` ${waitingOn}` : '');
  const confirmCopy = confirming ? CONFIRM_COPY[confirming] : null;
  const title = caseTitle(pkg.businessIdentifiers);
  const primaryField = pkg.businessIdentifiers.find((i) => i.primary) ?? pkg.businessIdentifiers[0];
  const canStartNew = status === 'REJECTED' && caps.includes(CAP.intake) && Boolean(primaryField?.value);

  return (
    <section className="cf-next" data-tone={tone} aria-label="Next step">
      <div className="cf-next-text">
        <span className="cf-eyebrow">{settled ? 'Done' : 'Next step'} · <StageChip stage={stage} status={status} label={info.label} /></span>
        <p className="cf-next-heading">{heading}</p>
        <p className="cf-next-body">{body}</p>
      </div>

      {stage === 'redacting' && <span className="cf-next-pulse" aria-hidden="true" />}

      {confirmCopy ? (
        <div className="cf-confirm" role="group" aria-label={confirmCopy.title}>
          <p className="cf-confirm-title">{confirmCopy.title}</p>
          <p className="cf-confirm-body">{confirmCopy.body}</p>
          <div className="cf-next-action">
            <button type="button" className="cf-btn" data-variant="mark" onClick={onConfirm} disabled={busy !== null} autoFocus>
              {busy ? <Spinner label="Working" /> : <Check size={16} aria-hidden="true" />} Confirm
            </button>
            <button type="button" className="cf-btn" data-variant="ghost" onClick={onCancel} disabled={busy !== null}>Cancel</button>
          </div>
        </div>
      ) : actions.length > 0 && (
        <div className="cf-next-action">
          {actions.map((a) => a.kind === 'review' ? (
            <Link key={a.kind} to={`/review?case=${packageId}`} className="cf-btn" data-variant="mark">
              {a.label} <ArrowRight size={16} aria-hidden="true" />
            </Link>
          ) : (
            <span key={a.kind} className="cf-next-choice">
              <button type="button" className="cf-btn" data-variant={a.primary ? 'mark' : 'ghost'} onClick={() => onAction(a)} disabled={busy !== null}>
                {a.kind === 'reprocess' || a.kind === 'technical-retry' ? <RotateCcw size={16} aria-hidden="true" /> : a.kind === 'manual-fallback' ? <Wrench size={16} aria-hidden="true" /> : <Check size={16} aria-hidden="true" />}
                {a.label}
              </button>
              {a.kind === 'reprocess' && life && (
                <span className="cf-next-hint">Runs redaction again on the kept original. {runsLeft(life.successfulExportCycles, life.maximumExportCycles)}.</span>
              )}
            </span>
          ))}
        </div>
      )}

      {!confirmCopy && canStartNew && primaryField && (
        <div className="cf-next-action">
          <span className="cf-next-choice">
            <button type="button" className="cf-btn" onClick={() => navigate(`/intake?id=${encodeURIComponent(primaryField.value)}`)}>
              <FilePlus size={16} aria-hidden="true" /> Start a new case for {title.primary}
            </button>
            <span className="cf-next-hint">Opens New case with the {primaryField.label} filled in.</span>
          </span>
        </div>
      )}
    </section>
  );
}

/** The plain body for the stopped, rejected and delivered states (UI spec finding 19); other states keep their detail. */
function bodyFor(status: string, detail: string, life: PackageLifecycleSummary | undefined, technical: TechnicalExceptionDetail | undefined): string {
  if (status === 'FAILED') {
    const reason = sentence(technical?.explanation?.trim() || 'Automatic processing could not finish this case.');
    if (life?.originalPurgedAt || life?.purgedAt) return `${reason} The original has been deleted, so the case cannot be finished here.`;
    const keptUntil = technical?.originalRetainedUntil ?? life?.technicalHoldUntil ?? life?.originalDeleteAt;
    return keptUntil
      ? `${reason} The original is kept until ${formatDay(keptUntil)}, so the case can still be finished.`
      : `${reason} The original is kept, so the case can still be finished.`;
  }
  if (status === 'REJECTED') {
    // The API returns no rejection reason today (see report U003); nothing is invented here.
    return 'The source was not accepted. Nothing was processed.';
  }
  if (['COMPLETED', 'EXPORTED', 'DELIVERED'].includes(status) && life?.lastExportedAt) {
    const kept: string[] = [];
    if (life.redactedPurgedAt) kept.push('the redacted copy was deleted on schedule');
    else if (life.redactedDeleteAt) kept.push(`redacted copy kept here until ${formatDay(life.redactedDeleteAt)}`);
    if (life.originalPurgedAt || life.purgedAt) kept.push('the original was deleted on schedule');
    return kept.length ? `${formatDate(life.lastExportedAt)}. ${capitalize(kept.join('; '))}.` : `${formatDate(life.lastExportedAt)}.`;
  }
  return detail;
}

function headingFor(stage: StageKey, status: string, openFindings: number, actionable: boolean, reprocessing: boolean, life?: PackageLifecycleSummary): string {
  if (reprocessing) return 'Processing the kept original again';
  switch (stage) {
    case 'intake': return 'Waiting for documents';
    case 'redacting': return 'LexaVault is redacting';
    case 'review':
      if (openFindings > 0) return `${openFindings} ${openFindings === 1 ? 'finding needs' : 'findings need'} a decision`;
      return 'No open findings in the current queue';
    case 'approval': return actionable ? 'Ready for your sign-off' : 'Waiting for sign-off';
    case 'delivered': return life?.lastExportedAt ? `Delivered to ${destinationName(life.lastDestinationType)}` : 'Delivered';
    case 'stopped': return status === 'REJECTED' ? 'Not accepted at intake' : 'Processing stopped';
    default: return 'Status not recognised';
  }
}

function waitingCopy(stage: StageKey, status: string, caps: string[]): string | null {
  if (stage === 'review' && !caps.includes(CAP.review)) return 'A reviewer will pick this up.';
  if (stage === 'approval' && !caps.includes(CAP.approve)) return 'A supervisor needs to approve delivery.';
  if (status === 'FAILED' && !caps.includes(CAP.technicalRetry) && !caps.includes(CAP.review)) return 'Technical support has been notified.';
  return null;
}

function TechnicalDetail({ data, error, isLoading }: { data?: TechnicalExceptionDetail; error?: unknown; isLoading: boolean }) {
  return (
    <details className="cf-disclosure">
      <summary>{CASE_SECTIONS.whatWentWrong}</summary>
      {isLoading && <Spinner label="Loading details" />}
      {Boolean(error) && <p className="cf-muted">The details are not available right now.</p>}
      {data && (
        <>
          <p className="cf-disclosure-lead">{data.explanation}</p>
          <dl className="cf-facts">
            <div><dt>Can be retried</dt><dd>{data.retryable ? 'Yes' : 'No'}</dd></div>
            <div><dt>Stopped</dt><dd><time dateTime={data.failedAt}>{relativeTime(data.failedAt)}</time></dd></div>
            {data.originalRetainedUntil && <div><dt>Original kept until</dt><dd><time dateTime={data.originalRetainedUntil}>{formatDate(data.originalRetainedUntil)}</time></dd></div>}
          </dl>
          <TechnicalDetails>
            <dl className="cf-tech-defs">
              <div><dt>Step</dt><dd className="cf-mono">{data.failedStage}</dd></div>
              <div><dt>Category</dt><dd className="cf-mono">{data.failureCategory}</dd></div>
              {data.correlationId && <div><dt>Reference</dt><dd className="cf-mono">{data.correlationId}</dd></div>}
            </dl>
          </TechnicalDetails>
        </>
      )}
    </details>
  );
}

function DocumentsSection({ pkg, openFindings }: { pkg: PackageDetail; openFindings: FocusedReviewItem[] }) {
  return (
    <details className="cf-disclosure" open={openFindings.length > 0 || pkg.documents.length <= 3}>
      <summary>Documents <span className="cf-count">{pkg.documents.length}</span></summary>
      {pkg.documents.length === 0 ? (
        <p className="cf-muted">No documents yet.</p>
      ) : (
        <ul className="cf-docs">
          {pkg.documents.map((doc) => {
            const findings = openFindings.filter((f) => f.documentId === doc.documentId).length;
            return (
              <li key={doc.documentId} className="cf-doc">
                <FileText size={16} aria-hidden="true" className="cf-doc-icon" />
                <span className="cf-doc-name">
                  <span>{doc.fileName || 'Untitled document'}</span>
                  <span className="cf-doc-meta">
                    {formatBytes(doc.sizeBytes)}
                    {doc.status === 'FAILED' && <> · Stopped</>}
                    {findings > 0 && <> · <mark className="cf-mark-text">{findings} to review</mark></>}
                  </span>
                </span>
              </li>
            );
          })}
        </ul>
      )}
      <p className="cf-muted cf-small">Documents are processed and delivered through configured integrations; files cannot be downloaded here.</p>
    </details>
  );
}

function DeliveriesSection({ packageId }: { packageId: string }) {
  const { data, error } = useDerivedDeliveries(packageId, true);
  if (error || !data || data.length === 0) return null;
  const attention = data.filter((d) => d.status === 'ATTENTION_REQUIRED').length;
  return (
    <details className="cf-disclosure" open={attention > 0}>
      <summary>Deliveries <span className="cf-count">{data.length}</span>{attention > 0 && <span className="cf-count" data-tone="danger">{attention} need attention</span>}</summary>
      <ul className="cf-docs">
        {data.map((d) => (
          <li key={d.derivedDeliveryId} className="cf-doc">
            <span className="cf-doc-name">
              <span>{d.destinationLabel || d.fileName || 'Destination'}</span>
              <span className="cf-doc-meta">
                {humanize(d.status)} · run {d.exportCycle}
                {d.deliveredAt && <> · <time dateTime={d.deliveredAt}>{relativeTime(d.deliveredAt)}</time></>}
                {d.failureCategory && <> · {humanize(d.failureCategory)}</>}
              </span>
            </span>
          </li>
        ))}
      </ul>
    </details>
  );
}

function RetentionSection({ life }: { life: PackageLifecycleSummary }) {
  const now = Date.now();
  const redactedMs = life.redactedDeleteAt ? new Date(life.redactedDeleteAt).getTime() - now : null;
  const soon = redactedMs !== null && redactedMs > 0 && redactedMs < 24 * 3_600_000 && !life.redactedPurgedAt;
  return (
    <details className="cf-disclosure" open={soon}>
      <summary>
        {CASE_SECTIONS.keeping}
        {soon && <span className="cf-count" data-tone="mark">Redacted copy expires {relativeFuture(life.redactedDeleteAt as string)}</span>}
      </summary>
      <dl className="cf-facts">
        <div><dt>Runs used</dt><dd>{life.successfulExportCycles} of {life.maximumExportCycles}</dd></div>
        <div><dt>Final approval</dt><dd>{!life.finalApprovalRequired ? 'Not required' : life.finalApprovalGranted ? 'Granted' : 'Required'}</dd></div>
        <RetentionRow label="Redacted copy" deleteAt={life.redactedDeleteAt} purgedAt={life.redactedPurgedAt} />
        <RetentionRow label="Kept original" deleteAt={life.originalDeleteAt} purgedAt={life.originalPurgedAt ?? life.purgedAt} />
        {life.technicalHoldUntil && <div><dt>Kept while stopped</dt><dd>Until <time dateTime={life.technicalHoldUntil}>{formatDate(life.technicalHoldUntil)}</time></dd></div>}
      </dl>
    </details>
  );
}

function RetentionRow({ label, deleteAt, purgedAt }: { label: string; deleteAt?: string | null; purgedAt?: string | null }) {
  let value: JSX.Element | string = 'No deletion scheduled';
  if (purgedAt) value = <>Deleted <time dateTime={purgedAt}>{relativeTime(purgedAt)}</time></>;
  else if (deleteAt) value = new Date(deleteAt).getTime() <= Date.now()
    ? 'Deletion pending'
    : <>Deleted <time dateTime={deleteAt}>{relativeFuture(deleteAt)}</time></>;
  return <div><dt>{label}</dt><dd>{value}</dd></div>;
}

function sentence(text: string): string {
  const trimmed = text.trim();
  return /[.!?]$/.test(trimmed) ? trimmed : `${trimmed}.`;
}

function capitalize(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}

function formatDate(value: string) {
  return new Date(value).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

function formatDay(value: string) {
  return new Date(value).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

function relativeFuture(value: string) {
  const ms = new Date(value).getTime() - Date.now();
  const hours = Math.round(ms / 3_600_000);
  if (hours < 1) return 'within the hour';
  if (hours < 48) return `in ${hours} hour${hours === 1 ? '' : 's'}`;
  return `in ${Math.round(hours / 24)} days`;
}
