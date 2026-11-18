import { useState } from 'react';
import { apiClient } from '../../api/client';
import type { BusinessAdminConfiguration, BusinessReviewMode, DocumentIntelligenceMode, ReportingConfiguration } from '../../types';
import { errorMessage } from '../data';
import { Notice } from '../atoms';
import { useToast } from '../toast';
import { SettingsCard, Toggle, Loading, useBusinessAdmin, humanize, ConfirmDialog, SelectField, DurationField } from './shared';
import { HOUR_UNITS, MINUTE_UNITS, bundleLabel, bundleName, bundleNames, durationText } from './adminRules';
import { ErrorBoundary } from '../failure';
import { SENSITIVE_TYPES, sensitiveTypeName } from '../words';

/** The one name for a sensitive type (words.ts), shared with Review, the case panel and Continue manually. */
export const entityLabel = sensitiveTypeName;

function SensitiveDataCard({ data, mutate }: { data: BusinessAdminConfiguration; mutate: (d: BusinessAdminConfiguration) => unknown }) {
  const sdp = data.sensitiveDataPolicy;
  const unsupported = sdp.enabledEntityTypes.filter((t) => !SENSITIVE_TYPES.some((s) => s.key === t));
  return (
    <SettingsCard
      title="Sensitive data"
      description="Which values are found and redacted, and when a person has to look."
      value={sdp}
      savedMessage="Redaction policy saved. New cases use it immediately."
      onSave={async (d) => {
        if (d.enabledEntityTypes.length === 0) throw new Error('Enable at least one sensitive-data category.');
        return mutate(await apiClient.updateSensitiveDataPolicy(d));
      }}
    >
      {(d, set) => (
        <>
          <fieldset className="cf-ad-fieldset">
            <legend>Send to review</legend>
            <div className="cf-segment" role="radiogroup" aria-label="Review mode">
              {(['UNCERTAIN_ONLY', 'REVIEW_ALL'] as BusinessReviewMode[]).map((m) => (
                <button key={m} type="button" role="radio" aria-checked={d.reviewMode === m} className={d.reviewMode === m ? 'is-active' : ''} onClick={() => set({ ...d, reviewMode: m })}>
                  {m === 'UNCERTAIN_ONLY' ? 'Only uncertain findings' : 'Every case'}
                </button>
              ))}
            </div>
          </fieldset>
          <div className="cf-ad-threshold">
            <label htmlFor="cf-threshold">Auto-redact when confidence is at least <strong>{Math.round(d.autoRedactThreshold * 100)}%</strong></label>
            <input id="cf-threshold" type="range" min={50} max={99} step={1} value={Math.round(d.autoRedactThreshold * 100)} onChange={(e) => set({ ...d, autoRedactThreshold: Number(e.target.value) / 100 })} />
            <p className="cf-ad-hint">Findings below this go to a reviewer{d.reviewMode === 'REVIEW_ALL' ? '. Every case is reviewed regardless' : ''}.</p>
          </div>
          <fieldset className="cf-ad-fieldset">
            <legend>Detected entities <span className="cf-muted">({d.enabledEntityTypes.length})</span></legend>
            <div className="cf-ad-chips">
              {[...SENSITIVE_TYPES.map((s) => s.key), ...unsupported].map((t) => {
                const on = d.enabledEntityTypes.includes(t);
                const meta = SENSITIVE_TYPES.find((s) => s.key === t);
                return (
                  <button key={t} type="button" aria-pressed={on} className="cf-ad-chip" title={meta?.detects}
                    onClick={() => set({ ...d, enabledEntityTypes: on ? d.enabledEntityTypes.filter((x) => x !== t) : [...d.enabledEntityTypes, t] })}>
                    {entityLabel(t)}{!meta && ' (not supported)'}
                  </button>
                );
              })}
            </div>
            <dl className="cf-ad-defs">
              {SENSITIVE_TYPES.map((s) => <div key={s.key}><dt>{s.name}</dt><dd>{s.detects}</dd></div>)}
            </dl>
            <p className="cf-ad-hint">
              Detection works on text values, not document types. Each finding blacks out only the matched value on the page.
              There is no whole-document or image-region redaction. A driver’s license, for example, is not an entity type,
              though an SSN or date of birth printed on one is found like any other text.
            </p>
            {unsupported.length > 0 && <p className="cf-ad-warn">{unsupported.map(entityLabel).join(', ')} {unsupported.length === 1 ? 'is' : 'are'} not acted on by the detection engine. Turn {unsupported.length === 1 ? 'it' : 'them'} off to keep the policy accurate.</p>}
            {d.enabledEntityTypes.length === 0 && <p className="cf-ad-warn">Enable at least one category. The policy cannot be saved empty.</p>}
          </fieldset>
          <Toggle label="Treat labeled last-four SSNs as sensitive" hint="When on, a last-four value is also redacted, but only when it is explicitly labeled SSN." checked={d.maskSsnLastFour} disabled={!d.enabledEntityTypes.includes('SSN')} onChange={(v) => set({ ...d, maskSsnLastFour: v })} />
        </>
      )}
    </SettingsCard>
  );
}

