import { useState } from 'react';
import useSWR, { useSWRConfig } from 'swr';
import { apiClient } from '../../api/client';
import type {
  DerivedDelivery,
  IntegrationAcknowledgementMode,
  IntegrationAuthenticationType,
  IntegrationConnection,
  IntegrationConnectionRequest,
  IntegrationDirection,
  IntegrationPollingScheduleType,
  IntegrationProviderType,
  IntegrationPurpose,
  PollingConnectionStatus,
} from '../../types';
import { errorMessage } from '../data';
import { Notice, Spinner } from '../atoms';
import { useToast } from '../toast';
import { ConfirmDialog, Loading, NumberField, SelectField, Sheet, TextField, Toggle, humanize, when } from './shared';
import RoutingRules from './RoutingRules';

type View = IntegrationPurpose | 'ALL';
type Draft = IntegrationConnectionRequest & { connectionId?: string; secretConfigured?: boolean };

const VIEWS: { id: View; label: string; note: string; empty: string }[] = [
  { id: 'INGESTION_SOURCE', label: 'Intake sources', note: 'Trusted sources that send or supply document packages into this tenant.', empty: 'No intake-source connections are configured.' },
  { id: 'SDV_DELIVERY', label: 'Master delivery', note: 'Destinations for the completed redacted master package.', empty: 'No master-delivery connections are configured.' },
  { id: 'DERIVED_DOCUMENT_DELIVERY', label: 'Derived delivery', note: 'Destinations used by derived-document routing rules.', empty: 'No derived-delivery connections are configured.' },
  { id: 'ALL', label: 'All', note: 'Every configured tenant integration in one place.', empty: 'No tenant integration connections are configured yet.' },
];

const PURPOSES: IntegrationPurpose[] = ['INGESTION_SOURCE', 'SDV_DELIVERY', 'DERIVED_DOCUMENT_DELIVERY'];
const PROVIDERS: IntegrationProviderType[] = ['PUSH_API', 'HTTP_API', 'FILE_SHARE', 'WATCHED_FOLDER', 'SCANNER'];
const PROVIDERS_BY_PURPOSE: Record<IntegrationPurpose, IntegrationProviderType[]> = {
  INGESTION_SOURCE: ['PUSH_API', 'HTTP_API', 'WATCHED_FOLDER', 'SCANNER'],
  SDV_DELIVERY: ['HTTP_API'],
  DERIVED_DOCUMENT_DELIVERY: ['HTTP_API', 'FILE_SHARE'],
};
const AUTH: IntegrationAuthenticationType[] = ['NONE', 'BASIC', 'API_KEY', 'JWT', 'OAUTH2_CLIENT_CREDENTIALS', 'MTLS'];
const ACK: IntegrationAcknowledgementMode[] = ['NONE', 'HTTP_SUCCESS', 'DESTINATION_RECEIPT', 'DURABLE_FILE_WRITE', 'SOURCE_PICKUP_ACK', 'SDV_SUBMISSION_ACK'];
const MB = 1048576;

/** Business document flow is separate from the direction of the HTTP request. */
const connectionFlow = (d: Pick<IntegrationConnection, 'purpose' | 'providerType'>) => {
  if (d.purpose !== 'INGESTION_SOURCE') return 'Send processed documents to an external destination';
  if (d.providerType === 'HTTP_API') return 'Receive documents — LexaVault retrieves them from an external HTTP source';
  if (d.providerType === 'PUSH_API') return 'Receive documents — the external system sends them to LexaVault';
  if (d.providerType === 'WATCHED_FOLDER') return 'Receive documents — LexaVault monitors a source folder';
  return 'Receive documents — a scanner supplies the intake package';
};

const isPollingConnection = (c: Pick<IntegrationConnection, 'purpose' | 'providerType' | 'direction'>) =>
  c.purpose === 'INGESTION_SOURCE' && c.providerType === 'HTTP_API' && c.direction === 'OUTBOUND';

