import { useState } from 'react';
import useSWR, { useSWRConfig } from 'swr';
import { apiClient } from '../../api/client';
import type {
  DerivedDelivery,
  IntegrationConnection,
  IntegrationConnectionRequest,
  IntegrationProviderType,
  IntegrationPurpose,
  PollingConnectionStatus,
  PlatformAdminOverview,
  WatchedFolderAdminStatus,
  WatchedFolderSettings,
} from '../../types';
import { errorMessage } from '../data';
import SourceBulk from './SourceBulk';
import { PRIORITIES, PRIORITY_FIELD, PRIORITY_NAMES, priorityChoice, type PriorityCode, SENSITIVITY_FIELD, SENSITIVITY_LEVELS, SENSITIVITY_NAMES, sensitivityChoice, sensitivityName, type SensitivityLevelCode } from '../words';
import { Notice, Spinner } from '../atoms';
import { useToast } from '../toast';
import { ConfirmDialog, DurationField, Loading, NumberField, SelectField, Sheet, TextField, Toggle, when } from './shared';
import { MINUTE_UNITS } from './adminRules';
import {
  ACK_NAMES, AUTH_NAMES, DEFAULT_MAX_PDF_SIZE_BYTES, RECEIVE_METHODS, SEND_METHODS, SOURCE_FILES_HINT, SOURCE_MAX_FILES, SOURCE_MAX_PAGES, SOURCE_PAGES_HINT, VIEWS,
  advancedSummary, attentionLine, attentionTitle, authName, capToCaseLimits,
  connectionStatus, connectionTag, draftTestResult, failureReason, folderCheckResult, isPolling, methodOf, slashParts, watchedFolderStatus,
  type Method, type StatusLine, type View,
} from './integrationRules';
import RoutingRules from './RoutingRules';
import { useUnsavedWork } from '../unsavedWork';
import { HelpTip } from './HelpTip';
import type { HelpKey } from './helpTexts';

type Draft = IntegrationConnectionRequest & { connectionId?: string; secretConfigured?: boolean };

const MB = 1048576;

const baseDraft = (): Draft => ({
  name: '', description: '', purpose: 'INGESTION_SOURCE', direction: 'INBOUND', providerType: 'PUSH_API', authenticationType: 'NONE',
  clientPrincipal: '', endpoint: '', fileShareRoot: '', secretReference: '', timeoutSeconds: 60, retryMaxAttempts: 2,
  incompleteWindowHours: 24, maxDocumentsPerIntake: SOURCE_MAX_FILES, maxPdfSizeBytes: DEFAULT_MAX_PDF_SIZE_BYTES, maxPdfPages: SOURCE_MAX_PAGES,
  pollingScheduleType: undefined, pollingIntervalMinutes: undefined, pollingLocalTime: undefined, pollingTimeZone: undefined, pollingBatchLimit: undefined,
  acknowledgementMode: 'NONE', enabled: false,
});

const NO_POLLING = { pollingScheduleType: undefined, pollingIntervalMinutes: undefined, pollingLocalTime: undefined, pollingTimeZone: undefined, pollingBatchLimit: undefined };

/**
 * A method sets purpose, provider, direction and acknowledgement together, with the same rules the earlier editor
 * applied per purpose and provider (applyPurpose / applyProvider). Fields the method does not use are cleared.
 */
function applyMethod(cur: Draft, method: Method): Draft {
  const keep = { connectionId: cur.connectionId, secretConfigured: cur.secretConfigured, name: cur.name, description: cur.description, enabled: cur.enabled,
    timeoutSeconds: cur.timeoutSeconds, retryMaxAttempts: cur.retryMaxAttempts, authenticationType: cur.authenticationType, secretReference: cur.secretReference };
  const base = { ...baseDraft(), ...keep };
  const receive = (providerType: IntegrationProviderType, extra: Partial<Draft> = {}): Draft => ({
    ...base, purpose: 'INGESTION_SOURCE', direction: 'INBOUND', providerType, acknowledgementMode: 'NONE', clientPrincipal: cur.clientPrincipal,
    incompleteWindowHours: cur.incompleteWindowHours, maxDocumentsPerIntake: cur.maxDocumentsPerIntake, maxPdfSizeBytes: cur.maxPdfSizeBytes, maxPdfPages: cur.maxPdfPages,
    ...NO_POLLING, ...extra,
  });
  switch (method) {
    case 'PUSH': return receive('PUSH_API');
    case 'FOLDER': return receive('WATCHED_FOLDER', { clientPrincipal: '' });
    case 'SCANNER': return receive('SCANNER', { maxDocumentsPerIntake: 1, maxPdfSizeBytes: 2147483648, maxPdfPages: SOURCE_MAX_PAGES });
    case 'POLL': return receive('HTTP_API', {
      direction: 'OUTBOUND', clientPrincipal: '', endpoint: cur.endpoint, acknowledgementMode: 'SOURCE_PICKUP_ACK',
      pollingScheduleType: cur.pollingScheduleType || 'INTERVAL', pollingIntervalMinutes: cur.pollingIntervalMinutes || 60,
      pollingLocalTime: cur.pollingLocalTime, pollingTimeZone: cur.pollingTimeZone, pollingBatchLimit: cur.pollingBatchLimit || 100,
    });
    case 'DOCUMENT_STORE': return { ...base, purpose: 'DOCUMENT_STORE_DELIVERY', direction: 'OUTBOUND', providerType: 'HTTP_API', endpoint: cur.endpoint, acknowledgementMode: 'SUBMISSION_ACK' };
    case 'BARCODE_WEB': return { ...base, purpose: 'DERIVED_DOCUMENT_DELIVERY', direction: 'OUTBOUND', providerType: 'HTTP_API', endpoint: cur.endpoint, acknowledgementMode: 'DESTINATION_RECEIPT' };
    case 'BARCODE_SHARE': return { ...base, purpose: 'DERIVED_DOCUMENT_DELIVERY', direction: 'OUTBOUND', providerType: 'FILE_SHARE', authenticationType: 'NONE', fileShareRoot: cur.fileShareRoot, acknowledgementMode: 'DURABLE_FILE_WRITE' };
  }
}

