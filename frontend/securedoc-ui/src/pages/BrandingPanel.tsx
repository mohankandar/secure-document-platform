import { useCallback, useEffect, useMemo, useState, type CSSProperties } from 'react';
import { apiClient } from '../api/client';
import type { BrandingConfiguration, BrandingOverview, BrandingUpdateRequest } from '../types';
import { useBranding } from '../branding/BrandingContext';
import Icon from '../components/ui/Icon';

type EditableBranding = {
  applicationName: string;
  shortName: string;
  organizationName: string;
  pageTitle: string;
  accentColor: string;
  secondaryColor: string;
  primaryLogoDataUrl: string | null;
  compactLogoDataUrl: string | null;
  faviconDataUrl: string | null;
  primaryLogoChanged: boolean;
  compactLogoChanged: boolean;
  faviconChanged: boolean;
  primaryLogoClear: boolean;
  compactLogoClear: boolean;
  faviconClear: boolean;
};

const FALLBACK = { applicationName: 'LexaVault', shortName: 'LexaVault', organizationName: '', pageTitle: 'LexaVault', accentColor: '#315CF4', secondaryColor: '#6D5AE6' };

function fromConfig(config?: BrandingConfiguration | null): EditableBranding {
  return {
    applicationName: config?.applicationName || '',
    shortName: config?.shortName || '',
    organizationName: config?.organizationName || '',
    pageTitle: config?.pageTitle || '',
    accentColor: config?.accentColor || '',
    secondaryColor: config?.secondaryColor || '',
    primaryLogoDataUrl: config?.primaryLogoDataUrl || null,
    compactLogoDataUrl: config?.compactLogoDataUrl || null,
    faviconDataUrl: config?.faviconDataUrl || null,
    primaryLogoChanged: false, compactLogoChanged: false, faviconChanged: false,
    primaryLogoClear: false, compactLogoClear: false, faviconClear: false,
  };
}

function toRequest(value: EditableBranding): BrandingUpdateRequest {
  return {
    applicationName: value.applicationName || null,
    shortName: value.shortName || null,
    organizationName: value.organizationName || null,
    pageTitle: value.pageTitle || null,
    accentColor: value.accentColor || null,
    secondaryColor: value.secondaryColor || null,
    primaryLogo: value.primaryLogoChanged ? { dataUrl: value.primaryLogoDataUrl, clear: value.primaryLogoClear } : null,
    compactLogo: value.compactLogoChanged ? { dataUrl: value.compactLogoDataUrl, clear: value.compactLogoClear } : null,
    favicon: value.faviconChanged ? { dataUrl: value.faviconDataUrl, clear: value.faviconClear } : null,
  };
}

function luminance(hex: string) {
  if (!/^#[0-9a-fA-F]{6}$/.test(hex)) return null;
  const values = [hex.slice(1, 3), hex.slice(3, 5), hex.slice(5, 7)].map((v) => parseInt(v, 16) / 255).map((v) => v <= .03928 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4));
  return values[0] * .2126 + values[1] * .7152 + values[2] * .0722;
}

function contrast(hex: string, white = true) {
  const l = luminance(hex); if (l == null) return 0;
  const other = white ? 1 : 0;
  return (Math.max(l, other) + .05) / (Math.min(l, other) + .05);
}

function accessibleAccent(hex: string) {
  return /^#[0-9a-fA-F]{6}$/.test(hex) && contrast(hex, true) >= 4.5;
}

async function readImage(file: File, maxBytes: number): Promise<string> {
  if (!['image/png', 'image/webp'].includes(file.type)) throw new Error('Use a PNG or WebP image.');
  if (file.size > maxBytes) throw new Error(`Image must be smaller than ${Math.round(maxBytes / 1024)} KB.`);
  return await new Promise((resolve, reject) => {
    const reader = new FileReader(); reader.onload = () => resolve(String(reader.result)); reader.onerror = () => reject(new Error('Unable to read image.')); reader.readAsDataURL(file);
  });
}