const baseDraft = (): Draft => ({
  name: '', description: '', purpose: 'INGESTION_SOURCE', direction: 'INBOUND', providerType: 'PUSH_API', authenticationType: 'NONE',
  clientPrincipal: '', endpoint: '', fileShareRoot: '', secretReference: '', timeoutSeconds: 60, retryMaxAttempts: 2,
  incompleteWindowHours: 24, maxDocumentsPerIntake: 100, maxPdfSizeBytes: 262144000, maxPdfPages: 2000,
  pollingScheduleType: undefined, pollingIntervalMinutes: undefined, pollingLocalTime: undefined, pollingTimeZone: undefined, pollingBatchLimit: undefined,
  acknowledgementMode: 'NONE', enabled: false,
});

function draftForPurpose(purpose: IntegrationPurpose): Draft {
  const d = baseDraft();
  if (purpose === 'SDV_DELIVERY') return { ...d, purpose, direction: 'OUTBOUND', providerType: 'HTTP_API', acknowledgementMode: 'SDV_SUBMISSION_ACK' };
  if (purpose === 'DERIVED_DOCUMENT_DELIVERY') return { ...d, purpose, direction: 'OUTBOUND', providerType: 'HTTP_API', acknowledgementMode: 'DESTINATION_RECEIPT' };
  return d;
}

function toDraft(c: IntegrationConnection): Draft {
  return {
    connectionId: c.connectionId, name: c.name, description: c.description || '', purpose: c.purpose, direction: c.direction,
    providerType: c.providerType, authenticationType: c.authenticationType, clientPrincipal: c.clientPrincipal || '', endpoint: c.endpoint || '',
    fileShareRoot: c.fileShareRoot || '', timeoutSeconds: c.timeoutSeconds, retryMaxAttempts: c.retryMaxAttempts,
    incompleteWindowHours: c.incompleteWindowHours, maxDocumentsPerIntake: c.maxDocumentsPerIntake, maxPdfSizeBytes: c.maxPdfSizeBytes,
    maxPdfPages: c.maxPdfPages, pollingScheduleType: c.pollingScheduleType || undefined, pollingIntervalMinutes: c.pollingIntervalMinutes || undefined,
    pollingLocalTime: c.pollingLocalTime || undefined, pollingTimeZone: c.pollingTimeZone || undefined, pollingBatchLimit: c.pollingBatchLimit || undefined,
    acknowledgementMode: c.acknowledgementMode, enabled: c.enabled, secretReference: '', secretConfigured: c.secretConfigured,
  };
}

/** Purpose change rules, identical to production IntegrationsPage.applyPurpose. */
function applyPurpose(cur: Draft, purpose: IntegrationPurpose): Draft {
  if (purpose === 'SDV_DELIVERY') return { ...cur, purpose, direction: 'OUTBOUND', providerType: 'HTTP_API', acknowledgementMode: 'SDV_SUBMISSION_ACK' };
  if (purpose === 'DERIVED_DOCUMENT_DELIVERY') {
    const fs = cur.providerType === 'FILE_SHARE';
    return { ...cur, purpose, direction: 'OUTBOUND', providerType: fs ? 'FILE_SHARE' : 'HTTP_API', acknowledgementMode: fs ? 'DURABLE_FILE_WRITE' : 'DESTINATION_RECEIPT' };
  }
  const providerType = PROVIDERS_BY_PURPOSE.INGESTION_SOURCE.includes(cur.providerType) ? cur.providerType : 'PUSH_API';
  const polling = providerType === 'HTTP_API';
  return {
    ...cur, purpose, providerType, direction: polling ? 'OUTBOUND' : 'INBOUND', acknowledgementMode: polling ? 'SOURCE_PICKUP_ACK' : 'NONE',
    pollingScheduleType: polling ? (cur.pollingScheduleType || 'INTERVAL') : undefined,
    pollingIntervalMinutes: polling ? (cur.pollingIntervalMinutes || 60) : undefined,
    pollingBatchLimit: polling ? (cur.pollingBatchLimit || 100) : undefined,
  };
}

const NO_POLLING = { pollingScheduleType: undefined, pollingIntervalMinutes: undefined, pollingLocalTime: undefined, pollingTimeZone: undefined, pollingBatchLimit: undefined };