function toDraft(c: IntegrationConnection): Draft {
  return capToCaseLimits({
    connectionId: c.connectionId, name: c.name, description: c.description || '', purpose: c.purpose, direction: c.direction,
    providerType: c.providerType, authenticationType: c.authenticationType, clientPrincipal: c.clientPrincipal || '', endpoint: c.endpoint || '',
    fileShareRoot: c.fileShareRoot || '', timeoutSeconds: c.timeoutSeconds, retryMaxAttempts: c.retryMaxAttempts,
    incompleteWindowHours: c.incompleteWindowHours, maxDocumentsPerIntake: c.maxDocumentsPerIntake, maxPdfSizeBytes: c.maxPdfSizeBytes,
    maxPdfPages: c.maxPdfPages, pollingScheduleType: c.pollingScheduleType || undefined, pollingIntervalMinutes: c.pollingIntervalMinutes || undefined,
    pollingLocalTime: c.pollingLocalTime || undefined, pollingTimeZone: c.pollingTimeZone || undefined, pollingBatchLimit: c.pollingBatchLimit || undefined,
    acknowledgementMode: c.acknowledgementMode, enabled: c.enabled, secretReference: '', secretConfigured: c.secretConfigured,
  });
}

function toPayload(d: Draft): IntegrationConnectionRequest {
  const polling = isPolling(d);
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

/** Which saved connections LexaVault can test: the ones it connects to itself. */
const testKind = (c: Pick<IntegrationConnection, 'purpose' | 'providerType'>): 'document-store' | 'derived' | 'polling' | null =>
  c.purpose === 'DOCUMENT_STORE_DELIVERY' && c.providerType === 'HTTP_API' ? 'document-store'
    : c.purpose === 'DERIVED_DOCUMENT_DELIVERY' ? 'derived'
      : isPolling(c) ? 'polling' : null;

const purposeOf = (v: View): IntegrationPurpose => (v === 'ALL' ? 'INGESTION_SOURCE' : v);

export default function IntegrationsArea() {
  const { mutate: globalMutate } = useSWRConfig();
  const conns = useSWR<IntegrationConnection[]>('integrations', () => apiClient.listIntegrationConnections(), { revalidateOnFocus: false });
  const [view, setView] = useState<View>('INGESTION_SOURCE');
  const showsReceiving = view === 'INGESTION_SOURCE' || view === 'ALL';
  const overview = useSWR<PlatformAdminOverview>('platform-overview', () => apiClient.getPlatformAdminOverview(), { revalidateOnFocus: false });
  const attention = useSWR<DerivedDelivery[]>('derived-attention', () => apiClient.listDerivedDeliveryAttention().catch(() => []), { revalidateOnFocus: false });
  const [draft, setDraft] = useState<Draft | null>(null);
  useUnsavedWork(draft !== null);
  const [toggling, setToggling] = useState<IntegrationConnection | null>(null);

  if (!conns.data) return <Loading error={conns.error} retry={() => conns.mutate()} label="Integrations" />;
  const all = [...conns.data].sort((a, b) => a.name.localeCompare(b.name));

  // The watched folder appears once (finding 16): on its connection when there is exactly one, else as its own row.
  const folder = overview.data?.watchedFolder;
  const folderConnections = all.filter((c) => c.providerType === 'WATCHED_FOLDER');
  const folderOn = folderConnections.length === 1 ? folderConnections[0].connectionId : null;
  const folderRow = !!folder?.rootPath && folderOn === null;

  const inView = (v: View) => (v === 'ALL' ? all : all.filter((c) => c.purpose === v));
  const count = (v: View) => inView(v).length + (folderRow && (v === 'INGESTION_SOURCE' || v === 'ALL') ? 1 : 0);
  const visible = inView(view);
  const viewCfg = VIEWS.find((v) => v.id === view)!;
  const failed = (attention.data ?? []).filter((d) => d.status === 'ATTENTION_REQUIRED' || d.status === 'RETRY_PENDING');

  const refresh = async () => {
    await Promise.all([conns.mutate(), attention.mutate(), overview.mutate(), globalMutate('routing-destinations'), globalMutate('routing-rules'), globalMutate((k) => Array.isArray(k) && k[0] === 'polling')]);
  };

  const empty = visible.length === 0 && !(folderRow && showsReceiving);

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
        <button type="button" className="cf-btn" data-size="sm" data-variant="ghost" onClick={() => void refresh()}>Refresh</button>
        <button type="button" className="cf-btn" data-size="sm" data-variant="solid" onClick={() => setDraft({ ...baseDraft(), purpose: purposeOf(view) })}>Add a connection</button>
      </div>

      <section className="cf-ad-card" aria-label={viewCfg.title}>
        <header className="cf-ad-card-head"><div><div className="cf-ad-title"><h2>{viewCfg.title}</h2><HelpTip topic={VIEW_HELP[view]} /></div><p>{viewCfg.note}</p></div></header>
        {empty ? (
          <div className="cf-ad-card-body"><p className="cf-ad-hint">{viewCfg.empty}</p></div>
        ) : (
          <ul className="cf-ad-conns">
            {visible.map((c) => (
              <Connection key={c.connectionId} c={c} showGroup={view === 'ALL'} folder={c.connectionId === folderOn ? folder : undefined}
                onChanged={refresh} onEdit={() => setDraft(toDraft(c))} onToggle={() => setToggling(c)} />
            ))}
            {folderRow && showsReceiving && folder && <FolderRow folder={folder} onChanged={refresh} />}
          </ul>
        )}
        {view === 'DERIVED_DOCUMENT_DELIVERY' && <BarcodeExtras failed={failed} onChanged={refresh} />}
      </section>

      {view === 'ALL' && (
        <section className="cf-ad-card" aria-label="Sending by barcode: rules">
          <header className="cf-ad-card-head"><div><h2>Sending by barcode: rules</h2><p>Which pages go where.</p></div></header>
          <BarcodeExtras failed={failed} onChanged={refresh} />
        </section>
      )}

      <ConnectionEditor draft={draft} onChange={setDraft} onClose={() => setDraft(null)}
        original={draft?.connectionId ? conns.data.find((c) => c.connectionId === draft.connectionId) : undefined}
        onSaved={async (saved) => { if (view !== 'ALL') setView(saved.purpose); await refresh(); }} />

      <ToggleConfirm conn={toggling} onClose={() => setToggling(null)} onDone={refresh} />
    </div>
  );
}