function AssetField({ label, help, value, onChange, maxBytes }: { label: string; help: string; value: string | null; onChange: (dataUrl: string | null, clear: boolean) => void; maxBytes: number }) {
  const [error, setError] = useState('');
  return (
    <div className="branding-asset-field">
      <div className="branding-asset-preview">{value ? <img src={value} alt="" /> : <span>No image</span>}</div>
      <div className="branding-asset-copy"><strong>{label}</strong><span>{help}</span>
        <div className="branding-asset-actions">
          <label className="ui-button secondary small">Choose image<input type="file" accept="image/png,image/webp" hidden onChange={async (event) => {
            const input = event.currentTarget; const file = input.files?.[0]; if (!file) return; setError('');
            try { onChange(await readImage(file, maxBytes), false); } catch (e) { setError((e as Error).message); }
            input.value = '';
          }} /></label>
          {value && <button className="ui-button tertiary small" type="button" onClick={() => onChange(null, true)}>Remove</button>}
        </div>
        {error && <small className="field-error">{error}</small>}
      </div>
    </div>
  );
}

function BrandingForm({ value, onChange, tenantMode = false }: { value: EditableBranding; onChange: (value: EditableBranding) => void; tenantMode?: boolean }) {
  const accent = value.accentColor || (tenantMode ? '#315CF4' : FALLBACK.accentColor);
  const accentOk = !value.accentColor || accessibleAccent(value.accentColor);
  return (
    <div className="branding-form">
      {tenantMode && <div className="ui-callout neutral">Leave a field blank to inherit the deployment brand.</div>}
      <div className="branding-form-grid">
        <label className="ui-field"><span>Application name</span><input value={value.applicationName} maxLength={80} placeholder={tenantMode ? 'Inherit deployment name' : FALLBACK.applicationName} onChange={(e) => onChange({ ...value, applicationName: e.target.value })} /></label>
        <label className="ui-field"><span>Short name</span><input value={value.shortName} maxLength={32} placeholder={tenantMode ? 'Inherit' : FALLBACK.shortName} onChange={(e) => onChange({ ...value, shortName: e.target.value })} /></label>
        <label className="ui-field"><span>Organization name</span><input value={value.organizationName} maxLength={100} placeholder={tenantMode ? 'Inherit' : 'Customer organization'} onChange={(e) => onChange({ ...value, organizationName: e.target.value })} /></label>
        <label className="ui-field"><span>Browser/page title</span><input value={value.pageTitle} maxLength={100} placeholder={tenantMode ? 'Inherit' : FALLBACK.pageTitle} onChange={(e) => onChange({ ...value, pageTitle: e.target.value })} /></label>
      </div>
      <div className="branding-color-grid">
        <label className="ui-field color-field"><span>Primary accent</span><div className="color-input-row"><input type="color" value={accent} onChange={(e) => onChange({ ...value, accentColor: e.target.value.toUpperCase() })} /><input value={value.accentColor} placeholder={tenantMode ? 'Inherit' : FALLBACK.accentColor} onChange={(e) => onChange({ ...value, accentColor: e.target.value.toUpperCase() })} /></div><small className={accentOk ? 'field-success' : 'field-error'}>{accentOk ? 'Accessible contrast can be generated for UI surfaces.' : 'Choose a color with sufficient contrast.'}</small></label>
        <label className="ui-field color-field"><span>Secondary brand color</span><div className="color-input-row"><input type="color" value={value.secondaryColor || FALLBACK.secondaryColor} onChange={(e) => onChange({ ...value, secondaryColor: e.target.value.toUpperCase() })} /><input value={value.secondaryColor} placeholder={tenantMode ? 'Inherit' : FALLBACK.secondaryColor} onChange={(e) => onChange({ ...value, secondaryColor: e.target.value.toUpperCase() })} /></div><small>Used sparingly for branded accents, never for semantic success/warning/error.</small></label>
      </div>
      <div className="branding-assets-grid">
        <AssetField label="Primary logo" help="Navigation and wider layouts. PNG or WebP, max 512 KB." value={value.primaryLogoDataUrl} maxBytes={512 * 1024} onChange={(dataUrl, clear) => onChange({ ...value, primaryLogoDataUrl: dataUrl, primaryLogoChanged: true, primaryLogoClear: clear })} />
        <AssetField label="Compact logo" help="Collapsed sidebar and tablet shell. PNG or WebP, max 512 KB." value={value.compactLogoDataUrl} maxBytes={512 * 1024} onChange={(dataUrl, clear) => onChange({ ...value, compactLogoDataUrl: dataUrl, compactLogoChanged: true, compactLogoClear: clear })} />
        <AssetField label="Favicon" help="Browser icon. PNG or WebP, max 128 KB." value={value.faviconDataUrl} maxBytes={128 * 1024} onChange={(dataUrl, clear) => onChange({ ...value, faviconDataUrl: dataUrl, faviconChanged: true, faviconClear: clear })} />
      </div>
    </div>
  );
}

