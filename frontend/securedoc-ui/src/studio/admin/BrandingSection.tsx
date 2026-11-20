import { useId, useState } from 'react';
import useSWR from 'swr';
import { apiClient } from '../../api/client';
import type { BrandingConfiguration, BrandingOverview, BrandingUpdateRequest, TenantBrandingDetail } from '../../types';
import { errorMessage } from '../data';
import { Notice, Spinner } from '../atoms';
import { useToast } from '../toast';
import { Loading, TextField } from './shared';
import { HelpTip } from './HelpTip';

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
  const inherit = (fallback: string) => (tenantMode ? "Everyone's look" : fallback);
  const accentOk = !value.accentColor || accessibleAccent(value.accentColor);
  const setAsset = (k: Asset) => (dataUrl: string | null, clear: boolean) => onChange({ ...value, assets: { ...value.assets, [k]: { dataUrl, clear, changed: true } } });
  return (
    <div className="cf-ad-form">
      <div className="cf-ad-grid">
        <TextField label="Product name" value={value.applicationName} maxLength={80} placeholder={inherit(FALLBACK.applicationName)} onChange={(v) => onChange({ ...value, applicationName: v })} />
        <TextField label="Organization name" value={value.organizationName} maxLength={100} placeholder={inherit('Your organization')} onChange={(v) => onChange({ ...value, organizationName: v })}
          hint="Shown in the account menu, above the entity switch." />
      </div>
      <div className="cf-ad-grid">
      <ColorField label="Accent colour" value={value.accentColor} fallback={FALLBACK.accentColor} invalid={!accentOk} onChange={(v) => onChange({ ...value, accentColor: v })}
        hint={accentOk ? 'Used for highlights on a few older screens. Status colours never change.' : 'Pick a darker colour: white text on it must be easy to read (4.5 to 1).'} />
      <AssetField label="Logo" help="PNG or WebP, up to 512 KB. Shown at the left of the top bar." value={value.assets.primaryLogo.dataUrl} maxBytes={512 * 1024} onChange={setAsset('primaryLogo')} />
      </div>
      <details className="cf-disclosure">
        <summary>More options</summary>
        <div className="cf-ad-form">
          <div className="cf-ad-grid">
            <TextField label="Short name" value={value.shortName} maxLength={32} placeholder={inherit('Same as product name')} onChange={(v) => onChange({ ...value, shortName: v })} hint="Shown in the top bar instead of the product name." />
            <TextField label="Browser tab title" value={value.pageTitle} maxLength={100} placeholder={inherit(FALLBACK.pageTitle)} onChange={(v) => onChange({ ...value, pageTitle: v })} />
          </div>
          <ColorField label="Second colour" value={value.secondaryColor} fallback={FALLBACK.secondaryColor} onChange={(v) => onChange({ ...value, secondaryColor: v })}
            hint="Used sparingly on older screens, never for success, warning or error." />
          <div className="cf-ad-assets">
            <AssetField label="Small logo" help="Used in the top bar instead of the logo. PNG or WebP, up to 512 KB." value={value.assets.compactLogo.dataUrl} maxBytes={512 * 1024} onChange={setAsset('compactLogo')} />
            <AssetField label="Browser icon" help="The small icon on the browser tab. PNG or WebP, up to 128 KB." value={value.assets.favicon.dataUrl} maxBytes={128 * 1024} onChange={setAsset('favicon')} />
          </div>
        </div>
      </details>
    </div>
  );
}

