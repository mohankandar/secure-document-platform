import { useId, useState, type CSSProperties } from 'react';
import useSWR from 'swr';
import { apiClient } from '../../api/client';
import type { BrandingConfiguration, BrandingOverview, BrandingUpdateRequest, TenantBrandingDetail } from '../../types';
import { errorMessage } from '../data';
import { Notice, Spinner } from '../atoms';
import { useToast } from '../toast';
import { Loading, TextField } from './shared';

type Asset = 'primaryLogo' | 'compactLogo' | 'favicon';
type Editable = {
  applicationName: string; shortName: string; organizationName: string; pageTitle: string; accentColor: string; secondaryColor: string;
  assets: Record<Asset, { dataUrl: string | null; changed: boolean; clear: boolean }>;
};

const FALLBACK = { applicationName: 'LexaVault', shortName: 'LexaVault', pageTitle: 'LexaVault', accentColor: '#315CF4', secondaryColor: '#6D5AE6' };
const HEX = /^#[0-9a-fA-F]{6}$/;

function fromConfig(c?: BrandingConfiguration | null): Editable {
  const asset = (v?: string | null) => ({ dataUrl: v || null, changed: false, clear: false });
  return {
    applicationName: c?.applicationName || '', shortName: c?.shortName || '', organizationName: c?.organizationName || '', pageTitle: c?.pageTitle || '',
    accentColor: c?.accentColor || '', secondaryColor: c?.secondaryColor || '',
    assets: { primaryLogo: asset(c?.primaryLogoDataUrl), compactLogo: asset(c?.compactLogoDataUrl), favicon: asset(c?.faviconDataUrl) },
  };
}

function toRequest(v: Editable): BrandingUpdateRequest {
  const asset = (k: Asset) => (v.assets[k].changed ? { dataUrl: v.assets[k].dataUrl, clear: v.assets[k].clear } : null);
  return {
    applicationName: v.applicationName || null, shortName: v.shortName || null, organizationName: v.organizationName || null, pageTitle: v.pageTitle || null,
    accentColor: v.accentColor || null, secondaryColor: v.secondaryColor || null,
    primaryLogo: asset('primaryLogo'), compactLogo: asset('compactLogo'), favicon: asset('favicon'),
  };
}

function contrastWithWhite(hex: string) {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  const l = r * 0.2126 + g * 0.7152 + b * 0.0722;
  return 1.05 / (l + 0.05);
}
const accessibleAccent = (hex: string) => HEX.test(hex) && contrastWithWhite(hex) >= 4.5;

async function readImage(file: File, maxBytes: number): Promise<string> {
  if (!['image/png', 'image/webp'].includes(file.type)) throw new Error('Use a PNG or WebP image.');
  if (file.size > maxBytes) throw new Error(`Image must be smaller than ${Math.round(maxBytes / 1024)} KB.`);
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(new Error('Unable to read image.'));
    reader.readAsDataURL(file);
  });
}