/** Provider change rules, identical to production IntegrationsPage.applyProvider. */
function applyProvider(cur: Draft, providerType: IntegrationProviderType): Draft {
  const next: Draft = { ...cur, providerType };
  if (providerType === 'PUSH_API' || providerType === 'WATCHED_FOLDER' || providerType === 'SCANNER') {
    Object.assign(next, { direction: 'INBOUND', purpose: 'INGESTION_SOURCE', endpoint: '', fileShareRoot: '', acknowledgementMode: 'NONE' }, NO_POLLING);
    if (providerType === 'SCANNER') Object.assign(next, { maxDocumentsPerIntake: 1, maxPdfSizeBytes: 2147483648, maxPdfPages: 10000 });
  } else if (providerType === 'FILE_SHARE') {
    Object.assign(next, { direction: 'OUTBOUND', purpose: 'DERIVED_DOCUMENT_DELIVERY', authenticationType: 'NONE', endpoint: '', acknowledgementMode: 'DURABLE_FILE_WRITE' }, NO_POLLING);
  } else {
    next.direction = 'OUTBOUND';
    next.fileShareRoot = '';
    next.acknowledgementMode = next.purpose === 'SDV_DELIVERY' ? 'SDV_SUBMISSION_ACK' : next.purpose === 'INGESTION_SOURCE' ? 'SOURCE_PICKUP_ACK' : 'DESTINATION_RECEIPT';
    if (next.purpose === 'INGESTION_SOURCE') {
      next.pollingScheduleType = next.pollingScheduleType || 'INTERVAL';
      next.pollingIntervalMinutes = next.pollingIntervalMinutes || 60;
      next.pollingBatchLimit = next.pollingBatchLimit || 100;
    }
  }
  return next;
}

function toPayload(d: Draft): IntegrationConnectionRequest {
  const polling = isPollingConnection(d);
  const scanner = d.providerType === 'SCANNER';
  return {
    name: d.name.trim(),
    description: d.description || undefined,
    purpose: d.purpose,
    direction: d.direction,
    providerType: d.providerType,
    authenticationType: d.authenticationType,
    clientPrincipal: d.providerType === 'PUSH_API' || scanner ? d.clientPrincipal || undefined : undefined,
    endpoint: d.providerType === 'HTTP_API' ? d.endpoint || undefined : undefined,
    fileShareRoot: d.providerType === 'FILE_SHARE' ? d.fileShareRoot || undefined : undefined,
    secretReference: d.secretReference || undefined,
    timeoutSeconds: d.timeoutSeconds,
    retryMaxAttempts: d.retryMaxAttempts,
    incompleteWindowHours: d.incompleteWindowHours,
    maxDocumentsPerIntake: scanner ? 1 : d.maxDocumentsPerIntake,
    maxPdfSizeBytes: d.maxPdfSizeBytes,
    maxPdfPages: d.maxPdfPages,
    pollingScheduleType: polling ? d.pollingScheduleType : undefined,
    pollingIntervalMinutes: polling && d.pollingScheduleType === 'INTERVAL' ? d.pollingIntervalMinutes : undefined,
    pollingLocalTime: polling && d.pollingScheduleType === 'DAILY' ? d.pollingLocalTime : undefined,
    pollingTimeZone: polling && d.pollingScheduleType === 'DAILY' ? d.pollingTimeZone : undefined,
    pollingBatchLimit: polling ? d.pollingBatchLimit : undefined,
    acknowledgementMode: scanner ? 'NONE' : d.acknowledgementMode,
    enabled: d.connectionId ? undefined : Boolean(d.enabled),
  };
}

