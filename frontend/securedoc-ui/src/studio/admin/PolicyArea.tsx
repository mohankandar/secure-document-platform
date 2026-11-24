import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react';
import { createPortal } from 'react-dom';
import { apiClient } from '../../api/client';
import type { BusinessAdminConfiguration, BusinessReviewMode, DocumentIntelligenceMode, ReportingConfiguration } from '../../types';
import { errorMessage } from '../data';
import { Notice, Spinner } from '../atoms';
import { useToast } from '../toast';
import { SettingsCard, Toggle, Loading, useBusinessAdmin, humanize, ConfirmDialog, SelectField, DurationField } from './shared';
import { HOUR_UNITS, MINUTE_UNITS, bundleLabel, bundleName, bundleNames, durationText } from './adminRules';
import { ErrorBoundary, checked, isRecord } from '../failure';
import useSWR from 'swr';
import { RETENTION_WORDS, retentionPlaceholder, retentionValueError, type RetentionSettings } from '../exportRules';
import { HelpTip } from './HelpTip';
import { MAX_MINUTES_PER_PAGE, MIN_MINUTES_PER_PAGE, MINUTES_PER_PAGE_ERROR, TIME_SAVED_SETTING_IS_NEW, minutesPerPageError } from '../reports/timeSavedRules';
import { SENSITIVE_TYPES, sensitiveTypeName, SENSITIVE_TYPES_HEADING, CATALOG_WORDS, SEARCHABLE_PAGES_WORDS, VISION_WORDS } from '../words';
import {
  MODE_WORDS, PRESETS, PREVIEW_WORDS, applyPreset, confirmWords, estimateQuery, estimateRunner, needsConfirm, presetOf, previewHeadline,
  previewTiles, reviewChanged, saveLabel, setKeepLastFour, setPack, tunedOff, type ConfirmWords, type EstimateRunner, type PolicyEstimate,
} from './policyRules';

/** The one name for a sensitive type (words.ts), shared with Review, the case panel and Continue manually. */
export const typeName = sensitiveTypeName;

type SensitiveDataPolicy = BusinessAdminConfiguration['sensitiveDataPolicy'];

/** The estimate is used for the confirm step only when it answers for these saved and edited values. */
const estimateKey = (saved: SensitiveDataPolicy, draft: SensitiveDataPolicy) =>
  JSON.stringify([estimateQuery(saved), estimateQuery(draft)]);

/**
 * UI task U023: the "Sensitive data" card with presets, a preview of what changes, and a confirm step when fewer
 * findings would go to a person. Presets set only the review mode and the threshold (policyRules.ts).
 */