const MODES: [DocumentIntelligenceMode, string, string][] = [
  ['DISABLED', 'Off', 'No AI runs. Staff type the case fields.'],
  ['OBSERVE', 'Observe', 'AI suggestions are recorded for comparison but never filled in.'],
  ['ENABLED', 'On', 'AI fills in case fields for staff to confirm.'],
];

function IntelligenceCard({ data, mutate }: { data: BusinessAdminConfiguration; mutate: (d: BusinessAdminConfiguration) => unknown }) {
  const di = data.documentIntelligence;
  const toast = useToast();
  const [confirm, setConfirm] = useState<'rollback' | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const choices = di.availableBundles.filter((b) => b.status === 'PROMOTED' || b.version === di.activeBundleVersion);
  const names = bundleNames(di.availableBundles);
  const nameOf = (version: string) => names.get(version)?.name ?? version;

  async function run(action: 'rollback' | 'lkg') {
    setBusy(true); setError(null);
    try {
      mutate(action === 'rollback' ? await apiClient.rollbackDocumentIntelligence() : await apiClient.markDocumentIntelligenceLastKnownGood());
      toast(action === 'rollback' ? `Went back to ${nameOf(di.lastKnownGoodBundleVersion)}.` : `${nameOf(di.activeBundleVersion)} is now the fallback.`);
      setConfirm(null);
    } catch (e) {
      setError(errorMessage(e, action === 'rollback' ? 'Could not go back to the fallback version.' : 'Could not set the fallback version.'));
    } finally { setBusy(false); }
  }

  return (
    <>
      <SettingsCard
        title="Document intelligence"
        description="Changes apply to cases received after saving."
        value={{ mode: di.mode, activeBundleVersion: di.activeBundleVersion }}
        savedMessage="Document intelligence saved."
        onSave={async (d) => mutate(await apiClient.updateDocumentIntelligence(d.mode, d.activeBundleVersion))}
      >
        {(d, set) => {
          const bundle = di.availableBundles.find((b) => b.version === d.activeBundleVersion);
          const dirty = d.mode !== di.mode || d.activeBundleVersion !== di.activeBundleVersion;
          const sameAsFallback = di.activeBundleVersion === di.lastKnownGoodBundleVersion;
          return (
            <>
              <fieldset className="cf-ad-fieldset">
                <legend>Mode</legend>
                <div className="cf-ad-radios" data-layout="row" role="radiogroup" aria-label="AI mode">
                  {MODES.map(([m, label, hint]) => (
                    <label key={m} className="cf-ad-radio">
                      <input type="radio" name="di-mode" checked={d.mode === m} onChange={() => set({ ...d, mode: m })} />
                      <span><strong>{label}</strong><span className="cf-ad-hint">{hint}</span></span>
                    </label>
                  ))}
                </div>
              </fieldset>
              <SelectField label="AI version in use" value={d.activeBundleVersion}
                options={choices.map((b) => [b.version, bundleLabel(names.get(b.version) ?? bundleName(b))] as [string, string])}
                onChange={(activeBundleVersion) => set({ ...d, activeBundleVersion })}
                hint={`${bundle?.description ? `${bundle.description} ` : ''}Only tested and released versions are offered.`} />
              {di.baselineDiscoveryRequired && <Notice tone="mark">Run in Observe first, so AI suggestions can be compared before you turn it on.</Notice>}
              <div className="cf-ad-inline-actions">
                <span className="cf-ad-hint">Fallback if something goes wrong: <strong>{nameOf(di.lastKnownGoodBundleVersion)}</strong></span>
                <span className="cf-ad-spacer" />
                <button type="button" className="cf-btn" data-size="sm" disabled={busy || dirty || sameAsFallback} onClick={() => run('lkg')}
                  title={dirty ? 'Save or reset changes first.' : sameAsFallback ? 'This version is already the fallback.' : undefined}>Use this version as the fallback</button>
                <button type="button" className="cf-btn" data-size="sm" disabled={busy || dirty || sameAsFallback} onClick={() => setConfirm('rollback')}
                  title={dirty ? 'Save or reset changes first.' : sameAsFallback ? 'This version is already the fallback.' : undefined}>Go back to fallback…</button>
              </div>
              {error && !confirm && <Notice tone="danger">{error}</Notice>}
              <details className="cf-disclosure">
                <summary>Technical details</summary>
                <dl className="cf-ad-facts cf-ad-facts-compact">
                  <div><dt>Version</dt><dd>{d.activeBundleVersion}</dd></div>
                  <div><dt>Fallback version</dt><dd>{di.lastKnownGoodBundleVersion}</dd></div>
                  <div><dt>Configuration</dt><dd>v{di.configurationVersion}</dd></div>
                  {bundle && (
                    <>
                      <div><dt>Status</dt><dd>{humanize(bundle.status)}</dd></div>
                      <div><dt>Model</dt><dd>{bundle.modelVersion}</dd></div>
                      <div><dt>Prompt</dt><dd>{bundle.promptVersion}</dd></div>
                      <div><dt>Detector</dt><dd>{bundle.detectorVersion}</dd></div>
                      <div><dt>OCR rules</dt><dd>{bundle.ocrRulesVersion}</dd></div>
                      <div><dt>Profile</dt><dd>{bundle.profileVersion}</dd></div>
                      <div><dt>Validation rules</dt><dd>{bundle.validationRulesVersion}</dd></div>
                      <div><dt>Thresholds</dt><dd>{bundle.thresholdsVersion}</dd></div>
                      <div className="cf-ad-span"><dt>Name</dt><dd>{nameSource(names.get(bundle.version) ?? bundleName(bundle))}</dd></div>
                    </>
                  )}
                </dl>
              </details>
            </>
          );
        }}
      </SettingsCard>
      <ConfirmDialog
        open={confirm === 'rollback'}
        title="Go back to the fallback version?"
        facts={[['Version in use', nameOf(di.activeBundleVersion)], ['Go back to', nameOf(di.lastKnownGoodBundleVersion)], ['Mode', MODES.find(([m]) => m === di.mode)?.[1] ?? humanize(di.mode)]]}
        consequence="New cases use the fallback version straight away. Cases already processed are not run again."
        confirmLabel="Go back to fallback"
        danger busy={busy} error={error}
        onConfirm={() => run('rollback')}
        onCancel={() => { setConfirm(null); setError(null); }}
      />
    </>
  );
}