export default function IntegrationsArea() {
  const { mutate: globalMutate } = useSWRConfig();
  const conns = useSWR<IntegrationConnection[]>('integrations', () => apiClient.listIntegrationConnections(), { revalidateOnFocus: false });
  const attention = useSWR<DerivedDelivery[]>('derived-attention', () => apiClient.listDerivedDeliveryAttention().catch(() => []), { revalidateOnFocus: false });
  const [view, setView] = useState<View>('INGESTION_SOURCE');
  const [draft, setDraft] = useState<Draft | null>(null);
  const [toggling, setToggling] = useState<IntegrationConnection | null>(null);

  if (!conns.data) return <Loading error={conns.error} retry={() => conns.mutate()} label="Integrations" />;
  const all = [...conns.data].sort((a, b) => a.name.localeCompare(b.name));
  const count = (v: View) => (v === 'ALL' ? all.length : all.filter((c) => c.purpose === v).length);
  const visible = view === 'ALL' ? all : all.filter((c) => c.purpose === view);
  const viewCfg = VIEWS.find((v) => v.id === view)!;
  const derivedAttention = attention.data ?? [];
  const showAttention = derivedAttention.length > 0 && (view === 'DERIVED_DOCUMENT_DELIVERY' || view === 'ALL');

  const refresh = async () => {
    await Promise.all([conns.mutate(), attention.mutate(), globalMutate('routing-destinations'), globalMutate((k) => Array.isArray(k) && k[0] === 'polling')]);
  };

  return (
    <div className="cf-ad-stack">
      <div className="cf-ad-toolbar">
        <div className="cf-segment" role="group" aria-label="Connection groups">
          {VIEWS.map((v) => (
            <button key={v.id} type="button" aria-pressed={view === v.id} onClick={() => setView(v.id)}>
              {v.label} <span>{count(v.id)}</span>
            </button>
          ))}
        </div>
        <span className="cf-ad-spacer" />
        <span className="cf-ad-hint">{all.filter((c) => c.enabled).length} of {all.length} enabled</span>
        <button type="button" className="cf-btn" data-size="sm" data-variant="ghost" onClick={() => void refresh()}>Refresh</button>
        <button type="button" className="cf-btn" data-size="sm" data-variant="solid" onClick={() => setDraft(draftForPurpose(view === 'ALL' ? 'INGESTION_SOURCE' : view))}>New connection</button>
      </div>

      <section className="cf-ad-card" aria-label={viewCfg.label}>
        <header className="cf-ad-card-head"><div><h2>{viewCfg.label}</h2><p>{viewCfg.note} Disabled connections remain auditable.</p></div></header>
        {visible.length === 0 ? (
          <div className="cf-ad-card-body"><p className="cf-ad-hint">{viewCfg.empty} Create a connection here when this tenant needs one.</p></div>
        ) : (
          <ul className="cf-ad-conns">
            {visible.map((c) => (
              <Connection key={c.connectionId} c={c} showPurpose={view === 'ALL'} onChanged={refresh} onEdit={() => setDraft(toDraft(c))} onToggle={() => setToggling(c)} />
            ))}
          </ul>
        )}
      </section>

      {showAttention && <AttentionList items={derivedAttention} onChanged={refresh} />}

      {(view === 'DERIVED_DOCUMENT_DELIVERY' || view === 'ALL') && <RoutingRules />}

      <ConnectionEditor draft={draft} purposeLocked={view !== 'ALL'} onChange={setDraft} onClose={() => setDraft(null)}
        onSaved={async (saved) => { setDraft(null); if (view !== 'ALL') setView(saved.purpose); await refresh(); }}
        original={draft?.connectionId ? conns.data.find((c) => c.connectionId === draft.connectionId) : undefined} />

      <ToggleConfirm conn={toggling} onClose={() => setToggling(null)} onDone={refresh} />
    </div>
  );
}

