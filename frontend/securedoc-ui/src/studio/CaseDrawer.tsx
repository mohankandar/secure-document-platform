import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import useSWR, { useSWRConfig } from 'swr';
import { ArrowRight, Check, FileText, RotateCcw, Wrench, X } from 'lucide-react';
import { apiClient } from '../api/client';
import type { FocusedReviewItem, PackageDetail, PackageLifecycleSummary } from '../types';
import {
  CAP, LIFECYCLE, SOURCE_LABEL, caseActions, caseTitle, errorMessage, formatBytes, humanize, isReprocessing,
  relativeTime, stageLabel, stageOf, statusInfo, useDerivedDeliveries, usePackage,
  usePackageLifecycle, useTechnicalException, type CaseAction, type CaseActionKind, type StageKey,
} from './data';
import { Notice, Spinner, StageChip } from './atoms';
import { useToast } from './toast';

const CONFIRM_COPY: Partial<Record<CaseActionKind, { title: string; body: string }>> = {
  approve: { title: 'Release redacted documents?', body: 'Delivery starts immediately and cannot be recalled from the destination.' },
  reprocess: { title: 'Start a new export cycle?', body: 'The retained original is redacted again and redelivered. This uses one of the allowed export cycles.' },
  'technical-retry': { title: 'Retry automatic processing?', body: 'LexaVault reruns the failed stage on the retained original.' },
  'manual-fallback': { title: 'Continue this case manually?', body: 'You will place redactions yourself instead of waiting for automatic processing.' },
};

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
  const { mutate: mutateGlobal } = useSWRConfig();
  const navigate = useNavigate();
  const toast = useToast();
  const [busy, setBusy] = useState<CaseActionKind | null>(null);
  const [confirming, setConfirming] = useState<CaseActionKind | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const run = async (kind: CaseActionKind) => {
    if (kind === 'review') { navigate(`/review?case=${packageId}`); return; }
    setBusy(kind);
    setActionError(null);
    try {
      if (kind === 'approve') await apiClient.approvePackageDelivery(packageId);
      else if (kind === 'reprocess') await apiClient.reprocessPackage(packageId);
      else if (kind === 'technical-retry') await apiClient.retryTechnicalException(packageId);
      else if (kind === 'manual-fallback') await apiClient.startManualFallback(packageId);
      await Promise.all([mutate(), mutateLife(), mutateGlobal('packages'), mutateGlobal('review-queue')]);
      setConfirming(null);
      const done: Partial<Record<CaseActionKind, string>> = {
        approve: 'Delivery approved.', reprocess: 'Reprocessing started.', 'technical-retry': 'Retry started.', 'manual-fallback': 'Manual review opened.',
      };
      toast(done[kind] ?? 'Done.', 'mark');
      if (kind === 'manual-fallback') navigate(`/packages/${packageId}/manual-processing`);
    } catch (err) {
      setActionError(errorMessage(err, 'That action could not be completed. Nothing was changed.'));
      await mutate();
    } finally {
      setBusy(null);
    }
  };

  const title = pkg ? caseTitle(pkg.businessIdentifiers) : null;
  const reprocessing = pkg ? isReprocessing(pkg.status, life) : false;
  const stage: StageKey | null = pkg ? (reprocessing ? 'redacting' : stageOf(pkg.status)) : null;
  const actions = pkg ? caseActions(pkg.status, caps, life, reprocessing, openFindings.length)
    .filter(action => action.kind !== 'manual-fallback' || manualEligibility.data?.eligible === true) : [];

  return (
    <aside className="cf-drawer" aria-label="Case details">
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
            stage={stage}
            status={pkg.status}
            reprocessing={reprocessing}
            caps={caps}
            packageId={packageId}
            openFindings={openFindings.length}
            actions={actions}
            busy={busy}
            confirming={confirming}
            onAction={(a) => (CONFIRM_COPY[a.kind] ? setConfirming(a.kind) : run(a.kind))}
            onConfirm={() => confirming && run(confirming)}
            onCancel={() => setConfirming(null)}
          />
          {actionError && <Notice tone="danger">{actionError}</Notice>}
          {pkg.status === 'FAILED' && caps.includes(CAP.review) && manualEligibility.data?.eligible === false && (
            <Notice tone="info">Manual processing unavailable: {manualEligibility.data.reason}</Notice>
          )}
          {pkg.status === 'FAILED' && caps.includes(CAP.review) && manualEligibility.error && (
            <Notice tone="info">Manual processing eligibility could not be verified. Automatic support recovery remains available.</Notice>
          )}

          {pkg.status === 'FAILED' && caps.includes(CAP.technicalView) && <TechnicalDetail packageId={packageId} />}

          <DocumentsSection pkg={pkg} openFindings={openFindings} />

          {caps.includes(CAP.approve) && life && life.successfulExportCycles > 0 && <DeliveriesSection packageId={packageId} />}

          {life && <RetentionSection life={life} />}

          <details className="cf-disclosure">
            <summary>Identity and source</summary>
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
              <div><dt>Status code</dt><dd className="cf-mono">{pkg.status}</dd></div>
            </dl>
          </details>
        </div>
      )}
    </aside>
  );
}

