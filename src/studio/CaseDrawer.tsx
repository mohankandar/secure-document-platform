import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useSWRConfig } from 'swr';
import { ArrowRight, Check, Download, FileText, RotateCcw, X } from 'lucide-react';
import { apiClient } from '../api/client';
import type { FocusedReviewItem } from '../types';
import { CAP, LIFECYCLE, SOURCE_LABEL, caseTitle, errorMessage, formatBytes, humanize, relativeTime, stageLabel, stageOf, usePackage, type StageKey } from './data';
import { Notice, Spinner, StageChip } from './atoms';
import { useToast } from './toast';

export default function CaseDrawer({ packageId, caps, openFindings, onClose }: {
  packageId: string; caps: string[]; openFindings: FocusedReviewItem[]; onClose: () => void;
}) {
  const { data: pkg, error, mutate } = usePackage(packageId);
  const { mutate: mutateGlobal } = useSWRConfig();
  const toast = useToast();
  const [busy, setBusy] = useState<'approve' | 'reprocess' | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const run = async (kind: 'approve' | 'reprocess') => {
    setBusy(kind);
    setActionError(null);
    try {
      if (kind === 'approve') await apiClient.approvePackageDelivery(packageId);
      else await apiClient.reprocessPackage(packageId);
      await Promise.all([mutate(), mutateGlobal('packages')]);
      toast(kind === 'approve' ? 'Delivery approved. Redacted documents are on their way.' : 'Reprocessing started.', 'mark');
    } catch (err) {
      setActionError(errorMessage(err, 'That action could not be completed.'));
    } finally {
      setBusy(null);
    }
  };

  const download = async (documentId: string, fileName?: string) => {
    try {
      const blob = await apiClient.downloadRedactedArtifact(documentId);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `redacted-${fileName || 'document.pdf'}`;
      a.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (err) {
      toast(errorMessage(err, 'Download failed.'), 'danger');
    }
  };

  const title = pkg ? caseTitle(pkg.businessIdentifiers) : null;
  const stage = pkg ? stageOf(pkg.status) : null;

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

      {error && <Notice tone="danger">This case could not be loaded.</Notice>}
      {!pkg && !error && <div className="cf-center"><Spinner label="Opening case" /></div>}

      {pkg && stage && (
        <div className="cf-drawer-body">
          <Timeline stage={stage} status={pkg.status} />

          <NextStep
            stage={stage}
            status={pkg.status}
            caps={caps}
            packageId={packageId}
            openFindings={openFindings.length}
            busy={busy}
            onApprove={() => run('approve')}
            onReprocess={() => run('reprocess')}
          />
          {actionError && <Notice tone="danger">{actionError}</Notice>}

          <section className="cf-drawer-section" aria-labelledby="cf-ids">
            <h3 id="cf-ids">Identity</h3>
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
          </section>

          <section className="cf-drawer-section" aria-labelledby="cf-docs">
            <h3 id="cf-docs">Documents <span className="cf-count">{pkg.documents.length}</span></h3>
            {pkg.documents.length === 0 ? (
              <p className="cf-muted">No documents yet.</p>
            ) : (
              <ul className="cf-docs">
                {pkg.documents.map((doc) => {
                  const findings = openFindings.filter((f) => f.documentId === doc.documentId).length;
                  const canDownload = stage === 'delivered' || stage === 'approval';
                  return (
                    <li key={doc.documentId} className="cf-doc">
                      <FileText size={16} aria-hidden="true" className="cf-doc-icon" />
                      <span className="cf-doc-name">
                        <span>{doc.fileName || 'Untitled document'}</span>
                        <span className="cf-doc-meta">
                          {formatBytes(doc.sizeBytes)}
                          {findings > 0 && <> · <mark className="cf-mark-text">{findings} to review</mark></>}
                        </span>
                      </span>
                      {canDownload && (
                        <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" onClick={() => download(doc.documentId, doc.fileName)}>
                          <Download size={14} aria-hidden="true" /> Redacted
                        </button>
                      )}
                    </li>
                  );
                })}
              </ul>
            )}
          </section>
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
            <span className="cf-timeline-label">{index === currentIndex && stopped ? humanize(status) : stageLabel(key)}</span>
          </li>
        );
      })}
    </ol>
  );
}

function NextStep({ stage, status, caps, packageId, openFindings, busy, onApprove, onReprocess }: {
  stage: StageKey; status: string; caps: string[]; packageId: string; openFindings: number;
  busy: 'approve' | 'reprocess' | null; onApprove: () => void; onReprocess: () => void;
}) {
  let tone: 'mark' | 'neutral' | 'danger' = 'neutral';
  let heading = '';
  let body = '';
  let action: JSX.Element | null = null;

  if (stage === 'intake') {
    heading = 'Waiting for documents';
    body = 'This case opens for processing once its documents are submitted.';
  } else if (stage === 'redacting') {
    heading = 'LexaVault is redacting';
    body = 'Sensitive data is being detected and removed. Anything uncertain will come to review.';
    action = <span className="cf-next-pulse" aria-hidden="true" />;
  } else if (stage === 'review') {
    const canReview = caps.includes(CAP.review);
    tone = canReview ? 'mark' : 'neutral';
    heading = openFindings > 0 ? `${openFindings} ${openFindings === 1 ? 'finding needs' : 'findings need'} a decision` : 'In review';
    body = canReview ? 'Confirm what to redact and what to keep. Processing resumes automatically.' : 'A reviewer is working through the uncertain findings.';
    if (canReview) {
      action = (
        <Link to={`/review?case=${packageId}`} className="cf-btn" data-variant="mark">
          Review findings <ArrowRight size={16} aria-hidden="true" />
        </Link>
      );
    }
  } else if (stage === 'approval') {
    const canApprove = caps.includes(CAP.approve);
    tone = canApprove ? 'mark' : 'neutral';
    heading = 'Redacted output is ready';
    body = canApprove ? 'Check the redacted documents, then release them for delivery.' : 'Waiting for a supervisor to approve delivery.';
    if (canApprove) {
      action = (
        <button type="button" className="cf-btn" data-variant="mark" onClick={onApprove} disabled={busy !== null}>
          {busy === 'approve' ? <Spinner label="Approving" /> : <Check size={16} aria-hidden="true" />} Approve delivery
        </button>
      );
    }
  } else if (stage === 'delivered') {
    heading = 'Delivered';
    body = 'Redacted documents reached their destination. Originals never leave the vault.';
  } else {
    tone = 'danger';
    heading = status === 'REJECTED' ? 'Rejected at intake' : 'Processing failed';
    body = status === 'REJECTED' ? 'The submission did not meet intake rules and was not processed.' : 'Something interrupted processing. Reprocessing starts a fresh cycle.';
    if (status === 'FAILED' && caps.includes(CAP.reprocess)) {
      action = (
        <button type="button" className="cf-btn" data-variant="solid" onClick={onReprocess} disabled={busy !== null}>
          {busy === 'reprocess' ? <Spinner label="Starting" /> : <RotateCcw size={16} aria-hidden="true" />} Reprocess
        </button>
      );
    }
  }

  return (
    <section className="cf-next" data-tone={tone} aria-label="Next step">
      <div className="cf-next-text">
        <span className="cf-eyebrow">Next step · <StageChip stage={stage} status={status} /></span>
        <p className="cf-next-heading">{heading}</p>
        <p className="cf-next-body">{body}</p>
      </div>
      {action && <div className="cf-next-action">{action}</div>}
    </section>
  );
}