function AssetField({ label, help, value, maxBytes, onChange }: { label: string; help: string; value: string | null; maxBytes: number; onChange: (dataUrl: string | null, clear: boolean) => void }) {
  const [error, setError] = useState('');
  const inputId = useId();
  return (
    <div className="cf-ad-asset">
      <div className="cf-ad-asset-preview">{value ? <img src={value} alt={`${label} preview`} /> : <span>No image</span>}</div>
      <div className="cf-ad-asset-copy">
        <strong>{label}</strong>
        <span className="cf-field-hint">{help}</span>
        <div className="cf-ad-inline-actions">
          <label htmlFor={inputId} className="cf-btn" data-variant="ghost" data-size="sm">Choose image</label>
          <input id={inputId} type="file" accept="image/png,image/webp" className="sr-only" onChange={async (e) => {
            const input = e.currentTarget;
            const file = input.files?.[0];
            if (!file) return;
            setError('');
            try { onChange(await readImage(file, maxBytes), false); } catch (err) { setError((err as Error).message); }
            input.value = '';
          }} />
          {value && <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" onClick={() => onChange(null, true)}>Remove</button>}
        </div>
        {error && <span className="cf-danger-text" role="alert">{error}</span>}
      </div>
    </div>
  );
}

function ColorField({ label, value, fallback, onChange, hint, invalid }: { label: string; value: string; fallback: string; onChange: (v: string) => void; hint: string; invalid?: boolean }) {
  const id = useId();
  return (
    <div className="cf-field" data-invalid={invalid || undefined}>
      <label htmlFor={id}><span>{label}</span><span className="cf-field-opt">Optional</span></label>
      <div className="cf-ad-color">
        <input type="color" aria-label={`${label} picker`} value={HEX.test(value) ? value : fallback} onChange={(e) => onChange(e.target.value.toUpperCase())} />
        <input id={id} className="cf-ad-mono" value={value} placeholder={fallback} maxLength={7} aria-invalid={invalid || undefined} onChange={(e) => onChange(e.target.value.toUpperCase())} />
      </div>
      <span className={invalid ? 'cf-field-hint cf-danger-text' : 'cf-field-hint'}>{hint}</span>
    </div>
  );
}

function BrandingForm({ value, onChange, tenantMode }: { value: Editable; onChange: (v: Editable) => void; tenantMode?: boolean }) {
  const inherit = (fallback: string) => (tenantMode ? 'Inherit deployment' : fallback);
  const accentOk = !value.accentColor || accessibleAccent(value.accentColor);
  const setAsset = (k: Asset) => (dataUrl: string | null, clear: boolean) => onChange({ ...value, assets: { ...value.assets, [k]: { dataUrl, clear, changed: true } } });
  return (
    <div className="cf-ad-form">
      {tenantMode && <p className="cf-ad-hint">Leave a field blank to inherit the deployment brand.</p>}
      <div className="cf-ad-grid">
        <TextField label="Application name" value={value.applicationName} maxLength={80} placeholder={inherit(FALLBACK.applicationName)} onChange={(v) => onChange({ ...value, applicationName: v })} />
        <TextField label="Short name" value={value.shortName} maxLength={32} placeholder={inherit(FALLBACK.shortName)} onChange={(v) => onChange({ ...value, shortName: v })} />
        <TextField label="Organization name" value={value.organizationName} maxLength={100} placeholder={inherit('Customer organization')} onChange={(v) => onChange({ ...value, organizationName: v })} />
        <TextField label="Browser/page title" value={value.pageTitle} maxLength={100} placeholder={inherit(FALLBACK.pageTitle)} onChange={(v) => onChange({ ...value, pageTitle: v })} />
      </div>
      <div className="cf-ad-grid">
        <ColorField label="Primary accent" value={value.accentColor} fallback={FALLBACK.accentColor} invalid={!accentOk} onChange={(v) => onChange({ ...value, accentColor: v })}
          hint={accentOk ? 'Accessible contrast can be generated for UI surfaces.' : 'Choose a color with sufficient contrast (4.5:1 against white).'} />
        <ColorField label="Secondary brand color" value={value.secondaryColor} fallback={FALLBACK.secondaryColor} onChange={(v) => onChange({ ...value, secondaryColor: v })}
          hint="Used sparingly for branded accents, never for semantic success, warning or error." />
      </div>
      <div className="cf-ad-assets">
        <AssetField label="Primary logo" help="Navigation and wider layouts. PNG or WebP, max 512 KB." value={value.assets.primaryLogo.dataUrl} maxBytes={512 * 1024} onChange={setAsset('primaryLogo')} />
        <AssetField label="Compact logo" help="Collapsed sidebar and tablet shell. PNG or WebP, max 512 KB." value={value.assets.compactLogo.dataUrl} maxBytes={512 * 1024} onChange={setAsset('compactLogo')} />
        <AssetField label="Favicon" help="Browser icon. PNG or WebP, max 128 KB." value={value.assets.favicon.dataUrl} maxBytes={128 * 1024} onChange={setAsset('favicon')} />
      </div>
    </div>
  );
}

function Preview({ value, inherited, label }: { value: Editable; inherited?: BrandingConfiguration | null; label: string }) {
  const app = value.applicationName || inherited?.applicationName || FALLBACK.applicationName;
  const short = value.shortName || inherited?.shortName || app;
  const org = value.organizationName || inherited?.organizationName || 'Customer organization';
  const accent = value.accentColor || inherited?.accentColor || FALLBACK.accentColor;
  const primary = value.assets.primaryLogo.dataUrl || inherited?.primaryLogoDataUrl;
  const compact = value.assets.compactLogo.dataUrl || inherited?.compactLogoDataUrl || primary;
  const mark = compact ? <img src={compact} alt="" /> : app.slice(0, 1).toUpperCase();
  return (
    <figure className="cf-ad-preview" style={{ '--preview-accent': HEX.test(accent) ? accent : FALLBACK.accentColor } as CSSProperties}>
      <figcaption className="sr-only">{label}</figcaption>
      <div className="cf-ad-preview-bar"><span className="cf-ad-preview-mark">{mark}</span><strong>{short}</strong></div>
      <div className="cf-ad-preview-body">
        <div className="cf-ad-preview-side">
          {primary ? <img className="cf-ad-preview-logo" src={primary} alt="" /> : <strong>{app}</strong>}
          <small>{org}</small>
          <span data-active="true">Desk</span><span>Intake</span><span>Reports</span>
        </div>
        <div className="cf-ad-preview-main"><small>Work</small><strong>Desk</strong><span className="cf-ad-preview-btn">Primary action</span></div>
      </div>
    </figure>
  );
}

export default function BrandingSection() {
  const { data, error, mutate } = useSWR<BrandingOverview>('platform-branding', () => apiClient.getBrandingOverview(), { revalidateOnFocus: false });
  const [picked, setPicked] = useState('');
  const tenantSelectId = useId();

  if (!data) return <Loading error={error} retry={() => mutate()} label="Branding" />;
  const tenantId = picked || data.tenants[0]?.tenantId || '';
  const tenant = data.tenants.find((t) => t.tenantId === tenantId) || null;

  return (
    <>
      <section className="cf-ad-card" aria-labelledby="cf-br-intro">
        <header className="cf-ad-card-head">
          <div>
            <h2 id="cf-br-intro">Branding and theme</h2>
            <p>Change customer-facing names, logos and brand colors without changing tenant IDs, licensing identity, APIs, audit records or exported documents. Semantic success, warning and error colors remain platform controlled.</p>
          </div>
        </header>
      </section>

      <DeploymentEditor key={data.deployment?.updatedAt ?? 'none'} config={data.deployment} onSaved={() => mutate()} />

      <section className="cf-ad-card" aria-labelledby="cf-br-tenant">
        <header className="cf-ad-card-head">
          <div>
            <h2 id="cf-br-tenant">Tenant overrides {tenant?.customBranding && <span className="cf-ad-tag">Custom branding</span>}</h2>
            <p>Override only what a tenant needs; blank values inherit deployment branding.</p>
          </div>
        </header>
        {data.tenants.length === 0 ? (
          <p className="cf-ad-hint">No provisioned tenants are available for branding.</p>
        ) : (
          <>
            <div className="cf-field cf-ad-narrow">
              <label htmlFor={tenantSelectId}><span>Tenant</span></label>
              <select id={tenantSelectId} value={tenantId} onChange={(e) => setPicked(e.target.value)}>
                {data.tenants.map((t) => <option key={t.tenantId} value={t.tenantId}>{t.tenantName} · {t.tenantSlug}</option>)}
              </select>
            </div>
            {tenant && <TenantEditor key={tenant.tenantId} tenantId={tenant.tenantId} tenantName={tenant.tenantName} custom={tenant.customBranding} inherited={data.deployment} onSaved={() => mutate()} />}
          </>
        )}
      </section>
    </>
  );
}

function DeploymentEditor({ config, onSaved }: { config?: BrandingConfiguration | null; onSaved: () => void }) {
  const toast = useToast();
  const [value, setValue] = useState(() => fromConfig(config));
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState('');

  async function save() {
    if (value.accentColor && !accessibleAccent(value.accentColor)) { setErr('Primary accent does not meet the branding contrast requirement.'); return; }
    setErr(''); setSaving(true);
    try {
      await apiClient.updateDeploymentBranding(toRequest(value));
      toast('Deployment branding saved.');
      onSaved();
    } catch (e) {
      setErr(errorMessage(e, 'Unable to save deployment branding.'));
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="cf-ad-card" aria-labelledby="cf-br-dep">
      <header className="cf-ad-card-head">
        <div>
          <h2 id="cf-br-dep">Deployment branding</h2>
          <p>Default presentation for this installation and any tenant that does not override it.</p>
        </div>
      </header>
      <div className="cf-ad-brand">
        <BrandingForm value={value} onChange={setValue} />
        <Preview value={value} label="Deployment branding preview" />
      </div>
      {err && <Notice tone="danger">{err}</Notice>}
      <div className="cf-ad-card-foot">
        <button type="button" className="cf-btn" data-variant="solid" data-size="sm" disabled={saving} onClick={() => void save()}>{saving && <Spinner label="Saving" />}Save deployment branding</button>
      </div>
    </section>
  );
}

function TenantEditor({ tenantId, tenantName, custom, inherited, onSaved }: { tenantId: string; tenantName: string; custom: boolean; inherited?: BrandingConfiguration | null; onSaved: () => void }) {
  const detail = useSWR<TenantBrandingDetail>(['platform-branding-tenant', tenantId], () => apiClient.getTenantBranding(tenantId), { revalidateOnFocus: false });
  if (!detail.data) return <Loading error={detail.error} retry={() => detail.mutate()} label="Tenant branding" />;
  return (
    <TenantForm key={detail.data.configuration?.updatedAt ?? 'none'} tenantId={tenantId} tenantName={tenantName} custom={custom} inherited={inherited}
      config={detail.data.configuration} onSaved={() => { void detail.mutate(); onSaved(); }} />
  );
}

function TenantForm({ tenantId, tenantName, custom, inherited, config, onSaved }: { tenantId: string; tenantName: string; custom: boolean; inherited?: BrandingConfiguration | null; config?: BrandingConfiguration | null; onSaved: () => void }) {
  const toast = useToast();
  const [value, setValue] = useState(() => fromConfig(config));
  const [busy, setBusy] = useState<'' | 'save' | 'reset'>('');
  const [err, setErr] = useState('');

  async function save() {
    if (value.accentColor && !accessibleAccent(value.accentColor)) { setErr('Primary accent does not meet the branding contrast requirement.'); return; }
    setErr(''); setBusy('save');
    try {
      await apiClient.updateTenantBranding(tenantId, toRequest(value));
      toast('Tenant branding override saved.');
      onSaved();
    } catch (e) {
      setErr(errorMessage(e, 'Unable to save tenant branding.'));
    } finally {
      setBusy('');
    }
  }

  async function reset() {
    setErr(''); setBusy('reset');
    try {
      await apiClient.clearTenantBranding(tenantId);
      toast('Tenant now inherits deployment branding.');
      onSaved();
    } catch (e) {
      setErr(errorMessage(e, 'Unable to reset tenant branding.'));
    } finally {
      setBusy('');
    }
  }

  return (
    <>
      <div className="cf-ad-brand">
        <BrandingForm value={value} onChange={setValue} tenantMode />
        <Preview value={value} inherited={inherited} label={`${tenantName} branding preview`} />
      </div>
      {err && <Notice tone="danger">{err}</Notice>}
      <div className="cf-ad-card-foot">
        <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={!!busy || !custom} onClick={() => void reset()}>{busy === 'reset' && <Spinner label="Resetting" />}Reset to deployment</button>
        <button type="button" className="cf-btn" data-variant="solid" data-size="sm" disabled={!!busy} onClick={() => void save()}>{busy === 'save' && <Spinner label="Saving" />}Save tenant override</button>
      </div>
    </>
  );
}
