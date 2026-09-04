import { useEffect, useMemo, useState } from 'react';
import { apiClient } from '../api/client';
import Breadcrumbs from '../components/Breadcrumbs';
import type {
  ApiError,
  BusinessAdminConfiguration,
  BusinessAdminIdentifierDraft,
  BusinessReviewMode,
  SensitiveDataPolicyConfiguration,
  LifecyclePolicyConfiguration,
  DashboardAdminColumnConfiguration,
  RoutingRule,
  RoutingRuleRequest,
  RoutingDestinationOption,
  RoutingMarkerType,
  RoutingScanRegion,
} from '../types';

const SENSITIVE_TYPES = [
  { key: 'SSN', label: 'Social Security Number' },
  { key: 'DATE_OF_BIRTH', label: 'Date of Birth' },
  { key: 'CREDIT_CARD_NUMBER', label: 'Credit Card Number' },
  { key: 'CREDIT_SCORE', label: 'Credit Score' },
] as const;

export default function BusinessAdminPage() {
  const [configuration, setConfiguration] = useState<BusinessAdminConfiguration | null>(null);
  const [identifiers, setIdentifiers] = useState<BusinessAdminIdentifierDraft[]>([]);
  const [policy, setPolicy] = useState<SensitiveDataPolicyConfiguration | null>(null);
  const [lifecyclePolicy, setLifecyclePolicy] = useState<LifecyclePolicyConfiguration | null>(null);
  const [dashboardColumns, setDashboardColumns] = useState<DashboardAdminColumnConfiguration[]>([]);
  const [routingRules, setRoutingRules] = useState<RoutingRule[]>([]);
  const [routingDestinations, setRoutingDestinations] = useState<RoutingDestinationOption[]>([]);
  const [routingDraft, setRoutingDraft] = useState<(RoutingRuleRequest & { ruleId?: string })>({
    destinationLabel: '', markerType: 'TEXT_PATTERN', markerValue: '', scanRegion: 'TOP_RIGHT', destinationConnectionId: '', enabled: true,
  });
  const [loading, setLoading] = useState(true);
  const [savingIdentifiers, setSavingIdentifiers] = useState(false);
  const [savingPolicy, setSavingPolicy] = useState(false);
  const [savingLifecycle, setSavingLifecycle] = useState(false);
  const [savingDashboardColumns, setSavingDashboardColumns] = useState(false);
  const [savingRouting, setSavingRouting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const [result, rules, destinations] = await Promise.all([
        apiClient.getBusinessAdminConfiguration(),
        apiClient.listRoutingRules(),
        apiClient.listRoutingDestinations(),
      ]);
      applyConfiguration(result);
      setRoutingRules(rules);
      setRoutingDestinations(destinations);
    } catch (err) {
      const apiError = err as ApiError;
      setError(apiError.message || 'Unable to load Business Admin configuration');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const applyConfiguration = (result: BusinessAdminConfiguration) => {
    setConfiguration(result);
    setIdentifiers(
      [...result.identifiers]
        .sort((a, b) => a.displayOrder - b.displayOrder)
        .map(({ id, label, required, primary, searchable, extractable, displayable }) => ({
          id,
          label,
          required,
          primary,
          searchable,
          extractable,
          displayable,
        }))
    );
    setDashboardColumns([...result.dashboardColumns].sort((a, b) => a.displayOrder - b.displayOrder));
    setPolicy({
      enabledEntityTypes: [...result.sensitiveDataPolicy.enabledEntityTypes],
      autoRedactThreshold: result.sensitiveDataPolicy.autoRedactThreshold,
      reviewMode: result.sensitiveDataPolicy.reviewMode,
      maskSsnLastFour: result.sensitiveDataPolicy.maskSsnLastFour,
    });
    setLifecyclePolicy({ ...result.lifecyclePolicy });
  };

  const identifierValidation = useMemo(() => {
    if (identifiers.length === 0) return 'At least one identifier is required.';
    if (identifiers.some((item) => !item.label.trim())) return 'Every identifier needs a label.';
    if (identifiers.filter((item) => item.primary).length !== 1) return 'Choose exactly one Primary identifier.';
    const labels = identifiers.map((item) => item.label.trim().toLowerCase());
    if (new Set(labels).size !== labels.length) return 'Identifier labels must be unique.';
    return null;
  }, [identifiers]);

  const updateIdentifier = (index: number, patch: Partial<BusinessAdminIdentifierDraft>) => {
    setIdentifiers((current) => current.map((item, i) => (i === index ? { ...item, ...patch } : item)));
  };

  const selectPrimary = (index: number) => {
    setIdentifiers((current) => current.map((item, i) => (
      i === index
        ? { ...item, primary: true, required: true, displayable: true }
        : { ...item, primary: false }
    )));
  };

  const moveIdentifier = (index: number, direction: -1 | 1) => {
    const target = index + direction;
    if (target < 0 || target >= identifiers.length) return;
    setIdentifiers((current) => {
      const next = [...current];
      [next[index], next[target]] = [next[target], next[index]];
      return next;
    });
  };

  const addIdentifier = () => {
    setIdentifiers((current) => [
      ...current,
      {
        label: '',
        required: false,
        primary: current.length === 0,
        searchable: true,
        extractable: false,
        displayable: true,
      },
    ]);
  };

  const removeIdentifier = (index: number) => {
    setIdentifiers((current) => {
      const removedWasPrimary = current[index]?.primary;
      const next = current.filter((_, i) => i !== index);
      if (removedWasPrimary && next.length > 0) {
        next[0] = { ...next[0], primary: true };
      }
      return next;
    });
  };

  const saveIdentifiers = async () => {
    if (identifierValidation) return;
    setSavingIdentifiers(true);
    setError(null);
    setSuccess(null);
    try {
      const result = await apiClient.updateBusinessIdentifiers(
        identifiers.map((item) => ({ ...item, label: item.label.trim() }))
      );
      applyConfiguration(result);
      setSuccess('Business identifiers saved. New intakes will use this configuration.');
    } catch (err) {
      const apiError = err as ApiError;
      setError(apiError.message || 'Unable to save business identifiers');
    } finally {
      setSavingIdentifiers(false);
    }
  };

  const updateDashboardColumn = (index: number, patch: Partial<DashboardAdminColumnConfiguration>) => {
    setDashboardColumns((current) => current.map((item, i) => (i === index ? { ...item, ...patch } : item)));
  };

  const moveDashboardColumn = (index: number, direction: -1 | 1) => {
    const target = index + direction;
    if (target < 0 || target >= dashboardColumns.length) return;
    setDashboardColumns((current) => {
      const next = [...current];
      [next[index], next[target]] = [next[target], next[index]];
      return next.map((column, order) => ({ ...column, displayOrder: order }));
    });
  };

  const saveDashboardColumns = async () => {
    setSavingDashboardColumns(true);
    setError(null);
    setSuccess(null);
    try {
      const result = await apiClient.updateDashboardColumns(dashboardColumns);
      applyConfiguration(result);
      setSuccess('Dashboard column policy saved. Users can personalize only the permitted columns.');
    } catch (err) {
      const apiError = err as ApiError;
      setError(apiError.message || 'Unable to save dashboard column policy');
    } finally {
      setSavingDashboardColumns(false);
    }
  };

  const toggleSensitiveType = (type: string) => {
    if (!policy) return;
    const current = new Set(policy.enabledEntityTypes);
    if (current.has(type)) current.delete(type);
    else current.add(type);
    setPolicy({ ...policy, enabledEntityTypes: [...current] });
  };

  const savePolicy = async () => {
    if (!policy || policy.enabledEntityTypes.length === 0) {
      setError('Enable at least one sensitive-data category.');
      return;
    }
    setSavingPolicy(true);
    setError(null);
    setSuccess(null);
    try {
      const result = await apiClient.updateSensitiveDataPolicy(policy);
      applyConfiguration(result);
      setSuccess('Sensitive-data policy saved. It will apply to new processing runs.');
    } catch (err) {
      const apiError = err as ApiError;
      setError(apiError.message || 'Unable to save sensitive-data policy');
    } finally {
      setSavingPolicy(false);
    }
  };

  const saveLifecyclePolicy = async () => {
    if (!lifecyclePolicy) return;
    setSavingLifecycle(true);
    setError(null);
    setSuccess(null);
    try {
      const result = await apiClient.updateLifecyclePolicy({
        originalRetentionHours: lifecyclePolicy.originalRetentionHours,
        redactedCacheHours: lifecyclePolicy.redactedCacheHours,
        neverExportedHoldHours: lifecyclePolicy.neverExportedHoldHours,
        finalApprovalRequired: lifecyclePolicy.finalApprovalRequired,
      });
      applyConfiguration(result);
      setSuccess('Lifecycle policy saved. New successful exports will use these retention settings.');
    } catch (err) {
      const apiError = err as ApiError;
      setError(apiError.message || 'Unable to save lifecycle policy');
    } finally {
      setSavingLifecycle(false);
    }
  };

  const setReviewMode = (reviewMode: BusinessReviewMode) => {
    if (policy) setPolicy({ ...policy, reviewMode });
  };

  const resetRoutingDraft = () => setRoutingDraft({
    destinationLabel: '', markerType: 'TEXT_PATTERN', markerValue: '', scanRegion: 'TOP_RIGHT', destinationConnectionId: '', enabled: true,
  });

  const saveRoutingRule = async () => {
    if (!routingDraft.destinationLabel.trim() || !routingDraft.markerValue.trim() || !routingDraft.destinationConnectionId) {
      setError('Routing destination label, marker value, and destination connection are required.');
      return;
    }
    setSavingRouting(true); setError(null); setSuccess(null);
    try {
      const body: RoutingRuleRequest = { ...routingDraft, destinationLabel: routingDraft.destinationLabel.trim(), markerValue: routingDraft.markerValue.trim() };
      if (routingDraft.ruleId) await apiClient.updateRoutingRule(routingDraft.ruleId, body);
      else await apiClient.createRoutingRule(body);
      const [rules, destinations] = await Promise.all([apiClient.listRoutingRules(), apiClient.listRoutingDestinations()]);
      setRoutingRules(rules); setRoutingDestinations(destinations); resetRoutingDraft();
      setSuccess('Routing rule saved. Derived routing will use the approved redacted-document destination.');
    } catch (err) {
      const apiError = err as ApiError; setError(apiError.message || 'Unable to save routing rule');
    } finally { setSavingRouting(false); }
  };

  const editRoutingRule = (rule: RoutingRule) => setRoutingDraft({
    ruleId: rule.ruleId, destinationLabel: rule.destinationLabel, markerType: rule.markerType,
    markerValue: rule.markerValue, scanRegion: rule.scanRegion, destinationConnectionId: rule.destinationConnectionId, enabled: rule.enabled,
  });

  const deleteRoutingRule = async (ruleId: string) => {
    setError(null); setSuccess(null);
    try { await apiClient.deleteRoutingRule(ruleId); setRoutingRules(await apiClient.listRoutingRules()); if (routingDraft.ruleId === ruleId) resetRoutingDraft(); setSuccess('Routing rule removed.'); }
    catch (err) { const apiError = err as ApiError; setError(apiError.message || 'Unable to remove routing rule'); }
  };

  if (loading) {
    return (
      <div className="business-admin-page">
        <Breadcrumbs items={[{ label: 'Administration' }, { label: 'Business Admin' }]} />
        <div className="card">Loading Business Admin configuration...</div>
      </div>
    );
  }

  return (
    <div className="business-admin-page">
      <Breadcrumbs items={[{ label: 'Administration' }, { label: 'Business Admin' }]} />
      <div className="business-admin-header">
        <div>
          <h1>Business Admin</h1>
          <p>Configure business terminology and how sensitive information is handled.</p>
        </div>
        <button className="secondary" onClick={() => void load()}>Refresh</button>
      </div>

      {error && <div className="error-msg">{error}</div>}
      {success && <div className="success-msg">{success}</div>}

      <section className="card admin-section-card">
        <div className="admin-section-heading">
          <div>
            <h2>Business Identifiers</h2>
            <p>These fields identify the long-lived business record and appear throughout intake and search.</p>
          </div>
          <button className="secondary" onClick={addIdentifier}>+ Add Identifier</button>
        </div>

        <div className="identifier-admin-list">
          {identifiers.map((item, index) => (
            <div className="identifier-admin-row" key={item.id || `new-${index}`}>
              <div className="identifier-order-controls" aria-label="Reorder identifier">
                <button className="icon-button" onClick={() => moveIdentifier(index, -1)} disabled={index === 0} title="Move up">↑</button>
                <button className="icon-button" onClick={() => moveIdentifier(index, 1)} disabled={index === identifiers.length - 1} title="Move down">↓</button>
              </div>

              <div className="identifier-main-field">
                <label>Label</label>
                <input
                  value={item.label}
                  placeholder="e.g. Contract Number"
                  onChange={(event) => updateIdentifier(index, { label: event.target.value })}
                  maxLength={128}
                />
              </div>

              <label className="admin-check primary-choice">
                <input type="radio" name="primaryIdentifier" checked={item.primary} onChange={() => selectPrimary(index)} />
                <span>Primary</span>
              </label>
              <label className="admin-check">
                <input
                  type="checkbox"
                  checked={item.required}
                  disabled={item.primary}
                  onChange={(event) => updateIdentifier(index, { required: event.target.checked })}
                />
                <span>Required</span>
              </label>
              <label className="admin-check">
                <input type="checkbox" checked={item.searchable} onChange={(event) => updateIdentifier(index, { searchable: event.target.checked })} />
                <span>Searchable</span>
              </label>
              <label className="admin-check">
                <input type="checkbox" checked={item.extractable} onChange={(event) => updateIdentifier(index, { extractable: event.target.checked })} />
                <span>Extractable</span>
              </label>
              <label className="admin-check">
                <input
                  type="checkbox"
                  checked={item.displayable}
                  disabled={item.primary}
                  onChange={(event) => updateIdentifier(index, { displayable: event.target.checked })}
                />
                <span>Displayable</span>
              </label>

              <button className="admin-remove" onClick={() => removeIdentifier(index)} disabled={identifiers.length === 1}>Remove</button>
            </div>
          ))}
        </div>

        <div className="admin-help-row">
          <span>Exactly one identifier is Primary. Primary is always Required and Displayable; additional identifiers may independently be Required.</span>
          {identifierValidation && <strong>{identifierValidation}</strong>}
        </div>
        <div className="admin-save-row">
          <button className="primary" disabled={savingIdentifiers || !!identifierValidation} onClick={() => void saveIdentifiers()}>
            {savingIdentifiers ? 'Saving...' : 'Save Identifiers'}
          </button>
        </div>
      </section>

      <section className="card admin-section-card">
        <div className="admin-section-heading">
          <div>
            <h2>Dashboard Columns</h2>
            <p>Choose which operational columns users may select and which ones appear by default.</p>
          </div>
        </div>

        <div className="dashboard-admin-list">
          {dashboardColumns.map((column, index) => (
            <div className="dashboard-admin-row" key={column.key}>
              <div className="identifier-order-controls" aria-label={`Reorder ${column.label}`}>
                <button className="icon-button" onClick={() => moveDashboardColumn(index, -1)} disabled={index === 0} title="Move up">↑</button>
                <button className="icon-button" onClick={() => moveDashboardColumn(index, 1)} disabled={index === dashboardColumns.length - 1} title="Move down">↓</button>
              </div>
              <div className="dashboard-admin-column-name">
                <strong>{column.label}</strong>
                <span>{column.kind === 'IDENTIFIER' ? 'Business identifier' : 'Operational field'}{column.primary ? ' · Primary' : ''}</span>
              </div>
              <label className="admin-check">
                <input
                  type="checkbox"
                  checked={column.allowed}
                  disabled={column.primary}
                  onChange={(event) => updateDashboardColumn(index, {
                    allowed: event.target.checked,
                    defaultVisible: event.target.checked ? column.defaultVisible : false,
                  })}
                />
                <span>Allowed</span>
              </label>
              <label className="admin-check">
                <input
                  type="checkbox"
                  checked={column.defaultVisible}
                  disabled={column.primary || !column.allowed}
                  onChange={(event) => updateDashboardColumn(index, { defaultVisible: event.target.checked })}
                />
                <span>Default</span>
              </label>
            </div>
          ))}
        </div>
        <div className="admin-help-row">
          <span>The Primary business identifier always remains Allowed and Default so the Dashboard never falls back to an internal UUID.</span>
        </div>
        <div className="admin-save-row">
          <button className="primary" disabled={savingDashboardColumns} onClick={() => void saveDashboardColumns()}>
            {savingDashboardColumns ? 'Saving...' : 'Save Dashboard Columns'}
          </button>
        </div>
      </section>

      <section className="card admin-section-card">
        <div className="admin-section-heading">
          <div>
            <h2>Sensitive Data Policy</h2>
            <p>Choose what LexaVault should find and permanently redact for this tenant.</p>
          </div>
        </div>

        {policy && (
          <>
            <div className="sensitive-type-grid">
              {SENSITIVE_TYPES.map((item) => {
                const enabled = policy.enabledEntityTypes.includes(item.key);
                return (
                  <button
                    type="button"
                    key={item.key}
                    className={`sensitive-type-card ${enabled ? 'enabled' : ''}`}
                    onClick={() => toggleSensitiveType(item.key)}
                    aria-pressed={enabled}
                  >
                    <span className="policy-toggle-indicator">{enabled ? '✓' : ''}</span>
                    <span>{item.label}</span>
                    <small>{enabled ? 'Redact' : 'Ignored by policy'}</small>
                  </button>
                );
              })}
            </div>

            <div className="policy-controls-grid">
              <div className="policy-control-card">
                <label htmlFor="confidenceThreshold">Automatic redaction threshold</label>
                <div className="threshold-row">
                  <input
                    id="confidenceThreshold"
                    type="range"
                    min="50"
                    max="99"
                    step="1"
                    value={Math.round(policy.autoRedactThreshold * 100)}
                    onChange={(event) => setPolicy({ ...policy, autoRedactThreshold: Number(event.target.value) / 100 })}
                  />
                  <strong>{Math.round(policy.autoRedactThreshold * 100)}%</strong>
                </div>
                <p>Findings below this threshold require review instead of irreversible automatic redaction.</p>
              </div>

              <div className="policy-control-card">
                <span className="policy-control-label">SSN exposure handling</span>
                <label className={`review-mode-option ${policy.maskSsnLastFour ? 'selected' : ''}`}>
                  <input
                    type="checkbox"
                    checked={policy.maskSsnLastFour}
                    disabled={!policy.enabledEntityTypes.includes('SSN')}
                    onChange={(event) => setPolicy({ ...policy, maskSsnLastFour: event.target.checked })}
                  />
                  <span>
                    <strong>Mask exposed SSN last four</strong>
                    <small>When only four digits are visible in clear SSN context, redact those four digits. Unrelated four-digit numbers stay visible.</small>
                  </span>
                </label>
              </div>

              <div className="policy-control-card">
                <span className="policy-control-label">Review behavior</span>
                <label className={`review-mode-option ${policy.reviewMode === 'UNCERTAIN_ONLY' ? 'selected' : ''}`}>
                  <input type="radio" checked={policy.reviewMode === 'UNCERTAIN_ONLY'} onChange={() => setReviewMode('UNCERTAIN_ONLY')} />
                  <span>
                    <strong>Review only uncertain findings</strong>
                    <small>Recommended. High-confidence findings continue automatically.</small>
                  </span>
                </label>
                <label className={`review-mode-option ${policy.reviewMode === 'REVIEW_ALL' ? 'selected' : ''}`}>
                  <input type="radio" checked={policy.reviewMode === 'REVIEW_ALL'} onChange={() => setReviewMode('REVIEW_ALL')} />
                  <span>
                    <strong>Review every finding</strong>
                    <small>Useful temporarily while a customer builds trust in the automated process.</small>
                  </span>
                </label>
              </div>
            </div>

            <div className="admin-save-row">
              <button className="primary" disabled={savingPolicy || policy.enabledEntityTypes.length === 0} onClick={() => void savePolicy()}>
                {savingPolicy ? 'Saving...' : 'Save Sensitive Data Policy'}
              </button>
            </div>
          </>
        )}
      </section>

      <section className="card admin-section-card">
        <div className="admin-section-heading">
          <div>
            <h2>Derived Document Routing</h2>
            <p>Map deterministic markers to destinations configured by Tenant IT. The full redacted master remains intact and exports independently.</p>
          </div>
        </div>

        <div className="policy-controls-grid">
          <div className="policy-control-card">
            <label>Destination label</label>
            <input value={routingDraft.destinationLabel} onChange={(e) => setRoutingDraft({ ...routingDraft, destinationLabel: e.target.value })} placeholder="South Carolina" />
          </div>
          <div className="policy-control-card">
            <label>Marker type</label>
            <select value={routingDraft.markerType} onChange={(e) => setRoutingDraft({ ...routingDraft, markerType: e.target.value as RoutingMarkerType })}>
              <option value="TEXT_PATTERN">Text pattern</option><option value="BARCODE">Barcode</option><option value="QR_CODE">QR code</option>
            </select>
          </div>
          <div className="policy-control-card">
            <label>Marker value</label>
            <input value={routingDraft.markerValue} onChange={(e) => setRoutingDraft({ ...routingDraft, markerValue: e.target.value })} placeholder="77777777" />
            <p>Text markers are normalized, so decorative bars/spaces do not need to match exactly.</p>
          </div>
          <div className="policy-control-card">
            <label>Fixed scan region</label>
            <select value={routingDraft.scanRegion} onChange={(e) => setRoutingDraft({ ...routingDraft, scanRegion: e.target.value as RoutingScanRegion })}>
              <option value="TOP_RIGHT">Top right</option><option value="BOTTOM_LEFT">Bottom left</option><option value="BOTH">Both</option>
            </select>
          </div>
          <div className="policy-control-card">
            <label>Approved destination</label>
            <select value={routingDraft.destinationConnectionId} onChange={(e) => setRoutingDraft({ ...routingDraft, destinationConnectionId: e.target.value })}>
              <option value="">Select destination…</option>
              {routingDestinations.filter((d) => d.enabled).map((d) => <option key={d.connectionId} value={d.connectionId}>{d.name} · {d.providerType}</option>)}
            </select>
            <p>Technical endpoint/authentication settings stay under IT Admin → Integrations.</p>
          </div>
          <div className="policy-control-card">
            <span className="policy-control-label">Rule status</span>
            <label className={`review-mode-option ${routingDraft.enabled ? 'selected' : ''}`}>
              <input type="checkbox" checked={routingDraft.enabled} onChange={(e) => setRoutingDraft({ ...routingDraft, enabled: e.target.checked })} />
              <span><strong>Enabled</strong><small>Only enabled rules are scanned during derived routing.</small></span>
            </label>
          </div>
        </div>
        <div className="admin-save-row">
          <button className="primary" disabled={savingRouting} onClick={() => void saveRoutingRule()}>{savingRouting ? 'Saving…' : routingDraft.ruleId ? 'Update Routing Rule' : 'Add Routing Rule'}</button>
          {routingDraft.ruleId && <button className="secondary" onClick={resetRoutingDraft}>Cancel edit</button>}
        </div>

        {routingRules.length > 0 && <table>
          <thead><tr><th>Marker</th><th>Region</th><th>Destination</th><th>Status</th><th></th></tr></thead>
          <tbody>{routingRules.map((rule) => <tr key={rule.ruleId}>
            <td><strong>{rule.markerValue}</strong><br/><small>{rule.markerType}</small></td>
            <td>{rule.scanRegion.replace(/_/g, ' ')}</td><td>{rule.destinationLabel}</td><td>{rule.enabled ? 'Enabled' : 'Disabled'}</td>
            <td><div className="inline-actions"><button className="secondary" onClick={() => editRoutingRule(rule)}>Edit</button><button className="secondary" onClick={() => void deleteRoutingRule(rule.ruleId)}>Remove</button></div></td>
          </tr>)}</tbody>
        </table>}
      </section>

      {lifecyclePolicy && (
        <section className="card admin-section-card">
          <div className="admin-section-heading">
            <div>
              <h2>Retention & Delivery Approval</h2>
              <p>Control how long LexaVault keeps temporary internal artifacts after delivery.</p>
            </div>
          </div>

          <div className="policy-controls-grid">
            <div className="policy-control-card">
              <label htmlFor="originalRetentionHours">Original retention after successful export</label>
              <div className="threshold-row">
                <input
                  id="originalRetentionHours"
                  type="number"
                  min="1"
                  value={lifecyclePolicy.originalRetentionHours}
                  onChange={(event) => setLifecyclePolicy({ ...lifecyclePolicy, originalRetentionHours: Number(event.target.value) })}
                />
                <strong>{lifecyclePolicy.originalRetentionHours}h</strong>
              </div>
              <p>The original is temporary recovery/reprocessing material. The clock starts only after a successful export.</p>
            </div>

            <div className="policy-control-card">
              <label htmlFor="redactedCacheHours">Internal redacted-copy cache</label>
              <div className="threshold-row">
                <input
                  id="redactedCacheHours"
                  type="number"
                  min="1"
                  max={lifecyclePolicy.maximumRedactedCacheHours}
                  value={lifecyclePolicy.redactedCacheHours}
                  onChange={(event) => setLifecyclePolicy({ ...lifecyclePolicy, redactedCacheHours: Number(event.target.value) })}
                />
                <strong>{lifecyclePolicy.redactedCacheHours}h</strong>
              </div>
              <p>Hard maximum {lifecyclePolicy.maximumRedactedCacheHours} hours. The durable redacted document lives at the customer destination.</p>
            </div>

            <div className="policy-control-card">
              <label htmlFor="technicalHoldHours">Never-exported technical hold</label>
              <div className="threshold-row">
                <input
                  id="technicalHoldHours"
                  type="number"
                  min="72"
                  max="336"
                  value={lifecyclePolicy.neverExportedHoldHours}
                  onChange={(event) => setLifecyclePolicy({ ...lifecyclePolicy, neverExportedHoldHours: Number(event.target.value) })}
                />
                <strong>{lifecyclePolicy.neverExportedHoldHours}h</strong>
              </div>
              <p>Failed packages that never exported are held temporarily for IT recovery, never indefinitely.</p>
            </div>

            <div className="policy-control-card">
              <span className="policy-control-label">Final delivery approval</span>
              <label className={`review-mode-option ${lifecyclePolicy.finalApprovalRequired ? 'selected' : ''}`}>
                <input
                  type="checkbox"
                  checked={lifecyclePolicy.finalApprovalRequired}
                  onChange={(event) => setLifecyclePolicy({ ...lifecyclePolicy, finalApprovalRequired: event.target.checked })}
                />
                <span>
                  <strong>Require approval before delivery</strong>
                  <small>Optional trust-building mode. Redaction completes first, then output waits for an Approver before export.</small>
                </span>
              </label>
              <p>V1 permits at most {lifecyclePolicy.maximumSuccessfulExportCycles} successful export cycles for the same original intake batch.</p>
            </div>
          </div>

          <div className="admin-save-row">
            <button className="primary" disabled={savingLifecycle} onClick={() => void saveLifecyclePolicy()}>
              {savingLifecycle ? 'Saving...' : 'Save Lifecycle Policy'}
            </button>
          </div>
        </section>
      )}

      {configuration && (
        <p className="admin-footer-note">
          Delivery-provider connections, watched-folder connections, AI/OCR providers and authentication remain IT / Platform Admin configuration.
        </p>
      )}
    </div>
  );
}
