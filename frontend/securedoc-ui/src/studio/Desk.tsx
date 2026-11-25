import { useEffect, useLayoutEffect, useMemo, useRef, useState, type RefObject } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { Plus, Search, X } from 'lucide-react';
import type { PackageSummary } from '../types';
import {
  CAP, CASE_TILES, SOURCE_LABEL, STAGES, caseTitle, needsYou, openFindingsByPackage, relativeTime, stageLabel, stageOf, tileOf,
  useCaseFields, usePackages, useReviewQueue, type StageKey,
} from './data';
import { CASE_GROUPS, NAV, STAGE_NAMES, caseSearchPlaceholders, firstThatFits, priorityName, priorityRank } from './words';
import { ExportChip, Kbd, Notice, Spinner, StageChip } from './atoms';
import { exportNextStep, exportStateOf, exportStatusLabel, isExporting } from './exportRules';
import CaseDrawer from './CaseDrawer';
import { BulkApproveBar, useBulkApprove, type BulkSelection } from './BulkApprove';
import { LoadErrorCard } from './failure';
import { apiClient } from '../api/client';
import { nextStepText, rowAction, rowDestination, rowReason, type RowAction } from './caseRules';
import { CONFIRM_COPY, useCaseActionRunner } from './caseActionRunner';
import { ConfirmDialog } from './admin/shared';
import { CASES_KEY_ZONE, TYPING_OR_DIALOG } from './shortcuts';

type Filter = 'all' | 'you' | StageKey;

interface Group { key: string; title: string; note?: string; cases: PackageSummary[] }

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