/** U026: the "?" note for each view's card (helpTexts.ts). */
const VIEW_HELP: Record<View, HelpKey> = { INGESTION_SOURCE: 'receiving', DOCUMENT_STORE_DELIVERY: 'sendingToDocumentStore', DERIVED_DOCUMENT_DELIVERY: 'sendingByBarcode', ALL: 'allConnections' };

/* ---------- One connection ---------- */

function StatusText({ s }: { s: StatusLine }) {
  return (
    <div className="cf-ad-conn-status">
      <span data-tone={s.tone}>{s.word}</span>{s.rest.map((r) => <span key={r}> · {r}</span>)}
    </div>
  );
}

function Connection({ c, showGroup, folder, onChanged, onEdit, onToggle }: {
  c: IntegrationConnection; showGroup: boolean; folder?: WatchedFolderAdminStatus; onChanged: () => Promise<unknown>; onEdit: () => void; onToggle: () => void;
}) {
  const toast = useToast();
  const [busy, setBusy] = useState<null | 'collect' | 'check'>(null);
  const polls = isPolling(c);
  const pollStatus = useSWR<PollingConnectionStatus | null>(polls ? ['polling', c.connectionId] : null,
    () => apiClient.getPollingConnectionStatus(c.connectionId).catch(() => null), { revalidateOnFocus: false });
  const now = Date.now();
  const status = folder && c.enabled ? watchedFolderStatus(folder, now) : connectionStatus(c, now);
  const address = c.endpoint || c.fileShareRoot || (folder ? folder.rootPath : null);

  async function collect() {
    setBusy('collect');
    try {
      const r = await apiClient.runPollingNow(c.connectionId);
      const n = r.packagesDiscovered;
      toast(r.status === 'SUCCESS' || r.status === 'PARTIAL' ? `${c.name}: ${n} case${n === 1 ? '' : 's'} found.` : `${c.name}: collecting didn't work (${failureReason(r.failureCategory, r.lvCode)}). See its status line.`, r.status === 'SUCCESS' ? undefined : 'danger');
      await onChanged();
    } catch (e) {
      toast(errorMessage(e, `${c.name} couldn't be collected from.`), 'danger');
    } finally {
      setBusy(null);
    }
  }

  async function check() {
    setBusy('check');
    try {
      const r = await apiClient.scanPlatformWatchedFolder();
      const result = folderCheckResult(r);
      toast(result.text, result.tone);
      await onChanged();
    } catch (e) {
      toast(errorMessage(e, "The folder couldn't be checked."), 'danger');
    } finally {
      setBusy(null);
    }
  }

  return (
    <li className="cf-ad-conn cf-ad-conn-top">
      <span className="cf-ad-health" data-h={status.tone === 'off' ? 'off' : status.tone} aria-hidden="true" />
      <div className="cf-ad-conn-main">
        <div className="cf-ad-conn-name">
          {c.name}
          <span className="cf-ad-tag">{connectionTag(c)}</span>
          {showGroup && <span className="cf-ad-tag">{VIEWS.find((v) => v.id === c.purpose)?.label}</span>}
        </div>
        <StatusText s={status} />
        {address && <div className="cf-ad-conn-endpoint cf-mono">{address}</div>}
        {folder && <FolderSensitivity />}
        {folder ? <><SourcePriority source="folder:installed" /><SourceBulk source="folder:installed" name={c.name} /></>
          : c.purpose === 'INGESTION_SOURCE' && <><SourcePriority source={c.connectionId} /><SourceBulk source={c.connectionId} name={c.name} /></>}
        <details className="cf-disclosure">
          <summary>Technical details</summary>
          <dl className="cf-ad-facts cf-ad-facts-compact">
            <div><dt>Provider</dt><dd>{c.providerType} · {c.direction}</dd></div>
            <div><dt>Sign-in</dt><dd>{authName(c.authenticationType)}{c.authenticationType !== 'NONE' && (c.secretConfigured ? ' · key stored' : ' · no key stored')}</dd></div>
            {c.clientPrincipal && <div><dt>Signs in as</dt><dd className="cf-mono">{c.clientPrincipal}</dd></div>}
            <div><dt>Settings</dt><dd>{advancedSummary(c)}</dd></div>
            {polls && <div><dt>Next collection</dt><dd>{pollStatus.data?.nextScheduledAt ? when(pollStatus.data.nextScheduledAt) : 'not scheduled'}</dd></div>}
            <div><dt>Health</dt><dd>{c.healthStatus}</dd></div>
            {c.lastFailureCategory && <div><dt>Last failure</dt><dd>{c.lastFailureCategory} · {when(c.lastFailureAt)}</dd></div>}
            {folder && <div><dt>Last check</dt><dd>{folder.lastScanOutcome}{folder.coordinationEnabled ? ' · several servers' : ''}</dd></div>}
            <div><dt>Configuration</dt><dd>v{c.configurationVersion}</dd></div>
            <div><dt>Connection ID</dt><dd className="cf-mono">{c.connectionId}</dd></div>
          </dl>
        </details>
      </div>
      <div className="cf-ad-conn-actions">
        {polls && <button type="button" className="cf-btn" data-size="sm" disabled={!c.enabled || !!busy} onClick={() => void collect()}>{busy === 'collect' && <Spinner label="Collecting" />}Collect now</button>}
        {folder && <button type="button" className="cf-btn" data-size="sm" disabled={!folder.enabled || !!busy} onClick={() => void check()}>{busy === 'check' && <Spinner label="Checking" />}Check now</button>}
        <button type="button" className="cf-btn" data-size="sm" data-variant="ghost" disabled={!!busy} onClick={onToggle}>{c.enabled ? 'Switch off' : 'Switch on'}</button>
        <button type="button" className="cf-btn" data-size="sm" onClick={onEdit}>Edit</button>
      </div>
    </li>
  );
}

