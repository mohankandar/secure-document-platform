import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { apiClient } from '../api/client';
import PageScaffold from '../components/ui/PageScaffold';
import HorizontalTabs from '../components/ui/HorizontalTabs';
import Icon from '../components/ui/Icon';
import type {
  ApiError,
  BusinessAdminConfiguration,
  BusinessAdminIdentifierDraft,
  BusinessReviewMode,
  SensitiveDataPolicyConfiguration,
  LifecyclePolicyConfiguration,
  DocumentIntelligenceConfiguration,
  DocumentIntelligenceMode,
  DashboardAdminColumnConfiguration,
  RoutingRule,
  RoutingRuleRequest,
  RoutingDestinationOption,
  RoutingMarkerType,
  RoutingScanRegion,
  ReportingConfiguration,
} from '../types';

const SENSITIVE_TYPES = [
  { key: 'SSN', label: 'Social Security Number' },
  { key: 'DATE_OF_BIRTH', label: 'Date of Birth' },
  { key: 'CREDIT_CARD_NUMBER', label: 'Credit Card Number' },
  { key: 'CREDIT_SCORE', label: 'Credit Score' },
] as const;

export default function BusinessAdminPage({ administrationTabs }: { administrationTabs?: ReactNode }) {
  const [activeSection, setActiveSection] = useState<'identifiers' | 'reporting' | 'dashboard' | 'sensitive' | 'intelligence' | 'routing' | 'retention'>('identifiers');
  const [configuration, setConfiguration] = useState<BusinessAdminConfiguration | null>(null);
  const [identifiers, setIdentifiers] = useState<BusinessAdminIdentifierDraft[]>([]);
  const [policy, setPolicy] = useState<SensitiveDataPolicyConfiguration | null>(null);
  const [lifecyclePolicy, setLifecyclePolicy] = useState<LifecyclePolicyConfiguration | null>(null);
  const [documentIntelligence, setDocumentIntelligence] = useState<DocumentIntelligenceConfiguration | null>(null);
  const [dashboardColumns, setDashboardColumns] = useState<DashboardAdminColumnConfiguration[]>([]);
  const [reportingConfiguration, setReportingConfiguration] = useState<ReportingConfiguration | null>(null);
  const [routingRules, setRoutingRules] = useState<RoutingRule[]>([]);
  const [routingDestinations, setRoutingDestinations] = useState<RoutingDestinationOption[]>([]);
  const [routingDraft, setRoutingDraft] = useState<(RoutingRuleRequest & { ruleId?: string })>({
    destinationLabel: '', markerType: 'TEXT_PATTERN', markerValue: '', scanRegion: 'TOP_RIGHT', destinationConnectionId: '', enabled: true,
  });
  const [loading, setLoading] = useState(true);
  const [savingIdentifiers, setSavingIdentifiers] = useState(false);
  const [savingPolicy, setSavingPolicy] = useState(false);
  const [savingLifecycle, setSavingLifecycle] = useState(false);
  const [savingIntelligence, setSavingIntelligence] = useState(false);
  const [savingDashboardColumns, setSavingDashboardColumns] = useState(false);
  const [savingRouting, setSavingRouting] = useState(false);
  const [savingReporting, setSavingReporting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [routingEditorOpen, setRoutingEditorOpen] = useState(false);

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
        .map(({ id, label, required, primary, searchable, extractable, displayable, validationType,
                exactLength, minLength, maxLength, caseNormalization, stripSeparators, allowedValues, customPattern }) => ({
          id,
          label,
          required,
          primary,
          searchable,
          extractable,
          displayable,
          validationType,
          exactLength,
          minLength,
          maxLength,
          caseNormalization,
          stripSeparators,
          allowedValues: [...allowedValues],
          customPattern,
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
    setDocumentIntelligence({
      ...result.documentIntelligence,
      availableBundles: [...result.documentIntelligence.availableBundles],
    });
    setReportingConfiguration({
      ...result.reportingConfiguration,
      dimensions: result.reportingConfiguration.dimensions.map((dimension) => ({ ...dimension })),
    });
  };

  const identifierValidation = useMemo(() => {
    if (identifiers.length === 0) return 'At least one identifier is required.';
    if (identifiers.some((item) => !item.label.trim())) return 'Every identifier needs a label.';
    if (identifiers.filter((item) => item.primary).length !== 1) return 'Choose exactly one Primary identifier.';
    const labels = identifiers.map((item) => item.label.trim().toLowerCase());
    if (new Set(labels).size !== labels.length) return 'Identifier labels must be unique.';
    for (const item of identifiers) {
      const lengths = [item.exactLength, item.minLength, item.maxLength].filter((value): value is number => value != null);
      if (lengths.some((value) => value < 1 || value > 512)) return `${item.label || 'Identifier'}: length constraints must be between 1 and 512.`;
      if (item.exactLength != null && (item.minLength != null || item.maxLength != null)) return `${item.label || 'Identifier'}: exact length cannot be combined with min/max length.`;
      if (item.minLength != null && item.maxLength != null && item.minLength > item.maxLength) return `${item.label || 'Identifier'}: minimum length cannot exceed maximum length.`;
      if (item.validationType === 'ALLOW_LIST' && item.allowedValues.filter((value) => value.trim().length > 0).length === 0) return `${item.label || 'Identifier'}: at least one allowed value is required.`;
      if (item.validationType === 'CUSTOM_PATTERN' && !item.customPattern?.trim()) return `${item.label || 'Identifier'}: custom pattern is required.`;
    }
    return null;
  }, [identifiers]);

  const intelligenceDirty = !!(documentIntelligence && configuration && (
    documentIntelligence.mode !== configuration.documentIntelligence.mode
      || documentIntelligence.activeBundleVersion !== configuration.documentIntelligence.activeBundleVersion
  ));

  const selectedIntelligenceBundle = useMemo(
    () => documentIntelligence?.availableBundles.find((bundle) => bundle.version === documentIntelligence.activeBundleVersion) ?? null,
    [documentIntelligence],
  );

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
        validationType: 'TEXT',
        exactLength: null,
        minLength: null,
        maxLength: null,
        caseNormalization: 'UPPER',
        stripSeparators: false,
        allowedValues: [],
        customPattern: null,
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
        identifiers.map((item) => ({
          ...item,
          label: item.label.trim(),
          allowedValues: item.allowedValues.map((value) => value.trim()).filter(Boolean),
        }))
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

  const eligibleReportingDimensions = useMemo(() => {
    if (!configuration) return [];
    const selected = new Set(reportingConfiguration?.dimensions.filter((dimension) => dimension.enabled).map((dimension) => dimension.definitionId) ?? []);
    return configuration.identifiers
      .filter((identifier) => identifier.allowedValues.length > 0 && identifier.allowedValues.length <= 100)
      .map((identifier, index) => ({
        definitionId: identifier.id,
        key: identifier.key,
        label: identifier.label,
        enabled: selected.has(identifier.id),
        displayOrder: index + 1,
      }));
  }, [configuration, reportingConfiguration]);

  const toggleReportingDimension = (definitionId: string, enabled: boolean) => {
    if (!reportingConfiguration) return;
    const current = new Map(reportingConfiguration.dimensions.map((dimension) => [dimension.definitionId, dimension]));
    const eligible = eligibleReportingDimensions.find((dimension) => dimension.definitionId === definitionId);
    if (!eligible) return;
    current.set(definitionId, { ...eligible, enabled });
    setReportingConfiguration({ ...reportingConfiguration, dimensions: [...current.values()] });
  };

  const saveReportingConfiguration = async () => {
    if (!reportingConfiguration) return;
    setSavingReporting(true); setError(null); setSuccess(null);
    try {
      const result = await apiClient.updateReportingConfiguration(reportingConfiguration);
      applyConfiguration(result);
      setSuccess('Reporting dimensions and business targets saved. Historical reporting facts remain aggregate-only and package-independent.');
    } catch (err) {
      const apiError = err as ApiError;
      setError(apiError.message || 'Unable to save reporting configuration');
    } finally { setSavingReporting(false); }
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

  const saveDocumentIntelligence = async () => {
    if (!documentIntelligence) return;
    setSavingIntelligence(true);
    setError(null);
    setSuccess(null);
    try {
      const result = await apiClient.updateDocumentIntelligence(
        documentIntelligence.mode, documentIntelligence.activeBundleVersion);
      applyConfiguration(result);
      setSuccess('Document Intelligence configuration saved. It applies to new processing cycles only.');
    } catch (err) {
      const apiError = err as ApiError;
      setError(apiError.message || 'Unable to save Document Intelligence configuration');
    } finally {
      setSavingIntelligence(false);
    }
  };

  const rollbackDocumentIntelligence = async () => {
    setSavingIntelligence(true);
    setError(null);
    setSuccess(null);
    try {
      const result = await apiClient.rollbackDocumentIntelligence();
      applyConfiguration(result);
      setSuccess('Document Intelligence rolled back to the tenant last-known-good bundle.');
    } catch (err) {
      const apiError = err as ApiError;
      setError(apiError.message || 'Unable to roll back Document Intelligence');
    } finally {
      setSavingIntelligence(false);
    }
  };

  const markDocumentIntelligenceLastKnownGood = async () => {
    setSavingIntelligence(true);
    setError(null);
    setSuccess(null);
    try {
      const result = await apiClient.markDocumentIntelligenceLastKnownGood();
      applyConfiguration(result);
      setSuccess('Current intelligence bundle marked as the tenant last-known-good version.');
    } catch (err) {
      const apiError = err as ApiError;
      setError(apiError.message || 'Unable to update the last-known-good intelligence bundle');
    } finally {
      setSavingIntelligence(false);
    }
  };

  const setDocumentIntelligenceMode = (mode: DocumentIntelligenceMode) => {
    if (documentIntelligence) setDocumentIntelligence({ ...documentIntelligence, mode });
  };

  const setReviewMode = (reviewMode: BusinessReviewMode) => {
    if (policy) setPolicy({ ...policy, reviewMode });
  };

  const resetRoutingDraft = () => {
    setRoutingDraft({
      destinationLabel: '', markerType: 'TEXT_PATTERN', markerValue: '', scanRegion: 'TOP_RIGHT', destinationConnectionId: '', enabled: true,
    });
    setRoutingEditorOpen(false);
  };

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

  const editRoutingRule = (rule: RoutingRule) => {
    setRoutingDraft({
      ruleId: rule.ruleId, destinationLabel: rule.destinationLabel, markerType: rule.markerType,
      markerValue: rule.markerValue, scanRegion: rule.scanRegion, destinationConnectionId: rule.destinationConnectionId, enabled: rule.enabled,
    });
    setRoutingEditorOpen(true);
  };

  const deleteRoutingRule = async (ruleId: string) => {
    setError(null); setSuccess(null);
    try { await apiClient.deleteRoutingRule(ruleId); setRoutingRules(await apiClient.listRoutingRules()); if (routingDraft.ruleId === ruleId) resetRoutingDraft(); setSuccess('Routing rule removed.'); }
    catch (err) { const apiError = err as ApiError; setError(apiError.message || 'Unable to remove routing rule'); }
  };

  if (loading) {
    return (
      <PageScaffold className="business-admin-page" breadcrumbs={[{ label: 'Administration' }, { label: 'Business Admin' }]} title="Business Administration" primaryNavigation={administrationTabs}>
        <div className="ui-card">Loading Business Admin configuration...</div>
      </PageScaffold>
    );
  }

  return (
    <PageScaffold
      className="business-admin-page"
      breadcrumbs={[{ label: 'Administration' }, { label: 'Business Admin' }]}
      title="Business Administration"
      eyebrow="Tenant policy"
      description="Configure business identifiers, processing policy, reporting, routing, and retention for this tenant."
      actions={<button className="ui-button secondary" onClick={() => void load()}>Refresh</button>}
      primaryNavigation={administrationTabs}
    >
      {error && <div className="ui-alert tone-danger">{error}</div>}
      {success && <div className="ui-alert tone-success">{success}</div>}

      <HorizontalTabs ariaLabel="Business administration sections" className="admin-section-nav">
        {([
          ['identifiers', 'Identifiers'],
          ['reporting', 'Reporting'],
          ['dashboard', 'Dashboard'],
          ['sensitive', 'Sensitive data'],
          ['intelligence', 'Document intelligence'],
          ['routing', 'Routing'],
          ['retention', 'Retention'],
        ] as const).map(([key, label]) => (
          <button key={key} type="button" className={activeSection === key ? 'active' : ''} onClick={() => setActiveSection(key)}>{label}</button>
        ))}
      </HorizontalTabs>

      {activeSection === 'identifiers' && <section id="business-identifiers" className="ui-card admin-section-card admin-anchor-section">
        <div className="admin-section-heading">
          <div>
            <h2>Business Identifiers</h2>
            <p>These fields identify the long-lived business record and appear throughout intake and search.</p>
          </div>
          <button className="ui-button secondary" onClick={addIdentifier}>+ Add Identifier</button>
        </div>

        <div className="identifier-admin-list">
          {identifiers.map((item, index) => (
            <details className="identifier-admin-card admin-disclosure" key={item.id || `new-${index}`} open={!item.id || undefined}>
              <summary className="identifier-summary-row">
                <div>
                  <strong>{item.label.trim() || 'New identifier'}</strong>
                  <span>{item.validationType.replace(/_/g, ' ').toLowerCase()}</span>
                </div>
                <div className="identifier-summary-badges" aria-label="Identifier attributes">
                  {item.primary && <span>Primary</span>}
                  {item.required && <span>Required</span>}
                  {item.searchable && <span>Searchable</span>}
                  {item.extractable && <span>Extractable</span>}
                </div>
              </summary>
              <div className="identifier-editor-body">
              <div className="identifier-admin-row">
                <div className="identifier-order-controls" aria-label="Reorder identifier">
                  <button className="ui-button icon" onClick={() => moveIdentifier(index, -1)} disabled={index === 0} title="Move up" aria-label="Move up"><Icon name="arrowUp" /></button>
                  <button className="ui-button icon" onClick={() => moveIdentifier(index, 1)} disabled={index === identifiers.length - 1} title="Move down" aria-label="Move down"><Icon name="arrowDown" /></button>
                </div>

                <div className="identifier-main-field">
                  <label>Label</label>
                  <input
                    value={item.label}
                    placeholder="e.g. Business Reference"
                    onChange={(event) => updateIdentifier(index, { label: event.target.value })}
                    maxLength={128}
                  />
                </div>

                <label className="admin-check primary-choice">
                  <input type="radio" name="primaryIdentifier" checked={item.primary} onChange={() => selectPrimary(index)} />
                  <span>Primary</span>
                </label>
                <label className="admin-check">
                  <input type="checkbox" checked={item.required} disabled={item.primary} onChange={(event) => updateIdentifier(index, { required: event.target.checked })} />
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
                  <input type="checkbox" checked={item.displayable} disabled={item.primary} onChange={(event) => updateIdentifier(index, { displayable: event.target.checked })} />
                  <span>Displayable</span>
                </label>
                <button className="admin-remove" onClick={() => removeIdentifier(index)} disabled={identifiers.length === 1}>Remove</button>
              </div>

              <div className="identifier-validation-grid">
                <div className="form-group">
                  <label>Validation</label>
                  <select value={item.validationType} onChange={(event) => updateIdentifier(index, { validationType: event.target.value as BusinessAdminIdentifierDraft['validationType'], customPattern: event.target.value === 'CUSTOM_PATTERN' ? item.customPattern : null })}>
                    <option value="TEXT">Text</option>
                    <option value="NUMERIC">Numeric (digits only)</option>
                    <option value="ALPHANUMERIC">Alphanumeric</option>
                    <option value="EMAIL">Email</option>
                    <option value="UUID">UUID</option>
                    <option value="ALLOW_LIST">Allow list / enum</option>
                    <option value="CUSTOM_PATTERN">Custom pattern / regex</option>
                  </select>
                </div>
                <div className="form-group">
                  <label>Exact length</label>
                  <input type="number" min={1} max={512} value={item.exactLength ?? ''} onChange={(event) => updateIdentifier(index, { exactLength: event.target.value ? Number(event.target.value) : null, minLength: event.target.value ? null : item.minLength, maxLength: event.target.value ? null : item.maxLength })} />
                </div>
                <div className="form-group">
                  <label>Min length</label>
                  <input type="number" min={1} max={512} disabled={item.exactLength != null} value={item.minLength ?? ''} onChange={(event) => updateIdentifier(index, { minLength: event.target.value ? Number(event.target.value) : null })} />
                </div>
                <div className="form-group">
                  <label>Max length</label>
                  <input type="number" min={1} max={512} disabled={item.exactLength != null} value={item.maxLength ?? ''} onChange={(event) => updateIdentifier(index, { maxLength: event.target.value ? Number(event.target.value) : null })} />
                </div>
                <div className="form-group">
                  <label>Canonical case</label>
                  <select disabled={!!item.id} value={item.caseNormalization} onChange={(event) => updateIdentifier(index, { caseNormalization: event.target.value as BusinessAdminIdentifierDraft['caseNormalization'] })}>
                    <option value="PRESERVE">Preserve</option>
                    <option value="UPPER">Uppercase</option>
                    <option value="LOWER">Lowercase</option>
                  </select>
                </div>
                <label className="admin-check validation-checkbox">
                  <input type="checkbox" disabled={!!item.id} checked={item.stripSeparators} onChange={(event) => updateIdentifier(index, { stripSeparators: event.target.checked })} />
                  <span>Remove spaces/hyphens in canonical value{item.id ? ' (fixed after creation)' : ''}</span>
                </label>
                <div className="form-group identifier-allowed-values">
                  <label>Allowed values <span>(optional, one per line or comma-separated)</span></label>
                  <textarea
                    rows={2}
                    value={item.allowedValues.join(', ')}
                    onChange={(event) => updateIdentifier(index, {
                      // Keep an empty trailing token while the admin is typing a comma/new line.
                      // Filtering here made the controlled textarea immediately remove the delimiter,
                      // which prevented entering a second allowed value. Empty tokens are removed on save.
                      allowedValues: event.target.value.split(/[\n,]/).map((value) => value.trim()),
                    })}
                    placeholder="e.g. 000, 001, 002"
                  />
                </div>
                {item.validationType === 'CUSTOM_PATTERN' && (
                  <div className="form-group identifier-custom-pattern">
                    <label>Custom regular expression</label>
                    <input value={item.customPattern ?? ''} onChange={(event) => updateIdentifier(index, { customPattern: event.target.value })} maxLength={512} placeholder="^[A-Z]{2}[0-9]{8}$" />
                  </div>
                )}
              </div>
              </div>
            </details>
          ))}
        </div>

        <div className="admin-help-row">
          <span>Exactly one identifier is Primary. Primary is always Required and Displayable; additional identifiers may independently be Required.</span>
          {identifierValidation && <strong>{identifierValidation}</strong>}
        </div>
        <div className="admin-save-row">
          <button className="ui-button primary" disabled={savingIdentifiers || !!identifierValidation} onClick={() => void saveIdentifiers()}>
            {savingIdentifiers ? 'Saving...' : 'Save Identifiers'}
          </button>
        </div>
      </section>}


      {activeSection === 'reporting' && reportingConfiguration && (
        <section id="reporting-targets" className="ui-card admin-section-card admin-anchor-section">
          <div className="admin-section-heading">
            <div>
              <h2>Reporting & Business Targets</h2>
              <p>Choose approved low-cardinality business dimensions and the targets used to judge delivery, automation, and review performance.</p>
            </div>
          </div>

          <div className="policy-controls-grid">
            <div className="ui-card subtle policy-control-card">
              <label>Delivery time target</label>
              <div className="threshold-row"><input type="number" min="1" max="10080" value={reportingConfiguration.deliveryTargetMinutes} onChange={(e) => setReportingConfiguration({ ...reportingConfiguration, deliveryTargetMinutes: Number(e.target.value) })} /><strong>{reportingConfiguration.deliveryTargetMinutes}m</strong></div>
              <p>Measured from intake to first successful delivery.</p>
            </div>
            <div className="ui-card subtle policy-control-card">
              <label>Delivery SLA target</label>
              <div className="threshold-row"><input type="number" min="1" max="100" step="0.1" value={reportingConfiguration.deliveryTargetPercent} onChange={(e) => setReportingConfiguration({ ...reportingConfiguration, deliveryTargetPercent: Number(e.target.value) })} /><strong>{reportingConfiguration.deliveryTargetPercent.toFixed(1)}%</strong></div>
              <p>Target share of eligible packages delivered within the configured time.</p>
            </div>
            <div className="ui-card subtle policy-control-card">
              <label>Straight-through target</label>
              <div className="threshold-row"><input type="number" min="0" max="100" step="0.1" value={reportingConfiguration.straightThroughTargetPercent} onChange={(e) => setReportingConfiguration({ ...reportingConfiguration, straightThroughTargetPercent: Number(e.target.value) })} /><strong>{reportingConfiguration.straightThroughTargetPercent.toFixed(1)}%</strong></div>
              <p>Target share of delivered packages with no recorded review workflow.</p>
            </div>
            <div className="ui-card subtle policy-control-card">
              <label>Review wait target</label>
              <div className="threshold-row"><input type="number" min="1" max="10080" value={reportingConfiguration.reviewWaitTargetMinutes} onChange={(e) => setReportingConfiguration({ ...reportingConfiguration, reviewWaitTargetMinutes: Number(e.target.value) })} /><strong>{reportingConfiguration.reviewWaitTargetMinutes}m</strong></div>
              <p>Target time from review creation until a reviewer starts work.</p>
            </div>
          </div>

          <div className="admin-section-heading reporting-dimension-heading">
            <div>
              <h3>Approved reporting dimensions</h3>
              <p>V1 allows identifiers with a tenant-controlled allowed-value set of 1–100 values. This supports dimensions such as Service Entity while preventing high-cardinality identifiers such as contract/member numbers from becoming reporting dimensions.</p>
            </div>
          </div>
          <div className="sensitive-type-grid">
            {eligibleReportingDimensions.length === 0 && <div className="admin-help-row"><span>No eligible low-cardinality identifier is configured. Add 1–100 allowed values to an approved business identifier first.</span></div>}
            {eligibleReportingDimensions.map((dimension) => (
              <button type="button" key={dimension.definitionId} className={`sensitive-type-card ${dimension.enabled ? 'enabled' : ''}`} onClick={() => toggleReportingDimension(dimension.definitionId, !dimension.enabled)} aria-pressed={dimension.enabled}>
                <span className="policy-toggle-indicator">{dimension.enabled ? '✓' : ''}</span>
                <span>{dimension.label}</span>
                <small>{dimension.enabled ? 'Included in reporting' : 'Not used as a reporting dimension'}</small>
              </button>
            ))}
          </div>
          <div className="admin-save-row"><button className="ui-button primary" disabled={savingReporting} onClick={() => void saveReportingConfiguration()}>{savingReporting ? 'Saving...' : 'Save Reporting Targets'}</button></div>
        </section>
      )}

      {activeSection === 'dashboard' && <section id="dashboard-columns" className="ui-card admin-section-card admin-anchor-section">
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
                <button className="ui-button icon" onClick={() => moveDashboardColumn(index, -1)} disabled={index === 0} title="Move up" aria-label="Move up"><Icon name="arrowUp" /></button>
                <button className="ui-button icon" onClick={() => moveDashboardColumn(index, 1)} disabled={index === dashboardColumns.length - 1} title="Move down" aria-label="Move down"><Icon name="arrowDown" /></button>
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
          <button className="ui-button primary" disabled={savingDashboardColumns} onClick={() => void saveDashboardColumns()}>
            {savingDashboardColumns ? 'Saving...' : 'Save Dashboard Columns'}
          </button>
        </div>
      </section>}

      {activeSection === 'sensitive' && <section id="sensitive-policy" className="ui-card admin-section-card admin-anchor-section">
        <div className="admin-section-heading">
          <div>
            <h2>Sensitive Data Policy</h2>
            <p>Choose what the platform should find and permanently redact for this tenant.</p>
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
              <div className="ui-card subtle policy-control-card">
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

              <div className="ui-card subtle policy-control-card">
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

              <div className="ui-card subtle policy-control-card">
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
              <button className="ui-button primary" disabled={savingPolicy || policy.enabledEntityTypes.length === 0} onClick={() => void savePolicy()}>
                {savingPolicy ? 'Saving...' : 'Save Sensitive Data Policy'}
              </button>
            </div>
          </>
        )}
      </section>}

      {activeSection === 'intelligence' && documentIntelligence && (
        <section id="document-intelligence" className="ui-card admin-section-card admin-anchor-section">
          <div className="admin-section-heading">
            <div>
              <h2>Document Intelligence</h2>
              <p>Control optional adaptive/profile intelligence without changing the frozen baseline discovery path.</p>
            </div>
          </div>

          <div className="policy-controls-grid">
            <div className="ui-card subtle policy-control-card">
              <span className="policy-control-label">Activation mode</span>
              <label className={`review-mode-option ${documentIntelligence.mode === 'DISABLED' ? 'selected' : ''}`}>
                <input type="radio" checked={documentIntelligence.mode === 'DISABLED'} onChange={() => setDocumentIntelligenceMode('DISABLED')} />
                <span>
                  <strong>Disabled</strong>
                  <small>Use the frozen R11/general-AI baseline only. Adaptive/profile intelligence is skipped.</small>
                </span>
              </label>
              <label className={`review-mode-option ${documentIntelligence.mode === 'OBSERVE' ? 'selected' : ''}`}>
                <input type="radio" checked={documentIntelligence.mode === 'OBSERVE'} onChange={() => setDocumentIntelligenceMode('OBSERVE')} />
                <span>
                  <strong>Observe</strong>
                  <small>Installed intelligence may evaluate documents, but cannot affect redaction, review, or export decisions.</small>
                </span>
              </label>
              <label className={`review-mode-option ${documentIntelligence.mode === 'ENABLED' ? 'selected' : ''}`}>
                <input type="radio" checked={documentIntelligence.mode === 'ENABLED'} onChange={() => setDocumentIntelligenceMode('ENABLED')} />
                <span>
                  <strong>Enabled</strong>
                  <small>Installed intelligence may contribute priors/context. Baseline discovery remains mandatory.</small>
                </span>
              </label>
            </div>

            <div className="ui-card subtle policy-control-card">
              <label htmlFor="documentIntelligenceBundle">Pinned intelligence bundle</label>
              <select
                id="documentIntelligenceBundle"
                value={documentIntelligence.activeBundleVersion}
                onChange={(event) => setDocumentIntelligence({ ...documentIntelligence, activeBundleVersion: event.target.value })}
              >
                {documentIntelligence.availableBundles
                  .filter((bundle) => bundle.status === 'PROMOTED' || bundle.version === documentIntelligence.activeBundleVersion)
                  .map((bundle) => <option key={bundle.version} value={bundle.version}>{bundle.version} · {bundle.status}</option>)}
              </select>
              <p>Only promoted bundles can be newly selected. Configuration v{documentIntelligence.configurationVersion}.</p>
              <dl className="technical-fingerprint-grid">
                <div><dt>Active</dt><dd>{documentIntelligence.activeBundleVersion}</dd></div>
                <div><dt>Last known good</dt><dd>{documentIntelligence.lastKnownGoodBundleVersion}</dd></div>
                {selectedIntelligenceBundle && <>
                  <div><dt>Model version</dt><dd>{selectedIntelligenceBundle.modelVersion}</dd></div>
                  <div><dt>Prompt version</dt><dd>{selectedIntelligenceBundle.promptVersion}</dd></div>
                  <div><dt>Detector version</dt><dd>{selectedIntelligenceBundle.detectorVersion}</dd></div>
                  <div><dt>OCR rules</dt><dd>{selectedIntelligenceBundle.ocrRulesVersion}</dd></div>
                  <div><dt>Profile version</dt><dd>{selectedIntelligenceBundle.profileVersion}</dd></div>
                  <div><dt>Validation rules</dt><dd>{selectedIntelligenceBundle.validationRulesVersion}</dd></div>
                  <div><dt>Thresholds</dt><dd>{selectedIntelligenceBundle.thresholdsVersion}</dd></div>
                </>}
              </dl>
            </div>

            <div className="ui-card subtle policy-control-card">
              <span className="policy-control-label">Safety boundary</span>
              <p><strong>Baseline discovery is always on.</strong> Document Intelligence cannot suppress the existing R11/general-AI, OCR, deterministic, review, or export-safety paths.</p>
              <p>Changing this setting affects only new processing cycles. Existing package cycles keep the version snapshot captured when they started.</p>
              <p>The current baseline bundle contains no adaptive document profile, so enabling it does not change R11 behavior by itself.</p>
            </div>
          </div>

          <div className="admin-save-row">
            <button className="ui-button primary" disabled={savingIntelligence} onClick={() => void saveDocumentIntelligence()}>
              {savingIntelligence ? 'Saving...' : 'Save Document Intelligence'}
            </button>
            <button
              className="ui-button secondary"
              disabled={savingIntelligence || intelligenceDirty || documentIntelligence.activeBundleVersion === documentIntelligence.lastKnownGoodBundleVersion}
              onClick={() => void rollbackDocumentIntelligence()}
            >
              Roll back to Last Known Good
            </button>
            <button
              className="ui-button secondary"
              disabled={savingIntelligence || intelligenceDirty || documentIntelligence.activeBundleVersion === documentIntelligence.lastKnownGoodBundleVersion}
              onClick={() => void markDocumentIntelligenceLastKnownGood()}
            >
              Mark Current as Last Known Good
            </button>
          </div>
        </section>
      )}

      {activeSection === 'routing' && <section id="derived-routing" className="ui-card admin-section-card admin-anchor-section">
        <div className="admin-section-heading">
          <div>
            <h2>Derived Document Routing</h2>
            <p>Map deterministic markers to destinations configured by Tenant IT. The full redacted master remains intact and exports independently.</p>
          </div>
          <button className="ui-button secondary" type="button" onClick={() => { resetRoutingDraft(); setRoutingEditorOpen(true); }}>+ Add Routing Rule</button>
        </div>

        {routingEditorOpen && <div className="admin-inline-editor">
        <div className="policy-controls-grid">
          <div className="ui-card subtle policy-control-card">
            <label>Destination label</label>
            <input value={routingDraft.destinationLabel} onChange={(e) => setRoutingDraft({ ...routingDraft, destinationLabel: e.target.value })} placeholder="South Carolina" />
          </div>
          <div className="ui-card subtle policy-control-card">
            <label>Marker type</label>
            <select value={routingDraft.markerType} onChange={(e) => setRoutingDraft({ ...routingDraft, markerType: e.target.value as RoutingMarkerType })}>
              <option value="TEXT_PATTERN">Text pattern</option><option value="BARCODE">Barcode</option><option value="QR_CODE">QR code</option>
            </select>
          </div>
          <div className="ui-card subtle policy-control-card">
            <label>Marker value</label>
            <input value={routingDraft.markerValue} onChange={(e) => setRoutingDraft({ ...routingDraft, markerValue: e.target.value })} placeholder="77777777" />
            <p>Text markers are normalized, so decorative bars/spaces do not need to match exactly.</p>
          </div>
          <div className="ui-card subtle policy-control-card">
            <label>Fixed scan region</label>
            <select value={routingDraft.scanRegion} onChange={(e) => setRoutingDraft({ ...routingDraft, scanRegion: e.target.value as RoutingScanRegion })}>
              <option value="TOP_RIGHT">Top right</option><option value="BOTTOM_LEFT">Bottom left</option><option value="BOTH">Both</option>
            </select>
          </div>
          <div className="ui-card subtle policy-control-card">
            <label>Approved destination</label>
            <select value={routingDraft.destinationConnectionId} onChange={(e) => setRoutingDraft({ ...routingDraft, destinationConnectionId: e.target.value })}>
              <option value="">Select destination…</option>
              {routingDestinations.filter((d) => d.enabled).map((d) => <option key={d.connectionId} value={d.connectionId}>{d.name} · {d.providerType}</option>)}
            </select>
            <p>Technical endpoint/authentication settings stay under IT Admin → Integrations.</p>
          </div>
          <div className="ui-card subtle policy-control-card">
            <span className="policy-control-label">Rule status</span>
            <label className={`review-mode-option ${routingDraft.enabled ? 'selected' : ''}`}>
              <input type="checkbox" checked={routingDraft.enabled} onChange={(e) => setRoutingDraft({ ...routingDraft, enabled: e.target.checked })} />
              <span><strong>Enabled</strong><small>Only enabled rules are scanned during derived routing.</small></span>
            </label>
          </div>
        </div>
        <div className="admin-save-row">
          <button className="ui-button primary" disabled={savingRouting} onClick={() => void saveRoutingRule()}>{savingRouting ? 'Saving…' : routingDraft.ruleId ? 'Update Routing Rule' : 'Add Routing Rule'}</button>
          <button className="ui-button secondary" onClick={resetRoutingDraft}>Cancel</button>
        </div>
        </div>}

        {routingRules.length === 0 ? <div className="ui-empty-state">No derived routing rules configured.</div> : (
          <div className="admin-disclosure-list">
            {routingRules.map((rule) => (
              <details className="admin-summary-row" key={rule.ruleId}>
                <summary>
                  <div><strong>{rule.destinationLabel}</strong><span>{rule.markerType.replace(/_/g, ' ')} · {rule.markerValue}</span></div>
                  <div className="admin-summary-meta"><span>{rule.scanRegion.replace(/_/g, ' ')}</span><span className={rule.enabled ? 'is-enabled' : ''}>{rule.enabled ? 'Enabled' : 'Disabled'}</span></div>
                </summary>
                <div className="admin-summary-actions">
                  <button className="ui-button secondary" onClick={() => editRoutingRule(rule)}>Edit rule</button>
                  <button className="ui-button secondary" onClick={() => void deleteRoutingRule(rule.ruleId)}>Remove</button>
                </div>
              </details>
            ))}
          </div>
        )}
      </section>}

      {activeSection === 'retention' && lifecyclePolicy && (
        <section id="retention-delivery" className="ui-card admin-section-card admin-anchor-section">
          <div className="admin-section-heading">
            <div>
              <h2>Retention & Delivery Approval</h2>
              <p>Control how long the platform keeps temporary internal artifacts after delivery.</p>
            </div>
          </div>

          <div className="policy-controls-grid">
            <div className="ui-card subtle policy-control-card">
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

            <div className="ui-card subtle policy-control-card">
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

            <div className="ui-card subtle policy-control-card">
              <label htmlFor="technicalHoldHours">Technical Attention retention</label>
              <div className="threshold-row">
                <input
                  id="technicalHoldHours"
                  type="number"
                  min="72"
                  max="720"
                  value={lifecyclePolicy.neverExportedHoldHours}
                  onChange={(event) => setLifecyclePolicy({ ...lifecyclePolicy, neverExportedHoldHours: Number(event.target.value) })}
                />
                <strong>{lifecyclePolicy.neverExportedHoldHours}h</strong>
              </div>
              <p>Failed packages that never exported remain actionable on the Dashboard for this period, then processing artifacts are purged and the item leaves the operational Dashboard. Support/audit history remains.</p>
            </div>

            <div className="ui-card subtle policy-control-card">
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
            <button className="ui-button primary" disabled={savingLifecycle} onClick={() => void saveLifecyclePolicy()}>
              {savingLifecycle ? 'Saving...' : 'Save Lifecycle Policy'}
            </button>
          </div>
        </section>
      )}

    </PageScaffold>
  );
}