/** Today's top bar, as the app draws it (CaseFileApp): small logo or logo, then the short or product name, the nav and the account. */
function Preview({ value, inherited, label }: { value: Editable; inherited?: BrandingConfiguration | null; label: string }) {
  const app = value.applicationName || inherited?.applicationName || FALLBACK.applicationName;
  const word = value.shortName || inherited?.shortName || app;
  const org = value.organizationName || inherited?.organizationName;
  const logo = value.assets.compactLogo.dataUrl || inherited?.compactLogoDataUrl || value.assets.primaryLogo.dataUrl || inherited?.primaryLogoDataUrl;
  return (
    <figure className="cf-ad-preview">
      <figcaption>Preview (today's top bar)</figcaption>
      <div className="cf-ad-preview-top" role="img" aria-label={label}>
        <span className="cf-brand">
          {logo ? <img src={logo} alt="" className="cf-ad-preview-logo" /> : <span className="cf-brand-mark" aria-hidden="true"><span /><span /><span /></span>}
          <span className="cf-brand-word">{word}</span>
        </span>
        <span className="cf-ad-preview-nav"><span data-active="true">Cases</span><span>Review</span></span>
        <span className="cf-ad-preview-acct"><span className="cf-acct-avatar">YN</span><span>Your name</span></span>
      </div>
      {org && <p className="cf-ad-hint">Account menu: {org} · Entity</p>}
    </figure>
  );
}

const EVERYONE = '';

export default function BrandingSection() {
  const { data, error, mutate } = useSWR<BrandingOverview>('platform-branding', () => apiClient.getBrandingOverview(), { revalidateOnFocus: false });
  const [target, setTarget] = useState(EVERYONE);
  const targetId = useId();

  if (!data) return <Loading error={error} retry={() => mutate()} label="Branding" />;
  const tenant = data.tenants.find((t) => t.tenantId === target) || null;

  return (
    <section className="cf-ad-card" aria-labelledby="cf-br-head">
      <header className="cf-ad-card-head">
        <div>
          <div className="cf-ad-title"><h2 id="cf-br-head">Branding</h2><HelpTip topic="branding" /></div>
          <p>Names, logos and colours people see. Status colours (success, warning, error) never change.</p>
        </div>
      <div className="cf-field cf-ad-apply">
        <label htmlFor={targetId}><span>Apply to</span></label>
        <select id={targetId} value={tenant ? target : EVERYONE} onChange={(e) => setTarget(e.target.value)}>
          <option value={EVERYONE}>Everyone (default)</option>
          {data.tenants.map((t) => <option key={t.tenantId} value={t.tenantId}>{t.tenantName}{t.customBranding ? ' · own look' : ''}</option>)}
        </select>
        <span className="cf-field-hint">{tenant ? "Blank fields use everyone's look." : 'Or pick one customer to give it its own look.'}</span>
      </div>
      </header>
      {tenant
        ? <TenantEditor key={tenant.tenantId} tenantId={tenant.tenantId} tenantName={tenant.tenantName} custom={tenant.customBranding} inherited={data.deployment} onSaved={() => mutate()} />
        : <Editor key={`everyone:${data.deployment?.updatedAt ?? 'none'}`} config={data.deployment} label="Everyone's branding preview" onSaved={() => mutate()}
            save={(r) => apiClient.updateDeploymentBranding(r)} savedMessage="Branding saved for everyone." />}
    </section>
  );
}

function TenantEditor({ tenantId, tenantName, custom, inherited, onSaved }: { tenantId: string; tenantName: string; custom: boolean; inherited?: BrandingConfiguration | null; onSaved: () => void }) {
  const detail = useSWR<TenantBrandingDetail>(['platform-branding-tenant', tenantId], () => apiClient.getTenantBranding(tenantId), { revalidateOnFocus: false });
  if (!detail.data) return <Loading error={detail.error} retry={() => detail.mutate()} label={`${tenantName} branding`} />;
  const refresh = () => { void detail.mutate(); onSaved(); };
  return (
    <Editor key={`${tenantId}:${detail.data.configuration?.updatedAt ?? 'none'}`} config={detail.data.configuration} inherited={inherited} tenantMode
      label={`${tenantName} branding preview`} onSaved={refresh}
      save={(r) => apiClient.updateTenantBranding(tenantId, r)} savedMessage={`${tenantName} now has its own look.`}
      clear={custom ? { label: "Use everyone's look", run: () => apiClient.clearTenantBranding(tenantId), done: `${tenantName} now uses everyone's look.` } : undefined} />
  );
}

function Editor({ config, inherited, tenantMode, label, save, savedMessage, clear, onSaved }: {
  config?: BrandingConfiguration | null; inherited?: BrandingConfiguration | null; tenantMode?: boolean; label: string;
  save: (r: BrandingUpdateRequest) => Promise<unknown>; savedMessage: string;
  clear?: { label: string; run: () => Promise<unknown>; done: string };
  onSaved: () => void;
}) {
  const toast = useToast();
  const [initial] = useState(() => fromConfig(config));
  const [value, setValue] = useState(initial);
  const [busy, setBusy] = useState<'' | 'save' | 'clear'>('');
  const [err, setErr] = useState('');
  const changed = JSON.stringify(value) !== JSON.stringify(initial);

  async function act(kind: 'save' | 'clear') {
    if (kind === 'save' && value.accentColor && !accessibleAccent(value.accentColor)) { setErr('Pick a darker accent colour before saving.'); return; }
    setErr(''); setBusy(kind);
    try {
      if (kind === 'save') { await save(toRequest(value)); toast(savedMessage); }
      else if (clear) { await clear.run(); toast(clear.done); }
      onSaved();
    } catch (e) {
      setErr(errorMessage(e, "The branding couldn't be saved. Nothing was changed."));
    } finally {
      setBusy('');
    }
  }

  return (
    <>
      <div className="cf-ad-brand">
        <BrandingForm value={value} onChange={setValue} tenantMode={tenantMode} />
        <Preview value={value} inherited={inherited} label={label} />
      </div>
      {err && <Notice tone="danger">{err}</Notice>}
      <div className="cf-ad-card-foot">
        {clear && <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={!!busy} onClick={() => void act('clear')}>{busy === 'clear' && <Spinner label="Resetting" />}{clear.label}</button>}
        <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={!!busy || !changed} onClick={() => { setValue(initial); setErr(''); }}>Reset</button>
        <button type="button" className="cf-btn" data-variant="solid" data-size="sm" disabled={!!busy || !changed} onClick={() => void act('save')}>{busy === 'save' && <Spinner label="Saving" />}Save</button>
      </div>
    </>
  );
}