/** Says where a version's plain name came from: versions have no display name of their own. */
function nameSource(n: ReturnType<typeof bundleName>) {
  if (n.from === 'version') return 'Taken from the year and month in the version.';
  if (n.from === 'release date') return 'Taken from the month the version was released.';
  return 'This version has no date, so its version is shown.';
}

function LifecycleCard({ data, mutate }: { data: BusinessAdminConfiguration; mutate: (d: BusinessAdminConfiguration) => unknown }) {
  const lc = data.lifecyclePolicy;
  return (
    <SettingsCard
      title="Keeping and deleting"
      description="How long LexaVault keeps copies, and what must happen before delivery."
      value={{ originalRetentionHours: lc.originalRetentionHours, redactedCacheHours: lc.redactedCacheHours, neverExportedHoldHours: lc.neverExportedHoldHours, finalApprovalRequired: lc.finalApprovalRequired }}
      savedMessage="Keeping and deleting saved."
      onSave={async (d) => {
        if ([d.originalRetentionHours, d.redactedCacheHours, d.neverExportedHoldHours].some((v) => !Number.isInteger(v) || v < 1)) throw new Error('Each time must be a whole number of hours, at least 1 hour.');
        if (d.redactedCacheHours > lc.maximumRedactedCacheHours) throw new Error(`Redacted copies can be kept for up to ${durationText(lc.maximumRedactedCacheHours, HOUR_UNITS)}.`);
        return mutate(await apiClient.updateLifecyclePolicy(d));
      }}
    >
      {(d, set) => (
        <>
          <div className="cf-ad-grid">
            <DurationField label="Keep originals for" units={HOUR_UNITS} fixedUnit="hours" min={1} value={d.originalRetentionHours}
              onChange={(v) => set({ ...d, originalRetentionHours: v })} hint="After this, a case can't be processed again." />
            <DurationField label="Keep redacted copies for" units={HOUR_UNITS} min={1} max={lc.maximumRedactedCacheHours} value={d.redactedCacheHours}
              onChange={(v) => set({ ...d, redactedCacheHours: v })} hint={`Up to ${durationText(lc.maximumRedactedCacheHours, HOUR_UNITS)}.`} />
            <DurationField label="Keep stopped cases for" units={HOUR_UNITS} min={1} value={d.neverExportedHoldHours}
              onChange={(v) => set({ ...d, neverExportedHoldHours: v })} hint="Time to fix and finish a stopped case before it is deleted." />
          </div>
          <Toggle label="Require a supervisor's approval before delivery" hint="Cases wait in Approval until a supervisor signs off." checked={d.finalApprovalRequired} onChange={(v) => set({ ...d, finalApprovalRequired: v })} />
          <p className="cf-ad-hint">A case can be processed again at most {lc.maximumSuccessfulExportCycles} {lc.maximumSuccessfulExportCycles === 1 ? 'time' : 'times'} (set by LexaVault).</p>
        </>
      )}
    </SettingsCard>
  );
}