/**
 * Task 0021: the level every file picked up from the watched folder gets (Tenant IT; default Standard). A default:
 * a later trigger-file mapping may give a drop its own level. Files already picked up keep theirs.
 */
function FolderSensitivity() {
  const toast = useToast();
  const settings = useSWR<WatchedFolderSettings>('watched-folder-settings', () => apiClient.getWatchedFolderSettings(), { revalidateOnFocus: false });
  const [draft, setDraft] = useState<SensitivityLevelCode | null>(null);
  const [saving, setSaving] = useState(false);
  if (!settings.data) return null;
  // Task 0023 A4: no setting saved, or a level this screen does not know, shows Standard.
  const saved = sensitivityChoice(settings.data.sensitivityLevel);
  const value = draft ?? saved;
  async function save() {
    if (draft === null || draft === saved) return;
    setSaving(true);
    try {
      await settings.mutate(await apiClient.updateWatchedFolderSettings(draft), { revalidate: false });
      setDraft(null);
      toast(`Files from this folder will be ${sensitivityName(draft)}.`);
    } catch (e) {
      toast(errorMessage(e, "The folder's sensitivity couldn't be saved."), 'danger');
    } finally {
      setSaving(false);
    }
  }
  return (
    <div data-testid="folder-sensitivity">
      <SelectField<SensitivityLevelCode>
        label={SENSITIVITY_FIELD.folderLabel}
        value={value}
        options={SENSITIVITY_LEVELS.map((l) => [l, SENSITIVITY_NAMES[l]] as [SensitivityLevelCode, string])}
        onChange={(v) => setDraft(v)}
        hint={SENSITIVITY_FIELD.folderHint}
        disabled={saving}
      />
      {draft !== null && draft !== saved && (
        <button type="button" className="cf-btn" data-size="sm" data-variant="primary" disabled={saving} onClick={() => void save()}>
          {saving && <Spinner label="Saving" />}Save
        </button>
      )}
    </div>
  );
}

/** Task 0073: the priority of a source's cases when it sends none. */
function SourcePriority({ source }: { source: string }) {
  const toast = useToast();
  const { data, mutate } = useSWR('source-priorities', () => apiClient.getSourcePriorities(), { revalidateOnFocus: false });
  const [saving, setSaving] = useState(false);
  if (!data) return null;
  async function save(next: PriorityCode) {
    setSaving(true);
    try {
      await mutate(await apiClient.changeSourcePriority(source, next), { revalidate: false });
      toast(`Cases from this source will be ${PRIORITY_NAMES[next]}.`);
    } catch (e) {
      toast(errorMessage(e, "The source's priority couldn't be saved."), 'danger');
    } finally {
      setSaving(false);
    }
  }
  return (
    <div data-testid="source-priority">
      <SelectField<PriorityCode>
        label={PRIORITY_FIELD.sourceLabel}
        value={priorityChoice(data.sources?.[source])}
        options={PRIORITIES.map((p) => [p, PRIORITY_NAMES[p]] as [PriorityCode, string])}
        onChange={(v) => void save(v)}
        hint={PRIORITY_FIELD.sourceHint}
        disabled={saving}
      />
    </div>
  );
}

