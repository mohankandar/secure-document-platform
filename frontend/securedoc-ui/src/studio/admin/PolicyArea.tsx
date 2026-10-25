import { useState } from 'react';
import { apiClient } from '../../api/client';
import type { BusinessAdminConfiguration, BusinessReviewMode, DocumentIntelligenceMode, ReportingConfiguration } from '../../types';
import { errorMessage } from '../data';
import { Notice } from '../atoms';
import { useToast } from '../toast';
import { SettingsCard, Toggle, NumberField, Loading, useBusinessAdmin, humanize, ConfirmDialog, SelectField } from './shared';

/**
 * The production catalog (BusinessAdminPage SENSITIVE_TYPES). These are the only types the
 * detection prompt and heuristic adapters act on (SensitiveContentPromptBuilder). Each one finds an
 * exact text value span and redacts only that span; none redacts a whole page, image or document.
 */
const SENSITIVE_TYPES: { key: string; label: string; detects: string }[] = [
  { key: 'SSN', label: 'Social Security Number', detects: 'Full SSNs in text, including clearly labeled test values. Form, reference and contract numbers are excluded.' },
  { key: 'DATE_OF_BIRTH', label: 'Date of Birth', detects: 'A person’s birth date only. Contract, signature, payment and effective dates are excluded.' },
  { key: 'CREDIT_CARD_NUMBER', label: 'Credit Card Number', detects: 'Full 13–19 digit payment card numbers. Masked or last-four displays and account numbers are excluded.' },
  { key: 'CREDIT_SCORE', label: 'Credit Score', detects: 'A consumer credit or FICO score tied to a person or report. Bare three-digit numbers are excluded.' },
];
export const entityLabel = (t: string) => SENSITIVE_TYPES.find((s) => s.key === t)?.label ?? humanize(t);

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
              {SENSITIVE_TYPES.map((s) => <div key={s.key}><dt>{s.label}</dt><dd>{s.detects}</dd></div>)}
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
  ['DISABLED', 'Disabled', 'No intelligence runs. Identifiers are keyed in by staff.'],
  ['OBSERVE', 'Observe', 'Proposals are recorded for comparison but never applied to cases.'],
  ['ENABLED', 'Enabled', 'Proposals pre-fill auto-extract identifiers for staff to confirm.'],
];

