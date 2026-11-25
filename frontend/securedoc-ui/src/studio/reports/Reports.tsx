import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react';
import useSWR from 'swr';
import { Link, useSearchParams } from 'react-router-dom';
import { apiClient } from '../../api/client';
import type { DailyReport, OperationalReports, ReportSource } from '../../types';
import { CAP, errorMessage, type StageKey } from '../data';
import { Notice, Spinner } from '../atoms';
import { useToast } from '../toast';
import { ErrorBoundary, LoadErrorCard, checked, isRecord } from '../failure';
import { axisTicks, bucketDate, hours, labelledBuckets, pct, ratio } from './reportRules';
import { isTimeSaved, timeSavedWords } from './timeSavedRules';
import { ComplianceReportButton } from '../compliance/ComplianceReport';
import { complianceEdition } from '../compliance/complianceRules';
import { dayIn, dayText, plusDays } from '../entityZone';
import { useEntityZone, useEntityZoneSet } from '../EntityZoneContext';
import QueueSection from '../admin/QueueSection';
import { SENSITIVITY_LEVELS, SENSITIVITY_NAMES } from '../words';
import DailyTab from './DailyTab';
import { PRESETS, TABS, defaultPreset, isDailyReport, periodFor, tabFrom, type Preset, type Tab } from './dailyRules';
import '../admin/admin.css';
import './reports.css';

const EMPTY_SOURCES: ReportSource[] = [];

function delta(current: number, previous: number) {
  if (!previous) return null;
  const change = ((current - previous) / previous) * 100;
  return { text: `${change >= 0 ? '+' : ''}${Math.round(change)}%`, up: change >= 0 };
}

