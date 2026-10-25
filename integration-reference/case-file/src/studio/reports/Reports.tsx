import { useMemo, useState } from 'react';
import useSWR from 'swr';
import { Link } from 'react-router-dom';
import { apiClient } from '../../api/client';
import type { OperationalReports } from '../../types';
import { CAP, errorMessage, type StageKey } from '../data';
import { Notice, Spinner } from '../atoms';
import { useToast } from '../toast';
import './reports.css';

type Preset = 'TODAY' | '7D' | '30D' | 'THIS_MONTH' | 'THIS_QUARTER' | 'CUSTOM';

const PRESETS: { key: Preset; label: string }[] = [
  { key: 'TODAY', label: 'Today' },
  { key: '7D', label: '7 days' },
  { key: '30D', label: '30 days' },
  { key: 'THIS_MONTH', label: 'Month' },
  { key: 'THIS_QUARTER', label: 'Quarter' },
  { key: 'CUSTOM', label: 'Custom' },
];

const SOURCES = [
  { value: 'ALL', label: 'All sources' },
  { value: 'MANUAL', label: 'Manual upload' },
  { value: 'API', label: 'API' },
  { value: 'WATCHED_FOLDER', label: 'Watched folder' },
  { value: 'SCANNER', label: 'Scanner' },
];

const OUTCOMES = [
  { value: 'ALL', label: 'All outcomes' },
  { value: 'DELIVERED', label: 'Delivered' },
  { value: 'IN_PROGRESS', label: 'In progress' },
  { value: 'ATTENTION', label: 'Needs attention' },
];

const startOfDay = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate());
const addDays = (d: Date, n: number) => new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);
const toDateInput = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const fromDateInput = (value: string) => {
  const [y, m, d] = value.split('-').map(Number);
  return new Date(y, (m || 1) - 1, d || 1);
};

/** Mirrors ReportsPage: local-day boundaries, exclusive `to`, sent as ISO instants. */
function periodFor(preset: Preset, customFrom: string, customTo: string): { from: Date; to: Date; label: string } | null {
  const today = startOfDay(new Date());
  const tomorrow = addDays(today, 1);
  switch (preset) {
    case 'TODAY': return { from: today, to: tomorrow, label: 'Today' };
    case '7D': return { from: addDays(today, -6), to: tomorrow, label: 'Last 7 days' };
    case '30D': return { from: addDays(today, -29), to: tomorrow, label: 'Last 30 days' };
    case 'THIS_MONTH': return { from: new Date(today.getFullYear(), today.getMonth(), 1), to: tomorrow, label: 'This month' };
    case 'THIS_QUARTER': return { from: new Date(today.getFullYear(), Math.floor(today.getMonth() / 3) * 3, 1), to: tomorrow, label: 'This quarter' };
    case 'CUSTOM': {
      if (!customFrom || !customTo) return null;
      const from = fromDateInput(customFrom);
      const to = addDays(fromDateInput(customTo), 1);
      return to > from ? { from, to, label: `${customFrom} – ${customTo}` } : null;
    }
  }
}

const pct = (v: number) => `${Math.round(v * (v <= 1 ? 100 : 1))}%`;
const ratio = (v: number) => (v <= 1 ? v * 100 : v);
const hours = (h: number) => (h < 1 ? `${Math.round(h * 60)}m` : h < 48 ? `${h.toFixed(1)}h` : `${(h / 24).toFixed(1)}d`);
function delta(current: number, previous: number) {
  if (!previous) return null;
  const change = ((current - previous) / previous) * 100;
  return { text: `${change >= 0 ? '+' : ''}${Math.round(change)}%`, up: change >= 0 };
}

