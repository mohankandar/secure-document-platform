import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { apiClient } from '../api/client';
import { hasCurrentCapability } from '../api/config';
import PageScaffold from '../components/ui/PageScaffold';
import useMediaQuery from '../components/ui/useMediaQuery';
import type {
  ApiError,
  DashboardColumnDefinition,
  DashboardItem,
  DashboardStage,
  OperationsDashboard,
} from '../types';
import StatusBadge from '../components/ui/StatusBadge';
import AttentionLink from '../components/ui/AttentionLink';
import Icon, { type IconName } from '../components/ui/Icon';
import './DashboardPage.css';

const OVERVIEW_KEY = 'securedoc.dashboard.overviewCollapsed.v1';
const STAGE_KEY = 'securedoc.dashboard.stage.v1';
const SEARCH_KEY = 'securedoc.dashboard.search.v1';
const COLUMNS_KEY = 'securedoc.dashboard.columns.v2';
const TABLET_COLUMNS_KEY = 'securedoc.dashboard.tabletColumns.v2';
const SORT_KEY = 'securedoc.dashboard.sort.v1';

type StageDefinition = {
  key: DashboardStage;
  label: string;
  countKey: keyof OperationsDashboard['overview'];
  tone: 'neutral' | 'info' | 'warning' | 'violet' | 'success' | 'danger' | 'muted';
  help: string;
};

const STAGES: StageDefinition[] = [
  { key: 'ALL', label: 'All', countKey: 'total', tone: 'neutral', help: 'Every visible intake' },
  { key: 'PROCESSING', label: 'Processing', countKey: 'processing', tone: 'info', help: 'OCR and redaction' },
  { key: 'NEEDS_REVIEW', label: 'Needs Review', countKey: 'needsReview', tone: 'warning', help: 'Human decision' },
  { key: 'NEEDS_APPROVAL', label: 'Needs Approval', countKey: 'needsApproval', tone: 'violet', help: 'Final sign-off' },
  { key: 'EXPORTED', label: 'Exported', countKey: 'exported', tone: 'success', help: 'Delivered downstream' },
  { key: 'TECHNICAL_ATTENTION', label: 'Technical', countKey: 'technicalAttention', tone: 'danger', help: 'Processing or delivery' },
  { key: 'REJECTED', label: 'Rejected', countKey: 'rejected', tone: 'muted', help: 'Closed, not delivered' },
];

const PIPELINE_FLOW: DashboardStage[] = ['PROCESSING', 'NEEDS_REVIEW', 'NEEDS_APPROVAL', 'EXPORTED'];
const PIPELINE_EXCEPTIONS: DashboardStage[] = ['TECHNICAL_ATTENTION', 'REJECTED'];

function safeStoredStage(): DashboardStage {
  const requested = new URLSearchParams(window.location.search).get('stage') as DashboardStage | null;
  if (STAGES.some((stage) => stage.key === requested)) return requested!;
  const stored = localStorage.getItem(STAGE_KEY) as DashboardStage | null;
  return STAGES.some((stage) => stage.key === stored) ? stored! : 'ALL';
}

function stageLabel(stage: DashboardStage) {
  return STAGES.find((item) => item.key === stage)?.label ?? stage;
}

function sourceLabel(type: string) {
  switch (type) {
    case 'MANUAL_UPLOAD': return 'Upload';
    case 'API_SUBMISSION': return 'API';
    case 'FOLDER_INTAKE': return 'Watched folder';
    case 'SCANNER_INTAKE': return 'Scanner';
    default: return type.replace(/_/g, ' ').toLowerCase();
  }
}

function sourceIcon(type: string): IconName {
  switch (type) {
    case 'MANUAL_UPLOAD': return 'upload';
    case 'SCANNER_INTAKE': return 'scan';
    case 'API_SUBMISSION': return 'integrations';
    default: return 'inbox';
  }
}

function formatDate(value?: string | null, withTime = false) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return withTime
    ? date.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })
    : date.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
}

function relativeTime(value?: string | null) {
  if (!value) return '—';
  const time = new Date(value).getTime();
  if (Number.isNaN(time)) return '—';
  const minutes = Math.round((Date.now() - time) / 60000);
  if (minutes < 1) return 'Just now';
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.round(hours / 24);
  if (days < 7) return `${days}d ago`;
  return formatDate(value);
}