function SensitiveDataCard({ data, mutate, entityName }: { data: BusinessAdminConfiguration; mutate: (d: BusinessAdminConfiguration) => unknown; entityName: string }) {
  const sdp = data.sensitiveDataPolicy;
  const savedPreset = presetOf(sdp.reviewMode, sdp.autoRedactThreshold);
  const unsupported = sdp.enabledEntityTypes.filter((t) => !SENSITIVE_TYPES.some((s) => s.key === t));
  const catalog = data.redactionCatalog;
  const locked = catalog ? catalog.kinds.filter((k) => k.locked).map((k) => k.key) : [];
  // Without the catalog (an older server), the two new locked kinds are not offered as switches.
  const listed = SENSITIVE_TYPES.filter((t) => catalog || (t.key !== 'CVV' && t.key !== 'PIN'));
  const radioName = useId();
  const saveButton = useRef<HTMLButtonElement>(null);
  const [runner] = useState(() => estimateRunner((query, signal) => apiClient.sensitiveDataPolicyEstimate(query, signal)));
  useEffect(() => () => runner.drop(), [runner]);
  const [confirm, setConfirm] = useState<{ words: ConfirmWords; save: () => Promise<void>; reset: () => void } | null>(null);
  // Task 0020 A1: Save waits for an estimate that is on its way; meanwhile the card is locked and Save does nothing.
  const [waiting, setWaiting] = useState(false);
  const waitingNow = useRef(false);

  // "Fine-tune by hand" is closed by default and opens by itself when the saved policy is Custom.
  const [fineTune, setFineTune] = useState(savedPreset === 'CUSTOM');
  const [seen, setSeen] = useState(sdp);
  if (seen !== sdp) { setSeen(sdp); if (savedPreset === 'CUSTOM') setFineTune(true); }

  const focusSave = () => requestAnimationFrame(() => saveButton.current?.focus());

  async function requestSave(draft: SensitiveDataPolicy, save: () => Promise<void>, reset: () => void) {
    if (waitingNow.current) return;
    if (!reviewChanged(sdp, draft)) { void save(); return; }
    const key = estimateKey(sdp, draft);
    let result = runner.answer(key);
    if (!result) {
      waitingNow.current = true;
      setWaiting(true);
      try { result = await runner.now(key, estimateQuery(draft)); } finally { waitingNow.current = false; setWaiting(false); }
    }
    // Only an answer for exactly these values counts; if they changed meanwhile, nothing is saved.
    if (result.status === 'dropped') return;
    if (result.status === 'ready' && needsConfirm(result.estimate)) setConfirm({ words: confirmWords(sdp, draft, result.estimate), save, reset });
    else void save();
  }

  return (
    <>
    <SettingsCard
      title="Sensitive data"
      help="sensitiveData"
      description="Which values are found and redacted, and when a person has to look."
      value={sdp}
      savedMessage="Redaction policy saved. New cases use it immediately."
      onSave={async (d) => {
        if (d.enabledEntityTypes.length === 0) throw new Error('Enable at least one sensitive-data category.');
        return mutate(await apiClient.updateSensitiveDataPolicy(d));
      }}
      footer={({ draft, dirty, saving, save, reset }) => {
        const off = !dirty || saving || waiting;
        return (
          <>
            <button type="button" className="cf-btn cf-sp-action" disabled={off} onClick={reset}>Reset</button>
            {/* aria-disabled, not disabled, so focus can come back here after the confirm step. */}
            <button ref={saveButton} type="button" className="cf-btn cf-sp-action" data-variant="solid" aria-disabled={off || undefined}
              aria-busy={waiting || undefined} onClick={() => { if (!off) void requestSave(draft, save, reset); }}>
              {saving && <Spinner label="Saving" />}{waiting ? 'Working out…' : saveLabel(sdp, draft)}
            </button>
          </>
        );
      }}
    >
      {(d, set) => {
        const chosen = presetOf(d.reviewMode, d.autoRedactThreshold);
        return (
        <fieldset className="cf-sp-lock" disabled={waiting}>
          <fieldset className="cf-ad-fieldset" aria-labelledby={`${radioName}-presets`}>
            <legend className="cf-ad-title"><span id={`${radioName}-presets`}>How much should a person check?</span><HelpTip topic="presets" /></legend>
            <div className="cf-sp-presets">
              {PRESETS.map((p) => (
                <label key={p.key} className="cf-sp-preset" data-chosen={chosen === p.key || undefined}>
                  <input type="radio" name={radioName} checked={chosen === p.key} onChange={() => set(applyPreset(d, p.key))} />
                  <span>
                    <b>{p.name}</b>
                    {savedPreset === p.key && <> <span className="cf-sp-inuse">In use</span></>}
                    {' '}<span className="cf-sp-line">{p.line}</span>
                  </span>
                </label>
              ))}
            </div>
            <p className="cf-sp-hint" data-custom={chosen === 'CUSTOM' || undefined}>
              {chosen === 'CUSTOM'
                ? <><b>Custom</b>: these settings match no preset. Below the threshold, a finding always goes to a person; nothing is skipped.</>
                : <>Changing the numbers below by hand shows <b>Custom</b>. Below the threshold, a finding always goes to a person; nothing is skipped.</>}
            </p>
          </fieldset>
          {reviewChanged(sdp, d) && (
            <EstimatePreview runner={runner} estimateKey={estimateKey(sdp, d)} draft={d} entityName={entityName} />
          )}
          <details className="cf-disclosure cf-sp-section" open={fineTune} onToggle={(e) => setFineTune(e.currentTarget.open)}>
            <summary>Fine-tune by hand</summary>
            <div className="cf-sp-fine">
          <fieldset className="cf-ad-fieldset" aria-labelledby={`${radioName}-review`}>
            <legend className="cf-ad-title"><span id={`${radioName}-review`}>Send to review</span><HelpTip topic="fineTune" /></legend>
            <div className="cf-segment" role="radiogroup" aria-label="Review mode">
              {(['UNCERTAIN_ONLY', 'REVIEW_ALL'] as BusinessReviewMode[]).map((m) => (
                <button key={m} type="button" role="radio" aria-checked={d.reviewMode === m} className={d.reviewMode === m ? 'is-active' : ''} onClick={() => set({ ...d, reviewMode: m })}>
                  {MODE_WORDS[m].button}
                </button>
              ))}
            </div>
          </fieldset>
          <div className="cf-ad-threshold">
            <label htmlFor="cf-threshold">Auto-redact when confidence is at least <strong>{Math.round(d.autoRedactThreshold * 100)}%</strong></label>
            <input id="cf-threshold" type="range" min={50} max={99} step={1} value={Math.round(d.autoRedactThreshold * 100)} onChange={(e) => set({ ...d, autoRedactThreshold: Number(e.target.value) / 100 })} />
            <p className="cf-ad-hint">{MODE_WORDS[d.reviewMode].hint}</p>
          </div>
            </div>
          </details>
          <div className="cf-sp-section">
          <h3 className="cf-sp-heading">What is redacted <span>· not changed by presets</span></h3>
          {catalog && (
            <fieldset className="cf-ad-fieldset">
              <legend>{CATALOG_WORDS.packsHeading}</legend>
              <div className="cf-ad-list">
                {catalog.packs.map((pack) => {
                  const on = (d.enabledPacks ?? []).includes(pack.key);
                  const off = on ? tunedOff(d, pack, locked) : [];
                  return (
                    <div key={pack.key}>
                      <Toggle label={pack.name} hint={`${pack.covers} ${CATALOG_WORDS.notCovered} ${pack.notCovered}`} checked={on}
                        onChange={(v) => set(setPack(d, pack, v))} />
                      {off.length > 0 && <p className="cf-ad-hint cf-ad-pack-off">{CATALOG_WORDS.tunedOff} {off.map(typeName).join(', ')}</p>}
                    </div>
                  );
                })}
              </div>
              <p className="cf-ad-hint">{CATALOG_WORDS.packsHint}</p>
            </fieldset>
          )}
          <fieldset className="cf-ad-fieldset">
            <legend>{SENSITIVE_TYPES_HEADING} <span className="cf-muted">({d.enabledEntityTypes.length})</span></legend>
            <div className="cf-ad-chips">
              {[...listed.map((s) => s.key), ...unsupported].map((t) => {
                const on = d.enabledEntityTypes.includes(t);
                if (locked.includes(t)) {
                  // Focusable, so its state can be read; it does nothing.
                  return <button key={t} type="button" aria-pressed={on || d.lockedItemsOn !== false} aria-disabled="true" className="cf-ad-chip" data-locked="">{typeName(t)} ({d.lockedItemsOn === false ? CATALOG_WORDS.lockedSoon : CATALOG_WORDS.locked})</button>;
                }
                const meta = SENSITIVE_TYPES.find((s) => s.key === t);
                return (
                  <button key={t} type="button" aria-pressed={on} className="cf-ad-chip" title={meta?.detects}
                    onClick={() => set({ ...d, enabledEntityTypes: on ? d.enabledEntityTypes.filter((x) => x !== t) : [...d.enabledEntityTypes, t] })}>
                    {typeName(t)}{!meta && ' (not supported)'}
                  </button>
                );
              })}
            </div>
            <dl className="cf-ad-defs">
              {listed.map((s) => <div key={s.key}><dt>{s.name}</dt><dd>{s.detects}</dd></div>)}
            </dl>
            <p className="cf-ad-hint">
              Detection works on single values, not document types. Each finding blacks out only that value, signature or
              photo on the page. A copy of a driver’s license, for example, is not blacked out as a whole: its number is found
              only when Driver’s license number is on, and an SSN or date of birth printed on it is found like any other.
            </p>
            {catalog && <p className="cf-ad-hint">{CATALOG_WORDS.lockedHint}</p>}
            {catalog && d.lockedItemsOn === false && <Notice tone="mark">{CATALOG_WORDS.lockedPending}</Notice>}
            {unsupported.length > 0 && <p className="cf-ad-warn">{unsupported.map(typeName).join(', ')} {unsupported.length === 1 ? 'is' : 'are'} not acted on by the detection engine. Turn {unsupported.length === 1 ? 'it' : 'them'} off to keep the policy accurate.</p>}
            {d.enabledEntityTypes.length === 0 && <p className="cf-ad-warn">Enable at least one category. The policy cannot be saved empty.</p>}
          </fieldset>
          {catalog && (
            <fieldset className="cf-ad-fieldset">
              <legend>{CATALOG_WORDS.styleHeading}</legend>
              <p className="cf-ad-hint">{CATALOG_WORDS.styleHint}</p>
              <div className="cf-ad-grid">
                {catalog.kinds.filter((k) => k.lastFourAllowed && d.enabledEntityTypes.includes(k.key)).map((k) => (
                  <SelectField key={k.key} label={typeName(k.key)} value={(d.keepLastFourKinds ?? []).includes(k.key) ? 'KEEP_LAST_FOUR' : 'REMOVE_FULLY'}
                    options={[['REMOVE_FULLY', CATALOG_WORDS.removeFully], ['KEEP_LAST_FOUR', CATALOG_WORDS.keepLastFour]] as ['REMOVE_FULLY' | 'KEEP_LAST_FOUR', string][]}
                    onChange={(v) => set(setKeepLastFour(d, k.key, v === 'KEEP_LAST_FOUR'))} />
                ))}
              </div>
            </fieldset>
          )}
          <Toggle label="Treat labeled last-four SSNs as sensitive" hint="When on, a last-four value is also redacted, but only when it is explicitly labeled SSN." checked={d.maskSsnLastFour} disabled={!d.enabledEntityTypes.includes('SSN')} onChange={(v) => set({ ...d, maskSsnLastFour: v })} />
          <Toggle label={SEARCHABLE_PAGES_WORDS.label} hint={SEARCHABLE_PAGES_WORDS.hint} checked={d.searchableRedactedPages !== false} onChange={(v) => set({ ...d, searchableRedactedPages: v })} />
          <Toggle label={VISION_WORDS.label} hint={VISION_WORDS.hint} checked={d.visionEnabled !== false} onChange={(v) => set({ ...d, visionEnabled: v })} />
          <div className="cf-ad-threshold">
            <label htmlFor="cf-vision-threshold">{VISION_WORDS.threshold} <strong>{Math.round((d.visionOcrThreshold ?? 0.85) * 100)}%</strong></label>
            <input id="cf-vision-threshold" type="range" min={50} max={99} step={1} value={Math.round((d.visionOcrThreshold ?? 0.85) * 100)} onChange={(e) => set({ ...d, visionOcrThreshold: Number(e.target.value) / 100 })} />
          </div>
          </div>
        </fieldset>
        );
      }}
    </SettingsCard>
    {confirm && (
      <FewerChecksDialog words={confirm.words} anchor={saveButton.current}
        onKeep={() => { confirm.reset(); setConfirm(null); focusSave(); }}
        onSave={() => { const { save } = confirm; setConfirm(null); focusSave(); void save().finally(focusSave); }} />
    )}
    </>
  );
}

