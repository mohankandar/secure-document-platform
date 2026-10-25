import { apiClient } from '../../api/client';
import type { BusinessReviewMode } from '../../types';
import { SettingsCard, Toggle, NumberField, Loading, useBusinessAdmin, humanize } from './shared';

export default function PolicyArea() {
  const { data, error, mutate } = useBusinessAdmin();
  if (!data) return <Loading error={error} retry={() => mutate()} label="Policy" />;

  const sdp = data.sensitiveDataPolicy;
  const lc = data.lifecyclePolicy;
  const rc = data.reportingConfiguration;
  const knownEntities = rememberEntityTypes(sdp.enabledEntityTypes);

  return (
    <div className="cf-ad-stack">
      <SettingsCard
        title="Sensitive data"
        description="Which entities are detected, and when a person has to look."
        value={sdp}
        savedMessage="Redaction policy saved. New cases use it immediately."
        onSave={async (d) => mutate(await apiClient.updateSensitiveDataPolicy(d), { revalidate: false })}
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
              <p className="cf-ad-hint">Findings below this go to a reviewer{d.reviewMode === 'REVIEW_ALL' ? ' — every case is reviewed regardless' : ''}.</p>
            </div>
            <fieldset className="cf-ad-fieldset">
              <legend>Detected entities <span className="cf-muted">({d.enabledEntityTypes.length})</span></legend>
              <div className="cf-ad-chips">
                {knownEntities.map((t) => {
                  const on = d.enabledEntityTypes.includes(t);
                  return (
                    <button key={t} type="button" aria-pressed={on} className="cf-ad-chip" onClick={() => set({ ...d, enabledEntityTypes: on ? d.enabledEntityTypes.filter((x) => x !== t) : [...d.enabledEntityTypes, t] })}>
                      {entityLabel(t)}
                    </button>
                  );
                })}
              </div>
              {d.enabledEntityTypes.length === 0 && <p className="cf-ad-warn">With no entities enabled, nothing will be redacted.</p>}
            </fieldset>
            <Toggle label="Keep the last four digits of SSNs visible" checked={d.maskSsnLastFour} onChange={(v) => set({ ...d, maskSsnLastFour: v })} />
          </>
        )}
      </SettingsCard>

      <div className="cf-ad-pair">
      <SettingsCard
        title="Retention and approval"
        description={`Originals are purged on schedule. Redacted copies can be kept up to ${lc.maximumRedactedCacheHours} hours.`}
        value={{ originalRetentionHours: lc.originalRetentionHours, redactedCacheHours: lc.redactedCacheHours, neverExportedHoldHours: lc.neverExportedHoldHours, finalApprovalRequired: lc.finalApprovalRequired }}
        savedMessage="Retention policy saved."
        onSave={async (d) => mutate(await apiClient.updateLifecyclePolicy(d), { revalidate: false })}
      >
        {(d, set) => (
          <>
            <div className="cf-ad-grid">
              <NumberField label="Keep originals" suffix="hours" min={1} value={d.originalRetentionHours} onChange={(v) => set({ ...d, originalRetentionHours: v })} />
              <NumberField label="Keep redacted copies" suffix="hours" min={1} max={lc.maximumRedactedCacheHours} value={d.redactedCacheHours} onChange={(v) => set({ ...d, redactedCacheHours: v })} />
              <NumberField label="Hold if never delivered" suffix="hours" min={1} value={d.neverExportedHoldHours} onChange={(v) => set({ ...d, neverExportedHoldHours: v })} />
            </div>
            <Toggle label="Require supervisor approval before delivery" hint="Cases wait in Approval until someone with approval rights signs off." checked={d.finalApprovalRequired} onChange={(v) => set({ ...d, finalApprovalRequired: v })} />
          </>
        )}
      </SettingsCard>

      <SettingsCard
        title="Service targets"
        description="Used by Reports to judge delivery and review performance."
        value={rc}
        savedMessage="Service targets saved."
        onSave={async (d) => mutate(await apiClient.updateReportingConfiguration(d), { revalidate: false })}
      >
        {(d, set) => (
          <>
            <div className="cf-ad-grid">
              <NumberField label="Deliver within" suffix="minutes" min={1} value={d.deliveryTargetMinutes} onChange={(v) => set({ ...d, deliveryTargetMinutes: v })} />
              <NumberField label="For at least" suffix="% of cases" min={1} max={100} value={d.deliveryTargetPercent} onChange={(v) => set({ ...d, deliveryTargetPercent: v })} />
              <NumberField label="Straight-through goal" suffix="%" min={0} max={100} value={d.straightThroughTargetPercent} onChange={(v) => set({ ...d, straightThroughTargetPercent: v })} />
              <NumberField label="Pick up reviews within" suffix="minutes" min={1} value={d.reviewWaitTargetMinutes} onChange={(v) => set({ ...d, reviewWaitTargetMinutes: v })} />
            </div>
            {d.dimensions.length > 0 && (
              <fieldset className="cf-ad-fieldset">
                <legend>Report breakdowns</legend>
                <div className="cf-ad-list">
                  {d.dimensions.map((dim) => (
                    <Toggle key={dim.definitionId} label={dim.label} checked={dim.enabled} onChange={(v) => set({ ...d, dimensions: d.dimensions.map((x) => (x.definitionId === dim.definitionId ? { ...x, enabled: v } : x)) })} />
                  ))}
                </div>
              </fieldset>
            )}
          </>
        )}
      </SettingsCard>
      </div>
    </div>
  );
}

// The policy contract only returns enabled types. Offer the catalog the production admin page
// uses (BusinessAdminPage SENSITIVE_TYPES) plus every type the server has ever reported in this
// browser, so a type turned off and saved can still be turned back on after a reload.
const SENSITIVE_TYPE_LABELS: Record<string, string> = {
  SSN: 'Social Security Number',
  DATE_OF_BIRTH: 'Date of Birth',
  CREDIT_CARD_NUMBER: 'Credit Card Number',
  CREDIT_SCORE: 'Credit Score',
};
export const entityLabel = (t: string) => SENSITIVE_TYPE_LABELS[t] ?? humanize(t);
const SEEN_ENTITY_KEY = 'lexavault.casefile.seenEntityTypes';
const seenEntityTypes = new Set<string>([...Object.keys(SENSITIVE_TYPE_LABELS), ...readSeenEntityTypes()]);
function readSeenEntityTypes(): string[] {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(SEEN_ENTITY_KEY) || '[]');
    return Array.isArray(parsed) ? parsed.filter((t): t is string => typeof t === 'string') : [];
  } catch {
    return [];
  }
}
function rememberEntityTypes(types: string[]) {
  const before = seenEntityTypes.size;
  types.forEach((t) => seenEntityTypes.add(t));
  if (seenEntityTypes.size !== before) {
    try {
      localStorage.setItem(SEEN_ENTITY_KEY, JSON.stringify(Array.from(seenEntityTypes)));
    } catch {
      /* storage unavailable: the in-memory set still covers this session */
    }
  }
  return Array.from(seenEntityTypes);
}