function getIdentifier(item: DashboardItem, key: string) {
  return item.identifiers.find((identifier) => identifier.key === key)?.value || '—';
}

function loadColumnPreference(storageKey = COLUMNS_KEY): string[] | null {
  try {
    const raw = localStorage.getItem(storageKey);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) && parsed.every((value) => typeof value === 'string') ? parsed : null;
  } catch {
    return null;
  }
}

function recommendedTabletColumns(columns: DashboardColumnDefinition[]) {
  const identifiers = columns.filter((column) => column.kind === 'IDENTIFIER');
  const primary = identifiers.find((column) => column.primary);
  const secondary = identifiers.find((column) => !column.primary && column.searchable);
  const preferred = [primary?.key, secondary?.key, 'stage', 'documents', 'intakeSource', 'lastUpdated']
    .filter((key): key is string => Boolean(key));
  const available = new Set(columns.map((column) => column.key));
  return Array.from(new Set(preferred.filter((key) => available.has(key))));
}

type NextAction = { key: string; tone: string; count: number; label: string; cta: string; to?: string; stage?: DashboardStage };

export default function DashboardPage() {
  const [data, setData] = useState<OperationsDashboard | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [lastRefreshedAt, setLastRefreshedAt] = useState<Date | null>(null);
  const [openActionPackageId, setOpenActionPackageId] = useState<string | null>(null);
  const [confirmHidePackageId, setConfirmHidePackageId] = useState<string | null>(null);
  const [hidingPackageId, setHidingPackageId] = useState<string | null>(null);
  const [stage, setStage] = useState<DashboardStage>(() => safeStoredStage());
  const [search, setSearch] = useState(() => localStorage.getItem(SEARCH_KEY) || '');
  const [searchInput, setSearchInput] = useState(search);
  const [offset, setOffset] = useState(0);
  const [limit, setLimit] = useState(25);
  const [sort, setSort] = useState<'receivedAt' | 'updatedAt' | 'stage'>(() => {
    const stored = localStorage.getItem(SORT_KEY);
    return stored === 'updatedAt' || stored === 'stage' ? stored : 'receivedAt';
  });
  const [overviewCollapsed, setOverviewCollapsed] = useState(() => localStorage.getItem(OVERVIEW_KEY) === 'true');
  const [columnsOpen, setColumnsOpen] = useState(false);
  const savedColumnPreference = useRef<string[] | null>(loadColumnPreference());
  const hasInitializedColumnPreference = useRef(savedColumnPreference.current !== null);
  const [columnOrder, setColumnOrder] = useState<string[]>(() => savedColumnPreference.current || []);
  const savedTabletColumnPreference = useRef<string[] | null>(loadColumnPreference(TABLET_COLUMNS_KEY));
  const hasInitializedTabletColumnPreference = useRef(savedTabletColumnPreference.current !== null);
  const [tabletColumnOrder, setTabletColumnOrder] = useState<string[]>(() => savedTabletColumnPreference.current || []);
  const tabletLandscape = useMediaQuery('(min-width: 900px) and (max-width: 1199px)');
  const requestId = useRef(0);
  const searchRef = useRef<HTMLInputElement>(null);

  const loadDashboard = useCallback(async (silent = false) => {
    const id = ++requestId.current;
    if (!silent) setLoading(true);
    setError(null);
    try {
      const result = await apiClient.getOperationsDashboard({ stage, search, offset, limit, sort, direction: 'desc' });
      if (id !== requestId.current) return;
      setData(result);
      setLastRefreshedAt(new Date());
      const available = new Set(result.columns.map((column) => column.key));
      const firstColumn = result.columns.slice(0, 1).map((column) => column.key);
      const defaultColumns = result.columns.filter((column) => column.defaultVisible).map((column) => column.key);
      // Captured before the updaters run: StrictMode invokes updaters twice, so they must not mutate refs.
      const useSavedColumns = hasInitializedColumnPreference.current;
      const useSavedTabletColumns = hasInitializedTabletColumnPreference.current;
      hasInitializedColumnPreference.current = true;
      hasInitializedTabletColumnPreference.current = true;
      setColumnOrder((existing) => {
        const retained = existing.filter((key) => available.has(key));
        const next = useSavedColumns && retained.length > 0
          ? retained
          : useSavedColumns ? firstColumn : (defaultColumns.length > 0 ? defaultColumns : firstColumn);
        localStorage.setItem(COLUMNS_KEY, JSON.stringify(next));
        return next;
      });
      setTabletColumnOrder((existing) => {
        const retained = existing.filter((key) => available.has(key));
        const next = useSavedTabletColumns && retained.length > 0 ? retained : recommendedTabletColumns(result.columns);
        localStorage.setItem(TABLET_COLUMNS_KEY, JSON.stringify(next));
        return next;
      });
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message || 'Failed to load dashboard');
    } finally {
      if (!silent && id === requestId.current) setLoading(false);
    }
  }, [stage, search, offset, limit, sort]);

  useEffect(() => { void loadDashboard(); }, [loadDashboard]);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      const next = searchInput.trim();
      setSearch(next);
      setOffset(0);
      localStorage.setItem(SEARCH_KEY, next);
    }, 300);
    return () => window.clearTimeout(timer);
  }, [searchInput]);

  useEffect(() => {
    const focusSearch = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      if (event.key !== '/' || target?.closest('input, textarea, select, [contenteditable="true"]')) return;
      event.preventDefault();
      searchRef.current?.focus();
    };
    document.addEventListener('keydown', focusSearch);
    return () => document.removeEventListener('keydown', focusSearch);
  }, []);

  useEffect(() => {
    if (!openActionPackageId) return;
    const closeOnOutsidePointer = (event: MouseEvent) => {
      const target = event.target as HTMLElement | null;
      if (!target?.closest('.dashboard-row-action')) setOpenActionPackageId(null);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpenActionPackageId(null);
    };
    document.addEventListener('mousedown', closeOnOutsidePointer);
    document.addEventListener('keydown', closeOnEscape);
    return () => {
      document.removeEventListener('mousedown', closeOnOutsidePointer);
      document.removeEventListener('keydown', closeOnEscape);
    };
  }, [openActionPackageId]);

  useEffect(() => {
    if (!confirmHidePackageId) return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !hidingPackageId) setConfirmHidePackageId(null);
    };
    document.addEventListener('keydown', closeOnEscape);
    return () => document.removeEventListener('keydown', closeOnEscape);
  }, [confirmHidePackageId, hidingPackageId]);

  const hasActiveProcessing = (data?.overview.processing ?? 0) > 0;

  useEffect(() => {
    const refresh = () => {
      if (!document.hidden) void loadDashboard(true);
    };
    const intervalMs = hasActiveProcessing ? 5000 : 30000;
    const timer = window.setInterval(refresh, intervalMs);
    const onVisibilityChange = () => { if (!document.hidden) refresh(); };
    const onFocus = () => refresh();
    document.addEventListener('visibilitychange', onVisibilityChange);
    window.addEventListener('focus', onFocus);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisibilityChange);
      window.removeEventListener('focus', onFocus);
    };
  }, [hasActiveProcessing, loadDashboard]);

  const availableColumns = data?.columns || [];
  const primaryIdentifierColumn = availableColumns.find((column) => column.kind === 'IDENTIFIER' && column.primary);
  const packagePendingRemoval = data?.items.find((item) => item.packageId === confirmHidePackageId) || null;
  const removalIdentifierLabel = primaryIdentifierColumn?.label || 'Package';
  const removalIdentifierValue = packagePendingRemoval && primaryIdentifierColumn?.key.startsWith('identifier:')
    ? getIdentifier(packagePendingRemoval, primaryIdentifierColumn.key.slice('identifier:'.length))
    : null;

  const effectiveColumnOrder = tabletLandscape ? tabletColumnOrder : columnOrder;
  const visibleColumns = useMemo(() => {
    const byKey = new Map(availableColumns.map((column) => [column.key, column]));
    return effectiveColumnOrder.map((key) => byKey.get(key)).filter((column): column is DashboardColumnDefinition => Boolean(column));
  }, [availableColumns, effectiveColumnOrder]);

  const searchableLabels = useMemo(() => availableColumns
    .filter((column) => column.kind === 'IDENTIFIER' && column.searchable)
    .map((column) => column.label), [availableColumns]);

  const totalPages = data ? Math.max(1, Math.ceil(data.totalItems / limit)) : 1;
  const currentPage = Math.floor(offset / limit) + 1;
  const canReview = hasCurrentCapability('MANUAL_REVIEW');
  const canApprove = hasCurrentCapability('PACKAGE_APPROVE_DELIVERY');
  const canRemoveFromDashboard = hasCurrentCapability('PACKAGE_REMOVE_FROM_DASHBOARD');
  const canViewTechnicalIssue = hasCurrentCapability('TECHNICAL_EXCEPTION_VIEW');

  const selectStage = (nextStage: DashboardStage) => {
    setStage(nextStage);
    setOffset(0);
    localStorage.setItem(STAGE_KEY, nextStage);
  };

  const toggleOverview = () => {
    setOverviewCollapsed((value) => {
      const next = !value;
      localStorage.setItem(OVERVIEW_KEY, String(next));
      return next;
    });
  };

  const setVisibleColumns = (keys: string[]) => {
    if (keys.length === 0) return;
    if (tabletLandscape) {
      setTabletColumnOrder(keys);
      localStorage.setItem(TABLET_COLUMNS_KEY, JSON.stringify(keys));
    } else {
      setColumnOrder(keys);
      localStorage.setItem(COLUMNS_KEY, JSON.stringify(keys));
    }
  };

  const toggleColumn = (key: string) => {
    if (effectiveColumnOrder.includes(key)) {
      if (effectiveColumnOrder.length === 1) return;
      setVisibleColumns(effectiveColumnOrder.filter((item) => item !== key));
    } else {
      const availableIndex = availableColumns.findIndex((column) => column.key === key);
      const next = [...effectiveColumnOrder];
      const insertAt = next.findIndex((existing) => {
        const index = availableColumns.findIndex((column) => column.key === existing);
        return index > availableIndex;
      });
      if (insertAt < 0) next.push(key); else next.splice(insertAt, 0, key);
      setVisibleColumns(next);
    }
  };

  const moveColumn = (key: string, direction: -1 | 1) => {
    const index = effectiveColumnOrder.indexOf(key);
    const target = index + direction;
    if (index < 0 || target < 0 || target >= effectiveColumnOrder.length) return;
    const next = [...effectiveColumnOrder];
    [next[index], next[target]] = [next[target], next[index]];
    setVisibleColumns(next);
  };

  const hideCompletedPackage = async (packageId: string) => {
    setError(null);
    setNotice(null);
    setHidingPackageId(packageId);
    try {
      await apiClient.hidePackageFromDashboard(packageId);
      setConfirmHidePackageId(null);
      setOpenActionPackageId(null);
      setNotice('Package removed from the Dashboard. Database history and retention cleanup are unchanged.');
      await loadDashboard();
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message || 'Unable to remove package from Dashboard');
    } finally {
      setHidingPackageId(null);
    }
  };

  const requestDashboardRemoval = (packageId: string) => {
    setOpenActionPackageId(null);
    setConfirmHidePackageId(packageId);
  };

  const overview = data?.overview;
  const countFor = (key: DashboardStage) => {
    const definition = STAGES.find((item) => item.key === key)!;
    return overview?.[definition.countKey] ?? 0;
  };
  const total = overview?.total ?? 0;

  const nextActions = ([
    canReview && countFor('NEEDS_REVIEW') > 0 ? { key: 'review', tone: 'warning', count: countFor('NEEDS_REVIEW'), label: 'intakes waiting on a redaction decision', cta: 'Start reviewing', to: '/review' } : null,
    canApprove && countFor('NEEDS_APPROVAL') > 0 ? { key: 'approval', tone: 'violet', count: countFor('NEEDS_APPROVAL'), label: 'ready for final approval', cta: 'Show approvals', stage: 'NEEDS_APPROVAL' as DashboardStage } : null,
    canViewTechnicalIssue && countFor('TECHNICAL_ATTENTION') > 0 ? { key: 'technical', tone: 'danger', count: countFor('TECHNICAL_ATTENTION'), label: 'with processing or delivery issues', cta: 'Show issues', stage: 'TECHNICAL_ATTENTION' as DashboardStage } : null,
  ] as (NextAction | null)[]).filter((action): action is NextAction => action !== null);

  const renderStageTab = (key: DashboardStage) => {
    const definition = STAGES.find((item) => item.key === key)!;
    const selected = stage === key;
    const count = countFor(key);
    const share = total > 0 && key !== 'ALL' ? Math.round((count / total) * 100) : null;
    return (
      <button
        type="button"
        role="tab"
        aria-selected={selected}
        className={`ops-lane tone-${definition.tone}${selected ? ' is-active' : ''}`}
        onClick={() => selectStage(key)}
      >
        <span className="ops-lane-label"><span className="ops-lane-dot" aria-hidden="true" />{definition.label}</span>
        <span className="ops-lane-figure">
          <strong>{count}</strong>
          {share !== null && <small>{share}%</small>}
        </span>
        <span className="ops-lane-help">{definition.help}</span>
      </button>
    );
  };

  const renderPackageAction = (item: DashboardItem) => {
    const menuOpen = openActionPackageId === item.packageId;
    const canRemove = item.status === 'COMPLETED' && canRemoveFromDashboard;
    return (
      <div className="dashboard-row-action">
        <button
          className="dashboard-action-trigger"
          type="button"
          aria-label="Package actions"
          aria-haspopup="menu"
          aria-expanded={menuOpen}
          onClick={() => setOpenActionPackageId((current) => current === item.packageId ? null : item.packageId)}
        >
          <Icon name="more" />
        </button>
        {menuOpen && (
          <div className="dashboard-action-menu" role="menu">
            <Link
              className="dashboard-action-menu-item"
              role="menuitem"
              to={`/packages/${item.packageId}`}
              onClick={() => setOpenActionPackageId(null)}
            >
              View Details
            </Link>
            {canRemove && (
              <button
                className="dashboard-action-menu-item danger"
                type="button"
                role="menuitem"
                onClick={() => requestDashboardRemoval(item.packageId)}
              >
                Remove from Dashboard
              </button>
            )}
          </div>
        )}
      </div>
    );
  };

  const renderCell = (item: DashboardItem, column: DashboardColumnDefinition) => {
    if (column.key.startsWith('identifier:')) {
      const key = column.key.slice('identifier:'.length);
      const value = getIdentifier(item, key);
      return column.primary
        ? <Link className="ops-primary-link" to={`/packages/${item.packageId}`}>{value}</Link>
        : value;
    }
    switch (column.key) {
      case 'received': return <span className="ops-muted">{formatDate(item.createdAt)}</span>;
      case 'stage': return <StatusBadge value={item.stage} label={stageLabel(item.stage)} />;
      case 'documents': return <span className="ops-num">{item.documentCount}</span>;
      case 'attention': {
        if (item.attention === 'REVIEW') return <AttentionLink value="REVIEW" to={canReview ? `/review?packageId=${item.packageId}` : undefined} label={canReview ? 'Review' : 'Needs review'} />;
        if (item.attention === 'APPROVAL') return <AttentionLink value="APPROVAL" to={canApprove ? `/packages/${item.packageId}` : undefined} label={canApprove ? 'Approval' : 'Needs approval'} />;
        if (item.attention === 'TECHNICAL') return <AttentionLink value="TECHNICAL" to={canViewTechnicalIssue ? `/technical-exceptions/${item.packageId}` : undefined} label={canViewTechnicalIssue ? 'Technical' : 'Technical attention'} />;
        if (item.attention === 'REJECTED') return <AttentionLink value="REJECTED" />;
        return <AttentionLink value="NONE" />;
      }
      case 'intakeSource': return <span className="ops-source"><Icon name={sourceIcon(item.packageType)} />{sourceLabel(item.packageType)}</span>;
      case 'exportStatus': return item.stage === 'EXPORTED' ? `Exported · Cycle ${item.successfulExportCycles}` : item.stage === 'NEEDS_APPROVAL' ? 'Awaiting approval' : 'Not exported';
      case 'lastUpdated': return <time className="ops-muted" dateTime={item.updatedAt} title={formatDate(item.updatedAt, true)}>{relativeTime(item.updatedAt)}</time>;
      case 'exportCycles': return <span className="ops-num">{item.successfulExportCycles}</span>;
      default: return '—';
    }
  };

  const railStages = [...PIPELINE_FLOW, ...PIPELINE_EXCEPTIONS].filter((key) => countFor(key) > 0);

  return (
    <PageScaffold className="ops-page" breadcrumbs={[]} title="Dashboard" eyebrow="Operations"
      description="Every intake in custody — where it is, and what it needs next."
      actions={<>
        {hasCurrentCapability('REPORT_EXECUTIVE_VIEW') && <Link to="/reports" className="ui-button secondary">View reports</Link>}
        {hasCurrentCapability('DOCUMENT_INTAKE') && <Link to="/packages/new" className="ui-button primary"><Icon name="upload" />Upload documents</Link>}
      </>}>
      {error && <div className="ui-alert tone-danger">{error}</div>}
      {notice && <div className="ui-alert tone-success">{notice}</div>}

      <section className={`ops-flow${overviewCollapsed ? ' is-compact' : ''}`} aria-labelledby="pipeline-title">
        <header className="ops-flow-header">
          <div className="ops-flow-heading">
            <h2 id="pipeline-title">Custody flow</h2>
            <span className="ops-live">
              <span className={`ops-live-dot${hasActiveProcessing ? ' is-active' : ''}`} aria-hidden="true" />
              {hasActiveProcessing ? 'Live · every 5s' : 'Idle · every 30s'}
              {lastRefreshedAt ? ` · ${lastRefreshedAt.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}` : ''}
            </span>
          </div>
          <button className="ui-button tertiary small" onClick={toggleOverview} aria-expanded={!overviewCollapsed}>
            {overviewCollapsed ? 'Expand' : 'Compact'}
            <Icon name={overviewCollapsed ? 'chevronDown' : 'chevronUp'} />
          </button>
        </header>

        <div className="ops-rail" aria-hidden="true">
          {railStages.length === 0
            ? <span className="ops-rail-seg tone-neutral is-empty" />
            : railStages.map((key) => {
              const definition = STAGES.find((item) => item.key === key)!;
              return (
                <span
                  key={key}
                  className={`ops-rail-seg tone-${definition.tone}${stage !== 'ALL' && stage !== key ? ' is-dimmed' : ''}`}
                  style={{ flexGrow: countFor(key) }}
                  onClick={() => selectStage(key)}
                />
              );
            })}
        </div>

        <div className="ops-lanes" role="tablist" aria-label="Filter by stage">
          <div className="ops-lane-group is-all">{renderStageTab('ALL')}</div>
          <div className="ops-lane-group is-flow">
            {PIPELINE_FLOW.map((key) => <div className="ops-lane-cell" key={key}>{renderStageTab(key)}</div>)}
          </div>
          <div className="ops-lane-group is-exceptions">
            {PIPELINE_EXCEPTIONS.map((key) => <div className="ops-lane-cell" key={key}>{renderStageTab(key)}</div>)}
          </div>
        </div>

        {nextActions.length > 0 && (
          <ul className="ops-next" aria-label="Needs your attention">
            {nextActions.map((action) => (
              <li key={action.key} className={`tone-${action.tone}`}>
                <span className="ops-next-copy"><strong>{action.count}</strong> {action.label}</span>
                {action.to
                  ? <Link className="ops-next-cta" to={action.to}>{action.cta}<Icon name="chevronRight" /></Link>
                  : <button type="button" className="ops-next-cta" onClick={() => action.stage && selectStage(action.stage)}>{action.cta}<Icon name="chevronRight" /></button>}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="ops-register" aria-labelledby="register-title">
        <div className="ops-toolbar">
          <div className="ops-register-heading">
            <h2 id="register-title">{stage === 'ALL' ? 'All intakes' : stageLabel(stage)}</h2>
            <span>{data ? `${data.totalItems} ${data.totalItems === 1 ? 'intake' : 'intakes'}` : '—'}{search ? ` matching “${search}”` : ''}</span>
          </div>

          <div className="ops-tools">
            <div className="ops-search">
              <Icon className="ops-search-icon" name="search" />
              <input
                ref={searchRef}
                value={searchInput}
                onChange={(event) => setSearchInput(event.target.value)}
                placeholder={searchableLabels.length ? `Search ${searchableLabels.slice(0, 2).join(' or ').toLowerCase()}` : 'Search identifiers'}
                aria-label="Search configured business identifiers"
              />
              {searchInput
                ? <button className="ops-search-clear" onClick={() => setSearchInput('')} aria-label="Clear search"><Icon name="close" /></button>
                : <kbd className="ops-kbd" aria-hidden="true">/</kbd>}
            </div>

            <select className="ops-select" value={sort} onChange={(event) => {
              const next = event.target.value as typeof sort;
              setSort(next); setOffset(0); localStorage.setItem(SORT_KEY, next);
            }} aria-label="Sort dashboard">
              <option value="receivedAt">Newest received</option>
              <option value="updatedAt">Recently updated</option>
              <option value="stage">Attention first</option>
            </select>

            <div className="columns-menu-wrap">
              <button className="ui-button secondary small" onClick={() => setColumnsOpen((open) => !open)} aria-expanded={columnsOpen}><Icon name="columns" /><span className="ops-tool-label">Columns</span></button>
              {columnsOpen && (
                <div className="columns-menu">
                  <div className="columns-menu-heading">
                    <strong>{tabletLandscape ? 'Tablet columns' : 'Visible columns'}</strong>
                    <span>{tabletLandscape ? 'Optimized for landscape; add fields when needed' : 'Show, hide, and reorder approved fields'}</span>
                  </div>
                  {availableColumns.map((column) => {
                    const visibleIndex = effectiveColumnOrder.indexOf(column.key);
                    const checked = visibleIndex >= 0;
                    return (
                      <div className="column-option" key={column.key}>
                        <label>
                          <input type="checkbox" checked={checked} onChange={() => toggleColumn(column.key)} />
                          <span>{column.label}</span>
                          {column.primary && <em>Primary</em>}
                        </label>
                        {checked && (
                          <div className="column-order-buttons">
                            <button onClick={() => moveColumn(column.key, -1)} disabled={visibleIndex === 0} aria-label={`Move ${column.label} left`}><Icon name="arrowUp" /></button>
                            <button onClick={() => moveColumn(column.key, 1)} disabled={visibleIndex === effectiveColumnOrder.length - 1} aria-label={`Move ${column.label} right`}><Icon name="arrowDown" /></button>
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              )}
            </div>

            <button className="ui-button secondary small" onClick={() => void loadDashboard()} disabled={loading} aria-label="Refresh dashboard">
              <Icon className={loading ? 'ops-spin' : undefined} name="refresh" />
            </button>
          </div>
        </div>

        <div className="ops-table-wrap">
          {loading && !data ? (
            <div className="ops-skeleton" aria-busy="true" aria-label="Loading operations">
              {[0, 1, 2, 3, 4, 5].map((index) => <span key={index} />)}
            </div>
          ) : !data || data.items.length === 0 ? (
            <div className="ops-empty">
              <span className="ops-empty-bars" aria-hidden="true"><i /><i /><i /></span>
              <h3>{search || stage !== 'ALL' ? 'No matching intakes' : 'Nothing in custody yet'}</h3>
              <p>{search || stage !== 'ALL' ? 'Try a different stage or search value.' : 'Upload or scan a package to start the first intake.'}</p>
              {!search && stage === 'ALL' && <Link to="/packages/new" className="ui-button primary">Upload documents</Link>}
            </div>
          ) : (
            <>
            <div className="ops-cards" aria-label="Packages">
              {data.items.map((item) => {
                const primaryValue = primaryIdentifierColumn?.key.startsWith('identifier:')
                  ? getIdentifier(item, primaryIdentifierColumn.key.slice('identifier:'.length))
                  : item.packageId.slice(0, 8);
                const attentionAction = item.attention === 'REVIEW' && canReview
                  ? { to: `/review?packageId=${item.packageId}`, value: 'REVIEW', label: 'Open review' }
                  : item.attention === 'APPROVAL' && canApprove
                    ? { to: `/packages/${item.packageId}`, value: 'APPROVAL', label: 'Open approval' }
                    : item.attention === 'TECHNICAL' && canViewTechnicalIssue
                      ? { to: `/technical-exceptions/${item.packageId}`, value: 'TECHNICAL', label: 'Open technical issue' }
                      : null;
                const secondary = item.identifiers.find((identifier) => !identifier.primary);
                return (
                  <article className="ops-card" key={item.packageId}>
                    <div className="ops-card-top">
                      <div className="ops-card-id">
                        <Link className="ops-primary-link" to={`/packages/${item.packageId}`}>{primaryValue}</Link>
                        {secondary && <span>{secondary.value}</span>}
                      </div>
                      <StatusBadge value={item.stage} label={stageLabel(item.stage)} />
                    </div>
                    <div className="ops-card-meta">
                      <span className="ops-source"><Icon name={sourceIcon(item.packageType)} />{sourceLabel(item.packageType)}</span>
                      <span>{item.documentCount} doc{item.documentCount === 1 ? '' : 's'}</span>
                      <span>{relativeTime(item.updatedAt)}</span>
                    </div>
                    {attentionAction && (
                      <div className="ops-card-footer">
                        <AttentionLink className="mobile-attention" value={attentionAction.value} to={attentionAction.to} label={attentionAction.label} />
                      </div>
                    )}
                  </article>
                );
              })}
            </div>
            <table className="ops-table">
              <thead>
                <tr>
                  {visibleColumns.map((column) => <th key={column.key} scope="col" data-column-key={column.key} data-column-kind={column.kind} data-primary={column.primary ? 'true' : undefined}>{column.label}</th>)}
                  {canApprove && <th scope="col" className="ops-actions-col"><span className="sr-only">Actions</span></th>}
                </tr>
              </thead>
              <tbody>
                {data.items.map((item) => (
                  <tr key={item.packageId} className={`stage-${item.stage.toLowerCase()}`}>
                    {visibleColumns.map((column) => <td key={column.key} data-column-key={column.key} data-column-kind={column.kind} data-primary={column.primary ? 'true' : undefined}>{renderCell(item, column)}</td>)}
                    {canApprove && <td className="ops-actions-col">{renderPackageAction(item)}</td>}
                  </tr>
                ))}
              </tbody>
            </table>
            </>
          )}
        </div>

        <footer className="ops-pagination">
          <div className="ops-pagination-range">
            <span>{data ? `${data.totalItems === 0 ? 0 : offset + 1}–${Math.min(offset + limit, data.totalItems)} of ${data.totalItems}` : '0 items'}</span>
            <select className="ops-select is-small" value={limit} onChange={(event) => { setLimit(Number(event.target.value)); setOffset(0); }} aria-label="Rows per page">
              <option value={10}>10 / page</option>
              <option value={25}>25 / page</option>
              <option value={50}>50 / page</option>
            </select>
          </div>
          <div className="ops-pagination-pages">
            <button className="ui-button secondary small" disabled={currentPage <= 1 || loading} onClick={() => setOffset(Math.max(0, offset - limit))} aria-label="Previous page"><Icon name="chevronLeft" /></button>
            <span>Page {currentPage} of {totalPages}</span>
            <button className="ui-button secondary small" disabled={currentPage >= totalPages || loading} onClick={() => setOffset(offset + limit)} aria-label="Next page"><Icon name="chevronRight" /></button>
          </div>
        </footer>
      </section>

      {confirmHidePackageId && (
        <div
          className="dashboard-modal-backdrop"
          role="presentation"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget && !hidingPackageId) setConfirmHidePackageId(null);
          }}
        >
          <section className="dashboard-confirm-modal" role="dialog" aria-modal="true" aria-labelledby="remove-dashboard-title">
            <div className="dashboard-confirm-modal-handle" aria-hidden="true" />
            <h2 id="remove-dashboard-title">Remove package from Dashboard?</h2>
            {removalIdentifierValue && (
              <p className="dashboard-confirm-identifier">
                <span>{removalIdentifierLabel}</span>
                <strong>{removalIdentifierValue}</strong>
              </p>
            )}
            <p>
              This package will be hidden from the normal Dashboard. Its database history and audit trail remain available,
              and physical files continue to follow the existing retention policy.
            </p>
            <div className="dashboard-confirm-actions">
              <button
                className="ui-button secondary dashboard-confirm-cancel"
                type="button"
                disabled={Boolean(hidingPackageId)}
                onClick={() => setConfirmHidePackageId(null)}
              >
                Cancel
              </button>
              <button
                className="ui-button danger dashboard-confirm-remove"
                type="button"
                disabled={Boolean(hidingPackageId)}
                onClick={() => void hideCompletedPackage(confirmHidePackageId)}
              >
                {hidingPackageId ? 'Removing…' : 'Remove'}
              </button>
            </div>
          </section>
        </div>
      )}
    </PageScaffold>
  );
}