/** Production rule: identifiers with 1–100 allowed values are the only eligible reporting dimensions. */
function eligibleDimensions(data: BusinessAdminConfiguration, rc: ReportingConfiguration) {
  const current = new Map(rc.dimensions.map((x) => [x.definitionId, x]));
  return data.identifiers
    .filter((i) => i.allowedValues.length > 0 && i.allowedValues.length <= 100)
    .map((i, n) => ({ definitionId: i.id, key: i.key, label: i.label, enabled: current.get(i.id)?.enabled ?? false, displayOrder: current.get(i.id)?.displayOrder ?? n }));
}

/** A percentage typed inside a sentence. Marked, never clamped, when outside its range. */
function PercentInput({ label, value, onChange, min = 0 }: { label: string; value: number; onChange: (v: number) => void; min?: number }) {
  const invalid = !Number.isFinite(value) || value < min || value > 100;
  return (
    <>
      <span className="cf-ad-duration">
        <input type="number" inputMode="decimal" min={min} max={100} step={1} aria-label={label} aria-invalid={invalid || undefined}
          value={Number.isNaN(value) ? '' : value} onChange={(e) => onChange(e.target.valueAsNumber)} />
        <span className="cf-ad-duration-unit">%</span>
      </span>
      {invalid && <span className="cf-ad-sentence-error">{label}: enter a number from {min} to 100.</span>}
    </>
  );
}