function Connection({ c, showPurpose, onChanged, onEdit, onToggle }: {
  c: IntegrationConnection; showPurpose: boolean; onChanged: () => Promise<unknown>; onEdit: () => void; onToggle: () => void;
}) {
  const toast = useToast();
  const [busy, setBusy] = useState<null | 'test' | 'poll'>(null);
  const polls = isPollingConnection(c);
  const pollStatus = useSWR<PollingConnectionStatus | null>(polls ? ['polling', c.connectionId] : null,
    () => apiClient.getPollingConnectionStatus(c.connectionId).catch(() => null), { revalidateOnFocus: false });
  const testable = (c.purpose === 'SDV_DELIVERY' && c.providerType === 'HTTP_API') || c.purpose === 'DERIVED_DOCUMENT_DELIVERY';
  const health = !c.enabled ? 'off' : c.healthStatus === 'HEALTHY' ? 'ok' : 'warn';
  const lastRun = pollStatus.data?.recentRuns?.[0];

  async function run(kind: 'test' | 'poll') {
    setBusy(kind);
    try {
      if (kind === 'poll') {
        const r = await apiClient.runPollingNow(c.connectionId);
        toast(`${c.name} polling finished: ${humanize(r.status).toLowerCase()}, ${r.packagesDiscovered} package${r.packagesDiscovered === 1 ? '' : 's'} found.`, r.status === 'SUCCESS' ? undefined : 'danger');
      } else {
        const r = c.purpose === 'SDV_DELIVERY' ? await apiClient.testSdvConnection(c.connectionId) : await apiClient.testDerivedConnection(c.connectionId);
        const label = c.purpose === 'SDV_DELIVERY' ? 'SDV connection' : 'derived destination';
        toast(`${c.name}: ${label} ${humanize(r.healthStatus).toLowerCase()}.`, r.healthStatus === 'HEALTHY' ? undefined : 'danger');
      }
      await onChanged();
    } catch (e) {
      toast(errorMessage(e, kind === 'poll' ? 'Unable to run polling source.' : 'Connection test failed.'), 'danger');
    } finally {
      setBusy(null);
    }
  }

  return (
    <li className="cf-ad-conn">
      <span className="cf-ad-health" data-h={health} aria-hidden="true" />
      <div className="cf-ad-conn-main">
        <div className="cf-ad-conn-name">
          {c.name}
          {showPurpose && <span className="cf-ad-tag">{humanize(c.purpose)}</span>}
          <span className="cf-ad-tag">{humanize(c.providerType)}</span>
          <span className="cf-ad-tag">{c.purpose === "INGESTION_SOURCE" ? "Documents in" : "Documents out"}</span>
          {!c.enabled && <span className="cf-ad-tag">Disabled</span>}
        </div>
        <div className="cf-ad-conn-meta">
          <span>{c.enabled ? humanize(c.healthStatus) : 'Not running'}</span>
          <span>Auth {humanize(c.authenticationType)}</span>
          {c.authenticationType !== 'NONE' && (c.secretConfigured ? <span>Secret configured</span> : <span className="cf-danger-text">Secret not configured</span>)}
          <span>Config v{c.configurationVersion}</span>
          <span>Last success {when(c.lastSuccessAt)}</span>
          {c.lastFailureAt && <span className="cf-danger-text">Failed {when(c.lastFailureAt)}{c.lastFailureCategory ? ` · ${humanize(c.lastFailureCategory)}` : ''}</span>}
          {polls && <span>{c.pollingScheduleType === 'DAILY' ? `Daily at ${c.pollingLocalTime}${c.pollingTimeZone ? ` ${c.pollingTimeZone}` : ''}` : `Every ${c.pollingIntervalMinutes} min`}</span>}
          {polls && <span>Next poll {pollStatus.data?.nextScheduledAt ? when(pollStatus.data.nextScheduledAt) : 'not scheduled'}</span>}
          {lastRun && <span className={lastRun.status === 'FAILED' ? 'cf-danger-text' : undefined}>Last run {humanize(lastRun.status).toLowerCase()} · {lastRun.packagesDiscovered} found</span>}
        </div>
        {c.endpoint && <div className="cf-ad-conn-endpoint cf-mono">{c.endpoint}</div>}
        {c.fileShareRoot && <div className="cf-ad-conn-endpoint cf-mono">{c.fileShareRoot}</div>}
      </div>
      <div className="cf-ad-conn-actions">
        <button type="button" className="cf-btn" data-size="sm" onClick={onEdit}>Edit</button>
        {polls && <button type="button" className="cf-btn" data-size="sm" disabled={!c.enabled || !!busy} onClick={() => run('poll')}>{busy === 'poll' && <Spinner label="Polling" />}Run now</button>}
        {testable && <button type="button" className="cf-btn" data-size="sm" disabled={!c.enabled || !!busy} onClick={() => run('test')}>{busy === 'test' && <Spinner label="Testing" />}Test</button>}
        <button type="button" className="cf-btn" data-size="sm" data-variant="ghost" disabled={!!busy} onClick={onToggle}>{c.enabled ? 'Disable' : 'Enable'}</button>
      </div>
    </li>
  );
}

