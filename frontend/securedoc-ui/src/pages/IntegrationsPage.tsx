import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { apiClient } from '../api/client';
import type {
  IntegrationAcknowledgementMode,
  IntegrationAuthenticationType,
  IntegrationConnection,
  IntegrationConnectionRequest,
  IntegrationDirection,
  IntegrationProviderType,
  IntegrationPurpose,
  IntegrationPollingScheduleType,
  PollingConnectionStatus,
  DerivedDelivery,
} from '../types';
import PageScaffold from '../components/ui/PageScaffold';
import HorizontalTabs from '../components/ui/HorizontalTabs';
import StatusBadge from '../components/ui/StatusBadge';

type Draft = IntegrationConnectionRequest & { connectionId?: string; secretConfigured?: boolean };
type IntegrationView = IntegrationPurpose | 'ALL';

const emptyDraft = (): Draft => ({
  name: '',
  description: '',
  purpose: 'INGESTION_SOURCE',
  direction: 'INBOUND',
  providerType: 'PUSH_API',
  authenticationType: 'NONE',
  clientPrincipal: '',
  endpoint: '',
  fileShareRoot: '',
  secretReference: '',
  timeoutSeconds: 60,
  retryMaxAttempts: 2,
  incompleteWindowHours: 24,
  maxDocumentsPerIntake: 100,
  maxPdfSizeBytes: 262144000,
  maxPdfPages: 2000,
  pollingScheduleType: undefined,
  pollingIntervalMinutes: undefined,
  pollingLocalTime: undefined,
  pollingTimeZone: undefined,
  pollingBatchLimit: undefined,
  acknowledgementMode: 'NONE',
  enabled: false,
});

const purposeOptions: IntegrationPurpose[] = ['INGESTION_SOURCE', 'SDV_DELIVERY', 'DERIVED_DOCUMENT_DELIVERY'];
const providerOptions: IntegrationProviderType[] = ['PUSH_API', 'HTTP_API', 'FILE_SHARE', 'WATCHED_FOLDER', 'SCANNER'];

const providersByPurpose: Record<IntegrationPurpose, IntegrationProviderType[]> = {
  INGESTION_SOURCE: ['PUSH_API', 'HTTP_API', 'WATCHED_FOLDER', 'SCANNER'],
  SDV_DELIVERY: ['HTTP_API'],
  DERIVED_DOCUMENT_DELIVERY: ['HTTP_API', 'FILE_SHARE'],
};
const authOptions: IntegrationAuthenticationType[] = ['NONE', 'BASIC', 'API_KEY', 'JWT', 'OAUTH2_CLIENT_CREDENTIALS', 'MTLS'];
const acknowledgementOptions: IntegrationAcknowledgementMode[] = ['NONE', 'HTTP_SUCCESS', 'DESTINATION_RECEIPT', 'DURABLE_FILE_WRITE', 'SOURCE_PICKUP_ACK', 'SDV_SUBMISSION_ACK'];

const integrationViews: Array<{ id: IntegrationView; label: string; description: string; empty: string }> = [
  { id: 'INGESTION_SOURCE', label: 'Intake Sources', description: 'Trusted sources that send or supply document packages into this tenant.', empty: 'No intake-source connections are configured.' },
  { id: 'SDV_DELIVERY', label: 'Master Delivery', description: 'Destinations for the completed redacted master package.', empty: 'No master-delivery connections are configured.' },
  { id: 'DERIVED_DOCUMENT_DELIVERY', label: 'Derived Delivery', description: 'Destinations used by derived-document routing rules.', empty: 'No derived-delivery connections are configured.' },
  { id: 'ALL', label: 'All Connections', description: 'Review every configured tenant integration in one place.', empty: 'No tenant integration connections are configured yet.' },
];