function ReportingCard({ data, mutate }: { data: BusinessAdminConfiguration; mutate: (d: BusinessAdminConfiguration) => unknown }) {
  const rc = data.reportingConfiguration;
  const value = { ...rc, dimensions: eligibleDimensions(data, rc) };
  return (
    <SettingsCard
      title="Report targets"
      description="What Reports measures against."
      value={value}
      savedMessage="Report targets saved."
      onSave={async (d) => {
        if (!(d.deliveryTargetPercent >= 1 && d.deliveryTargetPercent <= 100)) throw new Error('The delivery target must be from 1% to 100%.');
        if (!(d.straightThroughTargetPercent >= 0 && d.straightThroughTargetPercent <= 100)) throw new Error('The no-review target must be from 0% to 100%.');
        if (!(Number.isInteger(d.reviewWaitTargetPercent) && d.reviewWaitTargetPercent >= 0 && d.reviewWaitTargetPercent <= 100)) throw new Error('The review target must be a whole number from 0% to 100%.');
        if (!(Number.isInteger(d.deliveryTargetMinutes) && d.deliveryTargetMinutes >= 1) || !(Number.isInteger(d.reviewWaitTargetMinutes) && d.reviewWaitTargetMinutes >= 1)) throw new Error('Each time must be a whole number of minutes, at least 1 minute.');
        return mutate(await apiClient.updateReportingConfiguration(d));
      }}
    >
      {(d, set) => (
        <>
          <div className="cf-ad-sentences">
            <div className="cf-ad-sentence">
              <span>Deliver within</span>
              <DurationField inline label="Deliver within" units={MINUTE_UNITS} min={1} value={d.deliveryTargetMinutes} onChange={(v) => set({ ...d, deliveryTargetMinutes: v })} />
              <span>for at least</span>
              <PercentInput label="Share of cases delivered in time" min={1} value={d.deliveryTargetPercent} onChange={(v) => set({ ...d, deliveryTargetPercent: v })} />
              <span>of cases</span>
            </div>
            <div className="cf-ad-sentence">
              <span>Cases needing no review: at least</span>
              <PercentInput label="Cases needing no review" value={d.straightThroughTargetPercent} onChange={(v) => set({ ...d, straightThroughTargetPercent: v })} />
            </div>
            <div className="cf-ad-sentence">
              <span>Pick up reviews within</span>
              <DurationField inline label="Pick up reviews within" units={MINUTE_UNITS} min={1} value={d.reviewWaitTargetMinutes} onChange={(v) => set({ ...d, reviewWaitTargetMinutes: v })} />
              <span>for at least</span>
              <PercentInput label="Share of reviews picked up in time" value={d.reviewWaitTargetPercent} onChange={(v) => set({ ...d, reviewWaitTargetPercent: v })} />
              <span>of reviews</span>
            </div>
          </div>
          <fieldset className="cf-ad-fieldset">
            <legend>Break reports down by</legend>
            {d.dimensions.length === 0
              ? <p className="cf-ad-hint">No case field can be used yet. A field with 1–100 allowed values (set in Case fields) can be used. Fields such as loan numbers cannot.</p>
              : (
                <div className="cf-ad-list">
                  {d.dimensions.map((dim) => (
                    <Toggle key={dim.definitionId} label={dim.label} hint={dim.enabled ? 'Shown in Reports' : 'Not used in Reports'} checked={dim.enabled}
                      onChange={(v) => set({ ...d, dimensions: d.dimensions.map((x) => (x.definitionId === dim.definitionId ? { ...x, enabled: v } : x)) })} />
                  ))}
                </div>
              )}
          </fieldset>
        </>
      )}
    </SettingsCard>
  );
}

export default function PolicyArea() {
  const { data, error, mutate } = useBusinessAdmin();
  if (!data) return <Loading error={error} retry={() => mutate()} label="Policy" />;
  const apply = (next: BusinessAdminConfiguration) => mutate(next, { revalidate: false });

  return (
    <div className="cf-ad-stack">
      <ErrorBoundary title="Sensitive data settings couldn't be shown" request="GET /business-admin/configuration"><SensitiveDataCard data={data} mutate={apply} /></ErrorBoundary>
      <ErrorBoundary title="Document intelligence settings couldn't be shown" request="GET /business-admin/configuration"><IntelligenceCard data={data} mutate={apply} /></ErrorBoundary>
      <div className="cf-ad-pair">
        <ErrorBoundary title="Keeping and deleting couldn't be shown" request="GET /business-admin/configuration"><LifecycleCard data={data} mutate={apply} /></ErrorBoundary>
        <ErrorBoundary title="Report targets couldn't be shown" request="GET /business-admin/configuration"><ReportingCard data={data} mutate={apply} /></ErrorBoundary>
      </div>
    </div>
  );
}