function IntelligenceCard({ data, mutate }: { data: BusinessAdminConfiguration; mutate: (d: BusinessAdminConfiguration) => unknown }) {
  const di = data.documentIntelligence;
  const toast = useToast();
  const [confirm, setConfirm] = useState<'rollback' | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const choices = di.availableBundles.filter((b) => b.status === 'PROMOTED' || b.version === di.activeBundleVersion);

  async function run(action: 'rollback' | 'lkg') {
    setBusy(true); setError(null);
    try {
      mutate(action === 'rollback' ? await apiClient.rollbackDocumentIntelligence() : await apiClient.markDocumentIntelligenceLastKnownGood());
      toast(action === 'rollback' ? `Rolled back to ${di.lastKnownGoodBundleVersion}.` : `${di.activeBundleVersion} marked last known good.`);
      setConfirm(null);
    } catch (e) {
      setError(errorMessage(e, action === 'rollback' ? 'Unable to roll back Document Intelligence.' : 'Unable to update the last-known-good bundle.'));
    } finally { setBusy(false); }
  }

  return (
    <>
      <SettingsCard
        title="Document intelligence"
        description={`Configuration v${di.configurationVersion}. Changes apply to cases received after saving.`}
        value={{ mode: di.mode, activeBundleVersion: di.activeBundleVersion }}
        savedMessage="Document intelligence saved."
        onSave={async (d) => mutate(await apiClient.updateDocumentIntelligence(d.mode, d.activeBundleVersion))}
      >
        {(d, set) => {
          const bundle = di.availableBundles.find((b) => b.version === d.activeBundleVersion);
          const dirty = d.mode !== di.mode || d.activeBundleVersion !== di.activeBundleVersion;
          return (
            <>
              <fieldset className="cf-ad-fieldset">
                <legend>Mode</legend>
                <div className="cf-ad-radios" role="radiogroup" aria-label="Intelligence mode">
                  {MODES.map(([m, label, hint]) => (
                    <label key={m} className="cf-ad-radio">
                      <input type="radio" name="di-mode" checked={d.mode === m} onChange={() => set({ ...d, mode: m })} />
                      <span><strong>{label}</strong><span className="cf-ad-hint">{hint}</span></span>
                    </label>
                  ))}
                </div>
              </fieldset>
              <SelectField label="Active bundle" value={d.activeBundleVersion}
                options={choices.map((b) => [b.version, `${b.version}${b.status !== 'PROMOTED' ? ` (${humanize(b.status)})` : ''}`] as [string, string])}
                onChange={(activeBundleVersion) => set({ ...d, activeBundleVersion })}
                hint="Only promoted bundles can be selected. Candidate and retired bundles are not offered." />
              {bundle && (
                <dl className="cf-ad-facts cf-ad-facts-compact">
                  <div><dt>Status</dt><dd>{humanize(bundle.status)}</dd></div>
                  <div><dt>Model</dt><dd>{bundle.modelVersion}</dd></div>
                  <div><dt>Prompt</dt><dd>{bundle.promptVersion}</dd></div>
                  <div><dt>Detector</dt><dd>{bundle.detectorVersion}</dd></div>
                  <div><dt>OCR rules</dt><dd>{bundle.ocrRulesVersion}</dd></div>
                  <div><dt>Profile</dt><dd>{bundle.profileVersion}</dd></div>
                  <div><dt>Validation rules</dt><dd>{bundle.validationRulesVersion}</dd></div>
                  <div><dt>Thresholds</dt><dd>{bundle.thresholdsVersion}</dd></div>
                  {bundle.description && <div className="cf-ad-span"><dt>Notes</dt><dd>{bundle.description}</dd></div>}
                </dl>
              )}
              {di.baselineDiscoveryRequired && <Notice tone="mark">Baseline discovery is still required for this tenant. Run in Observe before enabling so proposals can be compared.</Notice>}
              <div className="cf-ad-inline-actions">
                <span className="cf-ad-hint">Last known good: <strong>{di.lastKnownGoodBundleVersion}</strong></span>
                <span className="cf-ad-spacer" />
                <button type="button" className="cf-btn" data-size="sm" disabled={busy || dirty || di.activeBundleVersion === di.lastKnownGoodBundleVersion} onClick={() => run('lkg')}
                  title={dirty ? 'Save or reset changes first.' : undefined}>Mark active as last known good</button>
                <button type="button" className="cf-btn" data-size="sm" disabled={busy || dirty || di.activeBundleVersion === di.lastKnownGoodBundleVersion} onClick={() => setConfirm('rollback')}>Roll back…</button>
              </div>
              {error && !confirm && <Notice tone="danger">{error}</Notice>}
            </>
          );
        }}
      </SettingsCard>
      <ConfirmDialog
        open={confirm === 'rollback'}
        title="Roll back document intelligence?"
        facts={[['Current bundle', di.activeBundleVersion], ['Roll back to', di.lastKnownGoodBundleVersion], ['Mode', humanize(di.mode)], ['Configuration', `v${di.configurationVersion} → v${di.configurationVersion + 1}`]]}
        consequence="New cases will use the last-known-good bundle immediately. Cases already processed are not re-run."
        confirmLabel="Roll back"
        danger busy={busy} error={error}
        onConfirm={() => run('rollback')}
        onCancel={() => { setConfirm(null); setError(null); }}
      />
    </>
  );
}