function emptyDraftForPurpose(purpose: IntegrationPurpose): Draft {
  const draft = emptyDraft();
  if (purpose === 'SDV_DELIVERY') {
    return { ...draft, purpose, direction: 'OUTBOUND', providerType: 'HTTP_API', acknowledgementMode: 'SDV_SUBMISSION_ACK' };
  }
  if (purpose === 'DERIVED_DOCUMENT_DELIVERY') {
    return { ...draft, purpose, direction: 'OUTBOUND', providerType: 'HTTP_API', acknowledgementMode: 'DESTINATION_RECEIPT' };
  }
  return draft;
}

function human(value: string) {
  return value.toLowerCase().replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
}


function toDraft(connection: IntegrationConnection): Draft {
  return {
    connectionId: connection.connectionId,
    name: connection.name,
    description: connection.description || '',
    purpose: connection.purpose,
    direction: connection.direction,
    providerType: connection.providerType,
    authenticationType: connection.authenticationType,
    clientPrincipal: connection.clientPrincipal || '',
    endpoint: connection.endpoint || '',
    fileShareRoot: connection.fileShareRoot || '',
    timeoutSeconds: connection.timeoutSeconds,
    retryMaxAttempts: connection.retryMaxAttempts,
    incompleteWindowHours: connection.incompleteWindowHours,
    maxDocumentsPerIntake: connection.maxDocumentsPerIntake,
    maxPdfSizeBytes: connection.maxPdfSizeBytes,
    maxPdfPages: connection.maxPdfPages,
    pollingScheduleType: connection.pollingScheduleType || undefined,
    pollingIntervalMinutes: connection.pollingIntervalMinutes || undefined,
    pollingLocalTime: connection.pollingLocalTime || undefined,
    pollingTimeZone: connection.pollingTimeZone || undefined,
    pollingBatchLimit: connection.pollingBatchLimit || undefined,
    acknowledgementMode: connection.acknowledgementMode,
    enabled: connection.enabled,
    secretReference: '',
    secretConfigured: connection.secretConfigured,
  };
}

