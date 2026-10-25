import { useId, useState, type FormEvent } from 'react';
import useSWR from 'swr';
import { apiClient } from '../../api/client';
import type { CreateProcessingStorageProviderRequest, ProcessingStorageOverview, ProcessingStorageProvider } from '../../types';
import { errorMessage } from '../data';
import { Notice, Spinner } from '../atoms';
import { useToast } from '../toast';
import { ConfirmDialog, Loading, SelectField, TextField, humanize, when } from './shared';

type ProviderType = CreateProcessingStorageProviderRequest['providerType'];
type Pending = { kind: 'default' | 'retire'; provider: ProcessingStorageProvider } | null;

export default function StorageSection() {
  const toast = useToast();
  const { data, error, mutate, isValidating } = useSWR<ProcessingStorageOverview>('platform-storage', () => apiClient.getProcessingStorageOverview(), { revalidateOnFocus: false });
  const [busy, setBusy] = useState('');
  const [name, setName] = useState('');
  const [providerType, setProviderType] = useState<ProviderType>('SHARED_FILESYSTEM');
  const [rootPath, setRootPath] = useState('');
  const [formError, setFormError] = useState('');
  const [tenantId, setTenantId] = useState('');
  const [overrideProviderId, setOverrideProviderId] = useState('');
  const [pending, setPending] = useState<Pending>(null);
  const [pendingError, setPendingError] = useState<string | null>(null);
  const tenantSelectId = useId();
  const providerSelectId = useId();

  if (!data) return <Loading error={error} retry={() => mutate()} label="Processing storage" />;
  const o = data;

  const healthyNodes = (providerId: string) =>
    new Set(o.nodeValidations.filter((v) => v.providerId === providerId && v.status === 'HEALTHY').map((v) => `${v.nodeId}:${v.processInstanceId}`)).size;
  const validated = (providerId: string) => o.activeNodeCount >= 1 && healthyNodes(providerId) === o.activeNodeCount;
  const byId = (id?: string | null) => o.providers.find((p) => p.id === id);
  const defaultProvider = byId(o.deploymentDefault?.providerId);
  const assignedIds = new Set([...(o.deploymentDefault ? [o.deploymentDefault.providerId] : []), ...o.tenantOverrides.map((a) => a.providerId)]);
  const selectable = o.providers.filter((p) => p.status !== 'RETIRED');
  const localUnsafe = o.activeNodeCount > 1 && o.providers.some((p) => assignedIds.has(p.id) && p.providerType === 'LOCAL_FILESYSTEM');

  async function run(key: string, action: () => Promise<unknown>, success: string) {
    setBusy(key);
    try {
      await action();
      await mutate();
      toast(success);
      return true;
    } catch (e) {
      toast(errorMessage(e, 'Processing storage operation failed.'), 'danger');
      return false;
    } finally {
      setBusy('');
    }
  }

  async function create(e: FormEvent) {
    e.preventDefault();
    if (!name.trim() || !rootPath.trim()) { setFormError('Provider name and filesystem path are required.'); return; }
    setFormError('');
    setBusy('create');
    try {
      const created = await apiClient.createProcessingStorageProvider({ name: name.trim(), providerType, rootPath: rootPath.trim() });
      setName(''); setRootPath('');
      await mutate();
      toast(`${created.name} created as a candidate. Test it from every active node before activation.`);
    } catch (err) {
      setFormError(errorMessage(err, 'Unable to create processing storage provider.'));
    } finally {
      setBusy('');
    }
  }

  async function confirmPending() {
    if (!pending) return;
    const { kind, provider } = pending;
    setPendingError(null);
    setBusy(`${kind}:${provider.id}`);
    try {
      if (kind === 'default') await apiClient.activateProcessingStorageDefault(provider.id);
      else await apiClient.retireProcessingStorageProvider(provider.id);
      await mutate();
      toast(kind === 'default' ? `${provider.name} is now the deployment default.` : `${provider.name} retired.`);
      setPending(null);
    } catch (e) {
      setPendingError(errorMessage(e, 'Processing storage operation failed.'));
    } finally {
      setBusy('');
    }
  }

  function applyOverride() {
    const tenant = o.tenants.find((t) => t.tenantId === tenantId);
    const provider = byId(overrideProviderId);
    void run(`tenant:${tenantId}`, () => apiClient.activateProcessingStorageTenant(overrideProviderId, tenantId), `Tenant override activated: ${tenant?.name || tenantId} → ${provider?.name || overrideProviderId}.`)
      .then((ok) => { if (ok) { setTenantId(''); setOverrideProviderId(''); } });
  }

  return (
    <>
      <section className="cf-ad-readiness" data-ready={o.ready || undefined} aria-labelledby="cf-st-ready">
        <div>
          <span className="cf-eyebrow">Platform readiness</span>
          <h2 id="cf-st-ready">{o.ready ? 'Processing storage is ready' : 'Processing storage requires attention'}</h2>
          <p>
            {o.ready
              ? 'New processing cycles can use the active deployment or tenant provider. Final redacted delivery remains configured separately.'
              : localUnsafe
                ? 'Multiple application nodes are active, so LOCAL_FILESYSTEM is not cluster-safe. Activate a validated SHARED_FILESYSTEM provider before document intake or processing can continue.'
                : 'Document processing stays fail-closed until a deployment default exists and validates healthy on every active application node.'}
          </p>
        </div>
        <div className="cf-ad-readiness-meta">
          <span className="cf-ad-tag" data-tone={o.ready ? undefined : 'danger'}>{o.ready ? 'Ready' : 'Not ready'}</span>
          <span>{o.activeNodeCount} active node{o.activeNodeCount === 1 ? '' : 's'}</span>
          <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={!!busy || isValidating} onClick={() => mutate()}>Refresh</button>
        </div>
      </section>

      <section className="cf-ad-card" aria-labelledby="cf-st-default">
        <header className="cf-ad-card-head">
          <div>
            <h2 id="cf-st-default">Deployment default</h2>
            <p>Used when a tenant has no dedicated override. A provider switch applies to new processing cycles only.</p>
          </div>
        </header>
        {defaultProvider ? (
          <div className="cf-ad-conn">
            <span className="cf-ad-health" data-h={validated(defaultProvider.id) ? 'ok' : 'danger'} aria-hidden="true" />
            <div className="cf-ad-conn-main">
              <div className="cf-ad-conn-name">{defaultProvider.name}<span className="cf-ad-tag">{humanize(defaultProvider.status)}</span></div>
              <div className="cf-ad-conn-meta"><span>{humanize(defaultProvider.providerType)}</span><span>Configuration v{defaultProvider.configurationVersion}</span></div>
              <code className="cf-ad-conn-endpoint cf-ad-mono">{defaultProvider.rootPath}</code>
            </div>
            <span className="cf-ad-status">{healthyNodes(defaultProvider.id)} / {o.activeNodeCount} nodes healthy</span>
          </div>
        ) : (
          <Notice tone="mark"><strong>Configuration required.</strong> Create a provider, test it from all active nodes, then activate it as the deployment default.</Notice>
        )}
      </section>

      <section className="cf-ad-card" aria-labelledby="cf-st-create">
        <header className="cf-ad-card-head">
          <div>
            <h2 id="cf-st-create">Create processing storage</h2>
            <p>The filesystem root must already exist. The platform uses the operating-system/service identity and does not store SMB/NFS passwords.</p>
          </div>
        </header>
        <form className="cf-ad-form" onSubmit={create} noValidate>
          <div className="cf-ad-grid">
            <TextField label="Name" value={name} onChange={setName} required maxLength={120} placeholder="Production Processing Storage" invalid={!!formError && !name.trim()} />
            <SelectField<ProviderType> label="Type" value={providerType} onChange={setProviderType} options={[['SHARED_FILESYSTEM', 'Shared filesystem'], ['LOCAL_FILESYSTEM', 'Local filesystem']]} />
          </div>
          <TextField label="Filesystem path" value={rootPath} onChange={setRootPath} required mono invalid={!!formError && !rootPath.trim()}
            placeholder={providerType === 'SHARED_FILESYSTEM' ? '\\\\NAS01\\DocumentPlatform\\Processing or /mnt/document-platform/processing' : 'C:\\ProgramData\\DocumentPlatform\\Processing'}
            hint={<>For multi-node deployments use <strong>Shared filesystem</strong>. Local filesystem cannot be activated while more than one application node is active.</>} />
          {formError && <Notice tone="danger">{formError}</Notice>}
          <div className="cf-ad-card-foot">
            <button type="submit" className="cf-btn" data-variant="solid" data-size="sm" disabled={!!busy}>{busy === 'create' && <Spinner label="Creating" />}Create candidate</button>
          </div>
        </form>
      </section>

      <section className="cf-ad-card" aria-labelledby="cf-st-providers">
        <header className="cf-ad-card-head">
          <div>
            <h2 id="cf-st-providers">Providers and cluster validation <span className="cf-ad-tag">{o.providers.length}</span></h2>
            <p>Test connection probes read/write/delete access from the current node immediately; other active nodes report on their normal monitor interval.</p>
          </div>
        </header>
        {o.providers.length === 0 ? (
          <p className="cf-ad-hint">No processing-storage providers have been created.</p>
        ) : (
          <ul className="cf-ad-conns">
            {o.providers.map((p) => {
              const isDefault = o.deploymentDefault?.providerId === p.id;
              const retired = p.status === 'RETIRED';
              const full = validated(p.id);
              const nodes = o.nodeValidations.filter((v) => v.providerId === p.id);
              return (
                <li key={p.id} className="cf-ad-conn cf-ad-conn-top">
                  <span className="cf-ad-health" data-h={retired ? undefined : full ? 'ok' : 'warn'} aria-hidden="true" />
                  <div className="cf-ad-conn-main">
                    <div className="cf-ad-conn-name">
                      {p.name}
                      <span className="cf-ad-tag">{humanize(p.status)}</span>
                      {isDefault && <span className="cf-ad-tag">Deployment default</span>}
                    </div>
                    <div className="cf-ad-conn-meta"><span>{humanize(p.providerType)} · v{p.configurationVersion}</span><span>{healthyNodes(p.id)} / {o.activeNodeCount} active nodes healthy</span></div>
                    <code className="cf-ad-conn-endpoint cf-ad-mono">{p.rootPath}</code>
                    {o.activeNodeCount < 1 && <span className="cf-ad-hint">No active application heartbeat detected.</span>}
                    {nodes.length > 0 && (
                      <ul className="cf-ad-nodes" aria-label={`${p.name} node validations`}>
                        {nodes.map((v) => (
                          <li key={`${v.nodeId}:${v.processInstanceId}`}>
                            <span className="cf-ad-mono">{v.nodeId}</span>
                            <span className={v.status === 'HEALTHY' ? '' : 'cf-danger-text'}>{humanize(v.status)}{v.failureCode ? ` · ${humanize(v.failureCode)}` : ''}</span>
                            <time dateTime={v.checkedAt}>{when(v.checkedAt)}</time>
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                  {!retired && (
                    <div className="cf-ad-conn-actions">
                      <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={!!busy}
                        onClick={() => void run(`test:${p.id}`, () => apiClient.testProcessingStorageProvider(p.id), `${p.name} validation requested.`)}>
                        {busy === `test:${p.id}` && <Spinner label="Testing" />}Test connection
                      </button>
                      {!isDefault && (
                        <button type="button" className="cf-btn" data-size="sm" disabled={!!busy || !full} title={full ? undefined : 'Every active node must validate healthy first'}
                          onClick={() => { setPendingError(null); setPending({ kind: 'default', provider: p }); }}>
                          Activate default
                        </button>
                      )}
                      {!assignedIds.has(p.id) && (
                        <button type="button" className="cf-btn" data-variant="danger" data-size="sm" disabled={!!busy}
                          onClick={() => { setPendingError(null); setPending({ kind: 'retire', provider: p }); }}>
                          Retire
                        </button>
                      )}
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        )}
      </section>

      <section className="cf-ad-card" aria-labelledby="cf-st-tenants">
        <header className="cf-ad-card-head">
          <div>
            <h2 id="cf-st-tenants">Tenant overrides</h2>
            <p>Tenants inherit the deployment default unless Platform Operations assigns a dedicated validated provider.</p>
          </div>
        </header>
        <div className="cf-ad-toolbar">
          <div className="cf-field">
            <label htmlFor={tenantSelectId}><span>Tenant</span></label>
            <select id={tenantSelectId} value={tenantId} onChange={(e) => setTenantId(e.target.value)}>
              <option value="">Select tenant</option>
              {o.tenants.map((t) => <option key={t.tenantId} value={t.tenantId}>{t.name} ({t.slug})</option>)}
            </select>
          </div>
          <div className="cf-field">
            <label htmlFor={providerSelectId}><span>Provider</span></label>
            <select id={providerSelectId} value={overrideProviderId} onChange={(e) => setOverrideProviderId(e.target.value)}>
              <option value="">Select validated provider</option>
              {selectable.map((p) => <option key={p.id} value={p.id} disabled={!validated(p.id)}>{p.name}{validated(p.id) ? '' : ' (validation required)'}</option>)}
            </select>
          </div>
          <button type="button" className="cf-btn" data-size="sm" disabled={!!busy || !tenantId || !overrideProviderId || !validated(overrideProviderId)} onClick={applyOverride}>
            {busy === `tenant:${tenantId}` && <Spinner label="Applying" />}Apply override
          </button>
        </div>
        {o.tenants.length === 0 ? (
          <p className="cf-ad-hint">No provisioned tenants are available for storage assignment.</p>
        ) : (
          <div className="cf-ad-table-wrap">
            <table className="cf-ad-table cf-ad-table-left">
              <thead><tr><th scope="col">Tenant</th><th scope="col">Effective provider</th><th scope="col">Source</th><th scope="col"><span className="sr-only">Actions</span></th></tr></thead>
              <tbody>
                {o.tenants.map((t) => {
                  const override = o.tenantOverrides.find((a) => a.tenantId === t.tenantId);
                  const effective = byId(override?.providerId || o.deploymentDefault?.providerId);
                  return (
                    <tr key={t.tenantId}>
                      <th scope="row"><span className="cf-ad-cell-title">{t.name}</span><span className="cf-ad-cell-sub">{t.slug} · {humanize(t.status)}</span></th>
                      <td>{effective?.name || 'Not configured'}</td>
                      <td>{override ? 'Tenant override' : 'Deployment default'}</td>
                      <td>
                        {override && (
                          <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={!!busy}
                            onClick={() => void run(`clear:${t.tenantId}`, () => apiClient.clearProcessingStorageTenantOverride(t.tenantId), `${t.name} now inherits the deployment default.`)}>
                            Use default
                          </button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <ConfirmDialog
        open={!!pending}
        title={pending?.kind === 'retire' ? 'Retire storage provider' : 'Activate deployment default'}
        facts={pending ? [['Provider', pending.provider.name], ['Type', humanize(pending.provider.providerType)], ['Path', <code className="cf-ad-mono" key="p">{pending.provider.rootPath}</code>], ['Configuration', `v${pending.provider.configurationVersion}`]] : []}
        consequence={pending?.kind === 'retire'
          ? 'It remains readable for existing artifacts but cannot receive new assignments.'
          : 'New processing cycles will use this provider. Existing artifacts remain tied to their original provider.'}
        confirmLabel={pending?.kind === 'retire' ? 'Retire provider' : 'Activate default'}
        danger={pending?.kind === 'retire'}
        busy={!!pending && busy === `${pending.kind}:${pending.provider.id}`}
        error={pendingError}
        onConfirm={() => void confirmPending()}
        onCancel={() => setPending(null)}
      />
    </>
  );
}