function Timeline({ stage, status }: { stage: StageKey; status: string }) {
  const stopped = stage === 'stopped';
  const currentIndex = stopped ? (status === 'REJECTED' ? 0 : 1) : LIFECYCLE.indexOf(stage);
  return (
    <ol className="cf-timeline" aria-label="Lifecycle">
      {LIFECYCLE.map((key, index) => {
        const state = index < currentIndex ? 'done' : index === currentIndex ? (stopped ? 'stopped' : key === 'delivered' ? 'done' : 'current') : 'next';
        return (
          <li key={key} data-state={state} aria-current={index === currentIndex ? 'step' : undefined}>
            <span className="cf-timeline-dot" aria-hidden="true">{state === 'done' && <Check size={11} strokeWidth={3} />}</span>
            <span className="cf-timeline-label">{index === currentIndex && stopped ? statusInfo(status).label : stageLabel(key)}</span>
          </li>
        );
      })}
    </ol>
  );
}

function NextStep({ stage, status, reprocessing, caps, packageId, openFindings, actions, busy, confirming, onAction, onConfirm, onCancel }: {
  stage: StageKey; status: string; reprocessing: boolean; caps: string[]; packageId: string; openFindings: number;
  actions: CaseAction[]; busy: CaseActionKind | null; confirming: CaseActionKind | null;
  onAction: (a: CaseAction) => void; onConfirm: () => void; onCancel: () => void;
}) {
  const info = statusInfo(status, reprocessing);
  const actionable = actions.some((a) => a.primary);
  const tone: 'mark' | 'neutral' | 'danger' = stage === 'stopped' ? 'danger' : actionable ? 'mark' : 'neutral';
  const heading = headingFor(stage, status, openFindings, actionable, reprocessing);
  const waitingOn = !actionable ? waitingCopy(stage, status, caps) : null;
  const confirmCopy = confirming ? CONFIRM_COPY[confirming] : null;

  return (
    <section className="cf-next" data-tone={tone} aria-label="Next step">
      <div className="cf-next-text">
        <span className="cf-eyebrow">Next step · <StageChip stage={stage} status={status} label={info.label} /></span>
        <p className="cf-next-heading">{heading}</p>
        <p className="cf-next-body">{info.detail}{waitingOn ? ` ${waitingOn}` : ''}</p>
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
            <button key={a.kind} type="button" className="cf-btn" data-variant={a.primary ? 'mark' : 'ghost'} onClick={() => onAction(a)} disabled={busy !== null}>
              {a.kind === 'reprocess' || a.kind === 'technical-retry' ? <RotateCcw size={16} aria-hidden="true" /> : a.kind === 'manual-fallback' ? <Wrench size={16} aria-hidden="true" /> : <Check size={16} aria-hidden="true" />}
              {a.label}
            </button>
          ))}
        </div>
      )}
    </section>
  );
}

