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

const OVERVIEW_KEY = 'securedoc.dashboard.overviewCollapsed.v1';
const STAGE_KEY = 'securedoc.dashboard.stage.v1';
const SEARCH_KEY = 'securedoc.dashboard.search.v1';
const COLUMNS_KEY = 'securedoc.dashboard.columns.v1';
const TABLET_COLUMNS_KEY = 'securedoc.dashboard.tabletColumns.v1';
const SORT_KEY = 'securedoc.dashboard.sort.v1';

const STAGES: Array<{ key: DashboardStage; label: string; countKey: keyof OperationsDashboard['overview'] }> = [
  { key: 'ALL', label: 'All', countKey: 'total' },
  { key: 'PROCESSING', label: 'Processing', countKey: 'processing' },
  { key: 'NEEDS_REVIEW', label: 'Needs Review', countKey: 'needsReview' },
  { key: 'NEEDS_APPROVAL', label: 'Needs Approval', countKey: 'needsApproval' },
  { key: 'EXPORTED', label: 'Exported', countKey: 'exported' },
  { key: 'TECHNICAL_ATTENTION', label: 'Technical Attention', countKey: 'technicalAttention' },
  { key: 'REJECTED', label: 'Rejected', countKey: 'rejected' },
];

const KPI_CARDS: Array<{ stage: DashboardStage; title: string; countKey: keyof OperationsDashboard['overview']; tone: string; help: string }> = [
  { stage: 'PROCESSING', title: 'Processing', countKey: 'processing', tone: 'blue', help: 'Intakes moving through the redaction pipeline' },
  { stage: 'NEEDS_REVIEW', title: 'Needs Review', countKey: 'needsReview', tone: 'amber', help: 'Only items that need a business decision' },
  { stage: 'NEEDS_APPROVAL', title: 'Needs Approval', countKey: 'needsApproval', tone: 'violet', help: 'Packages waiting for final business approval' },
  { stage: 'TECHNICAL_ATTENTION', title: 'Technical Attention', countKey: 'technicalAttention', tone: 'red', help: 'Processing or delivery issues for operations' },
];

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
    case 'MANUAL_UPLOAD': return 'Manual Upload';
    case 'API_SUBMISSION': return 'API';
    case 'FOLDER_INTAKE': return 'Watched Folder';
    case 'SCANNER_INTAKE': return 'Scanner';
    default: return type.replace(/_/g, ' ');
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

  const loadDashboard = useCallback(async (silent = false) => {
    const id = ++requestId.current;
    if (!silent) setLoading(true);
    setError(null);
    try {
      const result = await apiClient.getOperationsDashboard({ stage, search, offset, limit, sort, direction: 'desc' });
      if (id !== requestId.current) return;
      setData(result);
      setLastRefreshedAt(new Date());
      setColumnOrder((existing) => {
        const available = new Set(result.columns.map((column) => column.key));
        if (hasInitializedColumnPreference.current) {
          const retained = existing.filter((key) => available.has(key));
          const next = retained.length > 0 ? retained : result.columns.slice(0, 1).map((column) => column.key);
          localStorage.setItem(COLUMNS_KEY, JSON.stringify(next));
          return next;
        }
        hasInitializedColumnPreference.current = true;
        const defaults = result.columns.filter((column) => column.defaultVisible).map((column) => column.key);
        const next = defaults.length > 0 ? defaults : result.columns.slice(0, 1).map((column) => column.key);
        localStorage.setItem(COLUMNS_KEY, JSON.stringify(next));
        return next;
      });
      setTabletColumnOrder((existing) => {
        const available = new Set(result.columns.map((column) => column.key));
        if (hasInitializedTabletColumnPreference.current) {
          const retained = existing.filter((key) => available.has(key));
          const next = retained.length > 0 ? retained : recommendedTabletColumns(result.columns);
          localStorage.setItem(TABLET_COLUMNS_KEY, JSON.stringify(next));
          return next;
        }
        hasInitializedTabletColumnPreference.current = true;
        const next = recommendedTabletColumns(result.columns);
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
          ⋮
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
        ? <Link className="dashboard-primary-link" to={`/packages/${item.packageId}`}>{value}</Link>
        : value;
    }
    switch (column.key) {
      case 'received': return formatDate(item.createdAt);
      case 'stage': return <StatusBadge value={item.stage} label={stageLabel(item.stage)} />;
      case 'documents': return item.documentCount;
      case 'attention': {
        if (item.attention === 'REVIEW') return <AttentionLink value="REVIEW" to={canReview ? `/review?packageId=${item.packageId}` : undefined} label={canReview ? 'Review' : 'Needs review'} />;
        if (item.attention === 'APPROVAL') return <AttentionLink value="APPROVAL" to={canApprove ? `/packages/${item.packageId}` : undefined} label={canApprove ? 'Approval' : 'Needs approval'} />;
        if (item.attention === 'TECHNICAL') return <AttentionLink value="TECHNICAL" to={canViewTechnicalIssue ? `/technical-exceptions/${item.packageId}` : undefined} label={canViewTechnicalIssue ? 'Technical' : 'Technical attention'} />;
        if (item.attention === 'REJECTED') return <AttentionLink value="REJECTED" />;
        return <AttentionLink value="NONE" />;
      }
      case 'intakeSource': return sourceLabel(item.packageType);
      case 'exportStatus': return item.stage === 'EXPORTED' ? `Exported · Cycle ${item.successfulExportCycles}` : item.stage === 'NEEDS_APPROVAL' ? 'Awaiting approval' : 'Not exported';
      case 'lastUpdated': return formatDate(item.updatedAt, true);
      case 'exportCycles': return item.successfulExportCycles;
      default: return '—';
    }
  };

  return (
    <PageScaffold className="dashboard-page" breadcrumbs={[]} title="Dashboard" eyebrow="Operations"
      description="Monitor document intake, processing, review, approvals, and delivery from one operational view."
      actions={<>
        {hasCurrentCapability('REPORT_EXECUTIVE_VIEW') && <Link to="/reports" className="ui-button secondary">View reports</Link>}
        {hasCurrentCapability('DOCUMENT_INTAKE') && <Link to="/packages/new" className="ui-button primary">Upload documents</Link>}
      </>}>
      <p className="dashboard-refresh-status modern">
        Auto-refresh {hasActiveProcessing ? 'every 5 seconds while processing' : 'every 30 seconds while idle'}
        {lastRefreshedAt ? ` · Updated ${lastRefreshedAt.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit', second: '2-digit' })}` : ''}
      </p>

      {error && <div className="ui-alert tone-danger">{error}</div>}
      {notice && <div className="ui-alert tone-success">{notice}</div>}

      <section className={`ui-card overview-panel-layout ${overviewCollapsed ? 'collapsed' : ''}`}>
        <div className="overview-title-row">
          <div>
            <h2>Overview</h2>
            <span>{data?.overview.total ?? 0} intake batches</span>
          </div>
          <div className="dashboard-overview-actions">
            {overviewCollapsed && (
              <div className="dashboard-overview-glance" aria-label="Operational summary">
                <span><strong>{data?.overview.processing ?? 0}</strong> Processing</span>
                <span><strong>{data?.overview.needsReview ?? 0}</strong> Review</span>
                <span><strong>{data?.overview.technicalAttention ?? 0}</strong> Technical</span>
                <span><strong>{data?.overview.exported ?? 0}</strong> Exported</span>
              </div>
            )}
            <button className="ui-button tertiary small" onClick={toggleOverview} aria-expanded={!overviewCollapsed}>
              {overviewCollapsed ? 'Show detail' : 'Collapse'}
              <span aria-hidden="true">{overviewCollapsed ? '⌄' : '⌃'}</span>
            </button>
          </div>
        </div>
        {!overviewCollapsed && (
          <div className="kpi-grid">
            {KPI_CARDS.map((card) => (
              <button key={card.stage} className={`kpi-card tone-${card.tone} ${stage === card.stage ? 'active' : ''}`} onClick={() => selectStage(card.stage)}>
                <span className="kpi-dot" />
                <span className="kpi-label">{card.title}</span>
                <strong>{data?.overview[card.countKey] ?? 0}</strong>
                <small>{card.help}</small>
              </button>
            ))}
          </div>
        )}
      </section>

      <section className="ui-card dashboard-list-card">
        <div className="dashboard-toolbar">
          <div className="stage-chips" role="tablist" aria-label="Filter by stage">
            {STAGES.map((item) => (
              <button key={item.key} className={`stage-chip ${stage === item.key ? 'active' : ''}`} onClick={() => selectStage(item.key)}>
                {item.label}
                <span>{data?.overview[item.countKey] ?? 0}</span>
              </button>
            ))}
          </div>

          <div className="dashboard-tools-row">
            <div className="dashboard-search-wrap">
              <span className="search-icon" aria-hidden="true">⌕</span>
              <input
                value={searchInput}
                onChange={(event) => setSearchInput(event.target.value)}
                placeholder={searchableLabels.length ? `Search ${searchableLabels.slice(0, 2).join(' or ')}…` : 'Search configured identifiers…'}
                aria-label="Search configured business identifiers"
              />
              {searchInput && <button className="search-clear" onClick={() => setSearchInput('')} aria-label="Clear search">×</button>}
            </div>

            <select className="compact-select" value={sort} onChange={(event) => {
              const next = event.target.value as typeof sort;
              setSort(next); setOffset(0); localStorage.setItem(SORT_KEY, next);
            }} aria-label="Sort dashboard">
              <option value="receivedAt">Newest received</option>
              <option value="updatedAt">Recently updated</option>
              <option value="stage">Attention first</option>
            </select>

            <div className="columns-menu-wrap">
              <button className="ui-button secondary small" onClick={() => setColumnsOpen((open) => !open)} aria-expanded={columnsOpen}>Columns</button>
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
                            <button onClick={() => moveColumn(column.key, -1)} disabled={visibleIndex === 0} aria-label={`Move ${column.label} left`}>↑</button>
                            <button onClick={() => moveColumn(column.key, 1)} disabled={visibleIndex === effectiveColumnOrder.length - 1} aria-label={`Move ${column.label} right`}>↓</button>
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              )}
            </div>

            <button className="ui-button secondary small" onClick={() => void loadDashboard()} disabled={loading}>{loading ? 'Refreshing…' : 'Refresh'}</button>
          </div>
        </div>

        <div className="dashboard-table-wrap">
          {loading && !data ? (
            <div className="ui-empty-state dashboard-empty-layout"><div className="skeleton-line" /><div className="skeleton-line short" /><p>Loading operations…</p></div>
          ) : !data || data.items.length === 0 ? (
            <div className="ui-empty-state dashboard-empty-layout">
              <div className="empty-icon">✓</div>
              <h3>{search || stage !== 'ALL' ? 'No matching intakes' : 'No intakes yet'}</h3>
              <p>{search || stage !== 'ALL' ? 'Try a different stage or search value.' : 'Upload documents to start the first intake.'}</p>
              {!search && stage === 'ALL' && <Link to="/packages/new" className="ui-button primary">Upload Documents</Link>}
            </div>
          ) : (
            <>
            <div className="dashboard-mobile-list" aria-label="Packages">
              {data.items.map((item) => {
                const primaryValue = primaryIdentifierColumn?.key.startsWith('identifier:')
                  ? getIdentifier(item, primaryIdentifierColumn.key.slice('identifier:'.length))
                  : item.packageId.slice(0, 8);
                const attentionAction = item.attention === 'REVIEW' && canReview
                  ? { to: `/review?packageId=${item.packageId}`, value: 'REVIEW', label: 'Open review →' }
                  : item.attention === 'APPROVAL' && canApprove
                    ? { to: `/packages/${item.packageId}`, value: 'APPROVAL', label: 'Open approval →' }
                    : item.attention === 'TECHNICAL' && canViewTechnicalIssue
                      ? { to: `/technical-exceptions/${item.packageId}`, value: 'TECHNICAL', label: 'Open technical issue →' }
                      : null;
                return (
                  <article className="dashboard-mobile-card" key={item.packageId}>
                    <div className="dashboard-mobile-card-top">
                      <div>
                        <span className="dashboard-mobile-label">{primaryIdentifierColumn?.label || 'Package'}</span>
                        <Link className="dashboard-mobile-primary-link" to={`/packages/${item.packageId}`}>{primaryValue}</Link>
                      </div>
                      <StatusBadge value={item.stage} label={stageLabel(item.stage)} />
                    </div>
                    <div className="dashboard-mobile-meta">
                      <span><small>Documents</small><strong>{item.documentCount}</strong></span>
                      <span><small>Source</small><strong>{sourceLabel(item.packageType)}</strong></span>
                      <span><small>Updated</small><strong>{formatDate(item.updatedAt, true)}</strong></span>
                    </div>
                    <div className="dashboard-mobile-card-footer">
                      {attentionAction
                        ? <AttentionLink className="mobile-attention" value={attentionAction.value} to={attentionAction.to} label={attentionAction.label} />
                        : <Link className="dashboard-mobile-details-link" to={`/packages/${item.packageId}`}>View details →</Link>}
                    </div>
                  </article>
                );
              })}
            </div>
            <table className="operations-table">
              <thead>
                <tr>
                  {visibleColumns.map((column) => <th key={column.key} data-column-key={column.key} data-column-kind={column.kind} data-primary={column.primary ? 'true' : undefined}>{column.label}</th>)}
                  {canApprove && <th>Actions</th>}
                </tr>
              </thead>
              <tbody>
                {data.items.map((item) => (
                  <tr key={item.packageId}>
                    {visibleColumns.map((column) => <td key={column.key} data-column-key={column.key} data-column-kind={column.kind} data-primary={column.primary ? 'true' : undefined}>{renderCell(item, column)}</td>)}
                    {canApprove && <td className="dashboard-actions-cell">{renderPackageAction(item)}</td>}
                  </tr>
                ))}
              </tbody>
            </table>
            </>
          )}
        </div>

        <footer className="dashboard-pagination">
          <div>
            <span>{data ? `${data.totalItems === 0 ? 0 : offset + 1}–${Math.min(offset + limit, data.totalItems)} of ${data.totalItems}` : '0 items'}</span>
            <select value={limit} onChange={(event) => { setLimit(Number(event.target.value)); setOffset(0); }} aria-label="Rows per page">
              <option value={10}>10 / page</option>
              <option value={25}>25 / page</option>
              <option value={50}>50 / page</option>
            </select>
          </div>
          <div className="page-buttons">
            <button className="ui-button secondary small" disabled={currentPage <= 1 || loading} onClick={() => setOffset(Math.max(0, offset - limit))}>Previous</button>
            <span>Page {currentPage} of {totalPages}</span>
            <button className="ui-button secondary small" disabled={currentPage >= totalPages || loading} onClick={() => setOffset(offset + limit)}>Next</button>
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
