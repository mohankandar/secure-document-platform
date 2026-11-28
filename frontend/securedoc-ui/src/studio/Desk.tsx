import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type RefObject } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ChevronRight, Plus, Search, X } from 'lucide-react';
import type { PackageSummary } from '../types';
import {
  CAP, SOURCE_LABEL, STAGES, caseTitle, needsYou, openFindingsByPackage, relativeTime, stageOf, tileOf,
  useCaseFields, usePackages, useReviewQueue, useSensitiveMatches,
} from './data';
import { CASE_GROUPS, CASE_TAGS, NAV, STAGE_HELP, caseSearchPlaceholders, firstThatFits, priorityName } from './words';
import { ExportChip, Kbd, Notice, Spinner, StageChip } from './atoms';
import { isLvCode, lvLabel } from './lvCodes';
import { exportStateOf, exportStatusLabel, isExporting } from './exportRules';
import CaseDrawer from './CaseDrawer';
import { BulkApproveBar, useBulkApprove, type BulkSelection } from './BulkApprove';
import { LoadErrorCard } from './failure';
import { rowDestination, rowReason } from './caseRules';
import { CASES_KEY_ZONE, TYPING_OR_DIALOG } from './shortcuts';
import { LvText } from './help/LvCodeLink';
import CaseViewMenu from './CaseViewMenu';
import { DEFAULT_WAITING, caseComparator, shownColumns, sortLabel, useCaseView, orderWords } from './caseView';

/** Task 0108: the six stage boxes (approved "Cases at volume"). Exporting cases count under the exported box. */
type Box = 'intake' | 'redacting' | 'review' | 'approval' | 'delivered' | 'stopped';
const BOXES: Box[] = ['intake', 'redacting', 'review', 'approval', 'delivered', 'stopped'];
type Filter = 'all' | Box;

/** Final cases: they show in Reports (and Find), never on the Cases page (owner 2026-11-27 21:57). */
const FINAL = new Set(['REJECTED', 'CANCELLED']);

interface Group { key: 'you' | 'motion' | 'delivered' | 'stage' | 'code'; title: string; note?: string; cases: PackageSummary[] }

/** Task 0111: cases shown per group before "Show more". */
const GROUP_PAGE = 25;

const sameDay = (iso: string, now: Date) => new Date(iso).toDateString() === now.toDateString();

/** Which stage box a case counts in (null: not on the Cases page at all). */
export function boxOf(pkg: PackageSummary, now = new Date()): Box | null {
  if (FINAL.has(pkg.status)) return null;
  const tile = tileOf(pkg);
  if (tile === 'exporting') return 'delivered';
  if (tile === 'delivered') return sameDay(pkg.updatedAt, now) ? 'delivered' : null;
  if (tile === 'stopped') return 'stopped';
  if (tile === 'unknown') return 'redacting';
  return tile as Box;
}

/**
 * Where a case is listed for this person (null: not listed). Needs you holds business work only; a stopped case is
 * worked by IT in Support and is not listed (owner 2026-11-27 22:00); older exported cases are in Find and Reports.
 */
function groupOf(pkg: PackageSummary, caps: string[], now: Date): 'you' | 'motion' | 'delivered' | null {
  const box = boxOf(pkg, now);
  if (box === null || box === 'stopped') return null;
  if (needsYou(pkg, caps)) return 'you';
  if (box === 'delivered' && !isExporting(exportStateOf(pkg.exportState))) return 'delivered';
  return 'motion';
}