function LifecycleCard({ data, mutate }: { data: BusinessAdminConfiguration; mutate: (d: BusinessAdminConfiguration) => unknown }) {
  const lc = data.lifecyclePolicy;
  return (
    <SettingsCard
      title="Retention and approval"
      description="When originals and redacted copies are purged, and what must happen before delivery."
      value={{ originalRetentionHours: lc.originalRetentionHours, redactedCacheHours: lc.redactedCacheHours, neverExportedHoldHours: lc.neverExportedHoldHours, finalApprovalRequired: lc.finalApprovalRequired }}
      savedMessage="Retention policy saved."
      onSave={async (d) => {
        if (d.redactedCacheHours > lc.maximumRedactedCacheHours) throw new Error(`Redacted copies can be kept at most ${lc.maximumRedactedCacheHours} hours.`);
        if ([d.originalRetentionHours, d.redactedCacheHours, d.neverExportedHoldHours].some((v) => !Number.isInteger(v) || v < 1)) throw new Error('Retention periods must be whole hours of at least 1.');
        return mutate(await apiClient.updateLifecyclePolicy(d));
      }}
    >
      {(d, set) => (
        <>
          <div className="cf-ad-grid">
            <NumberField label="Keep originals" suffix="hours" min={1} value={d.originalRetentionHours} onChange={(v) => set({ ...d, originalRetentionHours: v })} hint="After this, originals are purged and the case can no longer be reprocessed." />
            <NumberField label="Keep redacted copies" suffix="hours" min={1} max={lc.maximumRedactedCacheHours} value={d.redactedCacheHours} onChange={(v) => set({ ...d, redactedCacheHours: v })} hint={`Maximum ${lc.maximumRedactedCacheHours} hours.`} />
            <NumberField label="Hold if never delivered" suffix="hours" min={1} value={d.neverExportedHoldHours} onChange={(v) => set({ ...d, neverExportedHoldHours: v })} hint="Cases needing technical attention are kept this long before purge." />
          </div>
          <Toggle label="Require final approval before delivery" hint="Cases wait in Approval until someone with approval rights signs off." checked={d.finalApprovalRequired} onChange={(v) => set({ ...d, finalApprovalRequired: v })} />
          <dl className="cf-ad-facts cf-ad-facts-compact">
            <div><dt>Successful export cycles</dt><dd>At most {lc.maximumSuccessfulExportCycles} per original intake batch</dd></div>
            <div><dt>Reprocessing</dt><dd>Only while the original is retained and the export-cycle limit has not been reached</dd></div>
          </dl>
          <p className="cf-ad-hint">The export-cycle limit and the redacted-copy maximum are fixed by the platform and cannot be changed here.</p>
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

function ReportingCard({ data, mutate }: { data: BusinessAdminConfiguration; mutate: (d: BusinessAdminConfiguration) => unknown }) {
  const rc = data.reportingConfiguration;
  const value = { ...rc, dimensions: eligibleDimensions(data, rc) };
  return (
    <SettingsCard
      title="Reporting"
      description="Targets Reports uses to judge delivery, automation and review, and the business dimensions it can break down by."
      value={value}
      savedMessage="Reporting saved. Historical reporting facts stay aggregate-only."
      onSave={async (d) => {
        if (d.deliveryTargetPercent < 1 || d.deliveryTargetPercent > 100 || d.straightThroughTargetPercent < 0 || d.straightThroughTargetPercent > 100) throw new Error('Percentages must be between 0 and 100.');
        if (d.deliveryTargetMinutes < 1 || d.reviewWaitTargetMinutes < 1) throw new Error('Time targets must be at least 1 minute.');
        return mutate(await apiClient.updateReportingConfiguration(d));
      }}
    >
      {(d, set) => (
        <>
          <div className="cf-ad-grid">
            <NumberField label="Deliver within" suffix="minutes" min={1} value={d.deliveryTargetMinutes} onChange={(v) => set({ ...d, deliveryTargetMinutes: v })} />
            <NumberField label="For at least" suffix="% of cases" min={1} max={100} value={d.deliveryTargetPercent} onChange={(v) => set({ ...d, deliveryTargetPercent: v })} />
            <NumberField label="Straight-through goal" suffix="%" min={0} max={100} value={d.straightThroughTargetPercent} onChange={(v) => set({ ...d, straightThroughTargetPercent: v })} />
            <NumberField label="Pick up reviews within" suffix="minutes" min={1} value={d.reviewWaitTargetMinutes} onChange={(v) => set({ ...d, reviewWaitTargetMinutes: v })} />
          </div>
          <fieldset className="cf-ad-fieldset">
            <legend>Report breakdowns</legend>
            {d.dimensions.length === 0
              ? <p className="cf-ad-hint">No eligible field yet. Give an identifier 1–100 allowed values in Case fields to make it a breakdown. High-cardinality fields such as account numbers cannot be used.</p>
              : (
                <div className="cf-ad-list">
                  {d.dimensions.map((dim) => (
                    <Toggle key={dim.definitionId} label={dim.label} hint={dim.enabled ? 'Included in Reports' : 'Not used in Reports'} checked={dim.enabled}
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
      <SensitiveDataCard data={data} mutate={apply} />
      <IntelligenceCard data={data} mutate={apply} />
      <div className="cf-ad-pair">
        <LifecycleCard data={data} mutate={apply} />
        <ReportingCard data={data} mutate={apply} />
      </div>
    </div>
  );
}
