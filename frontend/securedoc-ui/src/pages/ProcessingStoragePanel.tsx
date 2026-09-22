import { FormEvent, useCallback, useEffect, useMemo, useState } from 'react';
import { apiClient } from '../api/client';
import type {
  CreateProcessingStorageProviderRequest,
  ProcessingStorageOverview,
  ProcessingStorageProvider,
  ProcessingStorageTenant,
} from '../types';

function messageFrom(error: unknown, fallback: string) {
  const maybe = error as { message?: string; errorCode?: string };
  return maybe?.message || maybe?.errorCode || fallback;
}

function displayStatus(value: string) {
  return value.replace(/_/g, ' ');
}

function providerFor(overview: ProcessingStorageOverview, providerId?: string | null) {
  return overview.providers.find((provider) => provider.id === providerId);
}

export default function ProcessingStoragePanel() {
  const [overview, setOverview] = useState<ProcessingStorageOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [busyKey, setBusyKey] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [name, setName] = useState('');
  const [providerType, setProviderType] = useState<CreateProcessingStorageProviderRequest['providerType']>('SHARED_FILESYSTEM');
  const [rootPath, setRootPath] = useState('');
  const [overrideTenantId, setOverrideTenantId] = useState('');
  const [overrideProviderId, setOverrideProviderId] = useState('');

  const load = useCallback(async () => {
    try {
      setError('');
      setOverview(await apiClient.getProcessingStorageOverview());
    } catch (err) {
      setError(messageFrom(err, 'Unable to load processing storage configuration'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const run = async (key: string, action: () => Promise<unknown>, success: string) => {
    setBusyKey(key);
    setError('');
    setNotice('');
    try {
      await action();
      setNotice(success);
      await load();
    } catch (err) {
      setError(messageFrom(err, 'Processing storage operation failed'));
    } finally {
      setBusyKey('');
    }
  };

  const validationsByProvider = useMemo(() => {
    const grouped = new Map<string, NonNullable<ProcessingStorageOverview['nodeValidations']>>();
    overview?.nodeValidations.forEach((validation) => {
      const rows = grouped.get(validation.providerId) || [];
      rows.push(validation);
      grouped.set(validation.providerId, rows);
    });
    return grouped;
  }, [overview]);

  const healthyNodeCount = (providerId: string) => {
    const unique = new Set(
      (validationsByProvider.get(providerId) || [])
        .filter((validation) => validation.status === 'HEALTHY')
        .map((validation) => `${validation.nodeId}:${validation.processInstanceId}`),
    );
    return unique.size;
  };

  const providerValidated = (providerId: string) => {
    if (!overview || overview.activeNodeCount < 1) return false;
    return healthyNodeCount(providerId) === overview.activeNodeCount;
  };

  const createProvider = async (event: FormEvent) => {
    event.preventDefault();
    if (!name.trim() || !rootPath.trim()) {
      setError('Provider name and filesystem path are required.');
      return;
    }
    setBusyKey('create');
    setError('');
    setNotice('');
    try {
      const created = await apiClient.createProcessingStorageProvider({
        name: name.trim(),
        providerType,
        rootPath: rootPath.trim(),
      });
      setName('');
      setRootPath('');
      setNotice(`${created.name} created as a candidate. Test it from every active node before activation.`);
      await load();
    } catch (err) {
      setError(messageFrom(err, 'Unable to create processing storage provider'));
    } finally {
      setBusyKey('');
    }
  };

  const activateDefault = (provider: ProcessingStorageProvider) => {
    if (!window.confirm(`Activate "${provider.name}" as the deployment default for new processing cycles? Existing artifacts remain tied to their original provider.`)) return;
    void run(`default:${provider.id}`, () => apiClient.activateProcessingStorageDefault(provider.id), `${provider.name} is now the deployment default.`);
  };

  const retireProvider = (provider: ProcessingStorageProvider) => {
    if (!window.confirm(`Retire "${provider.name}"? It will remain readable for existing artifacts but cannot receive new assignments.`)) return;
    void run(`retire:${provider.id}`, () => apiClient.retireProcessingStorageProvider(provider.id), `${provider.name} retired.`);
  };

  const applyTenantOverride = () => {
    if (!overrideTenantId || !overrideProviderId) {
      setError('Select both a tenant and a validated provider.');
      return;
    }
    const tenant = overview?.tenants.find((item) => item.tenantId === overrideTenantId);
    const provider = overview?.providers.find((item) => item.id === overrideProviderId);
    const label = `${tenant?.name || overrideTenantId} → ${provider?.name || overrideProviderId}`;
    void run(`tenant:${overrideTenantId}`, () => apiClient.activateProcessingStorageTenant(overrideProviderId, overrideTenantId), `Tenant override activated: ${label}.`);
  };

  if (loading) return <div className="platform-section"><p>Loading processing storage…</p></div>;
  if (!overview) return <div className="platform-error">{error || 'Processing storage is unavailable.'}</div>;

  const defaultProvider = providerFor(overview, overview.deploymentDefault?.providerId);
  const activeProviderIds = new Set([
    ...(overview.deploymentDefault ? [overview.deploymentDefault.providerId] : []),
    ...overview.tenantOverrides.map((assignment) => assignment.providerId),
  ]);
  const selectableProviders = overview.providers.filter((provider) => provider.status !== 'RETIRED');
  const localAssignmentUnsafe = overview.activeNodeCount > 1 && overview.providers.some((provider) => activeProviderIds.has(provider.id) && provider.providerType === 'LOCAL_FILESYSTEM');

  return (
    <div className="processing-storage-page">
      {error && <div className="platform-error">{error}</div>}
      {notice && <div className="platform-success">{notice}</div>}

      <section className={`processing-storage-readiness ${overview.ready ? 'is-ready' : 'needs-config'}`}>
        <div>
          <div className="platform-eyebrow">Platform readiness</div>
          <h2>{overview.ready ? 'Processing storage is ready' : 'Processing storage requires attention'}</h2>
          <p>
            {overview.ready
              ? 'New processing cycles can use the active deployment or tenant provider. Final redacted delivery remains configured separately.'
              : localAssignmentUnsafe
                ? 'Multiple application nodes are active, so LOCAL_FILESYSTEM is not cluster-safe. Activate a validated SHARED_FILESYSTEM provider before document intake or processing can continue.'
                : 'Document processing must remain fail-closed until a deployment default exists and validates healthy on every active application node.'}
          </p>
        </div>
        <div className="processing-storage-readiness-meta">
          <span className={overview.ready ? 'platform-status platform-status-healthy' : 'platform-status platform-status-unhealthy'}>
            {overview.ready ? 'READY' : 'NOT READY'}
          </span>
          <strong>{overview.activeNodeCount} active node{overview.activeNodeCount === 1 ? '' : 's'}</strong>
          <button className="secondary" disabled={busyKey !== ''} onClick={() => void load()}>Refresh</button>
        </div>
      </section>

      <section className="platform-section">
        <div className="platform-section-heading">
          <div>
            <h2>Deployment default</h2>
            <p>Used when a tenant does not have a dedicated override. A provider switch applies to new processing cycles only.</p>
          </div>
        </div>
        {defaultProvider ? (
          <div className="processing-storage-active-card">
            <div>
              <strong>{defaultProvider.name}</strong>
              <span>{displayStatus(defaultProvider.providerType)} · configuration v{defaultProvider.configurationVersion}</span>
              <code>{defaultProvider.rootPath}</code>
            </div>
            <div className="processing-storage-validation-summary">
              <span className="platform-status platform-status-healthy">{defaultProvider.status}</span>
              <strong>{healthyNodeCount(defaultProvider.id)} / {overview.activeNodeCount} nodes healthy</strong>
            </div>
          </div>
        ) : (
          <div className="platform-empty processing-storage-setup-required">
            <strong>Configuration required</strong>
            <span>Create a provider, test it from all active nodes, then activate it as the deployment default.</span>
          </div>
        )}
      </section>

      <section className="platform-section">
        <div className="platform-section-heading">
          <div>
            <h2>Create processing storage</h2>
            <p>The filesystem root must already exist. The platform uses the operating-system/service identity and does not store SMB/NFS passwords.</p>
          </div>
        </div>
        <form className="processing-storage-form" onSubmit={(event) => void createProvider(event)}>
          <label>
            <span>Name</span>
            <input value={name} onChange={(event) => setName(event.target.value)} placeholder="Production Processing Storage" maxLength={120} />
          </label>
          <label>
            <span>Type</span>
            <select value={providerType} onChange={(event) => setProviderType(event.target.value as CreateProcessingStorageProviderRequest['providerType'])}>
              <option value="SHARED_FILESYSTEM">Shared filesystem</option>
              <option value="LOCAL_FILESYSTEM">Local filesystem</option>
            </select>
          </label>
          <label className="processing-storage-path-field">
            <span>Filesystem path</span>
            <input value={rootPath} onChange={(event) => setRootPath(event.target.value)} placeholder={providerType === 'SHARED_FILESYSTEM' ? '\\\\NAS01\\DocumentPlatform\\Processing or /mnt/document-platform/processing' : 'C:\\ProgramData\\DocumentPlatform\\Processing'} />
          </label>
          <button className="primary" type="submit" disabled={busyKey !== ''}>{busyKey === 'create' ? 'Creating…' : 'Create candidate'}</button>
        </form>
        <p className="platform-boundary-note">For multi-node deployments, use <strong>SHARED_FILESYSTEM</strong>. LOCAL_FILESYSTEM cannot be activated while more than one application node is active.</p>
      </section>

      <section className="platform-section">
        <div className="platform-section-heading">
          <div>
            <h2>Providers & cluster validation</h2>
            <p>Test Connection probes read/write/delete access from the current node immediately; other active nodes report on their normal monitor interval.</p>
          </div>
          <span className="platform-count">{overview.providers.length}</span>
        </div>
        {overview.providers.length === 0 ? (
          <div className="platform-empty">No processing-storage providers have been created.</div>
        ) : (
          <div className="processing-storage-provider-list">
            {overview.providers.map((provider) => {
              const validations = validationsByProvider.get(provider.id) || [];
              const healthy = healthyNodeCount(provider.id);
              const isDefault = overview.deploymentDefault?.providerId === provider.id;
              const assigned = activeProviderIds.has(provider.id);
              const fullyValidated = providerValidated(provider.id);
              return (
                <article className="processing-storage-provider-card" key={provider.id}>
                  <div className="processing-storage-provider-header">
                    <div>
                      <div className="processing-storage-provider-title">
                        <h3>{provider.name}</h3>
                        {isDefault && <span className="processing-storage-chip">Deployment default</span>}
                      </div>
                      <div className="processing-storage-provider-subtitle">{displayStatus(provider.providerType)} · v{provider.configurationVersion}</div>
                    </div>
                    <span className={`platform-status platform-status-${provider.status.toLowerCase()}`}>{displayStatus(provider.status)}</span>
                  </div>
                  <code className="processing-storage-root">{provider.rootPath}</code>
                  <div className="processing-storage-provider-health">
                    <strong>{healthy} / {overview.activeNodeCount} active nodes healthy</strong>
                    {overview.activeNodeCount < 1 && <span>No active application heartbeat detected.</span>}
                    {validations.map((validation) => (
                      <div className="processing-storage-node-row" key={`${validation.nodeId}:${validation.processInstanceId}`}>
                        <span>{validation.nodeId}</span>
                        <span className={validation.status === 'HEALTHY' ? 'processing-storage-node-healthy' : 'processing-storage-node-unhealthy'}>
                          {displayStatus(validation.status)}{validation.failureCode ? ` · ${displayStatus(validation.failureCode)}` : ''}
                        </span>
                        <time>{new Date(validation.checkedAt).toLocaleTimeString()}</time>
                      </div>
                    ))}
                  </div>
                  <div className="processing-storage-provider-actions">
                    {provider.status !== 'RETIRED' && (
                      <button className="secondary" disabled={busyKey !== ''} onClick={() => void run(`test:${provider.id}`, () => apiClient.testProcessingStorageProvider(provider.id), `${provider.name} validation requested.`)}>
                        {busyKey === `test:${provider.id}` ? 'Testing…' : 'Test connection'}
                      </button>
                    )}
                    {!isDefault && provider.status !== 'RETIRED' && (
                      <button className="primary" disabled={busyKey !== '' || !fullyValidated} title={!fullyValidated ? 'Every active node must validate healthy first' : undefined} onClick={() => activateDefault(provider)}>
                        {busyKey === `default:${provider.id}` ? 'Activating…' : 'Activate default'}
                      </button>
                    )}
                    {!assigned && provider.status !== 'RETIRED' && (
                      <button className="danger-outline" disabled={busyKey !== ''} onClick={() => retireProvider(provider)}>
                        {busyKey === `retire:${provider.id}` ? 'Retiring…' : 'Retire'}
                      </button>
                    )}
                  </div>
                </article>
              );
            })}
          </div>
        )}
      </section>

      <section className="platform-section">
        <div className="platform-section-heading">
          <div>
            <h2>Tenant overrides</h2>
            <p>Tenants inherit the deployment default unless Platform Operations assigns a dedicated validated provider.</p>
          </div>
        </div>
        <div className="processing-storage-override-form">
          <label>
            <span>Tenant</span>
            <select value={overrideTenantId} onChange={(event) => setOverrideTenantId(event.target.value)}>
              <option value="">Select tenant</option>
              {overview.tenants.map((tenant) => <option key={tenant.tenantId} value={tenant.tenantId}>{tenant.name} ({tenant.slug})</option>)}
            </select>
          </label>
          <label>
            <span>Provider</span>
            <select value={overrideProviderId} onChange={(event) => setOverrideProviderId(event.target.value)}>
              <option value="">Select validated provider</option>
              {selectableProviders.map((provider) => (
                <option key={provider.id} value={provider.id} disabled={!providerValidated(provider.id)}>
                  {provider.name}{providerValidated(provider.id) ? '' : ' — validation required'}
                </option>
              ))}
            </select>
          </label>
          <button className="primary" disabled={busyKey !== '' || !overrideTenantId || !overrideProviderId || !providerValidated(overrideProviderId)} onClick={applyTenantOverride}>Apply override</button>
        </div>

        {overview.tenants.length === 0 ? (
          <div className="platform-empty">No provisioned tenants are available for storage assignment.</div>
        ) : (
          <div className="processing-storage-tenant-table">
            <div className="processing-storage-tenant-row processing-storage-tenant-header"><span>Tenant</span><span>Effective provider</span><span>Source</span><span /></div>
            {overview.tenants.map((tenant: ProcessingStorageTenant) => {
              const override = overview.tenantOverrides.find((assignment) => assignment.tenantId === tenant.tenantId);
              const effective = providerFor(overview, override?.providerId || overview.deploymentDefault?.providerId);
              return (
                <div className="processing-storage-tenant-row" key={tenant.tenantId}>
                  <span><strong>{tenant.name}</strong><small>{tenant.slug} · {displayStatus(tenant.status)}</small></span>
                  <span>{effective?.name || 'Not configured'}</span>
                  <span>{override ? 'Tenant override' : 'Deployment default'}</span>
                  <span>{override && <button className="secondary small" disabled={busyKey !== ''} onClick={() => void run(`clear:${tenant.tenantId}`, () => apiClient.clearProcessingStorageTenantOverride(tenant.tenantId), `${tenant.name} now inherits the deployment default.`)}>Use default</button>}</span>
                </div>
              );
            })}
          </div>
        )}
      </section>
    </div>
  );
}