/** "12m", "4h 50m", "2d 3h": how long a Needs-you case has waited. */
function waited(iso: string, now: number): string {
  const mins = Math.max(0, Math.floor((now - new Date(iso).getTime()) / 60000));
  if (mins < 60) return `${mins}m`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h${mins % 60 ? ` ${mins % 60}m` : ''}`;
  return `${Math.floor(hours / 24)}d${hours % 24 ? ` ${hours % 24}h` : ''}`;
}

const hoursText = (mins: number) => (mins % 60 === 0 ? `${mins / 60} h` : `${mins} min`);

/**
 * The filter's placeholder: the first of the given texts that fits the box at its current width, otherwise the last
 * ("Filter cases"; U019, owner's U019 answer 1). Measured with the input's own font; measured again when the box
 * resizes and once the web font has loaded.
 */
function usePlaceholderThatFits(ref: RefObject<HTMLInputElement | null>, texts: readonly string[]): string {
  const key = JSON.stringify(texts);
  const [text, setText] = useState(texts[texts.length - 1] ?? '');
  useLayoutEffect(() => {
    const input = ref.current;
    if (!input) return undefined;
    const candidates = JSON.parse(key) as string[];
    const context = document.createElement('canvas').getContext('2d');
    let live = true;
    const measure = () => {
      if (!live) return;
      const style = getComputedStyle(input);
      if (context) context.font = `${style.fontStyle} ${style.fontWeight} ${style.fontSize} ${style.fontFamily}`;
      const room = input.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight);
      setText(firstThatFits(candidates, (t) => (context ? context.measureText(t).width : null), room));
    };
    measure();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure);
    observer?.observe(input);
    void document.fonts?.ready.then(measure);
    return () => { live = false; observer?.disconnect(); };
  }, [ref, key]);
  return text;
}

/** A box asked for in the address (?stage=): the old names still open the right box; anything else shows all. */
function filterFromAddress(requested: string | null): Filter {
  if (requested === 'exporting') return 'delivered';
  return requested && (BOXES as string[]).includes(requested) && requested !== 'stopped' ? requested as Box : 'all';
}

export default function Desk({ caps }: { caps: string[] }) {
  const { caseId } = useParams();
  const navigate = useNavigate();
  const searchRef = useRef<HTMLInputElement>(null);
  const [searchParams] = useSearchParams();
  const [filter, setFilter] = useState<Filter>(() => filterFromAddress(searchParams.get('stage')));
  // Task 0081: Reports opens the cases with one reason code.
  const [code, setCode] = useState(() => { const asked = searchParams.get('code'); return isLvCode(asked) ? asked.trim() : null; });
  const [query, setQuery] = useState('');
  // A report link opened while Cases is showing: follow the address.
  const askedCode = searchParams.get('code');
  useEffect(() => { if (isLvCode(askedCode)) setCode(askedCode.trim()); }, [askedCode]);

  const { data: packages, error, isLoading, mutate } = usePackages(true);
  const fields = useCaseFields(caps, packages);
  const placeholder = usePlaceholderThatFits(searchRef, caseSearchPlaceholders(fields));
  const { data: queue } = useReviewQueue(caps.includes(CAP.review));
  const findings = useMemo(() => openFindingsByPackage(queue?.items), [queue]);

  // The clock for "today" and the waiting colours; moves on with every refresh of the list.
  const now = useMemo(() => new Date(), [packages]); // eslint-disable-line react-hooks/exhaustive-deps
  const all = useMemo(() => packages ?? [], [packages]);
  const counts = useMemo(() => {
    const byBox = new Map<Box, number>();
    for (const p of all) {
      const box = boxOf(p, now);
      if (box) byBox.set(box, (byBox.get(box) ?? 0) + 1);
    }
    return byBox;
  }, [all, now]);

  // Task 0108: the View menu (sort, order, columns, density), saved on the server with the person.
  const caseView = useCaseView(true);
  const { settings: viewSettings } = caseView;
  const waiting = caseView.view?.waiting ?? DEFAULT_WAITING;
  const labels = useMemo(() => {
    const byKey = new Map<string, string>();
    for (const p of all) for (const i of p.businessIdentifiers) if (!byKey.has(i.key)) byKey.set(i.key, i.label);
    return byKey;
  }, [all]);
  const fieldLabel = useCallback((key: string) => labels.get(key) ?? key, [labels]);
  const columns = useMemo(() => shownColumns(viewSettings, caseView.view?.options.columns), [viewSettings, caseView.view]);

  const sensitiveMatches = useSensitiveMatches(query, caps);
  const compare = useMemo(() => caseComparator(viewSettings), [viewSettings]);

  // Task 0108: new cases never jump into the list. Cases first seen after the page opened wait behind the pill.
  const [seen, setSeen] = useState<Set<string> | null>(null);
  const [fresh, setFresh] = useState<Set<string>>(new Set());
  useEffect(() => {
    if (packages && seen === null) setSeen(new Set(packages.map((p) => p.packageId)));
  }, [packages, seen]);

  const groups = useMemo<Group[]>(() => {
    const q = query.trim().toLowerCase();
    const matches = all
      .filter((p) => !q || sensitiveMatches.has(p.packageId) || p.businessIdentifiers.some((i) => !i.sensitive && i.value.toLowerCase().includes(q)))
      .sort((a, b) => {
        // What waits for a person comes first; inside each group, the person's chosen sort (View menu).
        const [youA, youB] = [needsYou(a, caps), needsYou(b, caps)];
        if (youA !== youB) return youA ? -1 : 1;
        return compare(a, b);
      });

    // From a report link: every case with that code, stopped and final ones too (as Find would open them).
    if (code) return [{ key: 'code', title: NAV.cases, cases: matches.filter((p) => p.stopReasonCode === code) }];
    if (filter !== 'all') return [{ key: 'stage', title: STAGES.find((s) => s.key === filter)?.label ?? filter, cases: matches.filter((p) => boxOf(p, now) === filter) }];
    const by = (key: 'you' | 'motion' | 'delivered') => matches.filter((p) => groupOf(p, caps, now) === key);
    const motion = by('motion');
    const oldest = motion.reduce<string | null>((o, p) => (!o || p.createdAt < o ? p.createdAt : o), null);
    return [
      { key: 'you', title: CASE_GROUPS.needsYou, cases: by('you') },
      { key: 'motion', title: CASE_GROUPS.inProgress, note: `${CASE_GROUPS.inProgressNote}${oldest ? ` · oldest started ${relativeTime(oldest, now.getTime())}` : ''}`, cases: motion },
      { key: 'delivered', title: CASE_GROUPS.deliveredToday, note: CASE_GROUPS.deliveredTodayNote, cases: by('delivered') },
    ];
  }, [all, caps, code, compare, filter, now, query, sensitiveMatches]);

  // In progress and Exported today start as one line each; a filter text opens every group so a match is never hidden.
  const [opened, setOpened] = useState<Record<string, boolean>>({});
  const isOpen = (key: string) => key === 'you' || key === 'stage' || key === 'code' || Boolean(query.trim()) || Boolean(opened[key]);

  // Task 0076: bulk approval, only in the Approval stage.
  const approvable = useMemo(() => (filter === 'approval' ? groups.flatMap((g) => g.cases) : []).filter((p) => p.status === 'PENDING_FINAL_APPROVAL' && !isExporting(exportStateOf(p.exportState))), [filter, groups]);
  const bulk = useBulkApprove(filter === 'approval' && caps.includes(CAP.approve), approvable);

  // Task 0111: every group has its true count; it shows 25 cases, then 25 more each time "Show more" is pressed.
  const [shown, setShown] = useState<Record<string, number>>({});
  const listed = useMemo(() => groups.map((g) => {
    const known = seen ? g.cases.filter((p) => seen.has(p.packageId)) : g.cases;
    return { ...g, known, rows: isOpen(g.key) ? known.slice(0, shown[g.key] ?? GROUP_PAGE) : [] };
  }), [groups, seen, shown, opened, query]); // eslint-disable-line react-hooks/exhaustive-deps
  const flat = useMemo(() => listed.flatMap((g) => g.rows), [listed]);
  // The pill counts only new cases that belong in what is open now.
  const waitingNew = useMemo(() => (seen ? groups.filter((g) => isOpen(g.key)).flatMap((g) => g.cases).filter((p) => !seen.has(p.packageId)) : []),
    [groups, seen, opened, query]); // eslint-disable-line react-hooks/exhaustive-deps

  function showNew() {
    const ids = waitingNew.map((p) => p.packageId);
    setSeen((s) => new Set([...(s ?? []), ...ids]));
    setFresh(new Set(ids));
    // The first new row in sort order, once it is drawn.
    requestAnimationFrame(() => {
      const first = flat.concat(waitingNew).find((p) => ids.includes(p.packageId)) ?? waitingNew[0];
      if (first) document.getElementById(`cf-row-${first.packageId}`)?.scrollIntoView({ block: 'center', behavior: 'smooth' });
    });
    window.setTimeout(() => setFresh(new Set()), 2000);
  }

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement;
      if (event.metaKey || event.ctrlKey || event.altKey) return;
      if (target.closest(TYPING_OR_DIALOG)) {
        if (event.key === 'Escape' && target === searchRef.current) searchRef.current?.blur();
        return;
      }
      // U028 (WCAG 2.1.4): "/" works only while focus is in the case list, like J and K; it moves focus to the filter.
      if (event.key === '/' && target.closest(CASES_KEY_ZONE)) { event.preventDefault(); searchRef.current?.focus(); return; }
      if (event.key === 'Escape' && caseId) { navigate('/'); return; }
      // U027 (WCAG 2.1.4): J and K work only while focus is in the case list; focus moves with them.
      if ((event.key === 'j' || event.key === 'k') && target.closest(CASES_KEY_ZONE)) {
        if (flat.length === 0) return;
        event.preventDefault();
        const index = flat.findIndex((p) => p.packageId === caseId);
        const next = event.key === 'j' ? Math.min(index + 1, flat.length - 1) : Math.max(index - 1, 0);
        const chosen = flat[index === -1 ? 0 : next];
        navigate(`/case/${chosen.packageId}`);
        const row = document.getElementById(`cf-row-${chosen.packageId}`);
        row?.querySelector<HTMLElement>('a.cf-case')?.focus({ preventScroll: true });
        row?.scrollIntoView({ block: 'nearest' });
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [caseId, flat, navigate]);

  // Task 0108: a click on empty page space closes the case card (rows, buttons and fields keep their own meaning).
  useEffect(() => {
    if (!caseId) return undefined;
    const onDown = (event: MouseEvent) => {
      const target = event.target as HTMLElement | null;
      if (!target || !target.isConnected || event.button !== 0) return;
      if (target.closest('.cf-drawer, a, button, input, select, textarea, label, summary, [role="dialog"], [role="menu"], [role="group"], .cf-top, .cf-palette-scrim')) return;
      if (!target.closest('#cf-main, .cf-desk')) return;
      navigate('/');
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [caseId, navigate]);

  const youGroup = listed.find((g) => g.key === 'you');
  const stageGroup = listed.find((g) => g.key === 'stage');
  const sortText = `${sortLabel(viewSettings.sort, fieldLabel)}, ${orderWords(viewSettings.sort)[viewSettings.order].toLowerCase()}`;
  const canSupport = caps.includes(CAP.technicalView);

  return (
    <div className={`cf-desk${caseId ? ' has-drawer' : ''}`} data-density={viewSettings.density}>
      <section className="cf-desk-main" aria-labelledby="cf-desk-title">
        <header className="cf-page-head">
          <h1 id="cf-desk-title" className="cf-title">{NAV.cases}</h1>
          {caps.includes(CAP.intake) && (
            <Link to="/intake" className="cf-btn" data-variant="solid">
              <Plus size={16} aria-hidden="true" /> New case
            </Link>
          )}
        </header>

        <StageBoxes counts={counts} active={filter} canSupport={canSupport} onSelect={(f) => { setCode(null); setFilter((cur) => (cur === f ? 'all' : f)); }} />

        <div className="cf-desk-tools">
          <p className="cf-desk-heading" aria-live="polite">
            {code ? (
              <span className="cf-desk-code">
                Only cases with <LvText>{lvLabel(code)}</LvText>
                <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" onClick={() => setCode(null)}>Show all cases</button>
              </span>
            ) : filter !== 'all' ? (
              <>
                <b>{stageGroup?.title}{filter === 'delivered' ? ' today' : ''}</b>{' '}
                <span className="cf-group-count" data-testid="stage-count">{(stageGroup?.cases.length ?? 0).toLocaleString()}</span>{' '}
                <small className="cf-group-note">{STAGE_HELP[filter]}</small>
                <button type="button" className="cf-plain-link" onClick={() => setFilter('all')}>{CASE_GROUPS.showAll}</button>
              </>
            ) : (
              <>
                <b className="cf-mark-text" id="cf-group-you">{CASE_GROUPS.needsYou}</b>{' '}
                <span className="cf-group-count" data-testid="needs-you-count">{(youGroup?.cases.length ?? 0).toLocaleString()}</span>{' '}
                <small className="cf-group-sort" data-testid="sorted-you">sorted by {sortText}</small>
              </>
            )}
          </p>
          <label className="cf-search cf-search-cases">
            <Search size={15} aria-hidden="true" />
            <span className="cf-sr">Filter cases</span>
            <input ref={searchRef} value={query} onChange={(e) => setQuery(e.target.value)} placeholder={placeholder} />
            {query ? (
              <button type="button" className="cf-search-clear" onClick={() => setQuery('')} aria-label="Clear filter"><X size={14} /></button>
            ) : null}
          </label>
          <CaseViewMenu view={caseView.view} settings={viewSettings} fieldLabel={fieldLabel} onSave={caseView.save} />
        </div>

        {error && !packages && <LoadErrorCard title="Your cases couldn't be loaded" error={error} request="GET /packages" onRetry={() => void mutate()} />}
        {error && packages && <Notice tone="danger">Cases couldn&apos;t be refreshed. They&apos;ll refresh automatically.</Notice>}
        {isLoading && <div className="cf-center"><Spinner label="Loading cases" /></div>}

        {waitingNew.length > 0 && (
          <div className="cf-pill-row">
            <button type="button" className="cf-new-pill" onClick={showNew} data-testid="new-cases-pill">
              <span className="cf-new-pill-dot" aria-hidden="true" />{CASE_GROUPS.newCases(waitingNew.length)}
            </button>
          </div>
        )}

        {bulk.selection && <BulkApproveBar selection={bulk.selection} waiting={approvable} onDone={bulk.clear} />}

        {!isLoading && packages && listed.map((group) => {
          const open = isOpen(group.key);
          const collapsible = group.key === 'motion' || group.key === 'delivered';
          return (
            <section key={group.key} className="cf-group" aria-labelledby={`cf-group-${group.key}`} data-group={group.key} data-open={open || undefined}>
              {collapsible && (
                <button type="button" className="cf-group-line" aria-expanded={open} aria-controls={`cf-group-list-${group.key}`}
                  onClick={() => setOpened((o) => ({ ...o, [group.key]: !open }))} data-testid={`group-${group.key}`}>
                  <ChevronRight size={14} aria-hidden="true" className="cf-group-chevron" />
                  <b id={`cf-group-${group.key}`}>{group.title}</b>
                  <span className="cf-group-count">{group.cases.length.toLocaleString()}</span>
                  {group.note && <small className="cf-group-note">{group.note}</small>}
                  <span className="cf-group-more">{open ? CASE_GROUPS.hide : CASE_GROUPS.show}</span>
                </button>
              )}
              {!collapsible && group.key !== 'you' && <h2 id={`cf-group-${group.key}`} className="cf-sr">{group.title}</h2>}
              {group.key === 'you' && <h2 className="cf-sr">{CASE_GROUPS.needsYou}</h2>}
              {open && group.rows.length === 0 && group.known.length === 0 && !collapsible && (
                <div className="cf-empty">
                  <span className="cf-empty-bars" aria-hidden="true"><span /><span /><span /></span>
                  <p className="cf-empty-title">{query ? `No case matches “${query}”` : group.key === 'you' ? 'Nothing needs you right now' : 'No cases here yet'}</p>
                  <p className="cf-empty-text">{group.key === 'you' ? 'New review and approval work will appear here as it arrives.' : 'Try a different filter.'}</p>
                </div>
              )}
              {open && group.rows.length > 0 && (
                <ul className="cf-cases" id={`cf-group-list-${group.key}`}>
                  {group.rows.map((pkg) => (
                    <CaseRow key={pkg.packageId} pkg={pkg} caps={caps} columns={columns} selected={pkg.packageId === caseId}
                      fresh={fresh.has(pkg.packageId)} waiting={group.key === 'you' || needsYou(pkg, caps) ? waiting : null} now={now.getTime()}
                      bulk={bulk.selection && approvable.includes(pkg) ? bulk.selection : null} />
                  ))}
                </ul>
              )}
              {open && group.rows.length < group.known.length && (
                <button type="button" className="cf-show-more" data-testid={`show-more-${group.key}`}
                  onClick={() => setShown((s) => ({ ...s, [group.key]: group.rows.length + GROUP_PAGE }))}>
                  {CASE_GROUPS.showMore(Math.min(GROUP_PAGE, group.known.length - group.rows.length), group.known.length - group.rows.length)}
                </button>
              )}
            </section>
          );
        })}

        {flat.length > 0 && (
          <p className="cf-hint"><Kbd>J</Kbd><Kbd>K</Kbd> move between cases · <Kbd>Esc</Kbd> close</p>
        )}
      </section>

      {caseId && (
        <CaseDrawer key={caseId} packageId={caseId} caps={caps} openFindings={findings.get(caseId) ?? []} onClose={() => navigate('/')} />
      )}
    </div>
  );
}

/**
 * Task 0108: six slim stage boxes (label, then the number with commas). Choosing one lists its cases; choosing it
 * again shows all. The sentence shows on hover. Stopped cases are IT's (Support): for IT the box opens Support; for
 * everyone else it only counts.
 */
function StageBoxes({ counts, active, canSupport, onSelect }: {
  counts: Map<Box, number>; active: Filter; canSupport: boolean; onSelect: (f: Box) => void;
}) {
  return (
    <div className="cf-band" role="group" aria-label="Cases by lifecycle stage">
      {BOXES.map((key) => {
        const count = (counts.get(key) ?? 0).toLocaleString();
        const label = STAGES.find((s) => s.key === key)?.label;
        const inner = (
          <>
            <span className="cf-band-label">
              <span className="cf-band-dot" aria-hidden="true" />{label}
              {key === 'delivered' && <span className="cf-sr"> today</span>}
              {key === 'delivered' && (
                <span className="cf-band-info" aria-hidden="true">i</span>
              )}
            </span>
            <span className="cf-band-count">{count}</span>
            <span className="cf-band-tip" role="tooltip">{key === 'delivered' ? CASE_GROUPS.deliveredToday : STAGE_HELP[key]}</span>
          </>
        );
        if (key === 'stopped') {
          return canSupport ? (
            <Link key={key} to="/support/incidents" className="cf-band-seg" data-stage={key}>{inner}</Link>
          ) : (
            <div key={key} className="cf-band-seg" data-stage={key} data-static="" role="note" aria-label={`${label}: ${count}. ${STAGE_HELP.stopped}`}>{inner}</div>
          );
        }
        return (
          <button key={key} type="button" className="cf-band-seg" data-stage={key} aria-pressed={active === key} onClick={() => onSelect(key)}>
            {inner}
          </button>
        );
      })}
    </div>
  );
}

/**
 * One case. The whole row opens the case card; rows carry no next-step button and no next-step text (owner,
 * 2026-11-26): the card shows them. The reason a case stopped or waits and its destination stay (design chat 21:15).
 */
function CaseRow({ pkg, caps, columns, selected, fresh, waiting, now, bulk }: {
  pkg: PackageSummary; caps: string[]; columns: string[]; selected: boolean; fresh: boolean;
  waiting: { amberMinutes: number; redMinutes: number } | null; now: number; bulk: BulkSelection | null;
}) {
  // Task 0108: the second line shows the case fields the person chose in View > Columns (by default every displayable one).
  const title = caseTitle(pkg.businessIdentifiers.filter((i) => i.primary || columns.includes(i.key)));
  const stage = stageOf(pkg.status);
  const you = needsYou(pkg, caps);
  const exportState = exportStateOf(pkg.exportState);
  const exportLabel = exportStatusLabel(exportState);
  const reason = rowReason(pkg);
  const destination = rowDestination(pkg);
  const priority = priorityName(pkg.priority);
  const sendFailed = exportState === 'WAITING_FOR_YOU' || exportState === 'NEEDS_IT';
  const mins = Math.floor((now - new Date(pkg.updatedAt).getTime()) / 60000);
  const wait = waiting && you ? (mins >= waiting.redMinutes ? 'over' : mins >= waiting.amberMinutes ? 'late' : undefined) : undefined;
  const second = [
    title.secondary,
    columns.includes('received') ? `received ${relativeTime(pkg.createdAt, now)}` : '',
    columns.includes('uploaded') && pkg.uploadedAt ? `uploaded ${relativeTime(pkg.uploadedAt, now)}` : '',
  ].filter(Boolean);
  return (
    <li id={`cf-row-${pkg.packageId}`} className="cf-case-row" data-selected={selected || undefined} data-you={you || undefined}
      data-bulk={bulk ? '' : undefined} data-fresh={fresh || undefined}>
      {bulk && (
        <label className="cf-case-tick" title={bulk.blocked(pkg.packageId) ?? undefined}>
          <input type="checkbox" aria-label={`Select ${title.primary}`} checked={bulk.selected.includes(pkg.packageId)}
            disabled={bulk.blocked(pkg.packageId) !== null} aria-describedby={bulk.blocked(pkg.packageId) ? `cf-tick-${pkg.packageId}` : undefined}
            onChange={() => bulk.toggle(pkg.packageId)} />
        </label>
      )}
      <Link to={`/case/${pkg.packageId}`} className="cf-case" aria-current={selected ? 'true' : undefined} data-you={you || undefined}>
        <span className="cf-case-flag" aria-hidden="true" />
        <span className="cf-case-id">
          <span className="cf-case-name">
            <span className="cf-case-primary cf-mono">{title.primary}</span>
            {priority === 'Urgent' && <span className="cf-tag" data-tag="urgent">{CASE_TAGS.urgent}</span>}
            {sendFailed && <span className="cf-tag" data-tag="failed">{CASE_TAGS.sendFailed}</span>}
          </span>
          <span className="cf-case-secondary">{second.length ? second.join(' · ') : '—'}</span>
        </span>
        {/* The source slot stays when Source is not chosen, so the columns stay in line. */}
        <span className="cf-case-source">{columns.includes('source') ? SOURCE_LABEL[pkg.packageType] ?? pkg.packageType : null}</span>
        <span className="cf-case-docs">{pkg.documentCount} {pkg.documentCount === 1 ? 'doc' : 'docs'}</span>
        <span className="cf-case-next" data-stage={stage}>
          {priority !== 'Normal' && priority !== 'Urgent' && <span className="cf-case-why">{priority}</span>}
          {/* Task 0038: why it stopped or waits, and where it goes; codes and the destination's name only. */}
          {reason && <span className="cf-case-why">{reason}</span>}
          {destination && <span className="cf-case-to">{destination}</span>}
        </span>
        <span className="cf-case-stage">
          {exportLabel ? <ExportChip state={exportState} /> : <StageChip stage={stage} status={pkg.status} />}
        </span>
        <time className="cf-case-time" dateTime={pkg.updatedAt} data-wait={wait}
          title={wait ? `Waiting longer than ${hoursText(wait === 'over' ? waiting!.redMinutes : waiting!.amberMinutes)}` : undefined}>
          {you ? waited(pkg.updatedAt, now) : relativeTime(pkg.updatedAt, now)}
          {wait && <span className="cf-sr">, waiting longer than {hoursText(wait === 'over' ? waiting!.redMinutes : waiting!.amberMinutes)}</span>}
        </time>
      </Link>
      {bulk?.blocked(pkg.packageId) && <span id={`cf-tick-${pkg.packageId}`} className="cf-tick-why">{bulk.blocked(pkg.packageId)}</span>}
    </li>
  );
}
