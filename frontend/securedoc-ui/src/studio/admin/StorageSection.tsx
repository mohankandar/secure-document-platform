import { useId, useState, type FormEvent } from 'react';
import useSWR from 'swr';
import { apiClient } from '../../api/client';
import type { CreateProcessingStorageProviderRequest, ProcessingStorageOverview, ProcessingStorageProvider } from '../../types';
import { errorMessage } from '../data';
import { Notice, Spinner } from '../atoms';
import { useToast } from '../toast';
import { ConfirmDialog, Loading, SelectField, TextField, when } from './shared';
import { latestChecks, storageKind, storageReadiness, storageStatus, storageUsedBy, storageValidated } from './platformRules';

type ProviderType = CreateProcessingStorageProviderRequest['providerType'];
type Pending = { kind: 'default' | 'retire'; provider: ProcessingStorageProvider } | null;

/** Admin › Platform operations › Processing storage (UI task U010, finding 26). Platform Operations only. */
export default function StorageSection() {
  const toast = useToast();
  const { data, error, mutate, isValidating } = useSWR<ProcessingStorageOverview>('platform-storage', () => apiClient.getProcessingStorageOverview(), { revalidateOnFocus: false });
  const [busy, setBusy] = useState('');
  const [adding, setAdding] = useState(false);
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

  const byId = (id?: string | null) => o.providers.find((p) => p.id === id);
  const tenantName = (id?: string | null) => o.tenants.find((t) => t.tenantId === id)?.name ?? 'One customer';
  const assignedIds = new Set([...(o.deploymentDefault ? [o.deploymentDefault.providerId] : []), ...o.tenantOverrides.map((a) => a.providerId)]);
  const usable = o.providers.filter((p) => p.status !== 'RETIRED');
  const readiness = storageReadiness(o);

  async function run(key: string, action: () => Promise<unknown>, success: string) {
    setBusy(key);
    try {
      await action();
      await mutate();
      toast(success);
      return true;
    } catch (e) {
      toast(errorMessage(e, "That didn't work. Nothing was changed."), 'danger');
      return false;
    } finally {
      setBusy('');
    }
  }

  async function create(e: FormEvent) {
    e.preventDefault();
    if (!name.trim() || !rootPath.trim()) { setFormError('Give the storage a name and a folder path.'); return; }
    setFormError('');
    setBusy('create');
    try {
      const created = await apiClient.createProcessingStorageProvider({ name: name.trim(), providerType, rootPath: rootPath.trim() });
      setName(''); setRootPath(''); setAdding(false);
      await mutate();
      toast(`${created.name} added. Next: test it from every server.`);
    } catch (err) {
      setFormError(errorMessage(err, "The storage couldn't be added."));
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
      toast(kind === 'default' ? `${provider.name} is now the default.` : `${provider.name} is no longer used for new work.`);
      setPending(null);
    } catch (e) {
      setPendingError(errorMessage(e, "That didn't work. Nothing was changed."));
    } finally {
      setBusy('');
    }
  }

  function applyOverride() {
    const provider = byId(overrideProviderId);
    void run(`tenant:${tenantId}`, () => apiClient.activateProcessingStorageTenant(overrideProviderId, tenantId), `${tenantName(tenantId)} now uses ${provider?.name ?? 'its own storage'}.`)
      .then((ok) => { if (ok) { setTenantId(''); setOverrideProviderId(''); } });
  }

  return (
    <>
      <section className="cf-ad-card" aria-labelledby="cf-st-head">
        <header className="cf-ad-card-head">
          <div>
            <h2 id="cf-st-head">Where LexaVault works on documents</h2>
            <p>Temporary space used while documents are redacted. Delivered copies go to SDV, not here.</p>
          </div>
          <div className="cf-ad-inline-actions">
            <span className="cf-ad-pill" data-tone={readiness.ready ? 'ok' : 'danger'}><span className="cf-ad-health" data-h={readiness.ready ? 'ok' : 'danger'} aria-hidden="true" />{readiness.title}</span>
            <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={!!busy || isValidating} onClick={() => mutate()}>Refresh</button>
          </div>
        </header>
        {!readiness.ready && <Notice tone="danger">{readiness.note}</Notice>}

        {o.providers.length === 0 ? (
          <p className="cf-ad-hint">No storage has been added yet.</p>
        ) : (
          <div className="cf-ad-table-wrap">
            <table className="cf-ad-table cf-ad-table-left cf-ad-storage">
              <thead><tr><th scope="col">Storage</th><th scope="col">Used by</th><th scope="col">Status</th><th scope="col"><span className="sr-only">Actions</span></th></tr></thead>
              <tbody>
                {o.providers.map((p) => {
                  const isDefault = o.deploymentDefault?.providerId === p.id;
                  const retired = p.status === 'RETIRED';
                  const full = storageValidated(o, p.id);
                  const s = storageStatus(p, o);
                  return (
                    <tr key={p.id}>
                      <th scope="row">
                        <span className="cf-ad-cell-title">{p.name}</span>
                        <span className="cf-ad-cell-sub cf-mono">{p.rootPath}</span>
                      </th>
                      <td>{storageUsedBy(p, o)}</td>
                      <td>
                        <span className="cf-ad-conn-status"><span className="cf-ad-health" data-h={s.tone} aria-hidden="true" /> <span data-tone={s.tone}>{s.word}</span>{s.rest.length > 0 && ` · ${s.rest.join(' · ')}`}</span>
                      </td>
                      <td>
                        {!retired && (
                          <div className="cf-ad-conn-actions">
                            <button type="button" className="cf-btn" data-size="sm" disabled={!!busy}
                              onClick={() => void run(`test:${p.id}`, () => apiClient.testProcessingStorageProvider(p.id), `${p.name} tested from this server. Other servers report within a few minutes.`)}>
                              {busy === `test:${p.id}` && <Spinner label="Testing" />}Test
                            </button>
                            {!isDefault && (
                              <button type="button" className="cf-btn" data-size="sm" disabled={!!busy || !full} title={full ? undefined : 'It must work from every server first'}
                                onClick={() => { setPendingError(null); setPending({ kind: 'default', provider: p }); }}>
                                Make default
                              </button>
                            )}
                            {!assignedIds.has(p.id) && (
                              <button type="button" className="cf-btn" data-size="sm" disabled={!!busy}
                                onClick={() => { setPendingError(null); setPending({ kind: 'retire', provider: p }); }}>
                                Stop using…
                              </button>
                            )}
                          </div>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}

        {adding ? (
          <form className="cf-ad-form cf-ad-block" onSubmit={create} noValidate aria-labelledby="cf-st-add">
            <h3 id="cf-st-add" className="cf-ad-block-title">Add storage</h3>
            <div className="cf-ad-grid">
              <TextField label="Name" value={name} onChange={setName} required maxLength={120} placeholder="Shared processing folder" invalid={!!formError && !name.trim()} />
              <SelectField<ProviderType> label="Kind" value={providerType} onChange={setProviderType} options={[['SHARED_FILESYSTEM', storageKind('SHARED_FILESYSTEM')], ['LOCAL_FILESYSTEM', storageKind('LOCAL_FILESYSTEM')]]} />
            </div>
            <TextField label="Folder path" value={rootPath} onChange={setRootPath} required mono invalid={!!formError && !rootPath.trim()}
              placeholder={providerType === 'SHARED_FILESYSTEM' ? '\\\\NAS01\\LexaVault\\Processing' : 'D:\\LexaVault\\Processing'}
              hint={<>The folder must already exist. LexaVault uses the server's own account and stores no network passwords. With more than one server, use a <strong>shared network folder</strong>.</>} />
            {formError && <Notice tone="danger">{formError}</Notice>}
            <div className="cf-ad-card-foot">
              <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={busy === 'create'} onClick={() => { setAdding(false); setFormError(''); }}>Cancel</button>
              <button type="submit" className="cf-btn" data-variant="solid" data-size="sm" disabled={!!busy}>{busy === 'create' && <Spinner label="Adding" />}Add storage</button>
            </div>
          </form>
        ) : (
          <div className="cf-ad-card-foot cf-ad-foot-left">
            <button type="button" className="cf-btn" data-size="sm" onClick={() => setAdding(true)}>Add storage</button>
            <span className="cf-ad-hint">Add → Test from every server → Make default, or give it to one customer.</span>
          </div>
        )}
      </section>

      <section className="cf-ad-card" aria-labelledby="cf-st-tenants">
        <header className="cf-ad-card-head">
          <div>
            <h2 id="cf-st-tenants">Give one customer its own storage</h2>
            <p>Everyone else keeps using the default. A change applies to new work only.</p>
          </div>
        </header>
        {o.tenants.length === 0 ? (
          <p className="cf-ad-hint">There are no customers yet.</p>
        ) : (
          <>
            <div className="cf-ad-toolbar cf-ad-assign">
              <div className="cf-field">
                <label htmlFor={tenantSelectId}><span>Customer</span></label>
                <select id={tenantSelectId} value={tenantId} onChange={(e) => setTenantId(e.target.value)}>
                  <option value="">Choose a customer</option>
                  {o.tenants.map((t) => <option key={t.tenantId} value={t.tenantId}>{t.name}</option>)}
                </select>
              </div>
              <div className="cf-field">
                <label htmlFor={providerSelectId}><span>Storage</span></label>
                <select id={providerSelectId} value={overrideProviderId} onChange={(e) => setOverrideProviderId(e.target.value)}>
                  <option value="">Choose a storage</option>
                  {usable.map((p) => { const ok = storageValidated(o, p.id); return <option key={p.id} value={p.id} disabled={!ok}>{p.name}{ok ? '' : ' (test it first)'}</option>; })}
                </select>
              </div>
              <button type="button" className="cf-btn" disabled={!!busy || !tenantId || !overrideProviderId || !storageValidated(o, overrideProviderId)} onClick={applyOverride}>
                {busy === `tenant:${tenantId}` && <Spinner label="Applying" />}Apply
              </button>
            </div>
            {o.tenantOverrides.length > 0 && (
              <ul className="cf-ad-conns" aria-label="Customers with their own storage">
                {o.tenantOverrides.map((a) => (
                  <li key={a.id} className="cf-ad-conn">
                    <div className="cf-ad-conn-main">
                      <div className="cf-ad-conn-name">{tenantName(a.tenantId)}</div>
                      <div className="cf-ad-conn-meta"><span>Uses {byId(a.providerId)?.name ?? 'a storage that no longer exists'}</span></div>
                    </div>
                    <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={!!busy}
                      onClick={() => void run(`clear:${a.tenantId}`, () => apiClient.clearProcessingStorageTenantOverride(a.tenantId ?? ''), `${tenantName(a.tenantId)} now uses the default.`)}>
                      Use the default
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </>
        )}

        <details className="cf-disclosure">
        <summary>Technical details</summary>
        <div className="cf-ad-stack">
          <dl className="cf-ad-facts cf-ad-facts-compact">
            <div><dt>Active nodes</dt><dd>{o.activeNodeCount}</dd></div>
            <div><dt>Ready</dt><dd className="cf-ad-mono">{String(o.ready)}</dd></div>
            <div><dt>Deployment default</dt><dd className="cf-ad-mono">{o.deploymentDefault ? `${o.deploymentDefault.id} → ${o.deploymentDefault.providerId}` : 'None'}</dd></div>
            {o.tenantOverrides.map((a) => <div key={a.id}><dt>Tenant override</dt><dd className="cf-ad-mono">{a.id} · {a.tenantId} → {a.providerId}</dd></div>)}
          </dl>
          <div className="cf-ad-table-wrap">
            <table className="cf-ad-table cf-ad-table-left">
              <thead><tr><th scope="col">Provider</th><th scope="col">Type · status · version</th><th scope="col">Node validations</th></tr></thead>
              <tbody>
                {o.providers.map((p) => (
                  <tr key={p.id}>
                    <th scope="row"><span className="cf-ad-cell-title">{p.name}</span><span className="cf-ad-cell-sub cf-ad-mono">{p.id}</span></th>
                    <td className="cf-ad-mono">{p.providerType} · {p.status} · v{p.configurationVersion}</td>
                    <td>
                      {latestChecks(o.nodeValidations, p.id).map((v) => (
                        <div key={`${v.nodeId}:${v.processInstanceId}`} className="cf-ad-mono">{v.nodeId} · {v.status}{v.failureCode ? ` · ${v.failureCode}` : ''} · {when(v.checkedAt)}</div>
                      ))}
                      {latestChecks(o.nodeValidations, p.id).length === 0 && '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
        </details>
      </section>

      <ConfirmDialog
        open={!!pending}
        title={pending?.kind === 'retire' ? `Stop using ${pending.provider.name}?` : `Make ${pending?.provider.name ?? ''} the default?`}
        facts={pending ? [['Storage', pending.provider.name], ['Kind', storageKind(pending.provider.providerType)], ['Folder', <code className="cf-ad-mono" key="p">{pending.provider.rootPath}</code>]] : []}
        consequence={pending?.kind === 'retire'
          ? 'New work will not be placed here, and it cannot be made the default or given to a customer again. Documents already being worked on here can still be read.'
          : 'New work for every customer without its own storage goes here. Documents already being worked on stay where they are.'}
        confirmLabel={pending?.kind === 'retire' ? 'Stop using it' : 'Make default'}
        danger={pending?.kind === 'retire'}
        busy={!!pending && busy === `${pending.kind}:${pending.provider.id}`}
        error={pendingError}
        onConfirm={() => void confirmPending()}
        onCancel={() => setPending(null)}
      />
    </>
  );
}