/**
 * The preview: what the edited review mode and threshold would have meant over the last 30 days. Asks again about
 * 300 ms after the values stop changing; only the latest answer counts; on failure it says so once, no retry loop.
 */
function EstimatePreview({ runner, estimateKey: key, draft, entityName }: {
  runner: EstimateRunner; estimateKey: string; draft: SensitiveDataPolicy; entityName: string;
}) {
  const query = JSON.stringify(estimateQuery(draft));
  const [answer, setAnswer] = useState<{ status: 'loading' | 'ready' | 'failed'; estimate?: PolicyEstimate }>({ status: 'loading' });

  // The card's runner asks (only the latest values count); this box only shows what it answers for this key.
  useEffect(() => {
    setAnswer((a) => ({ status: 'loading', estimate: a.estimate }));
    const off = runner.subscribe((k, r) => {
      if (k !== key) return;
      if (r.status === 'ready') setAnswer({ status: 'ready', estimate: r.estimate });
      else if (r.status === 'failed') setAnswer({ status: 'failed' });
    });
    runner.request(key, JSON.parse(query));
    return () => { off(); runner.drop(key); };
  }, [runner, key, query]);

  const loading = answer.status === 'loading';
  const shown = answer.estimate;
  return (
    <div className="cf-sp-preview" role="status" aria-busy={loading || undefined} data-state={answer.status}>
      {answer.status === 'failed' && <p className="cf-sp-preview-text">{PREVIEW_WORDS.failed}</p>}
      {loading && <p className="cf-sp-preview-text cf-sp-preview-loading">{PREVIEW_WORDS.loading}</p>}
      {/* While loading, the last answer stays in place unseen, so the box keeps its height. */}
      {shown && answer.status !== 'failed' && (
        <div className="cf-sp-preview-body" aria-hidden={loading || undefined}>
          <EstimateBody draft={draft} estimate={shown} entityName={entityName} />
        </div>
      )}
    </div>
  );
}