function ToggleConfirm({ conn, onClose, onDone }: { conn: IntegrationConnection | null; onClose: () => void; onDone: () => Promise<unknown> }) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastId, setLastId] = useState<string | undefined>();
  if (conn?.connectionId !== lastId) { setLastId(conn?.connectionId); setError(null); }
  const disabling = !!conn?.enabled;

  async function confirm() {
    if (!conn) return;
    setBusy(true);
    setError(null);
    try {
      await apiClient.setIntegrationConnectionEnabled(conn.connectionId, !conn.enabled);
      await onDone();
      toast(`${conn.name} ${conn.enabled ? 'disabled' : 'enabled'}.`);
      onClose();
    } catch (e) {
      setError(errorMessage(e, 'Unable to update integration state.'));
    } finally {
      setBusy(false);
    }
  }

  const consequence = !conn ? null : disabling
    ? conn.purpose === 'INGESTION_SOURCE'
      ? 'This source stops accepting or polling new packages. Cases already received are unaffected.'
      : conn.purpose === 'SDV_DELIVERY'
        ? 'Completed master packages cannot be delivered through this destination until it is enabled again.'
        : 'Routing rules pointing at this destination stop delivering derived documents until it is enabled again.'
    : 'The connection resumes using its saved configuration immediately.';

  return (
    <ConfirmDialog open={!!conn} title={disabling ? 'Disable connection?' : 'Enable connection?'} danger={disabling}
      confirmLabel={disabling ? 'Disable' : 'Enable'} busy={busy} error={error}
      facts={conn ? [['Connection', conn.name], ['Purpose', humanize(conn.purpose)], ['Provider', `${humanize(conn.providerType)} · ${humanize(conn.direction)}`], ['Configuration', `v${conn.configurationVersion}`]] : []}
      consequence={consequence} onConfirm={confirm} onCancel={onClose} />
  );
}