/** The installed watched folder when no single connection stands for it. It is set when LexaVault is installed, so it has no Edit. */
function FolderRow({ folder, onChanged }: { folder: WatchedFolderAdminStatus; onChanged: () => Promise<unknown> }) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const status = watchedFolderStatus(folder, Date.now());
  async function check() {
    setBusy(true);
    try {
      const r = await apiClient.scanPlatformWatchedFolder();
      const result = folderCheckResult(r);
      toast(result.text, result.tone);
      await onChanged();
    } catch (e) {
      toast(errorMessage(e, "The folder couldn't be checked."), 'danger');
    } finally {
      setBusy(false);
    }
  }
  return (
    <li className="cf-ad-conn cf-ad-conn-top">
      <span className="cf-ad-health" data-h={status.tone} aria-hidden="true" />
      <div className="cf-ad-conn-main">
        <div className="cf-ad-conn-name">Watched folder<span className="cf-ad-tag">Folder</span></div>
        <StatusText s={status} />
        <div className="cf-ad-conn-endpoint cf-mono">{folder.rootPath}</div>
        <FolderSensitivity />
        <SourcePriority source="folder:installed" />
        <SourceBulk source="folder:installed" name="the watched folder" />
        <details className="cf-disclosure">
          <summary>Technical details</summary>
          <dl className="cf-ad-facts cf-ad-facts-compact">
            <div><dt>Set by</dt><dd>LexaVault's installation settings</dd></div>
            <div><dt>Checked automatically</dt><dd>{folder.schedulingEnabled ? `every ${Math.round(folder.scanIntervalMs / 1000)} s` : 'no'}</dd></div>
            <div><dt>Ready file</dt><dd className="cf-mono">{folder.triggerFileName}</dd></div>
            <div><dt>Last check</dt><dd>{folder.lastScanOutcome}{folder.lastScanAt ? ` · ${when(folder.lastScanAt)}` : ''}</dd></div>
            <div><dt>Servers</dt><dd>{folder.coordinationEnabled ? 'several, coordinated' : 'one'}</dd></div>
          </dl>
        </details>
      </div>
      <div className="cf-ad-conn-actions cf-ad-conn-actions-note">
        <button type="button" className="cf-btn" data-size="sm" disabled={!folder.enabled || busy} onClick={() => void check()}>{busy && <Spinner label="Checking" />}Check now</button>
        {/* U019: the backend has no edit or switch-off for the installed folder (scan only), so say why they're missing. */}
        <span className="cf-ad-conn-note">Set up by the install configuration</span>
      </div>
    </li>
  );
}

/* ---------- Switch on / off ---------- */

function ToggleConfirm({ conn, onClose, onDone }: { conn: IntegrationConnection | null; onClose: () => void; onDone: () => Promise<unknown> }) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastId, setLastId] = useState<string | undefined>();
  if (conn?.connectionId !== lastId) { setLastId(conn?.connectionId); setError(null); }
  const off = !!conn?.enabled;

  async function confirm() {
    if (!conn) return;
    setBusy(true);
    setError(null);
    try {
      await apiClient.setIntegrationConnectionEnabled(conn.connectionId, !conn.enabled);
      await onDone();
      toast(`${conn.name} switched ${conn.enabled ? 'off' : 'on'}.`);
      onClose();
    } catch (e) {
      setError(errorMessage(e, `${conn.name} couldn't be switched ${conn.enabled ? 'off' : 'on'}.`));
    } finally {
      setBusy(false);
    }
  }

  const consequence = !conn ? null : off
    ? conn.purpose === 'INGESTION_SOURCE'
      ? 'It stops receiving or collecting new documents. Cases already received are not affected.'
      : conn.purpose === 'DOCUMENT_STORE_DELIVERY'
        ? "Delivered cases can't be sent to the document store through it until it is switched on again."
        : 'Rules that send pages here stop sending until it is switched on again.'
    : 'It starts again with its saved settings.';

  return (
    <ConfirmDialog open={!!conn} title={off ? `Switch off ${conn?.name ?? ''}?` : `Switch on ${conn?.name ?? ''}?`} danger={off}
      confirmLabel={off ? 'Switch off' : 'Switch on'} busy={busy} error={error}
      facts={conn ? [['Connection', conn.name], ['What it does', connectionTag(conn)]] : []}
      consequence={consequence} onConfirm={confirm} onCancel={onClose} />
  );
}

/* ---------- Sending by barcode: rules and files that couldn't be sent ---------- */

function BarcodeExtras({ failed, onChanged }: { failed: DerivedDelivery[]; onChanged: () => Promise<unknown> }) {
  return (
    <>
      <RoutingRules />
      {failed.length > 0 && <FailedFiles items={failed} onChanged={onChanged} />}
    </>
  );
}