export default function Reports({ caps, entityName }: { caps: string[]; entityName: string }) {
  const toast = useToast();
  const canOperational = caps.includes(CAP.reportOperational);
  const canExport = caps.includes(CAP.reportExport);
  const canExecutive = caps.includes(CAP.reportExecutive);
  const compliance = complianceEdition('reports', caps);

  const [params, setParams] = useSearchParams();
  const tab = tabFrom(params.get('tab'));
  const [chosen, setChosen] = useState<Preset | null>(null);
  const preset = chosen ?? defaultPreset(tab);
  const zone = useEntityZone();
  const zoneSet = useEntityZoneSet();
  const [customFrom, setCustomFrom] = useState(() => dayText(plusDays(dayIn(new Date(), zone), -13)));
  const [customTo, setCustomTo] = useState(() => dayText(dayIn(new Date(), zone)));
  const [sourceSystem, setSourceSystem] = useState('ALL');
  const [level, setLevel] = useState('ALL');
  const [exporting, setExporting] = useState(false);
  const tabsId = useId();

  const period = useMemo(() => periodFor(preset, customFrom, customTo, zone), [preset, customFrom, customTo, zone]);
  const asked = period ? { from: period.from.toISOString(), to: period.to.toISOString(), sourceSystem, level } : null;
  const key = asked ? [asked.from, asked.to, sourceSystem, level] : null;

  // A filter without its source systems still has All; a report never waits for it.
  const { data: sources = EMPTY_SOURCES } = useSWR<ReportSource[]>('report-sources',
    () => checked('GET /operations/reports/sources', apiClient.getReportSources(), Array.isArray), { revalidateOnFocus: false });
  const reports = useSWR<OperationalReports>(
    key && tab !== 'daily' ? ['reports', ...key] : null,
    () => checked('GET /operations/reports', apiClient.getOperationalReports(asked!), isOperationalReports),
    { keepPreviousData: true, revalidateOnFocus: false },
  );
  const daily = useSWR<DailyReport>(
    key && tab === 'daily' ? ['reports-daily', ...key] : null,
    () => checked('GET /operations/reports/daily', apiClient.getDailyReport(asked!), isDailyReport),
    { keepPreviousData: true, revalidateOnFocus: false },
  );
  const { data: shown, error, isLoading, isValidating, mutate } = tab === 'daily' ? daily : reports;
  const data = reports.data;
  const request = tab === 'daily' ? 'GET /operations/reports/daily' : 'GET /operations/reports';

  async function exportCsv() {
    if (!asked) return;
    setExporting(true);
    try {
      const blob = await apiClient.exportReportTab(tab, asked);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `lexavault-${tab}-${dayText(period!.fromDay)}-to-${dayText(period!.toDay)}.csv`;
      a.click();
      URL.revokeObjectURL(url);
      toast('Report exported.');
    } catch (e) {
      toast(errorMessage(e, 'Export failed.'), 'danger');
    } finally {
      setExporting(false);
    }
  }

  function openTab(next: Tab) {
    setParams(next === 'overview' ? {} : { tab: next }, { replace: true });
  }
  function onTabKey(event: ReactKeyboardEvent<HTMLDivElement>) {
    const i = TABS.findIndex((t) => t.key === tab);
    const to = event.key === 'ArrowRight' ? (i + 1) % TABS.length : event.key === 'ArrowLeft' ? (i + TABS.length - 1) % TABS.length
      : event.key === 'Home' ? 0 : event.key === 'End' ? TABS.length - 1 : -1;
    if (to < 0) return;
    event.preventDefault();
    openTab(TABS[to].key);
    event.currentTarget.querySelectorAll<HTMLElement>('[role="tab"]')[to]?.focus();
  }

  return (
    <div className="cf-reports">
      <header className="cf-page-head">
        <div>
          <span className="cf-eyebrow">Reports</span>
          <h1 className="cf-title">How the work is going</h1>
          <p className="cf-lede">
            {period ? period.label : 'Choose a valid range'}
            {shown && <> · updated {new Date(shown.generatedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', timeZone: zone })}</>}
            {zoneSet && <> · days in {zoneSet}</>}
            {isValidating && shown && <> · refreshing</>}
          </p>
        </div>
        {(canExport || compliance) && (
          <div className="cf-rp-actions">
            {canExport && (
              <button type="button" className="cf-btn" onClick={exportCsv} disabled={!shown || exporting}>
                {exporting ? <Spinner label="Exporting" /> : null}
                Export CSV
              </button>
            )}
            {compliance && <ComplianceReportButton edition={compliance} entityName={entityName} />}
          </div>
        )}
      </header>

      <div className="cf-rp-tabs" role="tablist" aria-label="Reports" onKeyDown={onTabKey}>
        {TABS.map((t) => (
          <button key={t.key} type="button" role="tab" id={`${tabsId}-${t.key}`} aria-controls={`${tabsId}-panel`} aria-selected={tab === t.key}
            tabIndex={tab === t.key ? 0 : -1} onClick={() => openTab(t.key)}>{t.label}</button>
        ))}
      </div>

      <div className="cf-rp-filters" role="group" aria-label="Report filters">
        <div className="cf-segment" role="radiogroup" aria-label="Period">
          {PRESETS.map((p) => (
            <button key={p.key} type="button" role="radio" aria-checked={preset === p.key} className={preset === p.key ? 'is-active' : ''} onClick={() => setChosen(p.key)}>
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
        <label className="cf-rp-select"><span className="cf-sr">Source system</span>
          <select value={sourceSystem} onChange={(e) => setSourceSystem(e.target.value)}>
            <option value="ALL">All source systems</option>
            {sources.map((s) => <option key={s.key} value={s.key}>{s.name}</option>)}
          </select>
        </label>
        <label className="cf-rp-select"><span className="cf-sr">Level</span>
          <select value={level} onChange={(e) => setLevel(e.target.value)}>
            <option value="ALL">All levels</option>
            {SENSITIVITY_LEVELS.map((l) => <option key={l} value={l}>{SENSITIVITY_NAMES[l]}</option>)}
          </select>
        </label>
      </div>

      <div role="tabpanel" id={`${tabsId}-panel`} aria-labelledby={`${tabsId}-${tab}`}>
        {!period && <Notice>The end date must be on or after the start date.</Notice>}
        {error && !shown && <LoadErrorCard title="Reports couldn't be loaded" error={error} request={request} onRetry={() => void mutate()} />}
        {isLoading && !shown && <div className="cf-center"><Spinner label="Loading reports" /></div>}

        {tab === 'overview' && data && (
          <div className="cf-rp-body" aria-busy={isValidating}>
            {canExecutive && <Card name="What LexaVault did for you"><DidForYou r={data} /></Card>}
            <Card name="The headline"><Executive r={data} /></Card>
            <Card name="The trend"><Trend r={data} /></Card>
            <Card name="Service levels"><ServiceLevel r={data} /></Card>
          </div>
        )}
        {tab === 'daily' && daily.data && period && (
          <div className="cf-rp-body" aria-busy={isValidating}>
            <ErrorBoundary title="Daily intake and delivery couldn't be shown" request={request}>
              <DailyTab r={daily.data} preset={preset} period={period} caps={caps} zone={zone} />
            </ErrorBoundary>
          </div>
        )}
        {tab === 'operations' && data && (
          <div className="cf-rp-body" aria-busy={isValidating}>
            {canOperational ? (
              <>
                <ErrorBoundary title="The queue couldn't be shown" request="GET /operations/reports/queue"><QueueSection own /></ErrorBoundary>
                <Card name="The pipeline"><Pipeline r={data} links={caps.includes(CAP.desk)} /></Card>
                <div className="cf-rp-pair">
                  <Card name="The backlog"><Backlog r={data} /></Card>
                  <Card name="Keeping and deleting"><Retention r={data} /></Card>
                </div>
                <Card name="Timing"><Timing r={data} /></Card>
                {data.dimensions.map((d) => <Card key={d.key} name={d.label || 'A breakdown'}><Dimension dim={d} /></Card>)}
              </>
            ) : (
              <p className="cf-rp-scope">More detail is available to Business Administrators.</p>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

/** Each report card fails on its own: the other cards, the filters and the top bar keep working. */
function Card({ name, children }: { name: string; children: React.ReactNode }) {
  return <ErrorBoundary title={`${name} couldn't be shown`} request="GET /operations/reports">{children}</ErrorBoundary>;
}

/** The parts of the report every card reads; anything else is an answer this screen does not understand. */
function isOperationalReports(v: unknown): boolean {
  if (!isRecord(v)) return false;
  return typeof v.generatedAt === 'string' && isRecord(v.period) && isRecord(v.executive) && Array.isArray(v.executive.trend)
    && isRecord(v.intake) && isRecord(v.processing) && isRecord(v.export) && isRecord(v.retention)
    && isRecord(v.timing) && isRecord(v.backlog) && isRecord(v.serviceLevel)
    && Array.isArray(v.dimensions) && v.dimensions.every((d) => isRecord(d) && typeof d.label === 'string' && Array.isArray(d.values));
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
      <Stat label="Typical time to delivery" value={hours(e.medianDeliveryHours)} sub={`slowest 5%: ${hours(e.p95DeliveryHours)}`} />
      <Stat label="No review needed" value={pct(e.straightThroughRate)} sub={`${pct(e.reviewRate)} needed a person`} />
      <Stat label="Open now" value={e.currentBacklog.toLocaleString()} sub={e.currentAttention ? `${e.currentAttention} stopped · oldest ${hours(e.oldestBacklogHours)}` : `oldest ${hours(e.oldestBacklogHours)}`} />
    </dl>
  );
}

/**
 * U024: what LexaVault did for you in the chosen period and filters (task 0020's `timeSaved`). Seen by the same people
 * as the headline (REPORT_EXECUTIVE_VIEW). An answer without a readable block fails only this card.
 */
function DidForYou({ r }: { r: OperationalReports }) {
  const [open, setOpen] = useState(false);
  const link = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  // Opening moves focus into the panel once; a refresh of the report while it is open leaves focus where it is.
  useEffect(() => { if (open) panel.current?.focus(); }, [open]);
  const titleId = useId();
  const panelId = useId();
  const panelTitleId = useId();
  if (!isTimeSaved(r.timeSaved)) throw new Error('What LexaVault did for you is missing from the report.');
  const w = timeSavedWords(r.timeSaved);

  function close() {
    setOpen(false);
    link.current?.focus();
  }
  function onKeyDown(e: ReactKeyboardEvent<HTMLElement>) {
    if (e.key === 'Escape') { e.stopPropagation(); close(); }
  }

  return (
    <section className="cf-rp-did" aria-labelledby={titleId}>
      <h2 id={titleId}>What LexaVault did for you</h2>
      <div className="cf-rp-did-tiles">
        <div className="cf-rp-did-tile" data-tone="ok">
          <span className="cf-rp-did-label">Handled without a person</span>
          <b className="cf-rp-did-value">{w.noPerson.value}</b>
          <span className="cf-rp-did-sub">{w.noPerson.sub}</span>
        </div>
        <div className="cf-rp-did-tile">
          <span className="cf-rp-did-label">Pages checked</span>
          <b className="cf-rp-did-value">{w.pages.value}</b>
          <span className="cf-rp-did-sub">{w.pages.sub}</span>
        </div>
        <div className="cf-rp-did-tile">
          <span className="cf-rp-did-label">Sensitive items redacted</span>
          <b className="cf-rp-did-value">{w.items.value}</b>
          <span className="cf-rp-did-sub">{w.items.sub}</span>
        </div>
        <div className="cf-rp-did-tile" data-tone="strong">
          <span className="cf-rp-did-label">Review time saved (estimate)</span>
          <b className="cf-rp-did-value">{w.saved.value}</b>
          <button ref={link} type="button" className="cf-rp-did-how" aria-expanded={open} aria-controls={open ? panelId : undefined}
            onClick={() => (open ? close() : setOpen(true))}>
            How is this worked out?
          </button>
        </div>
      </div>
      {open && (
        <div id={panelId} className="cf-rp-did-panel" role="dialog" aria-labelledby={panelTitleId} tabIndex={-1} onKeyDown={onKeyDown}
          ref={panel}>
          <h3 id={panelTitleId}>How "Review time saved" is worked out</h3>
          <table className="cf-rp-did-formula">
            <tbody>
              <tr><td>Pages in cases that needed no person</td><td>{w.formula.pages}</td></tr>
              <tr><td>× minutes a person would take per page</td><td>{w.formula.minutesPerPage}</td></tr>
              <tr data-result><td>{w.formula.result}</td><td>{w.formula.resultValue}</td></tr>
            </tbody>
          </table>
          <p className="cf-rp-did-note">{w.formula.note}</p>
          <button type="button" className="cf-btn" onClick={close}>Close</button>
        </div>
      )}
    </section>
  );
}

function Trend({ r }: { r: OperationalReports }) {
  const points = r.executive.trend;
  const [hover, setHover] = useState<number | null>(null);
  const [dayFocused, setDayFocused] = useState(false);
  const days = useRef<HTMLDivElement>(null);
  if (!points.length) return null;
  const ticks = axisTicks(Math.max(...points.map((p) => Math.max(p.received, p.delivered))));
  const top = ticks[ticks.length - 1];
  const at = (v: number) => `${(v / top) * 100}%`;
  const labelled = new Set(labelledBuckets(points.length));
  const shown = hover === null ? null : points[hover];
  const bucketZone = useEntityZoneSet();
  const dayLabel = (p: (typeof points)[number]) => `${bucketDate(p.bucket, bucketZone)}: ${p.received.toLocaleString()} received, ${p.delivered.toLocaleString()} delivered`;
  // U027 (WCAG 2.1.1): one Tab stop for the chart (the group of days), then the arrow keys move between days. The
  // focused day shows the same tip as on hover and is read out by its name. Esc goes back to the chart itself.
  const focusDay = (i: number) => {
    const next = Math.max(0, Math.min(points.length - 1, i));
    setHover(next);
    days.current?.querySelectorAll<HTMLElement>('.cf-rp-trend-col')[next]?.focus();
  };
  const onDaysKey = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    const onDay = (event.target as HTMLElement).classList.contains('cf-rp-trend-col');
    const current = onDay && hover !== null ? hover : null;
    if (event.key === 'ArrowRight' || event.key === 'ArrowDown') { event.preventDefault(); focusDay(current === null ? 0 : current + 1); }
    else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') { event.preventDefault(); focusDay(current === null ? points.length - 1 : current - 1); }
    else if (event.key === 'Home') { event.preventDefault(); focusDay(0); }
    else if (event.key === 'End') { event.preventDefault(); focusDay(points.length - 1); }
    else if (event.key === 'Escape' && onDay) { event.preventDefault(); setHover(null); setDayFocused(false); days.current?.focus(); }
  };
  return (
    <Section title="Received and delivered" note="Cases per day">
      <div className="cf-rp-legend" aria-hidden="true"><span data-k="received">Received</span><span data-k="delivered">Delivered</span></div>
      <div className="cf-rp-chart" data-testid="trend-chart">
        <div className="cf-rp-yaxis" aria-hidden="true">
          {ticks.map((t) => <span key={t} style={{ bottom: at(t) }}>{t.toLocaleString()}</span>)}
        </div>
        <div className="cf-rp-plot" onMouseLeave={() => { if (!days.current?.contains(document.activeElement)) setHover(null); }}>
          {ticks.map((t) => <span key={t} className="cf-rp-gridline" style={{ bottom: at(t) }} aria-hidden="true" />)}
          <div ref={days} className="cf-rp-trend" role="group" aria-label="Received and delivered per day. Use the arrow keys to move between days."
            tabIndex={dayFocused ? -1 : 0} onKeyDown={onDaysKey}
            onFocus={(e) => { if (e.target !== e.currentTarget) setDayFocused(true); }}
            onBlur={(e) => { if (!e.currentTarget.contains(e.relatedTarget as Node | null)) { setDayFocused(false); setHover(null); } }}>
            {points.map((p, i) => (
              <div key={p.bucket} className="cf-rp-trend-col" data-hover={hover === i || undefined} role="img" aria-label={dayLabel(p)} tabIndex={-1}
                onMouseEnter={() => setHover(i)} onClick={() => setHover(i)} onFocus={() => setHover(i)}>
                <span data-k="received" style={{ height: at(p.received) }} />
                <span data-k="delivered" style={{ height: at(p.delivered) }} />
              </div>
            ))}
          </div>
          {shown && hover !== null && (
            <div className="cf-rp-tip" data-testid="trend-tip" aria-hidden="true" data-side={hover >= points.length / 2 ? 'left' : 'right'}
              style={{ left: `${((hover + 0.5) / points.length) * 100}%` }}>
              <strong>{bucketDate(shown.bucket, bucketZone)}</strong>
              <span data-k="received">{shown.received.toLocaleString()} received</span>
              <span data-k="delivered">{shown.delivered.toLocaleString()} delivered</span>
            </div>
          )}
          <div className="cf-rp-xaxis" aria-hidden="true">
            {points.map((p, i) => <span key={p.bucket}>{labelled.has(i) ? bucketDate(p.bucket, bucketZone) : ''}</span>)}
          </div>
        </div>
      </div>
      {/* A table never shrinks to the 1 px hidden box, so it is hidden through a wrapper. */}
      <div className="cf-sr">
        <table>
          <caption>Cases received and delivered per day</caption>
          <thead><tr><th scope="col">Day</th><th scope="col">Received</th><th scope="col">Delivered</th></tr></thead>
          <tbody>
            {points.map((p) => <tr key={p.bucket}><th scope="row">{bucketDate(p.bucket, bucketZone)}</th><td>{p.received}</td><td>{p.delivered}</td></tr>)}
          </tbody>
        </table>
      </div>
    </Section>
  );
}

/**
 * One service-level bar. `empty` is set when nothing in the period could be measured: the bar then says so in a
 * neutral colour instead of showing a red 0% (owner's answer to U006, 2026-11-18).
 */
function TargetBar({ label, actual, target, detail, empty }: { label: string; actual: number; target: number; detail: string; empty?: string }) {
  const a = Math.min(100, ratio(actual));
  const t = Math.min(100, ratio(target));
  const met = a >= t;
  if (empty) {
    return (
      <div className="cf-rp-target" data-empty>
        <div className="cf-rp-target-head">
          <span>{label}</span>
          <span className="cf-rp-target-value cf-muted">target {Math.round(t)}%</span>
        </div>
        <div className="cf-rp-target-track" role="img" aria-label={`${label}: ${empty}. Target ${Math.round(t)}%.`}>
          <span className="cf-rp-target-line" style={{ left: `${t}%` }} />
        </div>
        <p className="cf-rp-target-detail">{empty}</p>
      </div>
    );
  }
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
  // The review target is a setting (Admin › Report targets). An answer without a readable one is not guessed:
  // the card fails on its own and says so.
  const reviewTarget = s.reviewWaitTargetPercent;
  if (typeof reviewTarget !== 'number' || !Number.isFinite(reviewTarget) || reviewTarget < 0 || reviewTarget > 100) {
    throw new Error('The review pickup target is missing from the report.');
  }
  const noDeliveries = 'No deliveries in this period';
  return (
    <Section title="Service levels" note="Targets are set in Admin › Redaction & retention › Reporting">
      <div className="cf-rp-targets">
        <TargetBar label={`Delivered within ${hours(s.deliveryTargetMinutes / 60)}`} actual={s.deliveryWithinTargetRate} target={s.deliveryTargetPercent}
          detail={`${s.deliveryBreaches} of ${s.deliveryEligible} cases missed the target`} empty={s.deliveryEligible > 0 ? undefined : noDeliveries} />
        <TargetBar label="No review needed" actual={s.straightThroughActualPercent} target={s.straightThroughTargetPercent}
          detail="Cases delivered with no person reviewing or finishing them" empty={r.executive.delivered > 0 ? undefined : noDeliveries} />
        <TargetBar label={`Review picked up within ${hours(s.reviewWaitTargetMinutes / 60)}`} actual={s.reviewWithinTargetRate} target={reviewTarget}
          detail={`${s.reviewBreaches} of ${s.reviewEligible} reviews waited longer`} empty={s.reviewEligible > 0 ? undefined : 'No reviews in this period'} />
      </div>
    </Section>
  );
}

function Pipeline({ r, links }: { r: OperationalReports; links: boolean }) {
  const p = r.processing;
  const rows: { label: string; value: number; stage: StageKey; tone?: 'danger' }[] = [
    { label: 'Redacting', value: p.processing, stage: 'redacting' },
    { label: 'Needs review', value: p.needsReview, stage: 'review' },
    { label: 'Awaiting approval', value: p.needsApproval, stage: 'approval' },
    { label: 'Delivered', value: p.completed, stage: 'delivered' },
    { label: 'Stopped', value: p.technicalAttention, stage: 'stopped', tone: 'danger' },
    { label: 'Rejected', value: p.rejected, stage: 'stopped', tone: 'danger' },
  ];
  return (
    <Section title="Pipeline" note={links ? 'Select a stage to open the matching cases' : undefined}>
      <ul className="cf-rp-pipeline">
        {rows.map((row) => (
          <li key={row.label}>
            {links ? (
              <Link to={`/?stage=${row.stage}`} className="cf-rp-pipe" data-tone={row.value > 0 ? row.tone : undefined}>
                <span className="cf-rp-pipe-value">{row.value}</span>
                <span className="cf-rp-pipe-label">{row.label}</span>
              </Link>
            ) : (
              <span className="cf-rp-pipe" data-tone={row.value > 0 ? row.tone : undefined}>
                <span className="cf-rp-pipe-value">{row.value}</span>
                <span className="cf-rp-pipe-label">{row.label}</span>
              </span>
            )}
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
        <Stat label="Typical turnaround" value={hours(t.medianTurnaroundHours)} sub={`slowest 5%: ${hours(t.p95TurnaroundHours)} · ${t.turnaroundSamples} cases`} />
        <Stat label="Typical active processing" value={hours(t.medianActiveProcessingHours)} sub={`slowest 5%: ${hours(t.p95ActiveProcessingHours)}`} />
        <Stat label="Typical wait for a reviewer" value={hours(t.medianReviewWaitHours)} sub={`${t.reviewWaitSamples} reviews`} />
        <Stat label="Typical review handling" value={hours(t.medianReviewHandlingHours)} sub={`${t.reviewHandlingSamples} reviews`} />
        <Stat label="Documents per case" value={u.averageDocumentsPerPackage.toFixed(1)} sub={`${u.averagePagesPerPackage.toFixed(1)} pages`} />
        <Stat label="Reprocessed" value={u.reprocessedPackages.toLocaleString()} sub={`of ${u.packages.toLocaleString()} cases`} />
      </dl>
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
            <tr><th scope="col">{dim.label}</th><th scope="col">Cases</th><th scope="col">Delivered</th><th scope="col">Delivery rate</th><th scope="col">Typical time</th></tr>
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