function AttentionList({ items, onChanged }: { items: DerivedDelivery[]; onChanged: () => Promise<unknown> }) {
  const toast = useToast();
  const [busy, setBusy] = useState<string | null>(null);
  async function retry(d: DerivedDelivery) {
    setBusy(d.derivedDeliveryId);
    try {
      await apiClient.retryDerivedDelivery(d.derivedDeliveryId);
      toast('Derived delivery retry completed.');
      await onChanged();
    } catch (e) {
      toast(errorMessage(e, 'Derived delivery retry failed.'), 'danger');
    } finally {
      setBusy(null);
    }
  }
  return (
    <section className="cf-ad-card" aria-label="Derived delivery attention">
      <header className="cf-ad-card-head">
        <div><h2>Derived delivery attention</h2><p>Technical destination failures are independent of the completed master delivery.</p></div>
        <span className="cf-ad-tag">{items.length}</span>
      </header>
      <ul className="cf-ad-conns">
        {items.map((d) => (
          <li key={d.derivedDeliveryId} className="cf-ad-conn">
            <span className="cf-ad-health" data-h="warn" aria-hidden="true" />
            <div className="cf-ad-conn-main">
              <div className="cf-ad-conn-name">
                {d.destinationLabel || 'Routing attention'}
                <span className="cf-ad-tag">{humanize(d.status)}</span>
              </div>
              <div className="cf-ad-conn-meta">
                {d.failureCategory && <span className="cf-danger-text">{humanize(d.failureCategory)}</span>}
                <span className="cf-mono">{d.packageId}</span>
                <span>Pages {d.pageNumbers.length ? d.pageNumbers.join(', ') : '—'}</span>
                <span>{d.attemptCount} attempt{d.attemptCount === 1 ? '' : 's'}</span>
              </div>
            </div>
            {d.destinationLabel && (
              <div className="cf-ad-conn-actions">
                <button type="button" className="cf-btn" data-size="sm" disabled={!!busy} onClick={() => retry(d)}>{busy === d.derivedDeliveryId && <Spinner label="Retrying" />}Technical retry</button>
              </div>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

function ConnectionEditor({ draft, original, purposeLocked, onChange, onClose, onSaved }: {
  draft: Draft | null; original?: IntegrationConnection; purposeLocked: boolean;
  onChange: (d: Draft) => void; onClose: () => void; onSaved: (saved: IntegrationConnection) => Promise<void>;
}) {
  const toast = useToast();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [openKey, setOpenKey] = useState<string | null>(null);
  const key = draft ? draft.connectionId ?? 'new' : null;
  if (key !== openKey) { setOpenKey(key); setError(null); }

  if (!draft) return <Sheet open={false} title="" onClose={onClose}>{null}</Sheet>;
  const d = draft;
  const editing = !!d.connectionId;
  const set = <K extends keyof Draft>(k: K, v: Draft[K]) => onChange({ ...d, [k]: v });
  const polling = isPollingConnection(d);
  const scanner = d.providerType === 'SCANNER';
  const providers = purposeLocked ? PROVIDERS_BY_PURPOSE[d.purpose] : PROVIDERS;
  const nameMissing = !d.name.trim();

  function reset() {
    setError(null);
    onChange(original ? toDraft(original) : draftForPurpose(d.purpose));
  }

  async function save() {
    if (nameMissing) { setError('A connection name is required.'); return; }
    setSaving(true);
    setError(null);
    try {
      const body = toPayload(d);
      const saved = d.connectionId ? await apiClient.updateIntegrationConnection(d.connectionId, body) : await apiClient.createIntegrationConnection(body);
      toast(`${saved.name} ${editing ? 'updated' : 'created'}.`);
      await onSaved(saved);
    } catch (e) {
      setError(errorMessage(e, 'Unable to save integration.'));
    } finally {
      setSaving(false);
    }
  }

  return (
    <Sheet open wide title={editing ? 'Edit connection' : 'New connection'}
      description="Choose how documents enter or leave LexaVault. Only the configuration relevant to that method is shown."
      onClose={() => { if (!saving) onClose(); }}
      footer={<>
        <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={saving} onClick={reset}>Reset</button>
        <span className="cf-ad-spacer" />
        <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={saving} onClick={onClose}>Cancel</button>
        <button type="button" className="cf-btn" data-variant="solid" data-size="sm" disabled={saving || nameMissing} onClick={save}>
          {saving && <Spinner label="Saving" />}{editing ? 'Save changes' : 'Create connection'}
        </button>
      </>}
    >
      <div className="cf-ad-form">
        <fieldset className="cf-ad-fieldset">
          <legend>Connection</legend>
          <div className="cf-ad-grid">
            <TextField label="Name" required value={d.name} invalid={nameMissing} onChange={(v) => set('name', v)} hint={nameMissing ? 'Required.' : undefined} />
            <SelectField label="Purpose" value={d.purpose} options={PURPOSES} disabled={purposeLocked} onChange={(v) => onChange(applyPurpose(d, v))}
              hint={purposeLocked ? 'Locked to the current group. Use All to choose a different purpose.' : undefined} />
            <SelectField label="Transfer method" value={d.providerType} options={providers.map((provider) => [provider, provider === 'PUSH_API' ? 'External system sends (API push)' : provider === 'HTTP_API' && d.purpose === 'INGESTION_SOURCE' ? 'LexaVault retrieves (HTTP polling)' : provider === 'HTTP_API' ? 'Send to HTTP API' : provider === 'WATCHED_FOLDER' ? 'Watched folder' : provider === 'SCANNER' ? 'Scanner' : 'File share'] as [IntegrationProviderType, string])} onChange={(v) => onChange(applyProvider(d, v))} />
            <div className="cf-ad-span"><p className="cf-ad-context">{connectionFlow(d)}. Connection initiated: {d.direction === 'OUTBOUND' ? 'by LexaVault' : 'by the external source'}.</p></div>
          </div>
        </fieldset>

        <fieldset className="cf-ad-fieldset">
          <legend>Endpoint and authentication</legend>
          <div className="cf-ad-grid">
            {(d.providerType === 'PUSH_API' || scanner) && (
              <div className="cf-ad-span"><TextField label="Authenticated client principal" value={d.clientPrincipal || ''} mono placeholder="OIDC subject / preferred username bound to this source" onChange={(v) => set('clientPrincipal', v)} /></div>
            )}
            {d.providerType === 'HTTP_API' && (
              <div className="cf-ad-span"><TextField label="Endpoint" value={d.endpoint || ''} mono placeholder="https://..." onChange={(v) => set('endpoint', v)} /></div>
            )}
            {d.providerType === 'FILE_SHARE' && (
              <div className="cf-ad-span"><TextField label="Secure file-share root" value={d.fileShareRoot || ''} mono onChange={(v) => set('fileShareRoot', v)} /></div>
            )}
            <SelectField label="Authentication" value={d.authenticationType} options={AUTH} onChange={(v) => set('authenticationType', v)} />
            <SelectField label="Acknowledgement" value={scanner ? 'NONE' : d.acknowledgementMode} options={ACK} disabled={scanner} onChange={(v) => set('acknowledgementMode', v)}
              hint={scanner ? 'Scanner delivery uses the built-in idempotent intake and reconciliation contract.' : undefined} />
            {d.authenticationType !== 'NONE' && (
              <div className="cf-ad-span">
                <TextField label="Protected secret reference" type="password" value={d.secretReference || ''}
                  placeholder={d.secretConfigured ? 'Configured. Leave blank to keep the existing reference.' : 'Deployment-managed secret reference'}
                  onChange={(v) => set('secretReference', v)}
                  hint="Credential values are never displayed or returned by this screen." />
              </div>
            )}
          </div>
        </fieldset>

        <fieldset className="cf-ad-fieldset">
          <legend>Limits</legend>
          <div className="cf-ad-grid">
            <NumberField label="Timeout" suffix="seconds" min={1} max={600} value={d.timeoutSeconds ?? 60} onChange={(v) => set('timeoutSeconds', v)} />
            <NumberField label="Retry attempts" min={0} max={10} value={d.retryMaxAttempts ?? 2} onChange={(v) => set('retryMaxAttempts', v)} />
            {d.purpose === 'INGESTION_SOURCE' && <>
              <NumberField label="Incomplete window" suffix="hours" min={1} max={168} value={d.incompleteWindowHours ?? 24} onChange={(v) => set('incompleteWindowHours', v)} />
              <NumberField label="Max documents per intake" min={1} max={1000} value={scanner ? 1 : (d.maxDocumentsPerIntake ?? 100)} onChange={(v) => { if (!scanner) set('maxDocumentsPerIntake', v); }}
                hint={scanner ? 'Scanner intake is one consolidated PDF per physical package.' : undefined} />
              <NumberField label="Max PDF size" suffix="MB" min={1} max={2048} value={Math.round((d.maxPdfSizeBytes ?? 262144000) / MB)} onChange={(v) => set('maxPdfSizeBytes', v * MB)} />
              <NumberField label="Max PDF pages" min={1} max={10000} value={d.maxPdfPages ?? 2000} onChange={(v) => set('maxPdfPages', v)} />
            </>}
          </div>
        </fieldset>

        {polling && (
          <fieldset className="cf-ad-fieldset">
            <legend>Polling schedule</legend>
            <div className="cf-ad-grid">
              <SelectField<IntegrationPollingScheduleType> label="Schedule" value={d.pollingScheduleType || 'INTERVAL'} options={[['INTERVAL', 'Interval'], ['DAILY', 'Daily']]} onChange={(v) => set('pollingScheduleType', v)} />
              {d.pollingScheduleType !== 'DAILY' ? (
                <NumberField label="Interval" suffix="minutes" min={5} max={1440} value={d.pollingIntervalMinutes || 60} onChange={(v) => set('pollingIntervalMinutes', v)} />
              ) : <>
                <TextField label="Daily time" required type="time" value={d.pollingLocalTime || '01:00'} onChange={(v) => set('pollingLocalTime', v)} />
                <TextField label="IANA time zone" required mono value={d.pollingTimeZone || ''} placeholder="America/New_York" onChange={(v) => set('pollingTimeZone', v)} />
              </>}
              <NumberField label="Packages per poll" min={1} max={1000} value={d.pollingBatchLimit || 100} onChange={(v) => set('pollingBatchLimit', v)} />
            </div>
          </fieldset>
        )}

        <div className="cf-field">
          <label htmlFor="cf-int-desc"><span>Description</span><span className="cf-field-opt">Optional</span></label>
          <textarea id="cf-int-desc" rows={3} value={d.description || ''} onChange={(e) => set('description', e.target.value)} />
        </div>
        {!editing && <Toggle label="Enable immediately after creation" checked={Boolean(d.enabled)} onChange={(v) => set('enabled', v)} />}
        <p className="cf-ad-hint">Test actions never send a customer PDF.</p>
        {error && <Notice tone="danger">{error}</Notice>}
      </div>
    </Sheet>
  );
}