export default function Desk({ caps }: { caps: string[] }) {
  const { caseId } = useParams();
  const navigate = useNavigate();
  const searchRef = useRef<HTMLInputElement>(null);
  const [searchParams] = useSearchParams();
  const [filter, setFilter] = useState<Filter>(() => {
    const requested = searchParams.get('stage');
    return requested && (requested === 'you' || STAGES.some((s) => s.key === requested)) ? (requested as Filter) : 'all';
  });
  const [query, setQuery] = useState('');

  const { data: packages, error, isLoading, mutate } = usePackages(true);
  const fields = useCaseFields(caps, packages);
  const placeholder = usePlaceholderThatFits(searchRef, caseSearchPlaceholders(fields));
  const { data: queue } = useReviewQueue(caps.includes(CAP.review));
  const findings = useMemo(() => openFindingsByPackage(queue?.items), [queue]);

  const all = packages ?? [];
  const counts = useMemo(() => {
    const byStage = new Map<StageKey, number>();
    for (const p of all) byStage.set(tileOf(p), (byStage.get(tileOf(p)) ?? 0) + 1);
    return { byStage, you: all.filter((p) => needsYou(p, caps)).length };
  }, [all, caps]);

  const groups = useMemo<Group[]>(() => {
    const q = query.trim().toLowerCase();
    const matches = all
      .filter((p) => !q || p.businessIdentifiers.some((i) => i.value.toLowerCase().includes(q)))
      .filter((p) => filter === 'all' || (filter === 'you' ? needsYou(p, caps) : tileOf(p) === filter))
      .sort((a, b) => {
        // Task 0073: what waits for a person is Urgent, Normal, Low, the oldest first; the rest is newest first.
        const [youA, youB] = [needsYou(a, caps), needsYou(b, caps)];
        if (youA !== youB) return youA ? -1 : 1;
        if (youA) return priorityRank(a.priority) - priorityRank(b.priority) || +new Date(a.createdAt) - +new Date(b.createdAt);
        return +new Date(b.updatedAt) - +new Date(a.updatedAt);
      });

    if (filter !== 'all') {
      return [{ key: filter, title: filter === 'you' ? CASE_GROUPS.needsYou : stageLabel(filter), cases: matches }];
    }
    const you = matches.filter((p) => needsYou(p, caps));
    const rest = matches.filter((p) => !needsYou(p, caps));
    return [
      { key: 'you', title: CASE_GROUPS.needsYou, note: 'You can move these forward now', cases: you },
      { key: 'motion', title: CASE_GROUPS.inProgress, note: CASE_GROUPS.inProgressNote, cases: rest.filter((p) => !['delivered', 'stopped'].includes(tileOf(p))) },
      { key: 'stopped', title: CASE_GROUPS.stopped, cases: rest.filter((p) => tileOf(p) === 'stopped') },
      { key: 'delivered', title: CASE_GROUPS.delivered, cases: rest.filter((p) => tileOf(p) === 'delivered') },
    ].filter((g) => g.cases.length > 0);
  }, [all, caps, filter, query]);

  // Task 0076: bulk approval, only in the Approval stage.
  const waiting = useMemo(() => (filter === 'approval' ? groups.flatMap((g) => g.cases) : []).filter((p) => p.status === 'PENDING_FINAL_APPROVAL' && !isExporting(exportStateOf(p.exportState))), [filter, groups]);
  const bulk = useBulkApprove(filter === 'approval' && caps.includes(CAP.approve), waiting);

  const flat = useMemo(() => groups.flatMap((g) => g.cases), [groups]);

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

  const total = all.length;
  const deliveredCount = counts.byStage.get('delivered') ?? 0;

  return (
    <div className={`cf-desk${caseId ? ' has-drawer' : ''}`}>
      <section className="cf-desk-main" aria-labelledby="cf-desk-title">
        <header className="cf-page-head">
          <div>
            <h1 id="cf-desk-title" className="cf-title">{NAV.cases}</h1>
            <p className="cf-lede">
              {isLoading ? 'Gathering your cases…' : (
                <>
                  {counts.you > 0
                    ? <><mark className="cf-mark-text">{counts.you} {counts.you === 1 ? 'case needs' : 'cases need'} you</mark>{' · '}</>
                    : 'Nothing is waiting on you · '}
                  {total - deliveredCount} open · {deliveredCount} {STAGE_NAMES.delivered.toLowerCase()}
                </>
              )}
            </p>
          </div>
          {caps.includes(CAP.intake) && (
            <Link to="/intake" className="cf-btn" data-variant="solid">
              <Plus size={16} aria-hidden="true" /> New case
            </Link>
          )}
        </header>

        <LifecycleBand counts={counts.byStage} active={filter} onSelect={(f) => setFilter((cur) => (cur === f ? 'all' : f))} />

        <div className="cf-desk-tools">
          <div className="cf-segment" role="group" aria-label="Show">
            <button type="button" aria-pressed={filter === 'all'} onClick={() => setFilter('all')}>All <span>{total}</span></button>
            <button type="button" aria-pressed={filter === 'you'} onClick={() => setFilter('you')} data-tone="mark">{CASE_GROUPS.needsYou} <span>{counts.you}</span></button>
          </div>
          <label className="cf-search cf-search-cases">
            <Search size={15} aria-hidden="true" />
            <span className="cf-sr">Filter cases</span>
            <input ref={searchRef} value={query} onChange={(e) => setQuery(e.target.value)} placeholder={placeholder} />
            {query ? (
              <button type="button" className="cf-search-clear" onClick={() => setQuery('')} aria-label="Clear filter"><X size={14} /></button>
            ) : null}
          </label>
        </div>

        {error && !packages && <LoadErrorCard title="Your cases couldn't be loaded" error={error} request="GET /packages" onRetry={() => void mutate()} />}
        {error && packages && <Notice tone="danger">Cases couldn&apos;t be refreshed. They&apos;ll refresh automatically.</Notice>}
        {isLoading && <div className="cf-center"><Spinner label="Loading cases" /></div>}

        {!isLoading && flat.length === 0 && (
          <div className="cf-empty">
            <span className="cf-empty-bars" aria-hidden="true"><span /><span /><span /></span>
            <p className="cf-empty-title">{query ? `No case matches “${query}”` : filter === 'you' ? 'Nothing needs you right now' : 'No cases here yet'}</p>
            <p className="cf-empty-text">{filter === 'you' ? 'New review and approval work will appear here as it arrives.' : 'Try a different filter.'}</p>
          </div>
        )}

        {bulk.selection && <BulkApproveBar selection={bulk.selection} waiting={waiting} onDone={bulk.clear} />}

        {groups.map((group) => (
          <section key={group.key} className="cf-group" aria-labelledby={`cf-group-${group.key}`} data-group={group.key}>
            <header className="cf-group-head">
              <h2 id={`cf-group-${group.key}`}>{group.title}</h2>
              <span className="cf-group-count">{group.cases.length}</span>
              {group.note && <span className="cf-group-note">{group.note}</span>}
            </header>
            <ul className="cf-cases">
              {group.cases.map((pkg) => (
                <CaseRow key={pkg.packageId} pkg={pkg} caps={caps} open={findings.get(pkg.packageId)?.length ?? 0} selected={pkg.packageId === caseId}
                  bulk={bulk.selection && waiting.includes(pkg) ? bulk.selection : null} />
              ))}
            </ul>
          </section>
        ))}

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

function LifecycleBand({ counts, active, onSelect }: {
  counts: Map<StageKey, number>; active: Filter; onSelect: (f: StageKey) => void;
}) {
  const stages = CASE_TILES;
  return (
    <div className="cf-band" role="group" aria-label="Cases by lifecycle stage">
      {stages.map((key) => {
        const count = counts.get(key) ?? 0;
        return (
          <button
            key={key}
            type="button"
            className="cf-band-seg"
            data-stage={key}
            aria-pressed={active === key}
            onClick={() => onSelect(key)}
          >
            <span className="cf-band-label"><span className="cf-band-dot" aria-hidden="true" />{STAGES.find((s) => s.key === key)?.label}</span>
            <span className="cf-band-count">{count}</span>
          </button>
        );
      })}
    </div>
  );
}

/**
 * One case: the row opens the case panel; the action button at the end (only when this person can act) runs the
 * action with the same confirmation as the case panel and never also opens the panel (UI spec findings 2, 3).
 */
function CaseRow({ pkg, caps, open, selected, bulk }: { pkg: PackageSummary; caps: string[]; open: number; selected: boolean; bulk: BulkSelection | null }) {
  const title = caseTitle(pkg.businessIdentifiers);
  const stage = stageOf(pkg.status);
  const you = needsYou(pkg, caps);
  // Task 0029: a case with an open export job shows "Exporting · …" instead of its review status, and offers no row
  // action (a refused case is resent from its panel or from Waiting for you).
  const exportState = exportStateOf(pkg.exportState);
  const exporting = isExporting(exportState);
  const exportLabel = exportStatusLabel(exportState);
  const action = exporting ? null : rowAction(pkg.status, caps, open);
  const next = exportNextStep(exportState) ?? nextStepText(pkg.status, caps.includes(CAP.review) ? open : undefined);
  const reason = rowReason(pkg);
  const destination = rowDestination(pkg);
  return (
    <li id={`cf-row-${pkg.packageId}`} className="cf-case-row" data-selected={selected || undefined} data-you={you || undefined} data-bulk={bulk ? '' : undefined}>
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
          <span className="cf-case-primary cf-mono">{title.primary}</span>
          <span className="cf-case-secondary">{title.secondary || '—'}</span>
        </span>
        <span className="cf-case-source">{SOURCE_LABEL[pkg.packageType] ?? pkg.packageType}</span>
        <span className="cf-case-docs">{pkg.documentCount} {pkg.documentCount === 1 ? 'doc' : 'docs'}</span>
        <span className="cf-case-next" data-stage={stage}>
          {next}
          {priorityName(pkg.priority) !== 'Normal' && <span className="cf-case-why">{priorityName(pkg.priority)}</span>}
          {/* Task 0038: why it stopped or waits, and where it goes; codes and the destination's name only. */}
          {reason && <span className="cf-case-why">{reason}</span>}
          {destination && <span className="cf-case-to">{destination}</span>}
        </span>
        <span className="cf-case-stage">
          {exportLabel ? <ExportChip state={exportState} /> : <StageChip stage={stage} status={pkg.status} />}
        </span>
        <time className="cf-case-time" dateTime={pkg.updatedAt}>{relativeTime(pkg.updatedAt)}</time>
      </Link>
      <span className="cf-case-act">{action && <RowActionButton pkg={pkg} action={action} caseLabel={title.primary} />}</span>
      {bulk?.blocked(pkg.packageId) && <span id={`cf-tick-${pkg.packageId}`} className="cf-tick-why">{bulk.blocked(pkg.packageId)}</span>}
    </li>
  );
}

function RowActionButton({ pkg, action, caseLabel }: { pkg: PackageSummary; action: RowAction; caseLabel: string }) {
  const navigate = useNavigate();
  const runner = useCaseActionRunner(pkg.packageId);
  const [confirming, setConfirming] = useState(false);
  const [checking, setChecking] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);
  const copy = CONFIRM_COPY[action.kind];

  async function start() {
    runner.clearError();
    setRefusal(null);
    if (action.kind === 'review') { navigate(`/review?case=${pkg.packageId}`); return; }
    if (action.kind === 'manual-fallback') {
      // The same eligibility check the case panel makes before it offers Continue manually.
      setChecking(true);
      try {
        const eligibility = await apiClient.getManualFallbackEligibility(pkg.packageId);
        if (!eligibility.eligible) setRefusal(`Continuing manually is not possible: ${eligibility.reason}`);
      } catch {
        setRefusal('Whether this case can be continued manually could not be checked. Open the case for details.');
      } finally {
        setChecking(false);
      }
    }
    setConfirming(true);
  }

  return (
    <>
      <button type="button" className="cf-btn" data-size="sm" data-variant={action.kind === 'review' ? 'mark' : action.kind === 'approve' ? 'solid' : undefined}
        aria-label={`${action.label} ${caseLabel}`} disabled={checking || runner.busy !== null} onClick={start}>
        {checking && <Spinner label="Checking" />}{action.label}
      </button>
      {copy && (
        <ConfirmDialog
          open={confirming}
          title={refusal ? 'This case cannot be continued manually' : copy.title}
          facts={[['Case', <span className="cf-mono" key="c">{caseLabel}</span>]]}
          consequence={refusal ?? copy.body}
          confirmLabel={copy.confirm}
          busy={runner.busy !== null}
          error={runner.error}
          onCancel={() => { setConfirming(false); runner.clearError(); }}
          onConfirm={async () => {
            if (refusal) { setConfirming(false); return; }
            if (await runner.run(action.kind)) setConfirming(false);
          }}
        />
      )}
    </>
  );
}