function EstimateBody({ draft, estimate, entityName }: { draft: SensitiveDataPolicy; estimate: PolicyEstimate; entityName: string }) {
  const tiles = previewTiles(estimate);
  return (
    <>
      <p className="cf-sp-preview-head">{previewHeadline(draft, estimate, entityName)}</p>
      {tiles ? (
        <>
          <div className="cf-sp-tiles">
            {tiles.map((t) => (
              <div key={t.label} className="cf-sp-tile">
                <div className="cf-sp-tile-label">{t.label}</div>
                <div className="cf-sp-tile-nums">
                  <span className="cf-sp-now">{t.current}</span>
                  <span aria-hidden="true">→</span><span className="sr-only">to</span>
                  <b className="cf-sp-next">{t.proposed}</b>
                  <span className="cf-sp-change" data-direction={t.change.direction}>{t.change.text}</span>
                </div>
              </div>
            ))}
          </div>
          <p className="cf-sp-preview-note">{PREVIEW_WORDS.note}</p>
        </>
      ) : <p className="cf-sp-preview-text">{PREVIEW_WORDS.notEnough}</p>}
    </>
  );
}

const DIALOG_FOCUSABLE = 'button:not([disabled]), [href], input:not([disabled]), [tabindex]:not([tabindex="-1"])';

