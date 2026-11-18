import { useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import useSWR, { useSWRConfig } from 'swr';
import { apiClient } from '../../api/client';
import type { SupportIncidentDetail, TechnicalExceptionDetail } from '../../types';
import { Spinner } from '../atoms';
import { LoadErrorCard, TechnicalDetails, checked, isRecord } from '../failure';
import { errorMessage } from '../data';
import { useToast } from '../toast';
import { CONFIRM_COPY } from '../caseActionRunner';
import { ConfirmDialog, when } from '../admin/shared';
import { MORE_ACTIONS, SUPPORT_CAPS, fromIncident, fromTechnical, problemActions, type ProblemView } from './supportRules';
import './support.css';

/**
 * UI task U012 (finding 30): one problem page in place of Incident Details and Technical Issue Details.
 * Reached from Support (/support/incidents/:incidentId) or from a stopped case (/technical-exceptions/:packageId).
 */

const longWhen = (iso: string) => new Date(iso).toLocaleString([], { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });

const isIncident = (v: unknown) => isRecord(v) && typeof v.incidentId === 'string' && Array.isArray(v.events);
const isTechnical = (v: unknown) => isRecord(v) && typeof v.packageId === 'string' && typeof v.explanation === 'string';

/** Support › a row. */
export function IncidentProblem({ caps }: { caps: string[] }) {
  const { incidentId = '' } = useParams();
  const [searchParams] = useSearchParams();
  const tenantId = caps.includes(SUPPORT_CAPS.platformOps) ? searchParams.get('tenantId') || undefined : undefined;
  const request = `GET /support/incidents/${incidentId}`;
  const { data, error, mutate } = useSWR<SupportIncidentDetail>(['support-incident', incidentId, tenantId ?? ''],
    () => checked(request, apiClient.getSupportIncident(incidentId, tenantId), isIncident), { revalidateOnFocus: false });
  if (error && !data) return <LoadErrorCard title="This problem couldn't be loaded" error={error} request={request} onRetry={() => void mutate()} />;
  if (!data) return <p className="cf-muted"><Spinner label="Loading the problem" /> Loading the problem…</p>;
  return <ProblemPage view={fromIncident(data, longWhen)} caps={caps} tenantId={tenantId} refresh={() => mutate()} />;
}

/** A stopped case's problem. With an incident it is the same page as from Support. */
export function CaseProblem({ caps }: { caps: string[] }) {
  const { packageId = '' } = useParams();
  const request = `GET /technical-exceptions/${packageId}`;
  const tech = useSWR<TechnicalExceptionDetail>(['technical-exception', packageId],
    () => checked(request, apiClient.getTechnicalException(packageId), isTechnical), { revalidateOnFocus: false, shouldRetryOnError: false });
  const incidentId = tech.data?.incidentId ?? undefined;
  const incidentRequest = `GET /support/incidents/${incidentId}`;
  const incident = useSWR<SupportIncidentDetail>(incidentId ? ['support-incident', incidentId, ''] : null,
    () => checked(incidentRequest, apiClient.getSupportIncident(incidentId as string), isIncident), { revalidateOnFocus: false });

  if (tech.error && !tech.data) return <LoadErrorCard title="This problem couldn't be loaded" error={tech.error} request={request} onRetry={() => void tech.mutate()} />;
  if (!tech.data || (incidentId && !incident.data && !incident.error)) return <p className="cf-muted"><Spinner label="Loading the problem" /> Loading the problem…</p>;
  if (incident.error && !incident.data) return <LoadErrorCard title="This problem couldn't be loaded" error={incident.error} request={incidentRequest} onRetry={() => void incident.mutate()} />;
  const view = incident.data ? fromIncident(incident.data, longWhen) : fromTechnical(tech.data, longWhen);
  return <ProblemPage view={view} caps={caps} refresh={() => Promise.all([tech.mutate(), incident.mutate()])} />;
}

type Pending = { action: string; holdDays?: number; label: string; consequence: string; danger: boolean } | null;

function ProblemPage({ view, caps, tenantId, refresh }: { view: ProblemView; caps: string[]; tenantId?: string; refresh: () => Promise<unknown> }) {
  const toast = useToast();
  const { mutate } = useSWRConfig();
  const [busy, setBusy] = useState(false);
  const [pending, setPending] = useState<Pending>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const actions = problemActions(view, caps, Boolean(tenantId));
  const listPath = tenantId ? `/support/incidents?tenantId=${encodeURIComponent(tenantId)}` : '/support/incidents';
  const retryCopy = CONFIRM_COPY['technical-retry']!;

  async function run(action: string, holdDays: number | undefined, done: string) {
    setBusy(true);
    setFailure(null);
    try {
      if (action === 'REPROCESS' && !view.incidentId) await apiClient.retryTechnicalException(view.packageId);
      else await apiClient.actOnSupportIncident(view.incidentId as string, { action, holdDays }, tenantId);
      await Promise.all([refresh(), mutate('packages'), mutate((key) => Array.isArray(key) && key[0] === 'support-incidents')]);
      setPending(null);
      toast(done, 'mark');
    } catch (e) {
      setFailure(errorMessage(e, 'That action could not be completed. Nothing was changed.'));
    } finally {
      setBusy(false);
    }
  }

  const nothingToDo = !actions.retry && !actions.keep;
  return (
    <div className="cf-sp cf-pb">
      <header className="cf-page-head">
        <div>
          <nav className="cf-eyebrow cf-sp-crumbs" aria-label="Breadcrumb">
            <Link to={listPath}>Support</Link><span aria-hidden="true">›</span>
            <span className="cf-mono">{view.caseValue ?? 'Case'}</span>
          </nav>
          <div className="cf-pb-title">
            <h1 className="cf-title">{view.title}</h1>
            <span className="cf-stage" data-stage={view.impact.stage}><span className="cf-stage-dot" aria-hidden="true" />{view.impact.label}</span>
            <span className="cf-muted cf-pb-status">Problem: {view.status}</span>
          </div>
        </div>
      </header>

      <div className="cf-pb-cards">
        <section className="cf-pb-card" aria-labelledby="cf-pb-what">
          <h2 id="cf-pb-what">What happened</h2>
          <p>{view.happened}</p>
        </section>
        <section className="cf-pb-card" aria-labelledby="cf-pb-means">
          <h2 id="cf-pb-means">What it means for the case</h2>
          <p>{view.meaning}</p>
        </section>
        <section className="cf-pb-card" aria-labelledby="cf-pb-do">
          <h2 id="cf-pb-do">What you can do</h2>
          <div className="cf-pb-actions">
            {actions.retry && (
              <button type="button" className="cf-btn" data-variant="solid" disabled={busy} onClick={() => setPending({ action: 'REPROCESS', label: retryCopy.confirm, consequence: retryCopy.body, danger: false })}>
                Retry processing
              </button>
            )}
            {actions.manualHint && (
              <p className="cf-pb-hint">A Document Processor or Supervisor can also finish it with “Continue manually” from the case.</p>
            )}
            {actions.keep && (
              <button type="button" className="cf-btn" data-size="sm" disabled={busy}
                onClick={() => void run('PRESERVE_FOR_INVESTIGATION', 7, 'The original is kept 7 more days.')}>
                {busy && !pending && <Spinner label="Working" />}Keep the original 7 more days
              </button>
            )}
            {actions.keep && (
              <details className="cf-disclosure cf-pb-more">
                <summary>More actions</summary>
                <div className="cf-pb-more-list">
                  {MORE_ACTIONS.map((a) => (
                    <button key={`${a.action}-${a.holdDays ?? ''}`} type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={busy}
                      onClick={() => setPending({ action: a.action, holdDays: a.holdDays, label: a.label, consequence: a.consequence, danger: a.closes })}>
                      {a.label}
                    </button>
                  ))}
                </div>
              </details>
            )}
            {nothingToDo && (
              <p className="cf-pb-hint">{view.closed
                ? 'Nothing: this problem is closed.'
                : tenantId
                  ? 'Problems in another organization can be read here, not acted on.'
                  : caps.includes(SUPPORT_CAPS.retry) || caps.includes(SUPPORT_CAPS.hold)
                    ? 'Nothing right now: the case is no longer stopped.'
                    : 'Retry and keeping the original are for IT Administrators. Send them the reference under Technical details.'}</p>
            )}
            {failure && !pending && <p className="cf-danger-text" role="alert">{failure}</p>}
          </div>
        </section>
      </div>

      <section className="cf-pb-card" aria-labelledby="cf-pb-timeline">
        <h2 id="cf-pb-timeline">Timeline</h2>
        {view.timeline.length === 0 ? <p className="cf-muted">No steps were recorded.</p> : (
          <ol className="cf-pb-steps">
            {view.earlierSteps > 0 && <li className="cf-muted">{view.earlierSteps} earlier step{view.earlierSteps === 1 ? '' : 's'} (all are under Technical details)</li>}
            {view.timeline.map((t, i) => (
              <li key={`${t.at}-${i}`}><time className="cf-mono" dateTime={t.at}>{when(t.at)}</time><span>{t.text}</span></li>
            ))}
          </ol>
        )}
      </section>

      <TechnicalDetails>
        <dl className="cf-tech-defs">
          {view.technical.map(([k, v], i) => <div key={`${k}-${i}`}><dt>{k}</dt><dd className="cf-mono">{v}</dd></div>)}
        </dl>
      </TechnicalDetails>
      <p className="cf-sp-count">Support never shows document text or sensitive values.</p>

      <ConfirmDialog open={pending !== null} title={pending ? `${pending.label}?` : ''}
        facts={[['Case', <span className="cf-mono" key="c">{view.caseValue ?? view.packageId}</span>], ['Problem', view.title]]}
        consequence={pending?.consequence ?? ''} confirmLabel={pending?.label ?? ''} danger={pending?.danger} busy={busy} error={failure}
        onCancel={() => { setPending(null); setFailure(null); }}
        onConfirm={() => pending && void run(pending.action, pending.holdDays, pending.action === 'REPROCESS' ? 'Retry started.' : 'Done.')} />
    </div>
  );
}
