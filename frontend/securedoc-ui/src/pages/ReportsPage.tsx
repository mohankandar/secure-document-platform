import { useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { apiClient } from '../api/client';
import type { ApiError, DashboardStage, OperationalReports } from '../types';
import PageScaffold from '../components/ui/PageScaffold';
import HorizontalTabs from '../components/ui/HorizontalTabs';
import Icon, { type IconName } from '../components/ui/Icon';
import Skeleton from '../components/ui/Skeleton';
import EmptyState from '../components/ui/EmptyState';
import './ReportsPage.css';

type ReportView = 'executive' | 'business' | 'usage' | 'operations';
type PeriodPreset = 'TODAY' | 'YESTERDAY' | '7D' | '30D' | 'THIS_MONTH' | 'LAST_MONTH' | 'THIS_QUARTER' | 'LAST_QUARTER' | 'THIS_YEAR' | 'CUSTOM';
type CustomMode = 'DATES' | 'MONTH' | 'QUARTER' | 'YEAR';

type PeriodSelection = {
  preset: PeriodPreset;
  label: string;
  from: Date;
  to: Date;
};

const formatNumber = new Intl.NumberFormat();
const safeNumber = (value: number | null | undefined, fallback = 0) => Number.isFinite(value) ? Number(value) : fallback;
const formatSafeNumber = (value: number | null | undefined) => formatNumber.format(safeNumber(value));
const pct = (value: number | null | undefined) => `${safeNumber(value).toFixed(1)}%`;
const targetDelta = (actual: number | null | undefined, target: number | null | undefined) => safeNumber(actual) - safeNumber(target);
const targetStatus = (actual: number | null | undefined, target: number | null | undefined, eligible = true) => {
  if (!eligible) return { label: 'No data', cls: 'neutral' };
  const difference = targetDelta(actual, target);
  if (difference >= 0) return { label: `Target met · +${difference.toFixed(1)} pts`, cls: 'good' };
  return { label: `Below target · ${Math.abs(difference).toFixed(1)} pts`, cls: 'bad' };
};
const hours = (value: number) => {
  if (!Number.isFinite(value) || value <= 0) return '—';
  if (value < 1) return `${Math.max(1, Math.round(value * 60))}m`;
  if (value < 24) return `${value.toFixed(value < 10 ? 1 : 0)}h`;
  return `${(value / 24).toFixed(value < 72 ? 1 : 0)}d`;
};
const sampledHours = (value: number, samples: number) => {
  if (!samples) return 'No data';
  if (!Number.isFinite(value) || value <= 0) return '<1m';
  return hours(value);
};
const delta = (current: number, previous: number) => previous === 0 ? null : ((current - previous) / previous) * 100;
const startOfDay = (date: Date) => new Date(date.getFullYear(), date.getMonth(), date.getDate());
const endExclusiveTomorrow = (date: Date) => new Date(date.getFullYear(), date.getMonth(), date.getDate() + 1);
const startOfMonth = (date: Date) => new Date(date.getFullYear(), date.getMonth(), 1);
const startOfYear = (date: Date) => new Date(date.getFullYear(), 0, 1);
const startOfQuarter = (date: Date) => new Date(date.getFullYear(), Math.floor(date.getMonth() / 3) * 3, 1);
const addDays = (date: Date, days: number) => new Date(date.getTime() + days * 86_400_000);
const isoDate = (date: Date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;

function presetPeriod(preset: Exclude<PeriodPreset, 'CUSTOM'>, now = new Date()): PeriodSelection {
  const today = startOfDay(now);
  switch (preset) {
    case 'TODAY': return { preset, label: 'Today', from: today, to: endExclusiveTomorrow(today) };
    case 'YESTERDAY': return { preset, label: 'Yesterday', from: addDays(today, -1), to: today };
    case '7D': return { preset, label: 'Last 7 days', from: addDays(today, -6), to: endExclusiveTomorrow(today) };
    case '30D': return { preset, label: 'Last 30 days', from: addDays(today, -29), to: endExclusiveTomorrow(today) };
    case 'THIS_MONTH': return { preset, label: 'This month', from: startOfMonth(today), to: endExclusiveTomorrow(today) };
    case 'LAST_MONTH': {
      const from = new Date(today.getFullYear(), today.getMonth() - 1, 1);
      return { preset, label: 'Last month', from, to: startOfMonth(today) };
    }
    case 'THIS_QUARTER': return { preset, label: 'This quarter', from: startOfQuarter(today), to: endExclusiveTomorrow(today) };
    case 'LAST_QUARTER': {
      const thisQuarter = startOfQuarter(today);
      const from = new Date(thisQuarter.getFullYear(), thisQuarter.getMonth() - 3, 1);
      return { preset, label: 'Last quarter', from, to: thisQuarter };
    }
    case 'THIS_YEAR': return { preset, label: 'This year', from: startOfYear(today), to: endExclusiveTomorrow(today) };
  }
}

const sourceOptions = [
  ['ALL', 'All sources'],
  ['MANUAL_UPLOAD', 'Manual upload'],
  ['API_SUBMISSION', 'API'],
  ['FOLDER_INTAKE', 'Watched folder'],
  ['SCANNER_INTAKE', 'Scanner'],
] as const;

const outcomeOptions = [
  ['ALL', 'All outcomes'],
  ['PROCESSING', 'Processing'],
  ['NEEDS_REVIEW', 'Needs review'],
  ['NEEDS_APPROVAL', 'Needs approval'],
  ['COMPLETED', 'Completed'],
  ['TECHNICAL_ATTENTION', 'Technical attention'],
  ['REJECTED', 'Rejected'],
] as const;

function reportParams(period: PeriodSelection, source: string, outcome: string) {
  return { from: period.from.toISOString(), to: period.to.toISOString(), source, outcome };
}

function Delta({ current, previous, inverse = false }: { current: number; previous: number; inverse?: boolean }) {
  const value = delta(current, previous);
  if (value === null) return <span className="report-delta neutral">First comparable period</span>;
  const positive = inverse ? value <= 0 : value >= 0;
  return <span className={`report-delta ${positive ? 'good' : 'bad'}`}><Icon name={value >= 0 ? 'arrowUp' : 'arrowDown'} className="report-delta-icon" />{Math.abs(value).toFixed(1)}% vs prior</span>;
}

function SectionHead({ id, title, description }: { id: string; title: string; description: string }) {
  return <header className="rp-section-head"><h2 id={id}>{title}</h2><p>{description}</p></header>;
}

function Kpi({ label, value, detail, current, previous, tone = 'blue', onClick }:
  { label: string; value: string; detail: string; current?: number; previous?: number; tone?: string; onClick?: () => void }) {
  const content = <>
    <span className="report-kpi-label">{label}</span>
    <strong>{value}</strong>
    {current !== undefined && previous !== undefined && <Delta current={current} previous={previous} />}
    <p>{detail}</p>
  </>;
  return onClick
    ? <button type="button" className={`report-kpi tone-${tone} actionable`} onClick={onClick}>{content}<span className="report-open-hint">Open →</span></button>
    : <article className={`report-kpi tone-${tone}`}>{content}</article>;
}

function Trend({ points }: { points: OperationalReports['executive']['trend'] }) {
  const max = Math.max(1, ...points.flatMap((point) => [point.received, point.delivered]));
  const sampled = points.length > 42 ? points.filter((_, i) => i % Math.ceil(points.length / 42) === 0) : points;
  return <div className="report-trend" aria-label="Received and delivered package trend">
    <div className="report-legend"><span><i className="received" />Received</span><span><i className="delivered" />Delivered</span></div>
    <div className="report-bars">{sampled.map((point) => <div className="report-bar-group" key={point.bucket} title={`${new Date(point.bucket).toLocaleDateString()}: ${point.received} received, ${point.delivered} delivered`}>
      <i className="received" style={{ height: point.received === 0 ? 0 : `${Math.max(3, point.received / max * 100)}%` }} />
      <i className="delivered" style={{ height: point.delivered === 0 ? 0 : `${Math.max(3, point.delivered / max * 100)}%` }} />
    </div>)}</div>
  </div>;
}

function SourceMix({ intake }: { intake: OperationalReports['intake'] }) {
  const items = [
    { label: 'Manual upload', value: intake.manual, cls: 'manual' },
    { label: 'API', value: intake.api, cls: 'api' },
    { label: 'Watched folder', value: intake.watchedFolder, cls: 'folder' },
    { label: 'Scanner', value: intake.scanner, cls: 'scanner' },
  ];
  const total = Math.max(1, items.reduce((sum, item) => sum + item.value, 0));
  return <div className="source-mix">
    <div className="source-mix-bar" aria-label="Package intake source distribution">{items.map((item) => item.value > 0 && <span key={item.label} className={item.cls} style={{ width: `${item.value / total * 100}%` }} title={`${item.label}: ${item.value}`} />)}</div>
    <div className="source-mix-legend">{items.map((item) => <div key={item.label}><span><i className={item.cls} />{item.label}</span><strong>{formatNumber.format(item.value)}</strong><small>{pct(item.value / total * 100)}</small></div>)}</div>
  </div>;
}

function BacklogAging({ backlog }: { backlog: OperationalReports['backlog'] }) {
  const items = [
    ['< 1 hour', backlog.underOneHour],
    ['1–4 hours', backlog.oneToFourHours],
    ['4–24 hours', backlog.fourToTwentyFourHours],
    ['1–3 days', backlog.oneToThreeDays],
    ['> 3 days', backlog.overThreeDays],
  ] as const;
  const max = Math.max(1, ...items.map(([, value]) => value));
  return <div className="backlog-aging">{items.map(([label, value]) => <div key={label} className="backlog-row">
    <span>{label}</span><div><i style={{ width: `${value / max * 100}%` }} /></div><strong>{value}</strong>
  </div>)}</div>;
}

function IntakeProgress({ report }: { report: OperationalReports }) {
  const received = safeNumber(report.executive.batches);
  const delivered = safeNumber(report.executive.periodIntakeDelivered);
  const deliveryRate = safeNumber(report.executive.periodIntakeDeliveryRate);
  const remaining = Math.max(0, received - delivered);
  const deliveredWidth = Math.min(100, Math.max(0, deliveryRate));
  return <div className="rp-funnel">
    <div className="rp-funnel-track" role="img" aria-label={`${pct(deliveryRate)} of selected-period intake delivered by period end`}>
      <i className="delivered" style={{ width: `${deliveredWidth}%` }} />
      <i className="remaining" style={{ width: `${100 - deliveredWidth}%` }} />
    </div>
    <dl className="rp-funnel-stats">
      <div><dt>Received</dt><dd>{formatSafeNumber(received)}</dd><small>{formatSafeNumber(report.executive.documents)} documents · {formatSafeNumber(report.executive.pages)} pages</small></div>
      <div className="delivered"><dt><i aria-hidden="true" />Delivered by period end</dt><dd>{formatSafeNumber(delivered)}</dd><small>{pct(deliveryRate)} of selected-period intake</small></div>
      <div className="remaining"><dt><i aria-hidden="true" />Not yet delivered</dt><dd>{formatSafeNumber(remaining)}</dd><small>Processing, in review, rejected, or unresolved</small></div>
    </dl>
  </div>;
}

function FilterPopover({ label, valueLabel, open, onToggle, children }: { label: string; valueLabel: string; open: boolean; onToggle: () => void; children: ReactNode }) {
  return <div className="report-filter-wrap">
    <button type="button" className={`report-filter-button ${open ? 'open' : ''}`} onClick={onToggle} aria-expanded={open}>
      <span>{label}</span><strong>{valueLabel}</strong><Icon name="chevronDown" className="report-filter-chevron" />
    </button>
    {open && <div className="report-filter-popover">{children}</div>}
  </div>;
}

function ChoiceList({ options, value, onChoose }: { options: readonly (readonly [string, string])[]; value: string; onChoose: (value: string) => void }) {
  return <div className="report-choice-list">{options.map(([key, label]) => <button type="button" key={key} className={value === key ? 'selected' : ''} onClick={() => onChoose(key)}><span>{label}</span>{value === key && <Icon name="check" className="report-choice-check" />}</button>)}</div>;
}

function PeriodPicker({ period, onApply, onClose }: { period: PeriodSelection; onApply: (period: PeriodSelection) => void; onClose: () => void }) {
  const [customMode, setCustomMode] = useState<CustomMode>('DATES');
  const [showOther, setShowOther] = useState(period.preset === 'CUSTOM');
  const [fromDate, setFromDate] = useState(isoDate(period.from));
  const [toDate, setToDate] = useState(isoDate(new Date(period.to.getTime() - 1)));
  const [month, setMonth] = useState(`${period.from.getFullYear()}-${String(period.from.getMonth() + 1).padStart(2, '0')}`);
  const [quarter, setQuarter] = useState(String(Math.floor(period.from.getMonth() / 3) + 1));
  const [year, setYear] = useState(String(period.from.getFullYear()));
  const quick: Array<[Exclude<PeriodPreset, 'CUSTOM'>, string]> = [
    ['TODAY', 'Today'], ['YESTERDAY', 'Yesterday'], ['7D', 'Last 7 days'], ['30D', 'Last 30 days'],
    ['THIS_MONTH', 'This month'], ['LAST_MONTH', 'Last month'], ['THIS_QUARTER', 'This quarter'], ['LAST_QUARTER', 'Last quarter'], ['THIS_YEAR', 'This year'],
  ];

  const applyCustom = () => {
    if (customMode === 'DATES') {
      const from = new Date(`${fromDate}T00:00:00`);
      const toInclusive = new Date(`${toDate}T00:00:00`);
      if (Number.isNaN(from.getTime()) || Number.isNaN(toInclusive.getTime()) || from > toInclusive) return;
      onApply({ preset: 'CUSTOM', label: `${from.toLocaleDateString()} – ${toInclusive.toLocaleDateString()}`, from, to: addDays(toInclusive, 1) });
      return;
    }
    if (customMode === 'MONTH') {
      const [y, m] = month.split('-').map(Number);
      const from = new Date(y, m - 1, 1);
      onApply({ preset: 'CUSTOM', label: from.toLocaleDateString(undefined, { month: 'long', year: 'numeric' }), from, to: new Date(y, m, 1) });
      return;
    }
    const y = Number(year);
    if (customMode === 'QUARTER') {
      const q = Number(quarter);
      const from = new Date(y, (q - 1) * 3, 1);
      onApply({ preset: 'CUSTOM', label: `Q${q} ${y}`, from, to: new Date(y, q * 3, 1) });
      return;
    }
    const from = new Date(y, 0, 1);
    onApply({ preset: 'CUSTOM', label: String(y), from, to: new Date(y + 1, 0, 1) });
  };

  return <div className="period-picker">
    <div className="popover-title"><div><span>Reporting period</span><strong>Choose a useful business window</strong></div><button type="button" onClick={onClose} aria-label="Close period selector"><Icon name="close" /></button></div>
    {!showOther ? <>
      <div className="period-quick-grid">{quick.map(([key, label]) => <button type="button" key={key} className={period.preset === key ? 'selected' : ''} onClick={() => onApply(presetPeriod(key))}>{label}</button>)}</div>
      <button type="button" className="period-other" onClick={() => setShowOther(true)}><span>Other</span><small>Custom dates, month, quarter, or year</small><Icon name="chevronRight" className="period-other-arrow" /></button>
    </> : <>
      <button type="button" className="period-back" onClick={() => setShowOther(false)}>← Presets</button>
      <div className="custom-mode-tabs">{([['DATES', 'Date range'], ['MONTH', 'Month'], ['QUARTER', 'Quarter'], ['YEAR', 'Year']] as Array<[CustomMode, string]>).map(([key, label]) => <button type="button" key={key} className={customMode === key ? 'active' : ''} onClick={() => setCustomMode(key)}>{label}</button>)}</div>
      <div className="custom-period-fields">
        {customMode === 'DATES' && <><label>Start date<input type="date" value={fromDate} onChange={(event) => setFromDate(event.target.value)} /></label><label>End date<input type="date" value={toDate} onChange={(event) => setToDate(event.target.value)} /></label></>}
        {customMode === 'MONTH' && <label>Month<input type="month" value={month} onChange={(event) => setMonth(event.target.value)} /></label>}
        {customMode === 'QUARTER' && <><label>Quarter<select value={quarter} onChange={(event) => setQuarter(event.target.value)}><option value="1">Q1</option><option value="2">Q2</option><option value="3">Q3</option><option value="4">Q4</option></select></label><label>Year<input type="number" min="2000" max="2100" value={year} onChange={(event) => setYear(event.target.value)} /></label></>}
        {customMode === 'YEAR' && <label>Year<input type="number" min="2000" max="2100" value={year} onChange={(event) => setYear(event.target.value)} /></label>}
      </div>
      <button type="button" className="ui-button primary period-apply" onClick={applyCustom}>Apply period</button>
    </>}
  </div>;
}

function PanelHeader({ eyebrow, title, aside }: { eyebrow: string; title: string; aside?: string }) {
  return <div className="report-panel-head"><div><p className="eyebrow">{eyebrow}</p><h2>{title}</h2></div>{aside && <span>{aside}</span>}</div>;
}

export default function ReportsPage({ canViewOperations, canViewBusiness, canExport, canDrillDown }:
  { canViewOperations: boolean; canViewBusiness: boolean; canExport: boolean; canDrillDown: boolean }) {
  const [report, setReport] = useState<OperationalReports | null>(null);
  const [period, setPeriod] = useState<PeriodSelection>(() => presetPeriod('30D'));
  const [source, setSource] = useState('ALL');
  const [outcome, setOutcome] = useState('ALL');
  const [view, setView] = useState<ReportView>('executive');
  const [openFilter, setOpenFilter] = useState<'period' | 'source' | 'outcome' | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const toolbarRef = useRef<HTMLElement | null>(null);

  const load = async () => {
    setLoading(true); setError(null);
    try { setReport(await apiClient.getOperationalReports(reportParams(period, source, outcome))); }
    catch (err) { setError((err as ApiError).message || 'Failed to load reports'); }
    finally { setLoading(false); }
  };
  useEffect(() => { void load(); }, [period.from.getTime(), period.to.getTime(), source, outcome]);

  useEffect(() => {
    if (!openFilter) return;
    const closeOnEscape = (event: KeyboardEvent) => { if (event.key === 'Escape') setOpenFilter(null); };
    const closeOnOutside = (event: MouseEvent) => {
      const target = event.target as Node | null;
      if (target && toolbarRef.current && !toolbarRef.current.contains(target)) setOpenFilter(null);
    };
    document.addEventListener('keydown', closeOnEscape);
    document.addEventListener('mousedown', closeOnOutside);
    return () => { document.removeEventListener('keydown', closeOnEscape); document.removeEventListener('mousedown', closeOnOutside); };
  }, [openFilter]);

  const exportCsv = async () => {
    try {
      const blob = await apiClient.exportOperationalReports(reportParams(period, source, outcome));
      const url = URL.createObjectURL(blob); const anchor = document.createElement('a');
      anchor.href = url; anchor.download = `lexavault-report-${isoDate(period.from)}-${isoDate(new Date(period.to.getTime() - 1))}.csv`; anchor.click(); URL.revokeObjectURL(url);
    } catch (err) { setError((err as ApiError).message || 'Failed to export report'); }
  };

  const navigateStage = (stage: DashboardStage) => {
    if (!canDrillDown) return;
    window.location.assign(`/?stage=${encodeURIComponent(stage)}`);
  };

  const tabs = useMemo(() => {
    const values: Array<{ key: ReportView; label: string; icon: IconName }> = [
      { key: 'executive', label: 'Executive', icon: 'reports' },
    ];
    if (canViewBusiness) values.push({ key: 'business', label: 'Business Admin', icon: 'admin' });
    values.push({ key: 'usage', label: 'Usage & Adoption', icon: 'dashboard' });
    if (canViewOperations) values.push({ key: 'operations', label: 'Operational Details', icon: 'support' });
    return values;
  }, [canViewBusiness, canViewOperations]);

  useEffect(() => {
    if (!tabs.some((tab) => tab.key === view)) setView('executive');
  }, [tabs, view]);

  const sourceLabel = sourceOptions.find(([key]) => key === source)?.[1] || source;
  const outcomeLabel = outcomeOptions.find(([key]) => key === outcome)?.[1] || outcome;
  const activeFilters = [source !== 'ALL' ? sourceLabel : null, outcome !== 'ALL' ? outcomeLabel : null].filter(Boolean);

  return <PageScaffold className={`reports-page-v3${loading && report ? ' is-refreshing' : ''}`} breadcrumbs={[{ label: 'Reports' }]} title="Reporting"
    actions={<div className="report-actions"><button className="ui-button secondary" onClick={() => void load()} disabled={loading}>{loading ? 'Refreshing…' : 'Refresh'}</button>{canExport && <button className="ui-button primary" onClick={exportCsv}>Export</button>}</div>}
    primaryNavigation={<HorizontalTabs ariaLabel="Reporting views" className="report-experience-tabs">
      {tabs.map((tab) => <button type="button" key={tab.key} className={view === tab.key ? 'active' : ''} onClick={() => setView(tab.key)}><Icon name={tab.icon} className="report-tab-icon" /><strong>{tab.label}</strong></button>)}
    </HorizontalTabs>}>

    <section className="rp-controls" aria-label="Report controls" ref={toolbarRef}>
      <div className="report-filter-rail">
        <FilterPopover label="Period" valueLabel={period.label} open={openFilter === 'period'} onToggle={() => setOpenFilter(openFilter === 'period' ? null : 'period')}>
          <PeriodPicker period={period} onClose={() => setOpenFilter(null)} onApply={(next) => { setPeriod(next); setOpenFilter(null); }} />
        </FilterPopover>
        <FilterPopover label="Intake source" valueLabel={sourceLabel} open={openFilter === 'source'} onToggle={() => setOpenFilter(openFilter === 'source' ? null : 'source')}>
          <div className="popover-title compact"><div><span>Intake source</span><strong>Focus the report by channel</strong></div></div>
          <ChoiceList options={sourceOptions} value={source} onChoose={(value) => { setSource(value); setOpenFilter(null); }} />
        </FilterPopover>
        <FilterPopover label="Outcome" valueLabel={outcomeLabel} open={openFilter === 'outcome'} onToggle={() => setOpenFilter(openFilter === 'outcome' ? null : 'outcome')}>
          <div className="popover-title compact"><div><span>Current outcome</span><strong>Focus the report by workflow result</strong></div></div>
          <ChoiceList options={outcomeOptions} value={outcome} onChoose={(value) => { setOutcome(value); setOpenFilter(null); }} />
        </FilterPopover>
      </div>
      <div className="rp-controls-meta">
        {activeFilters.length > 0 && <div className="rp-chips" aria-label="Active filters">
          {source !== 'ALL' && <button type="button" onClick={() => setSource('ALL')} aria-label={`Remove ${sourceLabel} filter`}>{sourceLabel}<Icon name="close" /></button>}
          {outcome !== 'ALL' && <button type="button" onClick={() => setOutcome('ALL')} aria-label={`Remove ${outcomeLabel} filter`}>{outcomeLabel}<Icon name="close" /></button>}
          <button type="button" className="rp-clear" onClick={() => { setSource('ALL'); setOutcome('ALL'); }}>Clear all</button>
        </div>}
        <span className="rp-context">
          {report ? `${new Date(report.period.from).toLocaleDateString()} – ${new Date(new Date(report.period.to).getTime() - 1).toLocaleDateString()}` : period.label}
          {' · vs prior equal period · '}{Intl.DateTimeFormat().resolvedOptions().timeZone}
        </span>
        {report && <span className="rp-updated" aria-live="polite">{loading ? 'Refreshing…' : `Updated ${new Date(report.generatedAt).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}`}</span>}
      </div>
    </section>

    {error && <div className="ui-alert tone-danger rp-error" role="alert">
      <div><strong>{report ? 'Showing the last loaded report' : 'Reports could not be loaded'}</strong><span>{error}</span></div>
      <button type="button" className="ui-button secondary" onClick={() => void load()} disabled={loading}>Retry</button>
    </div>}

    {!report && loading && <div className="rp-loading" aria-busy="true">
      <span className="sr-only">Loading reporting data</span>
      <div className="rp-kpi-grid">{[0, 1, 2, 3].map((index) => <Skeleton key={index} className="rp-skeleton-kpi" />)}</div>
      <div className="rp-exec-grid"><Skeleton className="rp-skeleton-panel" /><Skeleton className="rp-skeleton-panel" /></div>
    </div>}

    {report && view === 'executive' && <div className="rp-executive">
      <section className="rp-section" aria-labelledby="rp-period-heading">
        <SectionHead id="rp-period-heading" title="Period performance" description={`${period.label}${activeFilters.length ? ` · ${activeFilters.join(' · ')}` : ''}, compared with the preceding equal period.`} />
        {report.executive.batches === 0 && report.executive.delivered === 0 ? (
          <EmptyState className="rp-empty">
            <Icon name="inbox" />
            <strong>No packages in this period</strong>
            <span>Nothing was received or delivered for the selected period and filters. Current risk below still reflects live queues.</span>
            <div className="rp-empty-actions">
              {activeFilters.length > 0 && <button type="button" className="ui-button secondary" onClick={() => { setSource('ALL'); setOutcome('ALL'); }}>Clear filters</button>}
              {period.preset !== '30D' && <button type="button" className="ui-button secondary" onClick={() => setPeriod(presetPeriod('30D'))}>View last 30 days</button>}
            </div>
          </EmptyState>
        ) : <>
          <div className="rp-kpi-grid">
            <Kpi label="Packages received" value={formatNumber.format(report.executive.batches)} detail={`${formatNumber.format(report.executive.documents)} documents · ${formatNumber.format(report.executive.pages)} pages`} current={report.executive.batches} previous={report.executive.previousBatches} />
            <Kpi label="Selected intake delivered" value={formatSafeNumber(report.executive.periodIntakeDelivered)} detail={`${pct(report.executive.periodIntakeDeliveryRate)} delivered by period end`} current={safeNumber(report.executive.periodIntakeDelivered)} previous={safeNumber(report.executive.previousPeriodIntakeDelivered)} tone="green" />
            <Kpi label="Straight-through delivery" value={pct(report.executive.straightThroughRate)} detail={`${formatNumber.format(report.usage.straightThroughPackages)} of ${formatNumber.format(report.executive.delivered)} deliveries had no recorded review`} tone="purple" />
            <Kpi label="Median turnaround" value={sampledHours(report.timing.medianTurnaroundHours, report.timing.turnaroundSamples)} detail={report.timing.turnaroundSamples ? `P95 ${sampledHours(report.timing.p95TurnaroundHours, report.timing.turnaroundSamples)} · ${pct(report.serviceLevel.deliveryWithinTargetRate)} within ${report.serviceLevel.deliveryTargetMinutes}m target` : 'No delivered sample in this period'} tone="teal" />
          </div>
          <div className="rp-exec-grid">
            <article className="ui-card rp-panel"><PanelHeader eyebrow="Activity trend" title="Received vs delivered" aside="Daily volume" /><Trend points={report.executive.trend} /><p className="report-definition">Delivered activity can include packages received before the selected period.</p></article>
            <article className="ui-card rp-panel"><PanelHeader eyebrow="Channel mix" title="How work enters the platform" /><SourceMix intake={report.intake} /></article>
          </div>
          <article className="ui-card rp-panel"><PanelHeader eyebrow="Selected intake progress" title="What happened to work received in this period" /><IntakeProgress report={report} /></article>
        </>}
      </section>

      <section className="rp-section" aria-labelledby="rp-risk-heading">
        <SectionHead id="rp-risk-heading" title="Current operational risk" description="Live queue state right now, independent of the selected period." />
        <div className="rp-risk-grid">
          <article className="ui-card rp-panel rp-action-panel">
            <div className="rp-risk-summary">
              <div><span>Open now</span><strong>{formatNumber.format(report.executive.currentBacklog)}</strong><small>All open packages</small></div>
              <div className={report.executive.currentAttention > 0 ? 'is-alert' : ''}><span>Needs action</span><strong>{formatNumber.format(report.executive.currentAttention)}</strong><small>Technical, review, or approval</small></div>
              <div><span>Oldest open</span><strong>{hours(report.backlog.oldestHours)}</strong><small>Age of oldest package</small></div>
            </div>
            <ul className="rp-action-list" aria-label="Where action is needed">
              {([
                ['TECHNICAL_ATTENTION', 'Technical attention', 'Processing or delivery issue', report.processing.technicalAttention, 'danger'],
                ['NEEDS_REVIEW', 'Manual review', 'Current business review queue', report.processing.needsReview, 'warning'],
                ['NEEDS_APPROVAL', 'Approval', 'Ready for release decision', report.processing.needsApproval, 'info'],
              ] as Array<[DashboardStage, string, string, number, string]>).map(([stage, label, detail, count, tone]) => (
                <li key={stage}>
                  <button type="button" className={`rp-action-row tone-${tone}`} disabled={!canDrillDown} onClick={() => navigateStage(stage)}
                    aria-label={canDrillDown ? `${label}: ${count}. Open on Dashboard` : `${label}: ${count}`}>
                    <i aria-hidden="true" />
                    <span><strong>{label}</strong><small>{detail}</small></span>
                    <b>{formatNumber.format(count)}</b>
                    {canDrillDown && <Icon name="chevronRight" className="rp-action-chevron" />}
                  </button>
                </li>
              ))}
            </ul>
            <p className="report-definition">{canDrillDown ? 'Straight-through describes the selected period; manual review is the live queue, so both can be true at once.' : 'Executive access is aggregate-only. Authorized workflow owners can investigate individual packages.'}</p>
          </article>
          <article className="ui-card rp-panel"><PanelHeader eyebrow="Backlog risk" title="Age of open work" aside={`Oldest ${hours(report.backlog.oldestHours)}`} /><BacklogAging backlog={report.backlog} /></article>
        </div>
      </section>
    </div>}

    {report && view === 'business' && canViewBusiness && <>
      <section className="report-section-intro"><div><p className="eyebrow">Business Admin</p><h2>Workflow performance and business value</h2></div><p>Understand automation, human effort, turnaround, intake quality, delivery, and backlog without exposing document contents.</p></section>
      <section className="report-grid business-grid">
        <article className="ui-card report-panel-layout"><PanelHeader eyebrow="Automation" title="Touchless vs reviewed delivery" /><div className="large-stat-split"><div><span>Straight-through</span><strong>{pct(report.executive.straightThroughRate)}</strong><small>{report.usage.straightThroughPackages} packages</small></div><div><span>With review</span><strong>{pct(report.executive.reviewRate)}</strong><small>{report.usage.reviewedPackages} packages</small></div></div></article>
        <article className="ui-card report-panel-layout"><PanelHeader eyebrow="Processing time" title="Where elapsed time goes" /><div className="timing-grid"><div><span>Active processing median</span><strong>{sampledHours(report.timing.medianActiveProcessingHours, report.timing.activeProcessingSamples)}</strong><small>{report.timing.activeProcessingSamples ? `P95 ${sampledHours(report.timing.p95ActiveProcessingHours, report.timing.activeProcessingSamples)}` : 'No completed processing sample'}</small></div><div><span>Review wait median</span><strong>{sampledHours(report.timing.medianReviewWaitHours, report.timing.reviewWaitSamples)}</strong><small>{report.timing.reviewWaitSamples ? `${report.timing.reviewWaitSamples} review samples` : 'No completed review sample'}</small></div><div><span>Review handling median</span><strong>{sampledHours(report.timing.medianReviewHandlingHours, report.timing.reviewHandlingSamples)}</strong><small>{report.timing.reviewHandlingSamples ? 'Start to decision' : 'No completed review sample'}</small></div><div><span>End-to-end median</span><strong>{sampledHours(report.timing.medianTurnaroundHours, report.timing.turnaroundSamples)}</strong><small>{report.timing.turnaroundSamples ? `P95 ${sampledHours(report.timing.p95TurnaroundHours, report.timing.turnaroundSamples)}` : 'No delivered sample'}</small></div></div><p className="report-definition">Timing is based on retained reporting telemetry. Older packages can show No data when their historical lifecycle did not contain enough timing evidence.</p></article>
        <article className="ui-card report-panel-layout"><PanelHeader eyebrow="Backlog" title="Open work aging" /><BacklogAging backlog={report.backlog} /></article>
        <article className="ui-card report-panel-layout"><PanelHeader eyebrow="Intake quality" title="Exceptions before processing" /><div className="report-metric-list"><span>Waiting for trigger <strong>{report.intake.waitingForTrigger}</strong></span><span>Duplicate observations <strong>{report.intake.duplicateObservations}</strong></span><span>Rejected observations <strong>{report.intake.rejectedObservations}</strong></span><span>Reprocessed packages <strong>{report.usage.reprocessedPackages}</strong></span></div></article>
        <article className="ui-card report-panel-layout"><PanelHeader eyebrow="Delivery" title="Release and export activity" /><div className="report-metric-list"><span>Delivered during period <strong>{formatSafeNumber(report.executive.delivered)}</strong></span><span>Selected intake delivered <strong>{formatSafeNumber(report.executive.periodIntakeDelivered)}</strong></span><span>Successful export cycles <strong>{formatSafeNumber(report.export.successfulExportCycles)}</strong></span><span>Selected-intake conversion <strong>{pct(report.executive.periodIntakeDeliveryRate)}</strong></span></div><p className="report-definition">A package can have more than one successful export cycle after reprocessing, so export-cycle totals may be higher than delivered-package totals.</p></article>
        <article className="ui-card report-panel-layout"><PanelHeader eyebrow="Service level" title="Targets vs actual" /><div className="report-metric-list target-list">
          <span>Delivery within {report.serviceLevel.deliveryTargetMinutes}m <strong>{report.serviceLevel.deliveryEligible ? pct(report.serviceLevel.deliveryWithinTargetRate) : 'No data'}</strong><small>Target {pct(report.serviceLevel.deliveryTargetPercent)} · {report.serviceLevel.deliveryBreaches} breaches <em className={`target-status ${targetStatus(report.serviceLevel.deliveryWithinTargetRate, report.serviceLevel.deliveryTargetPercent, report.serviceLevel.deliveryEligible > 0).cls}`}>{targetStatus(report.serviceLevel.deliveryWithinTargetRate, report.serviceLevel.deliveryTargetPercent, report.serviceLevel.deliveryEligible > 0).label}</em></small></span>
          <span>Straight-through delivery <strong>{pct(report.serviceLevel.straightThroughActualPercent)}</strong><small>Target {pct(report.serviceLevel.straightThroughTargetPercent)} <em className={`target-status ${targetStatus(report.serviceLevel.straightThroughActualPercent, report.serviceLevel.straightThroughTargetPercent).cls}`}>{targetStatus(report.serviceLevel.straightThroughActualPercent, report.serviceLevel.straightThroughTargetPercent).label}</em></small></span>
          <span>Review started within {report.serviceLevel.reviewWaitTargetMinutes}m <strong>{report.serviceLevel.reviewEligible ? pct(report.serviceLevel.reviewWithinTargetRate) : 'No data'}</strong><small>{report.serviceLevel.reviewEligible ? `${report.serviceLevel.reviewBreaches} breaches` : 'No completed review-start samples'} <em className={`target-status ${!report.serviceLevel.reviewEligible ? 'neutral' : report.serviceLevel.reviewBreaches === 0 ? 'good' : 'bad'}`}>{!report.serviceLevel.reviewEligible ? 'No target assessment yet' : report.serviceLevel.reviewBreaches === 0 ? 'No breaches' : `${report.serviceLevel.reviewBreaches} outside target`}</em></small></span>
        </div><p className="report-definition">Targets are tenant-configurable under Business Admin → Reporting & Business Targets.</p></article>
        {report.dimensions.map((dimension) => {
          const hasLegacyValues = dimension.values.some((value) => value.value === 'Legacy / Unmapped');
          const hasUnknownValues = dimension.values.some((value) => value.value === 'Not provided / Unknown');
          return <article className="ui-card report-panel-layout dimension-panel" key={dimension.key}><PanelHeader eyebrow="Business dimension" title={dimension.label} aside="Selected-period intake" /><div className="dimension-breakdown">{dimension.values.map((value) => {
            const specialClass = value.value === 'Legacy / Unmapped' ? 'legacy-dimension-row' : value.value === 'Not provided / Unknown' ? 'unknown-dimension-row' : '';
            return <div className={`dimension-row ${specialClass}`} key={value.value}><span><strong>{value.value}</strong><small>{formatNumber.format(value.packages)} packages · {formatNumber.format(value.delivered)} delivered</small></span><div><i style={{ width: `${Math.min(100, Math.max(0, value.deliveryRate))}%` }} /></div><b>{pct(value.deliveryRate)}</b></div>;
          })}</div>{dimension.values.length === 0 && <p className="report-definition">No selected-period packages are available for this dimension.</p>}{hasLegacyValues && <p className="report-definition">Legacy / Unmapped groups retained historical values that no longer match the tenant's current approved value set.</p>}{hasUnknownValues && <p className="report-definition">Not provided / Unknown represents selected-period packages with no retained value for this dimension. It is kept separate from legacy values so the breakdown reconciles to the selected-period cohort.</p>}</article>;
        })}
        <article className="ui-card report-panel-layout"><PanelHeader eyebrow="Retention" title="Records due for lifecycle action" /><div className="report-metric-list"><span>Originals overdue <strong className={report.retention.originalsOverdue ? 'danger-text' : ''}>{report.retention.originalsOverdue}</strong></span><span>Redacted copies overdue <strong className={report.retention.redactedCopiesOverdue ? 'danger-text' : ''}>{report.retention.redactedCopiesOverdue}</strong></span><span>Originals due within 24h <strong>{report.retention.originalsDueWithin24Hours}</strong></span><span>Redacted due within 24h <strong>{report.retention.redactedCopiesDueWithin24Hours}</strong></span></div></article>
      </section>
    </>}

    {report && view === 'usage' && <>
      <section className="report-section-intro"><div><p className="eyebrow">Usage & adoption</p><h2>How the organization is using the platform</h2></div><p>Volume, document/page load, channel adoption, and repeat processing show where the platform is creating value.</p></section>
      <section className="usage-hero">
        <div><span>Packages</span><strong>{formatNumber.format(report.usage.packages)}</strong><small>selected period</small></div>
        <div><span>Documents</span><strong>{formatNumber.format(report.usage.documents)}</strong><small>{report.usage.averageDocumentsPerPackage.toFixed(1)} per package</small></div>
        <div><span>Pages</span><strong>{formatNumber.format(report.usage.pages)}</strong><small>{report.usage.averagePagesPerPackage.toFixed(1)} per package</small></div>
        <div><span>Automated intake</span><strong>{pct(report.usage.packages ? (report.intake.api + report.intake.watchedFolder + report.intake.scanner) / report.usage.packages * 100 : 0)}</strong><small>{formatNumber.format(report.intake.api + report.intake.watchedFolder + report.intake.scanner)} API, watched-folder, or scanner packages</small></div>
        <div><span>Reprocess rate</span><strong>{pct(report.usage.packages ? report.usage.reprocessedPackages / report.usage.packages * 100 : 0)}</strong><small>{formatNumber.format(report.usage.reprocessedPackages)} of {formatNumber.format(report.usage.packages)} packages</small></div>
      </section>
      <section className="report-grid usage-grid">
        <article className="ui-card report-panel-layout"><PanelHeader eyebrow="Adoption" title="Intake source mix" /><SourceMix intake={report.intake} /></article>
        <article className="ui-card report-panel-layout"><PanelHeader eyebrow="Volume" title="Usage over time" aside="Received vs delivered" /><Trend points={report.executive.trend} /></article>
        <article className="ui-card report-panel-layout"><PanelHeader eyebrow="Efficiency" title="Human touch" /><div className="large-stat-split"><div><span>Touchless</span><strong>{report.usage.straightThroughPackages}</strong><small>{pct(report.executive.straightThroughRate)} of delivered</small></div><div><span>Reviewed</span><strong>{report.usage.reviewedPackages}</strong><small>{pct(report.executive.reviewRate)} of delivered</small></div></div></article>
        <article className="ui-card report-panel-layout"><PanelHeader eyebrow="Turnaround" title="Time to business outcome" /><div className="timing-grid compact"><div><span>Median</span><strong>{sampledHours(report.timing.medianTurnaroundHours, report.timing.turnaroundSamples)}</strong></div><div><span>P95</span><strong>{sampledHours(report.timing.p95TurnaroundHours, report.timing.turnaroundSamples)}</strong></div></div><p className="report-definition">Measured from package intake to first successful delivery. Active processing is shown separately in Business Admin reporting.</p></article>
      </section>
    </>}

    {report && view === 'operations' && canViewOperations && <>
      <section className="report-section-intro"><div><p className="eyebrow">Operational details</p><h2>Pipeline health</h2></div></section>
      <section className="report-grid operations-grid">
        <article className="ui-card report-panel-layout"><PanelHeader eyebrow="Current pipeline" title="Work by stage" /><div className="report-metric-list actionable-list"><button disabled={!canDrillDown} onClick={() => navigateStage('PROCESSING')}>Processing <strong>{report.processing.processing}</strong></button><button disabled={!canDrillDown} onClick={() => navigateStage('NEEDS_REVIEW')}>Needs review <strong>{report.processing.needsReview}</strong></button><button disabled={!canDrillDown} onClick={() => navigateStage('NEEDS_APPROVAL')}>Needs approval <strong>{report.processing.needsApproval}</strong></button><button disabled={!canDrillDown} onClick={() => navigateStage('TECHNICAL_ATTENTION')}>Technical attention <strong>{report.processing.technicalAttention}</strong></button><button disabled={!canDrillDown} onClick={() => navigateStage('REJECTED')}>Rejected <strong>{report.processing.rejected}</strong></button></div></article>
        <article className="ui-card report-panel-layout"><PanelHeader eyebrow="Intake controls" title="Observed-folder health" /><div className="report-metric-list"><span>Waiting for trigger <strong>{report.intake.waitingForTrigger}</strong></span><span>Duplicate observations <strong>{report.intake.duplicateObservations}</strong></span><span>Rejected observations <strong>{report.intake.rejectedObservations}</strong></span></div></article>
        <article className="ui-card report-panel-layout"><PanelHeader eyebrow="Delivery" title="Export activity" /><div className="report-metric-list"><span>Packages ever exported <strong>{report.export.exportedPackages}</strong></span><span>Successful export cycles <strong>{report.export.successfulExportCycles}</strong></span><span>Awaiting final approval <strong>{report.export.pendingFinalApproval}</strong></span></div></article>
        <article className="ui-card report-panel-layout"><PanelHeader eyebrow="Retention risk" title="Overdue and due soon" /><div className="report-metric-list"><span>Originals overdue <strong className={report.retention.originalsOverdue ? 'danger-text' : ''}>{report.retention.originalsOverdue}</strong></span><span>Redacted copies overdue <strong className={report.retention.redactedCopiesOverdue ? 'danger-text' : ''}>{report.retention.redactedCopiesOverdue}</strong></span><span>Technical holds due within 24h <strong>{report.retention.technicalHoldsDueWithin24Hours}</strong></span><span>Purged packages <strong>{report.retention.purgedPackages}</strong></span></div></article>
        <article className="ui-card report-panel-layout"><PanelHeader eyebrow="Capacity signal" title="Backlog aging" aside={`Oldest ${hours(report.backlog.oldestHours)}`} /><BacklogAging backlog={report.backlog} /></article>
        <article className="ui-card report-panel-layout"><PanelHeader eyebrow="Processing" title="Active processing time" /><div className="large-stat-split"><div><span>Median</span><strong>{sampledHours(report.timing.medianActiveProcessingHours, report.timing.activeProcessingSamples)}</strong><small>sum of completed processing cycles</small></div><div><span>P95</span><strong>{sampledHours(report.timing.p95ActiveProcessingHours, report.timing.activeProcessingSamples)}</strong><small>{report.timing.activeProcessingSamples} selected-period samples</small></div></div></article>
      </section>
    </>}
  </PageScaffold>;
}
