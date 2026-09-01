import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { apiClient } from '../api/client';
import { hasCurrentCapability } from '../api/config';
import type {
  ApiError,
  DashboardColumnDefinition,
  DashboardItem,
  DashboardStage,
  OperationsDashboard,
} from '../types';

const OVERVIEW_KEY = 'securedoc.dashboard.overviewCollapsed.v1';
const STAGE_KEY = 'securedoc.dashboard.stage.v1';
const SEARCH_KEY = 'securedoc.dashboard.search.v1';
const COLUMNS_KEY = 'securedoc.dashboard.columns.v1';
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
  { stage: 'EXPORTED', title: 'Exported', countKey: 'exported', tone: 'green', help: 'Successfully delivered intake batches' },
  { stage: 'TECHNICAL_ATTENTION', title: 'Technical Attention', countKey: 'technicalAttention', tone: 'red', help: 'Processing or delivery issues for operations' },
];

function safeStoredStage(): DashboardStage {
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

function loadColumnPreference(): string[] | null {
  try {
    const raw = localStorage.getItem(COLUMNS_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) && parsed.every((value) => typeof value === 'string') ? parsed : null;
  } catch {
    return null;
  }
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

  const visibleColumns = useMemo(() => {
    const byKey = new Map(availableColumns.map((column) => [column.key, column]));
    return columnOrder.map((key) => byKey.get(key)).filter((column): column is DashboardColumnDefinition => Boolean(column));
  }, [availableColumns, columnOrder]);

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
    setColumnOrder(keys);
    localStorage.setItem(COLUMNS_KEY, JSON.stringify(keys));
  };

  const toggleColumn = (key: string) => {
    if (columnOrder.includes(key)) {
      if (columnOrder.length === 1) return;
      setVisibleColumns(columnOrder.filter((item) => item !== key));
    } else {
      const availableIndex = availableColumns.findIndex((column) => column.key === key);
      const next = [...columnOrder];
      const insertAt = next.findIndex((existing) => {
        const index = availableColumns.findIndex((column) => column.key === existing);
        return index > availableIndex;
      });
      if (insertAt < 0) next.push(key); else next.splice(insertAt, 0, key);
      setVisibleColumns(next);
    }
  };

  const moveColumn = (key: string, direction: -1 | 1) => {
    const index = columnOrder.indexOf(key);
    const target = index + direction;
    if (index < 0 || target < 0 || target >= columnOrder.length) return;
    const next = [...columnOrder];
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
      case 'stage': return <span className={`stage-pill stage-${item.stage.toLowerCase()}`}>{stageLabel(item.stage)}</span>;
      case 'documents': return item.documentCount;
      case 'attention': {
        if (item.attention === 'REVIEW') return canReview
          ? <Link className="attention-link attention-review" to={`/review?packageId=${item.packageId}`}>Review</Link>
          : <span className="attention-link attention-review">Needs review</span>;
        if (item.attention === 'APPROVAL') return canApprove
          ? <Link className="attention-link attention-approval" to={`/packages/${item.packageId}`}>Approval</Link>
          : <span className="attention-link attention-approval">Needs approval</span>;
        if (item.attention === 'TECHNICAL') return canViewTechnicalIssue
          ? <Link className="attention-link attention-technical" to={`/technical-exceptions/${item.packageId}`}>Technical</Link>
          : <span className="attention-link attention-technical">Technical attention</span>;
        if (item.attention === 'REJECTED') return <span className="attention-muted">Rejected</span>;
        return <span className="attention-none">—</span>;
      }
      case 'intakeSource': return sourceLabel(item.packageType);
      case 'exportStatus': return item.stage === 'EXPORTED' ? `Exported · Cycle ${item.successfulExportCycles}` : item.stage === 'NEEDS_APPROVAL' ? 'Awaiting approval' : 'Not exported';
      case 'lastUpdated': return formatDate(item.updatedAt, true);
      case 'exportCycles': return item.successfulExportCycles;
      default: return '—';
    }
  };

  return (
    <div className="dashboard-page">
      <header className="dashboard-header">
        <div>
          <p className="eyebrow">Document operations</p>
          <h1>Dashboard</h1>
          <p className="dashboard-subtitle">One place to track intake, review, delivery, and exceptions.</p>
          <p className="dashboard-refresh-status">
            Auto-refresh {hasActiveProcessing ? 'every 5 seconds while processing' : 'every 30 seconds while idle'}
            {lastRefreshedAt ? ` · Updated ${lastRefreshedAt.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit', second: '2-digit' })}` : ''}
          </p>
        </div>
        <div className="dashboard-header-actions">
          <Link to="/reports" className="button-link secondary">View Reports</Link>
          <Link to="/packages/new" className="button-link primary">Upload Documents</Link>
        </div>
      </header>

      {error && <div className="error-msg">{error}</div>}
      {notice && <div className="success-msg">{notice}</div>}

      <section className={`overview-panel ${overviewCollapsed ? 'collapsed' : ''}`}>
        <div className="overview-title-row">
          <div>
            <h2>Overview</h2>
            <span>{data?.overview.total ?? 0} intake batches</span>
          </div>
          <button className="icon-text-button" onClick={toggleOverview} aria-expanded={!overviewCollapsed}>
            {overviewCollapsed ? 'Show overview' : 'Collapse overview'}
            <span aria-hidden="true">{overviewCollapsed ? '⌄' : '⌃'}</span>
          </button>
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

      <section className="dashboard-list-card">
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
              <button className="toolbar-button" onClick={() => setColumnsOpen((open) => !open)} aria-expanded={columnsOpen}>Columns</button>
              {columnsOpen && (
                <div className="columns-menu">
                  <div className="columns-menu-heading">
                    <strong>Visible columns</strong>
                    <span>Show, hide, and reorder approved fields</span>
                  </div>
                  {availableColumns.map((column) => {
                    const visibleIndex = columnOrder.indexOf(column.key);
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
                            <button onClick={() => moveColumn(column.key, 1)} disabled={visibleIndex === columnOrder.length - 1} aria-label={`Move ${column.label} right`}>↓</button>
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              )}
            </div>

            <button className="toolbar-button" onClick={() => void loadDashboard()} disabled={loading}>{loading ? 'Refreshing…' : 'Refresh'}</button>
          </div>
        </div>

        <div className="dashboard-table-wrap">
          {loading && !data ? (
            <div className="dashboard-empty"><div className="skeleton-line" /><div className="skeleton-line short" /><p>Loading operations…</p></div>
          ) : !data || data.items.length === 0 ? (
            <div className="dashboard-empty">
              <div className="empty-icon">✓</div>
              <h3>{search || stage !== 'ALL' ? 'No matching intakes' : 'No intakes yet'}</h3>
              <p>{search || stage !== 'ALL' ? 'Try a different stage or search value.' : 'Upload documents to start the first intake.'}</p>
              {!search && stage === 'ALL' && <Link to="/packages/new" className="button-link primary">Upload Documents</Link>}
            </div>
          ) : (
            <table className="operations-table">
              <thead>
                <tr>
                  {visibleColumns.map((column) => <th key={column.key}>{column.label}</th>)}
                  {canApprove && <th>Actions</th>}
                </tr>
              </thead>
              <tbody>
                {data.items.map((item) => (
                  <tr key={item.packageId}>
                    {visibleColumns.map((column) => <td key={column.key}>{renderCell(item, column)}</td>)}
                    {canApprove && <td className="dashboard-actions-cell">{renderPackageAction(item)}</td>}
                  </tr>
                ))}
              </tbody>
            </table>
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
            <button className="toolbar-button" disabled={currentPage <= 1 || loading} onClick={() => setOffset(Math.max(0, offset - limit))}>Previous</button>
            <span>Page {currentPage} of {totalPages}</span>
            <button className="toolbar-button" disabled={currentPage >= totalPages || loading} onClick={() => setOffset(offset + limit)}>Next</button>
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
                className="dashboard-confirm-cancel"
                type="button"
                disabled={Boolean(hidingPackageId)}
                onClick={() => setConfirmHidePackageId(null)}
              >
                Cancel
              </button>
              <button
                className="dashboard-confirm-remove"
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
    </div>
  );
}