function Preview({ value, inherited }: { value: EditableBranding; inherited?: BrandingConfiguration | null }) {
  const appName = value.applicationName || inherited?.applicationName || FALLBACK.applicationName;
  const shortName = value.shortName || inherited?.shortName || appName;
  const org = value.organizationName || inherited?.organizationName || 'Customer organization';
  const accent = value.accentColor || inherited?.accentColor || FALLBACK.accentColor;
  const primaryLogo = value.primaryLogoDataUrl || inherited?.primaryLogoDataUrl;
  const compactLogo = value.compactLogoDataUrl || inherited?.compactLogoDataUrl || primaryLogo;
  return (
    <div className="branding-preview" style={{ '--preview-accent': accent } as CSSProperties}>
      <div className="branding-preview-toolbar"><span className="preview-menu-lines">☰</span><div className="preview-brand-mark">{compactLogo ? <img src={compactLogo} alt="" /> : appName.slice(0, 1).toUpperCase()}</div><strong>{shortName}</strong><span className="preview-avatar">U</span></div>
      <div className="branding-preview-body"><aside>
        {primaryLogo ? <img className="preview-primary-logo" src={primaryLogo} alt="" /> : <div className="preview-inline-brand"><div className="preview-brand-mark large">{compactLogo ? <img src={compactLogo} alt="" /> : appName.slice(0, 1).toUpperCase()}</div><strong>{appName}</strong></div>}
        <small>{org}</small><span className="preview-nav active">Dashboard</span><span className="preview-nav">Upload</span><span className="preview-nav">Reports</span></aside><main><span className="preview-eyebrow">WORK</span><h3>Dashboard</h3><p>Operational overview</p><div className="preview-metrics"><span/><span/><span/></div><div className="preview-table-line"/><div className="preview-table-line short"/></main></div>
    </div>
  );
}