/** The confirm step when fewer findings would go to a person. "Keep" is the main button; Esc keeps. */
function FewerChecksDialog({ words, anchor, onKeep, onSave }: { words: ConfirmWords; anchor: HTMLElement | null; onKeep: () => void; onSave: () => void }) {
  const id = useId();
  const dialog = useRef<HTMLElement>(null);
  const keep = useRef<HTMLButtonElement>(null);
  useEffect(() => { keep.current?.focus(); }, []);

  // Wherever focus is while the dialog is open, Esc and Tab belong to the dialog.
  const keepRef = useRef(onKeep);
  keepRef.current = onKeep;
  useEffect(() => {
    const onDocumentKey = (e: KeyboardEvent) => {
      if (!dialog.current || dialog.current.contains(document.activeElement)) return;
      if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); keepRef.current(); }
      else if (e.key === 'Tab') { e.preventDefault(); keep.current?.focus(); }
    };
    document.addEventListener('keydown', onDocumentKey, true);
    return () => document.removeEventListener('keydown', onDocumentKey, true);
  }, []);

  function onKeyDown(e: ReactKeyboardEvent<HTMLElement>) {
    if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); onKeep(); return; }
    if (e.key !== 'Tab' || !dialog.current) return;
    const items = [...dialog.current.querySelectorAll<HTMLElement>(DIALOG_FOCUSABLE)];
    const first = items[0];
    const last = items[items.length - 1];
    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
  }

  // Drawn at the Case File root, where the colour tokens live, above every page section.
  const host = (anchor?.closest('.cf') as HTMLElement | null) ?? document.body;
  return createPortal(
    <div className="cf-sp-scrim" onMouseDown={(e) => { if (e.target === e.currentTarget) e.preventDefault(); }}>
      <section ref={dialog} className="cf-sp-dialog" role="alertdialog" aria-modal="true" aria-labelledby={`${id}-title`} aria-describedby={`${id}-desc`} tabIndex={-1} onKeyDown={onKeyDown}>
        <h2 id={`${id}-title`}>A person would check fewer findings</h2>
        <p id={`${id}-desc`}>
          With <b>{words.newPhrase}</b>, about <b className="cf-sp-num">{words.fewerFindings}</b> fewer findings
          {words.fewerCases && <> and <b className="cf-sp-num">{words.fewerCases}</b> fewer cases</>} in the last {words.days} days would have had a person check them.
        </p>
        <div className="cf-sp-dialog-box">Those findings are still redacted, automatically, without a person looking first. Findings below {words.threshold} confidence still go to a person.</div>
        <p className="cf-sp-dialog-note">{words.auditLine}</p>
        <div className="cf-sp-dialog-foot">
          <button ref={keep} type="button" className="cf-btn cf-sp-action" data-variant="solid" onClick={onKeep}>{words.keepLabel}</button>
          <button type="button" className="cf-btn cf-sp-action" onClick={onSave}>{words.saveLabel}</button>
        </div>
      </section>
    </div>,
    host,
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
        help="documentIntelligence"
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
      help="keepingAndDeleting"
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
            <DurationField label="Keep originals for" help="keepOriginals" units={HOUR_UNITS} fixedUnit="hours" min={1} value={d.originalRetentionHours}
              onChange={(v) => set({ ...d, originalRetentionHours: v })} hint="After this, a case can't be processed again." />
            <DurationField label="Keep redacted copies for" help="keepRedacted" units={HOUR_UNITS} min={1} max={lc.maximumRedactedCacheHours} value={d.redactedCacheHours}
              onChange={(v) => set({ ...d, redactedCacheHours: v })} hint={`Up to ${durationText(lc.maximumRedactedCacheHours, HOUR_UNITS)}.`} />
            <DurationField label="Keep stopped cases for" help="keepStopped" units={HOUR_UNITS} min={1} value={d.neverExportedHoldHours}
              onChange={(v) => set({ ...d, neverExportedHoldHours: v })} hint="Time to fix and finish a stopped case before it is deleted." />
          </div>
          <Toggle help="requireApproval" label="Require a supervisor's approval before delivery" hint="Cases wait in Approval until a supervisor signs off." checked={d.finalApprovalRequired} onChange={(v) => set({ ...d, finalApprovalRequired: v })} />
          <p className="cf-ad-hint">A case can be processed again at most {lc.maximumSuccessfulExportCycles} {lc.maximumSuccessfulExportCycles === 1 ? 'time' : 'times'} (set by LexaVault).</p>
        </>
      )}
    </SettingsCard>
  );
}

