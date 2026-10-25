import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { Plus, Search, X } from 'lucide-react';
import type { PackageSummary } from '../types';
import {
  CAP, LIFECYCLE, SOURCE_LABEL, STAGES, caseTitle, needsYou, openFindingsByPackage, relativeTime, stageLabel, stageOf,
  usePackages, useReviewQueue, type StageKey,
} from './data';
import { FindingStrip, Kbd, Notice, Spinner, StageChip } from './atoms';
import CaseDrawer from './CaseDrawer';

type Filter = 'all' | 'you' | StageKey;

interface Group { key: string; title: string; note?: string; cases: PackageSummary[] }

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

  const { data: packages, error, isLoading } = usePackages(true);
  const { data: queue } = useReviewQueue(caps.includes(CAP.review));
  const findings = useMemo(() => openFindingsByPackage(queue?.items), [queue]);

  const all = packages ?? [];
  const counts = useMemo(() => {
    const byStage = new Map<StageKey, number>();
    for (const p of all) byStage.set(stageOf(p.status), (byStage.get(stageOf(p.status)) ?? 0) + 1);
    return { byStage, you: all.filter((p) => needsYou(p, caps)).length };
  }, [all, caps]);

  const groups = useMemo<Group[]>(() => {
    const q = query.trim().toLowerCase();
    const matches = all
      .filter((p) => !q || p.businessIdentifiers.some((i) => i.value.toLowerCase().includes(q)))
      .filter((p) => filter === 'all' || (filter === 'you' ? needsYou(p, caps) : stageOf(p.status) === filter))
      .sort((a, b) => +new Date(b.updatedAt) - +new Date(a.updatedAt));

    if (filter !== 'all') {
      return [{ key: filter, title: filter === 'you' ? 'Needs you' : stageLabel(filter), cases: matches }];
    }
    const you = matches.filter((p) => needsYou(p, caps));
    const rest = matches.filter((p) => !needsYou(p, caps));
    return [
      { key: 'you', title: 'Needs you', note: 'You can move these forward now', cases: you },
      { key: 'motion', title: 'In motion', note: 'LexaVault or a colleague has these', cases: rest.filter((p) => !['delivered', 'stopped'].includes(stageOf(p.status))) },
      { key: 'stopped', title: 'Stopped', cases: rest.filter((p) => stageOf(p.status) === 'stopped') },
      { key: 'delivered', title: 'Delivered', cases: rest.filter((p) => stageOf(p.status) === 'delivered') },
    ].filter((g) => g.cases.length > 0);
  }, [all, caps, filter, query]);

  const flat = useMemo(() => groups.flatMap((g) => g.cases), [groups]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement;
      if (event.metaKey || event.ctrlKey || event.altKey) return;
      if (target.closest('input, textarea, select, [role="dialog"]')) {
        if (event.key === 'Escape' && target === searchRef.current) searchRef.current?.blur();
        return;
      }
      if (event.key === '/') { event.preventDefault(); searchRef.current?.focus(); return; }
      if (event.key === 'Escape' && caseId) { navigate('/'); return; }
      if (event.key === 'j' || event.key === 'k') {
        if (flat.length === 0) return;
        const index = flat.findIndex((p) => p.packageId === caseId);
        const next = event.key === 'j' ? Math.min(index + 1, flat.length - 1) : Math.max(index - 1, 0);
        const target = flat[index === -1 ? 0 : next];
        navigate(`/case/${target.packageId}`);
        document.getElementById(`cf-row-${target.packageId}`)?.scrollIntoView({ block: 'nearest' });
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
            <h1 id="cf-desk-title" className="cf-title">Desk</h1>
            <p className="cf-lede">
              {isLoading ? 'Gathering your cases…' : (
                <>
                  {counts.you > 0
                    ? <><mark className="cf-mark-text">{counts.you} {counts.you === 1 ? 'case needs' : 'cases need'} you</mark>{' · '}</>
                    : 'Nothing is waiting on you · '}
                  {total - deliveredCount} open · {deliveredCount} delivered
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

        <LifecycleBand counts={counts.byStage} total={total} active={filter} onSelect={(f) => setFilter((cur) => (cur === f ? 'all' : f))} />

        <div className="cf-desk-tools">
          <div className="cf-segment" role="group" aria-label="Show">
            <button type="button" aria-pressed={filter === 'all'} onClick={() => setFilter('all')}>All <span>{total}</span></button>
            <button type="button" aria-pressed={filter === 'you'} onClick={() => setFilter('you')} data-tone="mark">Needs you <span>{counts.you}</span></button>
          </div>
          <label className="cf-search">
            <Search size={15} aria-hidden="true" />
            <span className="cf-sr">Filter cases</span>
            <input ref={searchRef} value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Filter by contract or borrower" />
            {query ? (
              <button type="button" className="cf-search-clear" onClick={() => setQuery('')} aria-label="Clear filter"><X size={14} /></button>
            ) : <Kbd>/</Kbd>}
          </label>
        </div>

        {error && <Notice tone="danger">Cases couldn&apos;t be loaded. They&apos;ll refresh automatically.</Notice>}
        {isLoading && <div className="cf-center"><Spinner label="Loading cases" /></div>}

        {!isLoading && flat.length === 0 && (
          <div className="cf-empty">
            <span className="cf-empty-bars" aria-hidden="true"><span /><span /><span /></span>
            <p className="cf-empty-title">{query ? `No case matches “${query}”` : filter === 'you' ? 'Nothing needs you right now' : 'No cases here yet'}</p>
            <p className="cf-empty-text">{filter === 'you' ? 'New review and approval work will appear here as it arrives.' : 'Try a different filter.'}</p>
          </div>
        )}

        {groups.map((group) => (
          <section key={group.key} className="cf-group" aria-labelledby={`cf-group-${group.key}`} data-group={group.key}>
            <header className="cf-group-head">
              <h2 id={`cf-group-${group.key}`}>{group.title}</h2>
              <span className="cf-group-count">{group.cases.length}</span>
              {group.note && <span className="cf-group-note">{group.note}</span>}
            </header>
            <ul className="cf-cases">
              {group.cases.map((pkg) => (
                <CaseRow key={pkg.packageId} pkg={pkg} caps={caps} open={findings.get(pkg.packageId)?.length ?? 0} selected={pkg.packageId === caseId} />
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

function LifecycleBand({ counts, total, active, onSelect }: {
  counts: Map<StageKey, number>; total: number; active: Filter; onSelect: (f: StageKey) => void;
}) {
  const stages = [...LIFECYCLE, 'stopped' as StageKey];
  return (
    <div className="cf-band" role="group" aria-label="Cases by lifecycle stage">
      {stages.map((key) => {
        const count = counts.get(key) ?? 0;
        const share = total ? Math.max(count / total, count ? 0.06 : 0.02) : 0.2;
        return (
          <button
            key={key}
            type="button"
            className="cf-band-seg"
            data-stage={key}
            aria-pressed={active === key}
            style={{ flexGrow: share }}
            onClick={() => onSelect(key)}
          >
            <span className="cf-band-fill" aria-hidden="true" />
            <span className="cf-band-label">{STAGES.find((s) => s.key === key)?.label}</span>
            <span className="cf-band-count">{count}</span>
          </button>
        );
      })}
    </div>
  );
}

function CaseRow({ pkg, caps, open, selected }: { pkg: PackageSummary; caps: string[]; open: number; selected: boolean }) {
  const title = caseTitle(pkg.businessIdentifiers);
  const stage = stageOf(pkg.status);
  const you = needsYou(pkg, caps);
  return (
    <li id={`cf-row-${pkg.packageId}`}>
      <Link to={`/case/${pkg.packageId}`} className="cf-case" aria-current={selected ? 'true' : undefined} data-you={you || undefined}>
        <span className="cf-case-flag" aria-hidden="true" />
        <span className="cf-case-id">
          <span className="cf-case-primary cf-mono">{title.primary}</span>
          <span className="cf-case-secondary">{title.secondary || '—'}</span>
        </span>
        <span className="cf-case-source">{SOURCE_LABEL[pkg.packageType] ?? pkg.packageType}</span>
        <span className="cf-case-docs">{pkg.documentCount} {pkg.documentCount === 1 ? 'doc' : 'docs'}</span>
        <span className="cf-case-strip"><FindingStrip open={open} stage={stage} /></span>
        <span className="cf-case-stage"><StageChip stage={stage} status={pkg.status} /></span>
        <time className="cf-case-time" dateTime={pkg.updatedAt}>{relativeTime(pkg.updatedAt)}</time>
      </Link>
    </li>
  );
}