export default function BrandingPanel() {
  const { refreshBranding } = useBranding();
  const [overview, setOverview] = useState<BrandingOverview | null>(null);
  const [deployment, setDeployment] = useState<EditableBranding>(fromConfig(null));
  const [selectedTenantId, setSelectedTenantId] = useState('');
  const [tenantValue, setTenantValue] = useState<EditableBranding>(fromConfig(null));
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const load = useCallback(async () => {
    setError('');
    try {
      const data = await apiClient.getBrandingOverview(); setOverview(data); setDeployment(fromConfig(data.deployment));
      const selected = selectedTenantId || data.tenants[0]?.tenantId || ''; setSelectedTenantId(selected);
      if (selected) {
        const detail = await apiClient.getTenantBranding(selected); setTenantValue(fromConfig(detail.configuration));
      } else {
        setTenantValue(fromConfig(null));
      }
    } catch (e) { setError((e as { message?: string }).message || 'Unable to load branding configuration'); }
    finally { setLoading(false); }
  }, [selectedTenantId]);

  useEffect(() => { void load(); }, []); // intentional initial load only
  const selectedTenant = useMemo(() => overview?.tenants.find((item) => item.tenantId === selectedTenantId) || null, [overview, selectedTenantId]);

  const changeTenant = (tenantId: string) => {
    setSelectedTenantId(tenantId); setTenantValue(fromConfig(null)); setNotice(''); setError('');
    if (tenantId) {
      void apiClient.getTenantBranding(tenantId).then((detail) => setTenantValue(fromConfig(detail.configuration)))
        .catch((e) => setError((e as { message?: string }).message || 'Unable to load tenant branding'));
    }
  };

  const saveDeployment = async () => {
    if (deployment.accentColor && !accessibleAccent(deployment.accentColor)) { setError('Primary accent does not meet the branding contrast requirement.'); return; }
    setSaving(true); setError(''); setNotice('');
    try { await apiClient.updateDeploymentBranding(toRequest(deployment)); setNotice('Deployment branding saved.'); await load(); await refreshBranding(); }
    catch (e) { setError((e as { message?: string }).message || 'Unable to save deployment branding'); }
    finally { setSaving(false); }
  };

  const saveTenant = async () => {
    if (!selectedTenantId) return;
    if (tenantValue.accentColor && !accessibleAccent(tenantValue.accentColor)) { setError('Primary accent does not meet the branding contrast requirement.'); return; }
    setSaving(true); setError(''); setNotice('');
    try { await apiClient.updateTenantBranding(selectedTenantId, toRequest(tenantValue)); setNotice('Tenant branding override saved.'); await load(); await refreshBranding(); }
    catch (e) { setError((e as { message?: string }).message || 'Unable to save tenant branding'); }
    finally { setSaving(false); }
  };

  const resetTenant = async () => {
    if (!selectedTenantId) return; setSaving(true); setError(''); setNotice('');
    try { await apiClient.clearTenantBranding(selectedTenantId); setNotice('Tenant now inherits deployment branding.'); await load(); await refreshBranding(); }
    catch (e) { setError((e as { message?: string }).message || 'Unable to reset tenant branding'); }
    finally { setSaving(false); }
  };

  if (loading) return <div className="ui-loading-state">Loading branding configuration…</div>;

  return (
    <div className="branding-panel">
      {error && <div className="platform-error">{error}</div>}{notice && <div className="success-msg">{notice}</div>}
      <section className="platform-section branding-intro">
        <div><span className="platform-eyebrow">White-label foundation</span><h2>Branding & theme</h2><p>Change customer-facing names, logos and brand colors without changing tenant IDs, licensing identity, APIs, audit records or exported documents.</p></div>
        <div className="branding-policy-note"><Icon name="palette"/><span>Semantic success, warning and error colors remain platform controlled.</span></div>
      </section>

      <div className="branding-layout">
        <section className="platform-section platform-panel branding-editor">
          <div className="platform-section-heading compact"><div><h2>Deployment branding</h2><p>Default presentation for this installation and any tenant that does not override it.</p></div></div>
          <BrandingForm value={deployment} onChange={setDeployment} />
          <div className="branding-form-actions"><button className="ui-button primary" disabled={saving} onClick={() => void saveDeployment()}>{saving ? 'Saving…' : 'Save deployment branding'}</button></div>
        </section>
        <aside className="platform-section platform-panel branding-preview-card"><div className="platform-section-heading compact"><div><h2>Live preview</h2><p>Responsive shell preview using the current edit values.</p></div></div><Preview value={deployment}/></aside>
      </div>

      <section className="platform-section platform-panel branding-tenant-section">
        <div className="platform-section-heading"><div><h2>Tenant overrides</h2><p>Override only what a tenant needs; blank values inherit deployment branding.</p></div>{selectedTenant?.customBranding && <span className="platform-status platform-status-active">Custom branding</span>}</div>
        <div className="branding-tenant-controls"><label className="ui-field"><span>Tenant</span><select value={selectedTenantId} onChange={(e) => changeTenant(e.target.value)}>{overview?.tenants.map((tenant) => <option key={tenant.tenantId} value={tenant.tenantId}>{tenant.tenantName} · {tenant.tenantSlug}</option>)}</select></label><div className="branding-tenant-actions"><button className="ui-button secondary" disabled={saving || !selectedTenantId} onClick={() => void saveTenant()}>Save tenant override</button><button className="ui-button tertiary" disabled={saving || !selectedTenant?.customBranding} onClick={() => void resetTenant()}>Reset to deployment</button></div></div>
        {selectedTenant && <div className="branding-layout tenant"><div><BrandingForm value={tenantValue} onChange={setTenantValue} tenantMode /><div className="branding-tenant-bottom-actions"><button className="ui-button primary" disabled={saving || !selectedTenantId} onClick={() => void saveTenant()}>{saving ? 'Saving…' : 'Save tenant override'}</button><button className="ui-button tertiary" disabled={saving || !selectedTenant?.customBranding} onClick={() => void resetTenant()}>Reset to deployment</button></div></div><div className="branding-preview-card"><h3>{selectedTenant.tenantName} preview</h3><Preview value={tenantValue} inherited={overview?.deployment}/></div></div>}
      </section>
    </div>
  );
}
