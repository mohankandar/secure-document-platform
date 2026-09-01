import { useEffect, useState } from 'react';
import { apiClient } from '../api/client';
import type { ApiError, OperationalReports } from '../types';
import Breadcrumbs from '../components/Breadcrumbs';
import './ReportsPage.css';

type RangeKey = '7' | '30' | '90';
const formatNumber = new Intl.NumberFormat();
const pct = (value: number) => `${value.toFixed(1)}%`;
const hours = (value: number) => value < 24 ? `${value.toFixed(1)}h` : `${(value / 24).toFixed(1)}d`;
const delta = (current: number, previous: number) => previous === 0 ? null : ((current - previous) / previous) * 100;
const reportParams = (range: RangeKey, source: string, outcome: string) => {
  const to = new Date();
  return { from: new Date(to.getTime() - Number(range) * 86_400_000).toISOString(), to: to.toISOString(), source, outcome };
};

function Delta({ current, previous }: { current: number; previous: number }) {
  const value = delta(current, previous);
  if (value === null) return <small>No prior-period baseline</small>;
  return <small className={value >= 0 ? 'report-up' : 'report-down'}>{value >= 0 ? '↑' : '↓'} {Math.abs(value).toFixed(1)}% vs prior period</small>;
}

function Kpi({ label, value, note, current, previous, tone }: { label: string; value: string; note: string; current?: number; previous?: number; tone?: string }) {
  return <article className={`report-kpi ${tone || ''}`}><span>{label}</span><strong>{value}</strong>{current !== undefined && previous !== undefined ? <Delta current={current} previous={previous} /> : <small>{note}</small>}<p>{note}</p></article>;
}

function Trend({ points }: { points: OperationalReports['executive']['trend'] }) {
  const max = Math.max(1, ...points.flatMap((p) => [p.received, p.delivered]));
  const sampled = points.length > 31 ? points.filter((_, i) => i % Math.ceil(points.length / 31) === 0) : points;
  return <div className="report-trend" aria-label="Received and delivered volume trend">
    <div className="report-legend"><span><i className="received" />Received</span><span><i className="delivered" />Delivered</span></div>
    <div className="report-bars">{sampled.map((p) => <div className="report-bar-group" key={p.bucket} title={`${new Date(p.bucket).toLocaleDateString()}: ${p.received} received, ${p.delivered} delivered`}><i className="received" style={{ height: p.received === 0 ? 0 : `${Math.max(3, p.received / max * 100)}%` }} /><i className="delivered" style={{ height: p.delivered === 0 ? 0 : `${Math.max(3, p.delivered / max * 100)}%` }} /></div>)}</div>
  </div>;
}