export default function Reports({ caps }: { caps: string[] }) {
  const toast = useToast();
  const canOperational = caps.includes(CAP.reportOperational);
  const canExport = caps.includes(CAP.reportExport);

  const [preset, setPreset] = useState<Preset>('30D');
  const [customFrom, setCustomFrom] = useState(() => toDateInput(addDays(new Date(), -13)));
  const [customTo, setCustomTo] = useState(() => toDateInput(new Date()));
  const [source, setSource] = useState('ALL');
  const [outcome, setOutcome] = useState('ALL');
  const [exporting, setExporting] = useState(false);

  const period = useMemo(() => periodFor(preset, customFrom, customTo), [preset, customFrom, customTo]);
  const params = period ? { from: period.from.toISOString(), to: period.to.toISOString(), source, outcome } : null;

  const { data, error, isLoading, isValidating, mutate } = useSWR<OperationalReports>(
    params ? ['reports', params.from, params.to, source, outcome] : null,
    () => apiClient.getOperationalReports(params!),
    { keepPreviousData: true, revalidateOnFocus: false },
  );

  async function exportCsv() {
    if (!params) return;
    setExporting(true);
    try {
      const blob = await apiClient.exportOperationalReports(params);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `lexavault-report-${toDateInput(period!.from)}-to-${toDateInput(addDays(period!.to, -1))}.csv`;
      a.click();
      URL.revokeObjectURL(url);
      toast('Report exported.');
    } catch (e) {
      toast(errorMessage(e, 'Export failed.'), 'danger');
    } finally {
      setExporting(false);
    }
  }

  return (
    <div className="cf-reports">
      <header className="cf-page-head">
        <div>
          <span className="cf-eyebrow">Reports</span>
          <h1 className="cf-title">How the desk is performing</h1>
          <p className="cf-lede">
            {period ? period.label : 'Choose a valid range'}
            {data && <> · generated {new Date(data.generatedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</>}
            {isValidating && data && <> · refreshing</>}
          </p>
        </div>
        {canExport && (
          <button type="button" className="cf-btn" onClick={exportCsv} disabled={!data || exporting}>
            {exporting ? <Spinner label="Exporting" /> : null}
            Export CSV
          </button>
        )}
      </header>

      <div className="cf-rp-filters" role="group" aria-label="Report filters">
        <div className="cf-segment" role="radiogroup" aria-label="Period">
          {PRESETS.map((p) => (
            <button key={p.key} type="button" role="radio" aria-checked={preset === p.key} className={preset === p.key ? 'is-active' : ''} onClick={() => setPreset(p.key)}>
              {p.label}
            </button>
          ))}
        </div>
        {preset === 'CUSTOM' && (
          <div className="cf-rp-range">
            <label><span className="cf-sr">From</span><input type="date" value={customFrom} max={customTo} onChange={(e) => setCustomFrom(e.target.value)} /></label>
            <span aria-hidden="true">–</span>
            <label><span className="cf-sr">To</span><input type="date" value={customTo} min={customFrom} onChange={(e) => setCustomTo(e.target.value)} /></label>
          </div>
        )}
        <label className="cf-rp-select"><span className="cf-sr">Source</span>
          <select value={source} onChange={(e) => setSource(e.target.value)}>{SOURCES.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}</select>
        </label>
        <label className="cf-rp-select"><span className="cf-sr">Outcome</span>
          <select value={outcome} onChange={(e) => setOutcome(e.target.value)}>{OUTCOMES.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}</select>
        </label>
      </div>

      {!period && <Notice>The end date must be on or after the start date.</Notice>}
      {error && !data && (
        <Notice tone="danger">
          {errorMessage(error, 'Reports could not be loaded.')}{' '}
          <button type="button" className="cf-btn" data-size="sm" onClick={() => mutate()}>Retry</button>
        </Notice>
      )}
      {isLoading && !data && <div className="cf-center"><Spinner label="Loading reports" /></div>}

      {data && (
        <div className="cf-rp-body" aria-busy={isValidating}>
          <Executive r={data} />
          <Trend r={data} />
          <ServiceLevel r={data} />
          {canOperational ? (
            <>
              <Pipeline r={data} />
              <div className="cf-rp-pair">
                <Backlog r={data} />
                <Retention r={data} />
              </div>
              <Timing r={data} />
              <Intake r={data} />
              {data.dimensions.map((d) => <Dimension key={d.key} dim={d} />)}
            </>
          ) : (
            <p className="cf-rp-scope">Pipeline, backlog, retention and dimension breakdowns need operational report access.</p>
          )}
        </div>
      )}
    </div>
  );
}

function Section({ title, note, children }: { title: string; note?: string; children: React.ReactNode }) {
  return (
    <section className="cf-rp-section" aria-label={title}>
      <header className="cf-rp-section-head">
        <h2>{title}</h2>
        {note && <p>{note}</p>}
      </header>
      {children}
    </section>
  );
}

function Stat({ label, value, sub, change }: { label: string; value: string; sub?: string; change?: ReturnType<typeof delta> }) {
  return (
    <div className="cf-rp-stat">
      <dt>{label}</dt>
      <dd>
        <span className="cf-rp-stat-value">{value}</span>
        {change && <span className="cf-rp-delta" data-up={change.up || undefined}>{change.text}<span className="cf-sr"> versus previous period</span></span>}
      </dd>
      {sub && <dd className="cf-rp-stat-sub">{sub}</dd>}
    </div>
  );
}

function Executive({ r }: { r: OperationalReports }) {
  const e = r.executive;
  return (
    <dl className="cf-rp-stats">
      <Stat label="Cases received" value={e.batches.toLocaleString()} sub={`${e.documents.toLocaleString()} documents · ${e.pages.toLocaleString()} pages`} change={delta(e.batches, e.previousBatches)} />
      <Stat label="Delivered" value={e.delivered.toLocaleString()} sub={`${pct(e.deliveryRate)} of cases`} change={delta(e.delivered, e.previousDelivered)} />
      <Stat label="Median to delivery" value={hours(e.medianDeliveryHours)} sub={`p95 ${hours(e.p95DeliveryHours)}`} />
      <Stat label="Straight-through" value={pct(e.straightThroughRate)} sub={`${pct(e.reviewRate)} needed review`} />
      <Stat label="Open backlog" value={e.currentBacklog.toLocaleString()} sub={e.currentAttention ? `${e.currentAttention} need attention · oldest ${hours(e.oldestBacklogHours)}` : `oldest ${hours(e.oldestBacklogHours)}`} />
    </dl>
  );
}

function Trend({ r }: { r: OperationalReports }) {
  const points = r.executive.trend;
  const max = Math.max(1, ...points.map((p) => Math.max(p.received, p.delivered)));
  if (!points.length) return null;
  return (
    <Section title="Received and delivered" note="Per period bucket">
      <div className="cf-rp-legend" aria-hidden="true"><span data-k="received">Received</span><span data-k="delivered">Delivered</span></div>
      <div className="cf-rp-trend" role="img" aria-label={`Trend over ${points.length} buckets. Total received ${points.reduce((s, p) => s + p.received, 0)}, delivered ${points.reduce((s, p) => s + p.delivered, 0)}.`}>
        {points.map((p) => (
          <div key={p.bucket} className="cf-rp-trend-col" title={`${p.bucket}: ${p.received} received, ${p.delivered} delivered`}>
            <div className="cf-rp-trend-bars">
              <span data-k="received" style={{ height: `${(p.received / max) * 100}%` }} />
              <span data-k="delivered" style={{ height: `${(p.delivered / max) * 100}%` }} />
            </div>
          </div>
        ))}
      </div>
      <div className="cf-rp-trend-axis" aria-hidden="true">
        <span>{points[0].bucket}</span>
        <span>{points[points.length - 1].bucket}</span>
      </div>
    </Section>
  );
}

function TargetBar({ label, actual, target, detail }: { label: string; actual: number; target: number; detail: string }) {
  const a = Math.min(100, ratio(actual));
  const t = Math.min(100, ratio(target));
  const met = a >= t;
  return (
    <div className="cf-rp-target">
      <div className="cf-rp-target-head">
        <span>{label}</span>
        <span className="cf-rp-target-value" data-met={met || undefined}>{Math.round(a)}% <span className="cf-muted">/ target {Math.round(t)}%</span></span>
      </div>
      <div className="cf-rp-target-track" role="meter" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(a)} aria-label={`${label}: ${Math.round(a)}% against a ${Math.round(t)}% target`}>
        <span className="cf-rp-target-fill" data-met={met || undefined} style={{ width: `${a}%` }} />
        <span className="cf-rp-target-line" style={{ left: `${t}%` }} />
      </div>
      <p className="cf-rp-target-detail">{detail}</p>
    </div>
  );
}

function ServiceLevel({ r }: { r: OperationalReports }) {
  const s = r.serviceLevel;
  return (
    <Section title="Service levels" note="Targets are set in Admin › Reporting">
      <div className="cf-rp-targets">
        <TargetBar label={`Delivered within ${hours(s.deliveryTargetMinutes / 60)}`} actual={s.deliveryWithinTargetRate} target={s.deliveryTargetPercent} detail={`${s.deliveryBreaches} of ${s.deliveryEligible} eligible cases missed the target`} />
        <TargetBar label="Straight-through processing" actual={s.straightThroughActualPercent} target={s.straightThroughTargetPercent} detail="Cases delivered without a human review step" />
        <TargetBar label={`Review picked up within ${hours(s.reviewWaitTargetMinutes / 60)}`} actual={s.reviewWithinTargetRate} target={100} detail={`${s.reviewBreaches} of ${s.reviewEligible} reviews waited longer`} />
      </div>
    </Section>
  );
}

function Pipeline({ r }: { r: OperationalReports }) {
  const p = r.processing;
  const rows: { label: string; value: number; stage: StageKey; tone?: 'danger' }[] = [
    { label: 'Redacting', value: p.processing, stage: 'redacting' },
    { label: 'Needs review', value: p.needsReview, stage: 'review' },
    { label: 'Awaiting approval', value: p.needsApproval, stage: 'approval' },
    { label: 'Delivered', value: p.completed, stage: 'delivered' },
    { label: 'Technical attention', value: p.technicalAttention, stage: 'stopped', tone: 'danger' },
    { label: 'Rejected', value: p.rejected, stage: 'stopped', tone: 'danger' },
  ];
  return (
    <Section title="Pipeline" note="Select a stage to open the matching cases on the Desk">
      <ul className="cf-rp-pipeline">
        {rows.map((row) => (
          <li key={row.label}>
            <Link to={`/?stage=${row.stage}`} className="cf-rp-pipe" data-tone={row.value > 0 ? row.tone : undefined}>
              <span className="cf-rp-pipe-value">{row.value}</span>
              <span className="cf-rp-pipe-label">{row.label}</span>
            </Link>
          </li>
        ))}
      </ul>
    </Section>
  );
}

function Bars({ rows }: { rows: { label: string; value: number; tone?: 'danger' }[] }) {
  const max = Math.max(1, ...rows.map((r) => r.value));
  return (
    <dl className="cf-rp-bars">
      {rows.map((row) => (
        <div key={row.label}>
          <dt>{row.label}</dt>
          <dd>
            <span className="cf-rp-bar" data-tone={row.tone && row.value > 0 ? row.tone : undefined} style={{ width: `${(row.value / max) * 100}%` }} />
            <span className="cf-rp-bar-value">{row.value}</span>
          </dd>
        </div>
      ))}
    </dl>
  );
}

function Backlog({ r }: { r: OperationalReports }) {
  const b = r.backlog;
  return (
    <Section title="Backlog age" note={`Oldest open case ${hours(b.oldestHours)}`}>
      <Bars rows={[
        { label: 'Under 1 hour', value: b.underOneHour },
        { label: '1–4 hours', value: b.oneToFourHours },
        { label: '4–24 hours', value: b.fourToTwentyFourHours },
        { label: '1–3 days', value: b.oneToThreeDays, tone: 'danger' },
        { label: 'Over 3 days', value: b.overThreeDays, tone: 'danger' },
      ]} />
    </Section>
  );
}

function Retention({ r }: { r: OperationalReports }) {
  const t = r.retention;
  const overdue = t.originalsOverdue + t.redactedCopiesOverdue;
  return (
    <Section title="Retention" note={overdue ? `${overdue} artifacts past their purge window` : 'No artifacts are overdue'}>
      <Bars rows={[
        { label: 'Originals due in 24h', value: t.originalsDueWithin24Hours },
        { label: 'Redacted copies due in 24h', value: t.redactedCopiesDueWithin24Hours },
        { label: 'Technical holds due in 24h', value: t.technicalHoldsDueWithin24Hours },
        { label: 'Originals overdue', value: t.originalsOverdue, tone: 'danger' },
        { label: 'Redacted copies overdue', value: t.redactedCopiesOverdue, tone: 'danger' },
      ]} />
      <p className="cf-rp-foot">{t.purgedPackages} cases purged in this period</p>
    </Section>
  );
}

function Timing({ r }: { r: OperationalReports }) {
  const t = r.timing;
  const u = r.usage;
  return (
    <Section title="Timing and volume">
      <dl className="cf-rp-grid">
        <Stat label="Turnaround" value={hours(t.medianTurnaroundHours)} sub={`p95 ${hours(t.p95TurnaroundHours)} · ${t.turnaroundSamples} cases`} />
        <Stat label="Active processing" value={hours(t.medianActiveProcessingHours)} sub={`p95 ${hours(t.p95ActiveProcessingHours)}`} />
        <Stat label="Wait for reviewer" value={hours(t.medianReviewWaitHours)} sub={`${t.reviewWaitSamples} reviews`} />
        <Stat label="Review handling" value={hours(t.medianReviewHandlingHours)} sub={`${t.reviewHandlingSamples} reviews`} />
        <Stat label="Documents per case" value={u.averageDocumentsPerPackage.toFixed(1)} sub={`${u.averagePagesPerPackage.toFixed(1)} pages`} />
        <Stat label="Reprocessed" value={u.reprocessedPackages.toLocaleString()} sub={`of ${u.packages.toLocaleString()} cases`} />
      </dl>
    </Section>
  );
}

function Intake({ r }: { r: OperationalReports }) {
  const i = r.intake;
  return (
    <Section title="Intake channels" note={`${i.duplicateObservations} duplicates and ${i.rejectedObservations} rejected submissions filtered out`}>
      <Bars rows={[
        { label: 'Manual upload', value: i.manual },
        { label: 'API', value: i.api },
        { label: 'Watched folder', value: i.watchedFolder },
        { label: 'Scanner', value: i.scanner },
        { label: 'Waiting for trigger file', value: i.waitingForTrigger },
      ]} />
    </Section>
  );
}

function Dimension({ dim }: { dim: OperationalReports['dimensions'][number] }) {
  if (!dim.values.length) return null;
  return (
    <Section title={`By ${dim.label.toLowerCase()}`}>
      <div className="cf-rp-table-wrap">
        <table className="cf-rp-table">
          <thead>
            <tr><th scope="col">{dim.label}</th><th scope="col">Cases</th><th scope="col">Delivered</th><th scope="col">Rate</th><th scope="col">Median</th></tr>
          </thead>
          <tbody>
            {dim.values.map((v) => (
              <tr key={v.value}>
                <th scope="row">{v.value}</th>
                <td>{v.packages}</td>
                <td>{v.delivered}</td>
                <td>{pct(v.deliveryRate)}</td>
                <td>{hours(v.medianTurnaroundHours)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Section>
  );
}