export default function IntegrationsPage({ administrationTabs }: { administrationTabs?: ReactNode }) {
  const [connections, setConnections] = useState<IntegrationConnection[]>([]);
  const [draft, setDraft] = useState<Draft>(emptyDraft());
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [pollingStatus, setPollingStatus] = useState<Record<string, PollingConnectionStatus>>({});
  const [derivedAttention, setDerivedAttention] = useState<DerivedDelivery[]>([]);
  const [editorOpen, setEditorOpen] = useState(false);
  const [activeView, setActiveView] = useState<IntegrationView>('INGESTION_SOURCE');

  const load = useCallback(async () => {
    try {
      setError('');
      const [items, attention] = await Promise.all([
        apiClient.listIntegrationConnections(),
        apiClient.listDerivedDeliveryAttention().catch(() => []),
      ]);
      setConnections(items);
      setDerivedAttention(attention);
      const pollSources = items.filter((c) => c.purpose === 'INGESTION_SOURCE' && c.providerType === 'HTTP_API' && c.direction === 'OUTBOUND');
      const statuses = await Promise.all(pollSources.map(async (c) => {
        try { return [c.connectionId, await apiClient.getPollingConnectionStatus(c.connectionId)] as const; } catch { return null; }
      }));
      setPollingStatus(Object.fromEntries(statuses.filter((v): v is readonly [string, PollingConnectionStatus] => Boolean(v))));
    } catch (err) {
      setError((err as { message?: string }).message || 'Unable to load integrations');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const editing = Boolean(draft.connectionId);
  const showEndpoint = draft.providerType === 'HTTP_API';
  const showFileShare = draft.providerType === 'FILE_SHARE';
  const secretNeeded = draft.authenticationType !== 'NONE';
  const isPollingSource = draft.purpose === 'INGESTION_SOURCE' && draft.providerType === 'HTTP_API' && draft.direction === 'OUTBOUND';
  const isScannerSource = draft.providerType === 'SCANNER';
  const purposeLocked = activeView !== 'ALL';
  const visibleProviderOptions = activeView === 'ALL' ? providerOptions : providersByPurpose[activeView];

  const sortedConnections = useMemo(
    () => [...connections].sort((a, b) => a.name.localeCompare(b.name)),
    [connections]
  );
  const viewCounts = useMemo(() => ({
    INGESTION_SOURCE: connections.filter((connection) => connection.purpose === 'INGESTION_SOURCE').length,
    SDV_DELIVERY: connections.filter((connection) => connection.purpose === 'SDV_DELIVERY').length,
    DERIVED_DOCUMENT_DELIVERY: connections.filter((connection) => connection.purpose === 'DERIVED_DOCUMENT_DELIVERY').length,
    ALL: connections.length,
  }), [connections]);
  const visibleConnections = useMemo(
    () => activeView === 'ALL' ? sortedConnections : sortedConnections.filter((connection) => connection.purpose === activeView),
    [activeView, sortedConnections]
  );
  const activeViewConfig = integrationViews.find((view) => view.id === activeView) || integrationViews[0];
  const showDerivedAttention = derivedAttention.length > 0 && (activeView === 'DERIVED_DOCUMENT_DELIVERY' || activeView === 'ALL');

  const update = <K extends keyof Draft>(key: K, value: Draft[K]) => setDraft((current) => ({ ...current, [key]: value }));

  const changeActiveView = (view: IntegrationView) => {
    if (view === activeView) return;
    setActiveView(view);
    setEditorOpen(false);
    setDraft(view === 'ALL' ? emptyDraft() : emptyDraftForPurpose(view));
    setError('');
  };

  const startNewConnection = (view: IntegrationView = activeView) => {
    const purpose: IntegrationPurpose = view === 'ALL' ? 'INGESTION_SOURCE' : view;
    setDraft(emptyDraftForPurpose(purpose));
    setEditorOpen(true);
  };

  const resetEditor = () => {
    if (draft.connectionId) {
      const original = connections.find((connection) => connection.connectionId === draft.connectionId);
      if (original) {
        setDraft(toDraft(original));
        return;
      }
    }
    const purpose: IntegrationPurpose = activeView === 'ALL' ? 'INGESTION_SOURCE' : activeView;
    setDraft(emptyDraftForPurpose(purpose));
  };

  const applyPurpose = (purpose: IntegrationPurpose) => {
    if (purpose === 'SDV_DELIVERY') {
      setDraft((current) => ({ ...current, purpose, direction: 'OUTBOUND', providerType: 'HTTP_API', acknowledgementMode: 'SDV_SUBMISSION_ACK' }));
    } else if (purpose === 'DERIVED_DOCUMENT_DELIVERY') {
      setDraft((current) => ({ ...current, purpose, direction: 'OUTBOUND', providerType: current.providerType === 'FILE_SHARE' ? 'FILE_SHARE' : 'HTTP_API', acknowledgementMode: current.providerType === 'FILE_SHARE' ? 'DURABLE_FILE_WRITE' : 'DESTINATION_RECEIPT' }));
    } else {
      setDraft((current) => {
        const providerType = providersByPurpose.INGESTION_SOURCE.includes(current.providerType) ? current.providerType : 'PUSH_API';
        const polling = providerType === 'HTTP_API';
        return {
          ...current,
          purpose,
          providerType,
          direction: polling ? 'OUTBOUND' : 'INBOUND',
          acknowledgementMode: polling ? 'SOURCE_PICKUP_ACK' : 'NONE',
          pollingScheduleType: polling ? (current.pollingScheduleType || 'INTERVAL') : undefined,
          pollingIntervalMinutes: polling ? (current.pollingIntervalMinutes || 60) : undefined,
          pollingBatchLimit: polling ? (current.pollingBatchLimit || 100) : undefined,
        };
      });
    }
  };

  const applyProvider = (providerType: IntegrationProviderType) => {
    setDraft((current) => {
      const next = { ...current, providerType };
      if (providerType === 'PUSH_API' || providerType === 'WATCHED_FOLDER' || providerType === 'SCANNER') {
        next.direction = 'INBOUND';
        next.purpose = 'INGESTION_SOURCE';
        next.endpoint = '';
        next.fileShareRoot = '';
        next.acknowledgementMode = 'NONE';
        if (providerType === 'SCANNER') {
          next.maxDocumentsPerIntake = 1;
          next.maxPdfSizeBytes = 2147483648;
          next.maxPdfPages = 10000;
        }
        next.pollingScheduleType = undefined; next.pollingIntervalMinutes = undefined; next.pollingLocalTime = undefined; next.pollingTimeZone = undefined; next.pollingBatchLimit = undefined;
      } else if (providerType === 'FILE_SHARE') {
        next.direction = 'OUTBOUND';
        next.purpose = 'DERIVED_DOCUMENT_DELIVERY';
        next.authenticationType = 'NONE';
        next.endpoint = '';
        next.acknowledgementMode = 'DURABLE_FILE_WRITE';
        next.pollingScheduleType = undefined; next.pollingIntervalMinutes = undefined; next.pollingLocalTime = undefined; next.pollingTimeZone = undefined; next.pollingBatchLimit = undefined;
      } else {
        next.direction = 'OUTBOUND';
        next.fileShareRoot = '';
        next.acknowledgementMode = next.purpose === 'SDV_DELIVERY' ? 'SDV_SUBMISSION_ACK'
          : next.purpose === 'INGESTION_SOURCE' ? 'SOURCE_PICKUP_ACK' : 'DESTINATION_RECEIPT';
        if (next.purpose === 'INGESTION_SOURCE') {
          next.pollingScheduleType = next.pollingScheduleType || 'INTERVAL';
          next.pollingIntervalMinutes = next.pollingIntervalMinutes || 60;
          next.pollingBatchLimit = next.pollingBatchLimit || 100;
        }
      }
      return next;
    });
  };

  const save = async () => {
    setSaving(true);
    setError('');
    setMessage('');
    try {
      const payload: IntegrationConnectionRequest = {
        name: draft.name,
        description: draft.description || undefined,
        purpose: draft.purpose,
        direction: draft.direction,
        providerType: draft.providerType,
        authenticationType: draft.authenticationType,
        clientPrincipal: draft.providerType === 'PUSH_API' || draft.providerType === 'SCANNER' ? draft.clientPrincipal || undefined : undefined,
        endpoint: showEndpoint ? draft.endpoint || undefined : undefined,
        fileShareRoot: showFileShare ? draft.fileShareRoot || undefined : undefined,
        secretReference: draft.secretReference || undefined,
        timeoutSeconds: draft.timeoutSeconds,
        retryMaxAttempts: draft.retryMaxAttempts,
        incompleteWindowHours: draft.incompleteWindowHours,
        maxDocumentsPerIntake: isScannerSource ? 1 : draft.maxDocumentsPerIntake,
        maxPdfSizeBytes: draft.maxPdfSizeBytes,
        maxPdfPages: draft.maxPdfPages,
        pollingScheduleType: isPollingSource ? draft.pollingScheduleType : undefined,
        pollingIntervalMinutes: isPollingSource && draft.pollingScheduleType === 'INTERVAL' ? draft.pollingIntervalMinutes : undefined,
        pollingLocalTime: isPollingSource && draft.pollingScheduleType === 'DAILY' ? draft.pollingLocalTime : undefined,
        pollingTimeZone: isPollingSource && draft.pollingScheduleType === 'DAILY' ? draft.pollingTimeZone : undefined,
        pollingBatchLimit: isPollingSource ? draft.pollingBatchLimit : undefined,
        acknowledgementMode: isScannerSource ? 'NONE' : draft.acknowledgementMode,
        enabled: editing ? undefined : Boolean(draft.enabled),
      };
      const saved = draft.connectionId
        ? await apiClient.updateIntegrationConnection(draft.connectionId, payload)
        : await apiClient.createIntegrationConnection(payload);
      setMessage(`${saved.name} ${editing ? 'updated' : 'created'}.`);
      if (activeView !== 'ALL') setActiveView(saved.purpose);
      setDraft(emptyDraft());
      setEditorOpen(false);
      await load();
    } catch (err) {
      setError((err as { message?: string }).message || 'Unable to save integration');
    } finally {
      setSaving(false);
    }
  };

  const runNow = async (connection: IntegrationConnection) => {
    setError(''); setMessage('');
    try {
      const run = await apiClient.runPollingNow(connection.connectionId);
      setMessage(`${connection.name} polling finished with ${human(run.status)}.`);
      await load();
    } catch (err) { setError((err as { message?: string }).message || 'Unable to run polling source'); }
  };

  const testSdv = async (connection: IntegrationConnection) => {
    setError(''); setMessage('');
    try {
      const result = await apiClient.testSdvConnection(connection.connectionId);
      setMessage(`${connection.name}: SDV connection ${human(result.healthStatus)}.`);
      await load();
    } catch (err) {
      setError((err as { message?: string }).message || 'SDV connection test failed');
    }
  };

  const testDerived = async (connection: IntegrationConnection) => {
    setError(''); setMessage('');
    try {
      const result = await apiClient.testDerivedConnection(connection.connectionId);
      setMessage(`${connection.name}: derived destination ${human(result.healthStatus)}.`);
      await load();
    } catch (err) { setError((err as { message?: string }).message || 'Derived destination test failed'); }
  };

  const retryDerived = async (delivery: DerivedDelivery) => {
    setError(''); setMessage('');
    try { await apiClient.retryDerivedDelivery(delivery.derivedDeliveryId); setMessage('Derived delivery retry completed.'); await load(); }
    catch (err) { setError((err as { message?: string }).message || 'Derived delivery retry failed'); }
  };

  const toggle = async (connection: IntegrationConnection) => {
    setError('');
    setMessage('');
    try {
      await apiClient.setIntegrationConnectionEnabled(connection.connectionId, !connection.enabled);
      setMessage(`${connection.name} ${connection.enabled ? 'disabled' : 'enabled'}.`);
      await load();
    } catch (err) {
      setError((err as { message?: string }).message || 'Unable to update integration state');
    }
  };

  return (
    <PageScaffold
      className="platform-admin-page"
      breadcrumbs={[{ label: 'Administration' }, { label: 'Integrations' }]}
      title="Integrations"
      eyebrow="Connected systems"
      description="Manage tenant-scoped delivery, polling, and external system connections from one operational view."
      actions={<button className="ui-button secondary" onClick={() => void load()}>Refresh</button>}
      primaryNavigation={administrationTabs}
    >
      <div className="integration-summary-grid">
        <div><span>Configured</span><strong>{connections.length}</strong></div>
        <div><span>Enabled</span><strong>{connections.filter((connection) => connection.enabled).length}</strong></div>
        <div className={derivedAttention.length ? 'needs-attention' : ''}><span>Derived attention</span><strong>{derivedAttention.length}</strong></div>
      </div>

      {error && <div className="ui-alert tone-danger">{error}</div>}
      {message && <div className="ui-alert tone-success">{message}</div>}

      <HorizontalTabs ariaLabel="Integration connection groups" className="integration-group-nav">
        {integrationViews.map((view) => (
          <button
            type="button"
            key={view.id}
            className={`integration-group-tab${activeView === view.id ? ' active' : ''}`}
            aria-pressed={activeView === view.id}
            onClick={() => changeActiveView(view.id)}
          >
            <span>{view.label}</span>
            <strong>{viewCounts[view.id]}</strong>
          </button>
        ))}
      </HorizontalTabs>

      <div className={`integration-layout${editorOpen ? ' editor-open' : ''}`}>
        <section className="ui-card platform-section-layout">
          <div className="platform-section-heading integration-section-heading">
            <div><h2>{activeViewConfig.label}</h2><p>{activeViewConfig.description} Disabled connections remain auditable.</p></div>
            <div className="integration-heading-actions">
              <span className="platform-count">{visibleConnections.length}</span>
              <button className="ui-button primary" onClick={() => startNewConnection()}>+ New connection</button>
            </div>
          </div>
          {loading ? <p>Loading integrations…</p> : visibleConnections.length === 0 ? (
            <div className="ui-empty-state integration-empty">
              <strong>{activeViewConfig.empty}</strong>
              <span>Create a connection here when this tenant needs one.</span>
            </div>
          ) : (
            <div className="integration-list">
              {visibleConnections.map((connection) => (
                <details className="platform-provider-card integration-disclosure" key={connection.connectionId}>
                  <summary className="integration-connection-summary">
                    <div><div className="platform-provider-category">{human(connection.purpose)}</div><h3>{connection.name}</h3><span>{human(connection.providerType)} · {human(connection.direction)}</span></div>
                    <StatusBadge value={connection.enabled ? connection.healthStatus : 'DISABLED'} label={human(connection.enabled ? connection.healthStatus : 'DISABLED')} />
                  </summary>
                  <dl className="platform-definition-list">
                    <div><dt>Provider</dt><dd>{human(connection.providerType)}</dd></div>
                    <div><dt>Direction</dt><dd>{human(connection.direction)}</dd></div>
                    <div><dt>Authentication</dt><dd>{human(connection.authenticationType)}</dd></div>
                    <div><dt>Secret</dt><dd>{connection.secretConfigured ? 'Configured' : 'Not configured'}</dd></div>
                    <div><dt>Configuration</dt><dd>v{connection.configurationVersion}</dd></div>
                    {connection.purpose === 'INGESTION_SOURCE' && connection.providerType === 'HTTP_API' && <div><dt>Next poll</dt><dd>{pollingStatus[connection.connectionId]?.nextScheduledAt ? new Date(pollingStatus[connection.connectionId].nextScheduledAt!).toLocaleString() : 'Not scheduled'}</dd></div>}
                  </dl>
                  <div className="integration-card-actions">
                    <button className="ui-button secondary" onClick={() => { setDraft(toDraft(connection)); setEditorOpen(true); }}>Edit</button>
                    {connection.purpose === 'INGESTION_SOURCE' && connection.providerType === 'HTTP_API' && <button className="ui-button secondary" disabled={!connection.enabled} onClick={() => void runNow(connection)}>Run now</button>}
                    {connection.purpose === 'SDV_DELIVERY' && connection.providerType === 'HTTP_API' && <button className="ui-button secondary" disabled={!connection.enabled} onClick={() => void testSdv(connection)}>Test connection</button>}
                    {connection.purpose === 'DERIVED_DOCUMENT_DELIVERY' && <button className="ui-button secondary" disabled={!connection.enabled} onClick={() => void testDerived(connection)}>Test connection</button>}
                    <button className="ui-button secondary" onClick={() => void toggle(connection)}>{connection.enabled ? 'Disable' : 'Enable'}</button>
                  </div>
                </details>
              ))}
            </div>
          )}
        </section>

        {editorOpen ? <section className="ui-card platform-section-layout integration-editor">
          <div className="platform-section-heading compact">
            <div><h2>{editing ? 'Edit connection' : 'New connection'}</h2><p>Technical configuration is tenant scoped and provider validated.</p></div>
            <button className="ui-button secondary" onClick={() => { setDraft(emptyDraft()); setEditorOpen(false); }}>Close</button>
          </div>

          <div className="integration-form-grid">
            <label><span>Name</span><input value={draft.name} onChange={(e) => update('name', e.target.value)} /></label>
            <label><span>Purpose</span><select disabled={purposeLocked} value={draft.purpose} onChange={(e) => applyPurpose(e.target.value as IntegrationPurpose)}>{purposeOptions.map((v) => <option key={v} value={v}>{human(v)}</option>)}</select>{purposeLocked && <small>Locked to the current connection group. Use All Connections when you need to choose a different purpose.</small>}</label>
            <label><span>Provider</span><select value={draft.providerType} onChange={(e) => applyProvider(e.target.value as IntegrationProviderType)}>{visibleProviderOptions.map((v) => <option key={v} value={v}>{human(v)}</option>)}</select></label>
            <label><span>Direction</span><select value={draft.direction} onChange={(e) => update('direction', e.target.value as IntegrationDirection)}><option value="INBOUND">Inbound</option><option value="OUTBOUND">Outbound</option></select></label>
            {(draft.providerType === 'PUSH_API' || draft.providerType === 'SCANNER') && <label className="integration-wide"><span>Authenticated client principal</span><input value={draft.clientPrincipal || ''} placeholder="OIDC subject / preferred username bound to this source" onChange={(e) => update('clientPrincipal', e.target.value)} /></label>}
            <label><span>Authentication</span><select value={draft.authenticationType} onChange={(e) => update('authenticationType', e.target.value as IntegrationAuthenticationType)}>{authOptions.map((v) => <option key={v} value={v}>{human(v)}</option>)}</select></label>
            <label><span>Acknowledgement</span><select disabled={isScannerSource} value={isScannerSource ? 'NONE' : draft.acknowledgementMode} onChange={(e) => update('acknowledgementMode', e.target.value as IntegrationAcknowledgementMode)}>{acknowledgementOptions.map((v) => <option key={v} value={v}>{human(v)}</option>)}</select>{isScannerSource && <small>Scanner delivery uses the built-in idempotent intake/reconciliation contract.</small>}</label>
            {showEndpoint && <label className="integration-wide"><span>Endpoint</span><input value={draft.endpoint || ''} placeholder="https://..." onChange={(e) => update('endpoint', e.target.value)} /></label>}
            {showFileShare && <label className="integration-wide"><span>Secure file-share root</span><input value={draft.fileShareRoot || ''} onChange={(e) => update('fileShareRoot', e.target.value)} /></label>}
            {secretNeeded && <label className="integration-wide"><span>Protected secret reference</span><input type="password" value={draft.secretReference || ''} placeholder={draft.secretConfigured ? 'Configured — leave blank to keep existing reference' : 'Deployment-managed secret reference'} onChange={(e) => update('secretReference', e.target.value)} /></label>}
            <label><span>Timeout (seconds)</span><input type="number" min={1} max={600} value={draft.timeoutSeconds || 60} onChange={(e) => update('timeoutSeconds', Number(e.target.value))} /></label>
            <label><span>Retry attempts</span><input type="number" min={0} max={10} value={draft.retryMaxAttempts ?? 2} onChange={(e) => update('retryMaxAttempts', Number(e.target.value))} /></label>
            {draft.purpose === 'INGESTION_SOURCE' && <>
              <label><span>Incomplete window (hours)</span><input type="number" min={1} max={168} value={draft.incompleteWindowHours ?? 24} onChange={(e) => update('incompleteWindowHours', Number(e.target.value))} /></label>
              <label><span>Max documents / intake</span><input type="number" min={1} max={1000} disabled={isScannerSource} value={isScannerSource ? 1 : (draft.maxDocumentsPerIntake ?? 100)} onChange={(e) => update('maxDocumentsPerIntake', Number(e.target.value))} />{isScannerSource && <small>Scanner Intake is one consolidated PDF per physical package.</small>}</label>
              <label><span>Max PDF size (MB)</span><input type="number" min={1} max={2048} value={Math.round((draft.maxPdfSizeBytes ?? 262144000) / 1048576)} onChange={(e) => update('maxPdfSizeBytes', Number(e.target.value) * 1048576)} /></label>
              <label><span>Max PDF pages</span><input type="number" min={1} max={10000} value={draft.maxPdfPages ?? 2000} onChange={(e) => update('maxPdfPages', Number(e.target.value))} /></label>
            </>}
            {isPollingSource && <>
              <label><span>Polling schedule</span><select value={draft.pollingScheduleType || 'INTERVAL'} onChange={(e) => update('pollingScheduleType', e.target.value as IntegrationPollingScheduleType)}><option value="INTERVAL">Interval</option><option value="DAILY">Daily</option></select></label>
              {draft.pollingScheduleType !== 'DAILY' ? <label><span>Interval (minutes)</span><input type="number" min={5} max={1440} value={draft.pollingIntervalMinutes || 60} onChange={(e) => update('pollingIntervalMinutes', Number(e.target.value))} /></label> : <>
                <label><span>Daily time</span><input type="time" value={draft.pollingLocalTime || '01:00'} onChange={(e) => update('pollingLocalTime', e.target.value)} /></label>
                <label><span>IANA time zone</span><input value={draft.pollingTimeZone || ''} placeholder="America/New_York" onChange={(e) => update('pollingTimeZone', e.target.value)} /></label>
              </>}
              <label><span>Packages per poll</span><input type="number" min={1} max={1000} value={draft.pollingBatchLimit || 100} onChange={(e) => update('pollingBatchLimit', Number(e.target.value))} /></label>
            </>}
            <label className="integration-wide"><span>Description</span><textarea value={draft.description || ''} onChange={(e) => update('description', e.target.value)} /></label>
            {!editing && <label className="integration-checkbox"><input type="checkbox" checked={Boolean(draft.enabled)} onChange={(e) => update('enabled', e.target.checked)} /><span>Enable immediately after creation</span></label>}
          </div>

          <div className="integration-form-actions">
            <button className="ui-button primary" disabled={saving || !draft.name.trim()} onClick={() => void save()}>{saving ? 'Saving…' : editing ? 'Save changes' : 'Create connection'}</button>
            <button className="ui-button secondary" disabled={saving} onClick={resetEditor}>Reset</button>
          </div>
          <p className="platform-boundary-note">Credential values are not displayed or returned by this screen. Provider-specific execution and safe health checks are available when the corresponding provider is implemented; test actions never send a customer PDF.</p>
        </section> : null}

        {showDerivedAttention && <section className="ui-card platform-section-layout integration-attention">
          <div className="platform-section-heading compact"><div><h2>Derived delivery attention</h2><p>Technical destination failures are independent of the completed master SDV export.</p></div><span className="platform-count">{derivedAttention.length}</span></div>
          <div className="integration-list">{derivedAttention.map((d) => <div className="platform-provider-card" key={d.derivedDeliveryId}>
            <div className="platform-provider-top"><div><div className="platform-provider-category">{d.destinationLabel || 'Routing attention'}</div><h3>{d.failureCategory || d.status}</h3></div><StatusBadge value={d.status} label={human(d.status)} /></div>
            <dl className="platform-definition-list"><div><dt>Package</dt><dd>{d.packageId}</dd></div><div><dt>Pages</dt><dd>{d.pageNumbers.length ? d.pageNumbers.join(', ') : '—'}</dd></div><div><dt>Attempts</dt><dd>{d.attemptCount}</dd></div></dl>
            {d.destinationLabel && <div className="integration-card-actions"><button className="ui-button secondary" onClick={() => void retryDerived(d)}>Technical retry</button></div>}
          </div>)}</div>
        </section>}
      </div>
    </PageScaffold>
  );
}