export default function ReportsPage({ canViewOperations, canExport }: { canViewOperations: boolean; canExport: boolean }) {
  const [report, setReport] = useState<OperationalReports | null>(null);
  const [range, setRange] = useState<RangeKey>('30');
  const [source, setSource] = useState('ALL');
  const [outcome, setOutcome] = useState('ALL');
  const [view, setView] = useState<'executive' | 'operations'>('executive');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const load = async () => { setLoading(true); setError(null); try { setReport(await apiClient.getOperationalReports(reportParams(range, source, outcome))); } catch (err) { setError((err as ApiError).message || 'Failed to load reports'); } finally { setLoading(false); } };
  useEffect(() => { void load(); }, [range, source, outcome]);
  const exportCsv = async () => { try { const blob = await apiClient.exportOperationalReports(reportParams(range, source, outcome)); const url = URL.createObjectURL(blob); const anchor = document.createElement('a'); anchor.href = url; anchor.download = 'secure-document-report.csv'; anchor.click(); URL.revokeObjectURL(url); } catch (err) { setError((err as ApiError).message || 'Failed to export report'); } };

  return <div className="reports-page-v2">
    <Breadcrumbs items={[{ label: 'Reports' }]} />
    <header className="report-header"><div><p className="eyebrow">Operational intelligence</p><h1>Reports</h1><p>Decision-ready visibility into volume, delivery, automation, backlog, and operational risk.</p></div><div className="report-actions"><button className="toolbar-button" onClick={load} disabled={loading}>{loading ? 'Refreshing…' : 'Refresh'}</button>{canExport && <button className="primary-button" onClick={exportCsv}>Export CSV</button>}</div></header>
    <section className="report-toolbar" aria-label="Report controls"><div className="report-tabs"><button className={view === 'executive' ? 'active' : ''} onClick={() => setView('executive')}>Executive overview</button>{canViewOperations && <button className={view === 'operations' ? 'active' : ''} onClick={() => setView('operations')}>Operational details</button>}</div><div className="report-filters"><label>Period<select value={range} onChange={(e) => setRange(e.target.value as RangeKey)}><option value="7">Last 7 days</option><option value="30">Last 30 days</option><option value="90">Last 90 days</option></select></label><label>Intake source<select value={source} onChange={(e) => setSource(e.target.value)}><option value="ALL">All sources</option><option value="MANUAL_UPLOAD">Manual upload</option><option value="API_SUBMISSION">API</option><option value="FOLDER_INTAKE">Watched folder</option></select></label><label>Current outcome<select value={outcome} onChange={(e) => setOutcome(e.target.value)}><option value="ALL">All outcomes</option><option value="PROCESSING">Processing</option><option value="NEEDS_REVIEW">Needs review</option><option value="NEEDS_APPROVAL">Needs approval</option><option value="COMPLETED">Completed</option><option value="TECHNICAL_ATTENTION">Technical attention</option><option value="REJECTED">Rejected</option></select></label><span>Timezone: {Intl.DateTimeFormat().resolvedOptions().timeZone}</span></div></section>
    {error && <div className="error-msg">{error}</div>}{!report && loading && <div className="card">Loading report…</div>}
    {report && view === 'executive' && <>
      <section className="report-context"><span>{new Date(report.period.from).toLocaleDateString()} – {new Date(report.period.to).toLocaleDateString()}</span><span>Compared with the immediately preceding equal period</span><span>Generated {new Date(report.generatedAt).toLocaleString()}</span></section>
      <div className="report-kpis"><Kpi label="Intake batches" value={formatNumber.format(report.executive.batches)} note={`${formatNumber.format(report.executive.documents)} documents · ${formatNumber.format(report.executive.pages)} pages`} current={report.executive.batches} previous={report.executive.previousBatches} /><Kpi label="Completed packages" value={formatNumber.format(report.executive.completed)} note={`${pct(report.executive.completionRate)} of period intake; terminal workflow status`} current={report.executive.completed} previous={report.executive.previousCompleted} /><Kpi label="Delivered packages" value={formatNumber.format(report.executive.delivered)} note={`${pct(report.executive.deliveryRate)} throughput vs period intake; unique packages with export record`} current={report.executive.delivered} previous={report.executive.previousDelivered} /><Kpi label="Needs attention now" value={formatNumber.format(report.executive.currentAttention)} note={`${formatNumber.format(report.executive.currentBacklog)} total open · oldest ${hours(report.executive.oldestBacklogHours)}`} tone={report.executive.currentAttention > 0 ? 'warning' : ''} /></div>
      <div className="report-layout"><section className="report-panel report-volume"><div className="report-panel-head"><div><p className="eyebrow">Flow</p><h2>Received vs delivered</h2></div><span>Daily volume</span></div><Trend points={report.executive.trend} /></section><section className="report-panel"><div className="report-panel-head"><div><p className="eyebrow">Service level</p><h2>Time to first delivery</h2></div></div><div className="report-stat-pair"><div><span>Median</span><strong>{hours(report.executive.medianDeliveryHours)}</strong></div><div><span>95th percentile</span><strong>{hours(report.executive.p95DeliveryHours)}</strong></div></div><p className="report-definition">Measured from package creation to its first successful export. Zero indicates no delivered packages in this period.</p></section><section className="report-panel"><div className="report-panel-head"><div><p className="eyebrow">Automation value</p><h2>Touchless delivery</h2></div></div><div className="report-stat-pair"><div><span>Straight-through</span><strong>{pct(report.executive.straightThroughRate)}</strong></div><div><span>With review</span><strong>{pct(report.executive.reviewRate)}</strong></div></div><p className="report-definition">Share of delivered packages with or without a recorded review workflow event.</p></section><section className="report-panel"><div className="report-panel-head"><div><p className="eyebrow">Current risk</p><h2>Where leadership should focus</h2></div></div><div className="report-actions-list"><div><strong>{report.processing.technicalAttention}</strong><span>Technical attention</span></div><div><strong>{report.processing.needsReview}</strong><span>Needs manual review</span></div><div><strong>{report.processing.needsApproval}</strong><span>Needs approval</span></div></div><p className="report-definition">Authorized workflow owners can act on these items from Dashboard, Manual Review, or Technical Administration.</p></section></div>
    </>}
    {report && view === 'operations' && canViewOperations && <div className="report-layout operations"><section className="report-panel"><div className="report-panel-head"><div><p className="eyebrow">Current pipeline</p><h2>Work by stage</h2></div></div><div className="report-metric-list"><span>Processing <strong>{report.processing.processing}</strong></span><span>Needs review <strong>{report.processing.needsReview}</strong></span><span>Needs approval <strong>{report.processing.needsApproval}</strong></span><span>Technical attention <strong>{report.processing.technicalAttention}</strong></span><span>Rejected <strong>{report.processing.rejected}</strong></span></div></section><section className="report-panel"><div className="report-panel-head"><div><p className="eyebrow">Intake controls</p><h2>Observed-folder health</h2></div></div><div className="report-metric-list"><span>Waiting for trigger <strong>{report.intake.waitingForTrigger}</strong></span><span>Duplicate observations <strong>{report.intake.duplicateObservations}</strong></span><span>Rejected observations <strong>{report.intake.rejectedObservations}</strong></span></div></section><section className="report-panel"><div className="report-panel-head"><div><p className="eyebrow">Delivery</p><h2>Export activity</h2></div></div><div className="report-metric-list"><span>Packages ever exported <strong>{report.export.exportedPackages}</strong></span><span>Successful export cycles <strong>{report.export.successfulExportCycles}</strong></span><span>Awaiting final approval <strong>{report.export.pendingFinalApproval}</strong></span></div><p className="report-definition">“Completed” is a workflow status. “Delivered” requires a structured package export record; the measures are intentionally separate.</p></section><section className="report-panel"><div className="report-panel-head"><div><p className="eyebrow">Retention risk</p><h2>Overdue and due soon</h2></div></div><div className="report-metric-list"><span>Originals overdue <strong className={report.retention.originalsOverdue ? 'danger' : ''}>{report.retention.originalsOverdue}</strong></span><span>Redacted copies overdue <strong className={report.retention.redactedCopiesOverdue ? 'danger' : ''}>{report.retention.redactedCopiesOverdue}</strong></span><span>Originals due within 24h <strong>{report.retention.originalsDueWithin24Hours}</strong></span><span>Redacted copies due within 24h <strong>{report.retention.redactedCopiesDueWithin24Hours}</strong></span></div></section></div>}
  </div>;
}