function headingFor(stage: StageKey, status: string, openFindings: number, actionable: boolean, reprocessing: boolean): string {
  if (reprocessing) return 'Reprocessing the retained original';
  switch (stage) {
    case 'intake': return 'Waiting for documents';
    case 'redacting': return 'LexaVault is redacting';
    case 'review':
      if (openFindings > 0) return `${openFindings} ${openFindings === 1 ? 'finding needs' : 'findings need'} a decision`;
      return 'No open findings in the current queue';
    case 'approval': return actionable ? 'Ready for your sign-off' : 'Waiting for sign-off';
    case 'delivered': return 'Delivered';
    case 'stopped': return status === 'REJECTED' ? 'Rejected at intake' : 'Processing stopped';
    default: return 'Status not recognised';
  }
}

function waitingCopy(stage: StageKey, status: string, caps: string[]): string | null {
  if (stage === 'review' && !caps.includes(CAP.review)) return 'A reviewer will pick this up.';
  if (stage === 'approval' && !caps.includes(CAP.approve)) return 'A supervisor needs to approve delivery.';
  if (status === 'FAILED' && !caps.includes(CAP.technicalRetry) && !caps.includes(CAP.review)) return 'Technical support has been notified.';
  return null;
}

function TechnicalDetail({ packageId }: { packageId: string }) {
  const { data, error, isLoading } = useTechnicalException(packageId, true);
  return (
    <details className="cf-disclosure">
      <summary>What went wrong</summary>
      {isLoading && <Spinner label="Loading details" />}
      {error && <p className="cf-muted">Technical details are not available right now.</p>}
      {data && (
        <>
          <p className="cf-disclosure-lead">{data.explanation}</p>
          <dl className="cf-facts">
            <div><dt>Stage</dt><dd>{humanize(data.failedStage)}</dd></div>
            <div><dt>Category</dt><dd>{humanize(data.failureCategory)}</dd></div>
            <div><dt>Retry possible</dt><dd>{data.retryable ? 'Yes' : 'No'}</dd></div>
            <div><dt>Failed</dt><dd><time dateTime={data.failedAt}>{relativeTime(data.failedAt)}</time></dd></div>
            {data.originalRetainedUntil && <div><dt>Original kept until</dt><dd><time dateTime={data.originalRetainedUntil}>{formatDate(data.originalRetainedUntil)}</time></dd></div>}
            {data.correlationId && <div><dt>Reference</dt><dd className="cf-mono">{data.correlationId}</dd></div>}
          </dl>
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
                    {doc.status === 'FAILED' && <> · Failed</>}
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
                {humanize(d.status)} · cycle {d.exportCycle}
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
        Retention
        {soon && <span className="cf-count" data-tone="mark">Redacted copy expires {relativeFuture(life.redactedDeleteAt as string)}</span>}
      </summary>
      <dl className="cf-facts">
        <div><dt>Export cycles</dt><dd>{life.successfulExportCycles} of {life.maximumExportCycles}</dd></div>
        <div><dt>Final approval</dt><dd>{!life.finalApprovalRequired ? 'Not required' : life.finalApprovalGranted ? 'Granted' : 'Required'}</dd></div>
        <RetentionRow label="Redacted copy" deleteAt={life.redactedDeleteAt} purgedAt={life.redactedPurgedAt} />
        <RetentionRow label="Retained original" deleteAt={life.originalDeleteAt} purgedAt={life.originalPurgedAt ?? life.purgedAt} />
        {life.technicalHoldUntil && <div><dt>Technical hold</dt><dd>Until <time dateTime={life.technicalHoldUntil}>{formatDate(life.technicalHoldUntil)}</time></dd></div>}
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

function formatDate(value: string) {
  return new Date(value).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

function relativeFuture(value: string) {
  const ms = new Date(value).getTime() - Date.now();
  const hours = Math.round(ms / 3_600_000);
  if (hours < 1) return 'within the hour';
  if (hours < 48) return `in ${hours} hour${hours === 1 ? '' : 's'}`;
  return `in ${Math.round(hours / 24)} days`;
}