interface RetentionDraft { masterHours: number | null; splits: Array<{ destinationId: string; name: string; hours: number | null; effective: number }> }

/**
 * Task 0029: the retention period per export destination (GET/PUT /business-admin/export-retention), next to
 * "Keeping and deleting". Empty means the default, shown as "Same as …". The API checks the range (LV-CFG-001) and its
 * message is shown as it is; the field marks a value out of range as it is typed.
 */
function ExportRetentionCard() {
  const { data, error, mutate } = useSWR<RetentionSettings>('export-retention',
    () => checked('GET /business-admin/export-retention', apiClient.getExportRetention(),
      (v) => isRecord(v) && isRecord(v.master) && Array.isArray(v.splitDestinations) && typeof v.minHours === 'number' && typeof v.maxHours === 'number'),
    { revalidateOnFocus: false });
  // SettingsCard starts again from `value` whenever it is a new object: one object per answer.
  const value = useMemo<RetentionDraft | null>(() => (data ? {
    masterHours: data.master.setHours ?? null,
    splits: data.splitDestinations.filter((s) => s.destinationId).map((s) => ({ destinationId: s.destinationId as string, name: s.name ?? 'Split destination', hours: s.setHours ?? null, effective: s.hours })),
  } : null), [data]);
  if (!data || !value) return <Loading error={error} retry={() => mutate()} label="Keeping exported copies" request="GET /business-admin/export-retention" />;
  const masterEffective = value.masterHours ?? data.masterDefaultHours;
  return (
    <SettingsCard
      title={RETENTION_WORDS.title}
      description={RETENTION_WORDS.description}
      value={value}
      savedMessage={RETENTION_WORDS.saved}
      onSave={async (d) => mutate(await apiClient.updateExportRetention({
        masterHours: d.masterHours,
        splitDestinations: d.splits.map((s) => ({ destinationId: s.destinationId, hours: s.hours })),
      }), { revalidate: false })}
    >
      {(d, set) => (
        <div className="cf-ad-grid" data-testid="export-retention">
          <HoursField label={`${RETENTION_WORDS.master}${data.master.name ? ` (${data.master.name})` : ''}`} value={d.masterHours} min={data.minHours} max={data.maxHours}
            defaultHours={data.masterDefaultHours} defaultText={retentionPlaceholder('master', data.masterDefaultHours)} onChange={(v) => set({ ...d, masterHours: v })} />
          {d.splits.map((s, i) => (
            <HoursField key={s.destinationId} label={s.name} value={s.hours} min={data.minHours} max={data.maxHours}
              defaultHours={d.masterHours ?? masterEffective} defaultText={retentionPlaceholder('split', d.masterHours ?? masterEffective)}
              onChange={(v) => set({ ...d, splits: d.splits.map((x, j) => (j === i ? { ...x, hours: v } : x)) })} />
          ))}
        </div>
      )}
    </SettingsCard>
  );
}