function FailedFiles({ items, onChanged }: { items: DerivedDelivery[]; onChanged: () => Promise<unknown> }) {
  const toast = useToast();
  const [busy, setBusy] = useState<string | null>(null);
  async function retry(d: DerivedDelivery) {
    setBusy(d.derivedDeliveryId);
    try {
      await apiClient.retryDerivedDelivery(d.derivedDeliveryId);
      toast('Sent again.');
      await onChanged();
    } catch (e) {
      toast(errorMessage(e, "It couldn't be sent again."), 'danger');
    } finally {
      setBusy(null);
    }
  }
  return (
    <div className="cf-ad-block" role="region" aria-label="Files that couldn't be sent">
      <h3 className="cf-ad-block-title"><span className="cf-ad-health" data-h="warn" aria-hidden="true" />{attentionTitle(items.length)}</h3>
      <ul className="cf-ad-conns">
        {items.map((d) => (
          <li key={d.derivedDeliveryId} className="cf-ad-conn cf-ad-conn-top">
            <div className="cf-ad-conn-main">
              <div className="cf-ad-conn-status">{attentionLine(d)}</div>
              <details className="cf-disclosure">
                <summary>Technical details</summary>
                <dl className="cf-ad-facts cf-ad-facts-compact">
                  <div><dt>Package</dt><dd className="cf-mono">{d.packageId}</dd></div>
                  <div><dt>Status</dt><dd>{d.status}</dd></div>
                  {d.failureCategory && <div><dt>Reason code</dt><dd>{d.failureCategory}</dd></div>}
                  {/* Task 0023 B4: the document id, never the file name. */}
                  {d.sourceDocumentId && <div><dt>Document</dt><dd className="cf-mono">{d.sourceDocumentId}</dd></div>}
                  <div><dt>Delivery run</dt><dd>{d.exportCycle}</dd></div>
                </dl>
              </details>
            </div>
            {d.markerType && (
              <div className="cf-ad-conn-actions">
                <button type="button" className="cf-btn" data-size="sm" disabled={!!busy} onClick={() => void retry(d)}>{busy === d.derivedDeliveryId && <Spinner label="Retrying" />}Retry</button>
              </div>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}

/* ---------- The connection editor: what, details, test and save ---------- */

type Step = 1 | 2 | 3;
type TestResult = { tone: 'ok' | 'danger'; text: string };

/** The fields a test of the saved connection depends on, to tell whether the draft still matches what was saved. */
const sameAsSaved = (d: Draft, c: IntegrationConnection | undefined) =>
  !!c && JSON.stringify({ ...toPayload(d), enabled: undefined, description: undefined }) === JSON.stringify({ ...toPayload(toDraft(c)), enabled: undefined, description: undefined });

function ConnectionEditor({ draft, original, onChange, onClose, onSaved }: {
  draft: Draft | null; original?: IntegrationConnection;
  onChange: (d: Draft) => void; onClose: () => void; onSaved: (saved: IntegrationConnection) => Promise<void>;
}) {
  const toast = useToast();
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [tested, setTested] = useState<TestResult | null>(null);
  const [openKey, setOpenKey] = useState<string | null>(null);
  const [step, setStep] = useState<Step>(1);
  const key = draft ? draft.connectionId ?? 'new' : null;
  if (key !== openKey) { setOpenKey(key); setError(null); setTested(null); setStep(draft?.connectionId ? 2 : 1); }

  if (!draft) return <Sheet open={false} title="" onClose={onClose}>{null}</Sheet>;
  const d = draft;
  const editing = !!d.connectionId;
  const receive = d.purpose === 'INGESTION_SOURCE';
  const method = methodOf(d);
  const set = <K extends keyof Draft>(k: K, v: Draft[K]) => { setTested(null); onChange({ ...d, [k]: v }); };
  const nameMissing = !d.name.trim();
  const outbound = d.direction === 'OUTBOUND';
  const hasAddress = method === 'POLL' || method === 'DOCUMENT_STORE' || method === 'BARCODE_WEB';
  const kind = testKind(d);
  const unchanged = editing && sameAsSaved(d, original);

  async function save() {
    if (nameMissing) { setStep(2); setError('Give the connection a name.'); return; }
    setSaving(true);
    setError(null);
    try {
      const body = toPayload(d);
      const saved = d.connectionId ? await apiClient.updateIntegrationConnection(d.connectionId, body) : await apiClient.createIntegrationConnection(body);
      toast(`${saved.name} saved.`);
      onClose();
      await onSaved(saved);
    } catch (e) {
      setError(errorMessage(e, "The connection couldn't be saved."));
    } finally {
      setSaving(false);
    }
  }

  async function test() {
    if (!kind) return;
    setTesting(true);
    setTested(null);
    try {
      if (d.connectionId && unchanged) {
        // The saved settings: the existing test, which also updates the connection's status.
        const r = kind === 'document-store' ? await apiClient.testDocumentStoreConnection(d.connectionId)
          : kind === 'derived' ? await apiClient.testDerivedConnection(d.connectionId)
            : await apiClient.testPollingConnection(d.connectionId);
        setTested(r.healthStatus === 'HEALTHY'
          ? { tone: 'ok', text: 'The connection works.' }
          : { tone: 'danger', text: "The connection didn't work. Check the address and sign-in, then test again." });
      } else {
        // New or changed settings: tested before saving. Nothing is saved and the status doesn't change.
        const r = await apiClient.testDraftConnection(toPayload(d), d.connectionId ?? undefined);
        setTested(draftTestResult(r));
      }
    } catch (e) {
      setTested({ tone: 'danger', text: errorMessage(e, "The connection didn't work. Check the address and sign-in, then test again.") });
    } finally {
      setTesting(false);
    }
  }

  const methodLabel = [...RECEIVE_METHODS, ...SEND_METHODS].find(([m]) => m === method)?.[1] ?? '';
  const where = receive ? 'Receiving documents' : 'Sending documents';
  const description = `Step ${step} of 3 · ${step === 1 ? 'What should it do?' : `${where} · ${methodLabel}`}`;

  const testNote = !kind
    ? receive ? 'Nothing to test here: the documents are sent to LexaVault, and they show up on Cases when they arrive.' : null
    : !unchanged ? "The test uses the settings above. Nothing is saved until you choose Save, and you can save without testing." : null;

  return (
    <Sheet open wide title={editing ? `Edit ${original?.name ?? 'connection'}` : 'Add a connection'} description={description}
      onClose={() => { if (!saving) onClose(); }}
      footer={<>
        {step > (editing ? 2 : 1) && <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={saving} onClick={() => setStep((step - 1) as Step)}>Back</button>}
        <span className="cf-ad-spacer" />
        <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={saving} onClick={onClose}>Cancel</button>
        {step < 3 && <button type="button" className="cf-btn" data-variant="solid" data-size="sm" disabled={step === 2 && nameMissing}
          onClick={() => { setError(null); setStep((step + 1) as Step); }}>{step === 1 ? 'Next' : 'Next: test and save'}</button>}
        {step === 3 && <>
          {kind && <button type="button" className="cf-btn" data-size="sm" disabled={saving || testing} onClick={() => void test()}>{testing && <Spinner label="Testing" />}Test connection</button>}
          <button type="button" className="cf-btn" data-variant="solid" data-size="sm" disabled={saving || nameMissing} onClick={() => void save()}>{saving && <Spinner label="Saving" />}Save</button>
        </>}
      </>}
    >
      <div className="cf-ad-form">
        {step === 1 && (
          <fieldset className="cf-ad-fieldset">
            <legend>What should this connection do?</legend>
            <div className="cf-ad-radios" data-layout="row">
              {([
                ['receive', 'Receive documents', 'A system sends them, LexaVault collects them, or a folder or scanner supplies them.'],
                ['send', 'Send documents', 'To the document store, or pages by barcode to another place.'],
              ] as const).map(([v, label, hint]) => (
                <label key={v} className="cf-ad-radio">
                  <input type="radio" name="cf-int-direction" checked={(v === 'receive') === receive}
                    onChange={() => onChange(applyMethod(d, v === 'receive' ? 'PUSH' : 'DOCUMENT_STORE'))} />
                  <span><strong>{label}</strong><span className="cf-ad-hint">{hint}</span></span>
                </label>
              ))}
            </div>
          </fieldset>
        )}

        {step === 2 && <>
          <fieldset className="cf-ad-fieldset">
            <legend>Basics</legend>
            <div className="cf-ad-grid">
              <div className="cf-ad-span"><TextField label="Name" required value={d.name} invalid={nameMissing} onChange={(v) => set('name', v)} hint={nameMissing ? 'Required.' : 'Shown in this list and in the audit trail.'} /></div>
              <div className="cf-ad-span"><SelectField<Method> label={receive ? 'How documents arrive' : 'Where documents go'} value={method}
                options={receive ? RECEIVE_METHODS : SEND_METHODS} onChange={(m) => { setTested(null); onChange(applyMethod(d, m)); }} /></div>
            </div>
          </fieldset>

          {method !== 'FOLDER' && (
            <fieldset className="cf-ad-fieldset">
              <legend>Where and how to connect</legend>
              <div className="cf-ad-grid">
                {hasAddress && (
                  <div className="cf-ad-span"><TextField label="Address" required mono value={d.endpoint || ''} placeholder="https://…" onChange={(v) => set('endpoint', v)}
                    hint={method === 'POLL' ? 'The web address LexaVault collects documents from.' : 'The web address LexaVault sends to.'} /></div>
                )}
                {method === 'BARCODE_SHARE' && (
                  <div className="cf-ad-span"><TextField label="Folder on the file share" required mono value={d.fileShareRoot || ''} placeholder="\\server\share\folder" onChange={(v) => set('fileShareRoot', v)}
                    hint="Files are written here. LexaVault needs permission to write to it." /></div>
                )}
                {(method === 'PUSH' || method === 'SCANNER') && (
                  <div className="cf-ad-span"><TextField label="Account it signs in with" mono value={d.clientPrincipal || ''} onChange={(v) => set('clientPrincipal', v)}
                    hint="The sign-in name the sending system uses (its OIDC subject or user name). Only this account can send documents here." /></div>
                )}
                {method !== 'BARCODE_SHARE' && (
                  <SelectField label={outbound ? 'How LexaVault proves who it is' : 'How the system proves who it is'} value={d.authenticationType} options={AUTH_NAMES}
                    onChange={(v) => set('authenticationType', v)} hint="The sign-in method the other side expects." />
                )}
                {d.authenticationType !== 'NONE' && (
                  <TextField label="Where the key is stored" type="password" value={d.secretReference || ''}
                    placeholder={d.secretConfigured ? 'A key is stored. Leave empty to keep it.' : 'For example vault:servicing/api-key'}
                    onChange={(v) => set('secretReference', v)} hint="A reference to your secrets store. The key itself is never shown." />
                )}
                {method === 'POLL' && <>
                  <SelectField label="Collect" value={d.pollingScheduleType || 'INTERVAL'} options={[['INTERVAL', 'Every few minutes or hours'], ['DAILY', 'Once a day']]}
                    onChange={(v) => set('pollingScheduleType', v)} />
                  {d.pollingScheduleType !== 'DAILY' ? (
                    <DurationField label="Collect every" value={d.pollingIntervalMinutes || 60} units={MINUTE_UNITS.slice(0, 2)} min={5} max={1440}
                      onChange={(v) => set('pollingIntervalMinutes', v)} hint="From 5 minutes up to 24 hours." />
                  ) : <>
                    <TextField label="At" required type="time" value={d.pollingLocalTime || '01:00'} onChange={(v) => set('pollingLocalTime', v)} />
                    <TextField label="Time zone" required mono value={d.pollingTimeZone || ''} placeholder="America/New_York" onChange={(v) => set('pollingTimeZone', v)} hint="As a region and city, for example America/New_York." />
                  </>}
                </>}
              </div>
            </fieldset>
          )}
          {method === 'FOLDER' && <p className="cf-ad-hint">The folder itself is set when LexaVault is installed. Its path and status are on the Receiving list.</p>}

          <details className="cf-disclosure">
            <summary>Advanced (usually leave as is)</summary>
            <p className="cf-ad-hint">{advancedSummary(d)}</p>
            <div className="cf-ad-grid">
              <NumberField label="Wait for an answer" suffix="seconds" min={1} max={600} value={d.timeoutSeconds ?? 60} onChange={(v) => set('timeoutSeconds', v)} />
              <NumberField label="Try again up to" suffix="times" min={0} max={10} value={d.retryMaxAttempts ?? 2} onChange={(v) => set('retryMaxAttempts', v)} />
              {receive && <>
                <NumberField label="Finish incomplete cases within" suffix="hours" min={1} max={168} value={d.incompleteWindowHours ?? 24} onChange={(v) => set('incompleteWindowHours', v)}
                  hint="A case still missing documents after this is refused." />
                <NumberField label="Files per case, at most" min={1} max={SOURCE_MAX_FILES} value={method === 'SCANNER' ? 1 : (d.maxDocumentsPerIntake ?? SOURCE_MAX_FILES)} onChange={(v) => { if (method !== 'SCANNER') set('maxDocumentsPerIntake', v); }}
                  hint={method === 'SCANNER' ? 'A scanner sends one PDF per case.' : SOURCE_FILES_HINT} />
                <NumberField label="File size, at most" suffix="MB" min={1} max={2048} value={Math.round((d.maxPdfSizeBytes ?? DEFAULT_MAX_PDF_SIZE_BYTES) / MB)} onChange={(v) => set('maxPdfSizeBytes', v * MB)} />
                <NumberField label="Pages per file, at most" min={1} max={SOURCE_MAX_PAGES} value={d.maxPdfPages ?? SOURCE_MAX_PAGES} onChange={(v) => set('maxPdfPages', v)}
                  hint={SOURCE_PAGES_HINT} />
              </>}
              {method === 'POLL' && <NumberField label="Cases per collection, at most" min={1} max={1000} value={d.pollingBatchLimit || 100} onChange={(v) => set('pollingBatchLimit', v)} />}
              {method !== 'SCANNER' && <SelectField label="Confirmation" value={d.acknowledgementMode} options={ACK_NAMES} onChange={(v) => set('acknowledgementMode', v)}
                hint="How LexaVault knows a hand-over worked." />}
            </div>
          </details>

          <div className="cf-field">
            <label htmlFor="cf-int-desc"><span>Description</span><span className="cf-field-opt">Optional</span></label>
            <textarea id="cf-int-desc" rows={2} value={d.description || ''} onChange={(e) => set('description', e.target.value)} />
          </div>
          {!editing && <Toggle label="Switch on when saved" checked={Boolean(d.enabled)} onChange={(v) => set('enabled', v)} />}
        </>}

        {step === 3 && <>
          <dl className="cf-ad-facts" aria-label="What will be saved">
            <div><dt>Name</dt><dd>{d.name}</dd></div>
            <div><dt>{receive ? 'How documents arrive' : 'Where documents go'}</dt><dd>{methodLabel}</dd></div>
            {(d.endpoint || d.fileShareRoot) && <div className="cf-ad-span"><dt>{method === 'BARCODE_SHARE' ? 'Folder' : 'Address'}</dt><dd className="cf-mono cf-ad-address">{slashParts((hasAddress ? d.endpoint : d.fileShareRoot) ?? '').map((part, i) => <span key={i}>{part}<wbr /></span>)}</dd></div>}
            {method !== 'BARCODE_SHARE' && method !== 'FOLDER' && <div><dt>Sign-in</dt><dd>{authName(d.authenticationType)}</dd></div>}
            <div><dt>Advanced</dt><dd>{advancedSummary(d)}</dd></div>
            {!editing && <div><dt>When saved</dt><dd>{d.enabled ? 'Switched on' : 'Switched off'}</dd></div>}
          </dl>
          {testNote && <p className="cf-ad-hint">{testNote}</p>}
          {kind && <p className="cf-ad-hint">A test never sends a customer document.</p>}
          {tested && <Notice tone={tested.tone === 'ok' ? 'neutral' : 'danger'}>{tested.text}</Notice>}
        </>}
        {error && <Notice tone="danger">{error}</Notice>}
      </div>
    </Sheet>
  );
}