/** Empty is the default: the box shows its hours greyed and the line under it says "Same as …". */
function HoursField({ label, value, min, max, defaultHours, defaultText, onChange }: {
  label: string; value: number | null; min: number; max: number; defaultHours: number; defaultText: string; onChange: (v: number | null) => void;
}) {
  const id = useId();
  const problem = retentionValueError(value, min, max);
  const hint = problem ?? (value === null ? `${defaultText}. ${min} to ${max} hours.` : `${min} to ${max} hours. Leave empty for the default.`);
  return (
    <div className="cf-field cf-ad-number" data-invalid={problem ? true : undefined}>
      <label htmlFor={id}><span>{label}</span></label>
      <span className="cf-ad-duration">
        <input id={id} type="number" inputMode="numeric" step={1} value={value ?? ''} placeholder={String(defaultHours)}
          aria-invalid={problem ? true : undefined} aria-describedby={`${id}-hint`}
          onChange={(e) => onChange(e.target.value === '' ? null : e.target.valueAsNumber)} />
        <span className="cf-ad-duration-unit">hours</span>
      </span>
      <span id={`${id}-hint`} className="cf-field-hint">{hint}</span>
    </div>
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
  const errorId = useId();
  const invalid = !Number.isFinite(value) || value < min || value > 100;
  return (
    <>
      <span className="cf-ad-duration">
        <input type="number" inputMode="decimal" min={min} max={100} step={1} aria-label={label} aria-invalid={invalid || undefined} aria-describedby={invalid ? errorId : undefined}
          value={Number.isNaN(value) ? '' : value} onChange={(e) => onChange(e.target.valueAsNumber)} />
        <span className="cf-ad-duration-unit">%</span>
      </span>
      {invalid && <span id={errorId} className="cf-ad-sentence-error">{label}: enter a number from {min} to 100.</span>}
    </>
  );
}

/** U024 canvas "Report targets card with minutes per page": the minutes a person takes to check one page by hand. */
function MinutesPerPageField({ value, onChange }: { value: number | undefined; onChange: (v: number) => void }) {
  const errorId = useId();
  const error = value === undefined ? null : minutesPerPageError(value);
  return (
    <div className="cf-ad-timesaved">
      <div className="cf-ad-timesaved-title">
        Time saved estimate{TIME_SAVED_SETTING_IS_NEW && <span className="cf-ad-new-pill">New</span>}<HelpTip topic="minutesPerPage" />
      </div>
      <label className="cf-ad-timesaved-field">
        A person takes{' '}
        <input type="number" inputMode="decimal" min={MIN_MINUTES_PER_PAGE} max={MAX_MINUTES_PER_PAGE} step={0.5} className="cf-ad-minutes"
          value={value === undefined || Number.isNaN(value) ? '' : value} aria-invalid={error ? true : undefined}
          aria-describedby={error ? errorId : undefined} onChange={(e) => onChange(e.target.valueAsNumber)} />
        {' '}minutes to check one page by hand
      </label>
      {error && <p id={errorId} className="cf-ad-sentence-error">{error}</p>}
      <p className="cf-ad-hint">Used for "Review time saved" on Reports and in the compliance report. Default 1 minute: deliberately low, so the estimate never overclaims. Set it from your own team's experience; changing it is recorded in the audit trail.</p>
    </div>
  );
}

function ReportingCard({ data, mutate }: { data: BusinessAdminConfiguration; mutate: (d: BusinessAdminConfiguration) => unknown }) {
  const breakdownId = useId();
  const rc = data.reportingConfiguration;
  const value = { ...rc, dimensions: eligibleDimensions(data, rc) };
  return (
    <SettingsCard
      title="Report targets"
      help="reportTargets"
      description="What Reports measures against."
      value={value}
      savedMessage="Report targets saved."
      invalid={(d) => d.minutesPerPage !== undefined && minutesPerPageError(d.minutesPerPage) !== null}
      onSave={async (d) => {
        if (d.minutesPerPage !== undefined && minutesPerPageError(d.minutesPerPage)) throw new Error(MINUTES_PER_PAGE_ERROR);
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
          <MinutesPerPageField value={d.minutesPerPage} onChange={(v) => set({ ...d, minutesPerPage: v })} />
          <fieldset className="cf-ad-fieldset" aria-labelledby={breakdownId}>
            <legend className="cf-ad-title"><span id={breakdownId}>Break reports down by</span><HelpTip topic="reportBreakdown" /></legend>
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

export default function PolicyArea({ entityName }: { entityName: string }) {
  const { data, error, mutate } = useBusinessAdmin();
  if (!data) return <Loading error={error} retry={() => mutate()} label="Policy" />;
  const apply = (next: BusinessAdminConfiguration) => mutate(next, { revalidate: false });

  return (
    <div className="cf-ad-stack">
      <ErrorBoundary title="Sensitive data settings couldn't be shown" request="GET /business-admin/configuration"><SensitiveDataCard data={data} mutate={apply} entityName={entityName} /></ErrorBoundary>
      <ErrorBoundary title="Document intelligence settings couldn't be shown" request="GET /business-admin/configuration"><IntelligenceCard data={data} mutate={apply} /></ErrorBoundary>
      <div className="cf-ad-pair">
        <ErrorBoundary title="Keeping and deleting couldn't be shown" request="GET /business-admin/configuration"><LifecycleCard data={data} mutate={apply} /></ErrorBoundary>
        <ErrorBoundary title="Report targets couldn't be shown" request="GET /business-admin/configuration"><ReportingCard data={data} mutate={apply} /></ErrorBoundary>
      </div>
      <ErrorBoundary title="Keeping exported copies couldn't be shown" request="GET /business-admin/export-retention"><ExportRetentionCard /></ErrorBoundary>
    </div>
  );
}
