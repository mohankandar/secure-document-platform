import { useState } from 'react';
import useSWR from 'swr';
import { apiClient } from '../../api/client';
import type { RoutingDestinationOption, RoutingMarkerType, RoutingRule, RoutingRuleRequest, RoutingScanRegion } from '../../types';
import { errorMessage } from '../data';
import { Notice, Spinner } from '../atoms';
import { useToast } from '../toast';
import { ConfirmDialog, Loading, SelectField, Sheet, TextField, Toggle } from './shared';
import { ruleSentence } from './integrationRules';
import { useUnsavedWork } from '../unsavedWork';
import { HelpTip } from './HelpTip';

type Draft = RoutingRuleRequest & { ruleId?: string };

const MARKERS: [RoutingMarkerType, string][] = [['BARCODE', 'A barcode'], ['QR_CODE', 'A QR code'], ['TEXT_PATTERN', 'Text']];
const REGIONS: [RoutingScanRegion, string][] = [['TOP_RIGHT', 'Top right'], ['BOTTOM_LEFT', 'Bottom left'], ['BOTH', 'Top right or bottom left']];

const emptyDraft = (): Draft => ({ destinationLabel: '', markerType: 'TEXT_PATTERN', markerValue: '', scanRegion: 'TOP_RIGHT', destinationConnectionId: '', enabled: true });

/**
 * Sending by barcode: each rule reads as a sentence ("Title pages: barcode “TITLE-001”, top right of the page →
 * Title team folder") and sends matching pages to one of the barcode connections listed above it.
 */
export default function RoutingRules() {
  const toast = useToast();
  const rules = useSWR<RoutingRule[]>('routing-rules', () => apiClient.listRoutingRules(), { revalidateOnFocus: false });
  const destinations = useSWR<RoutingDestinationOption[]>('routing-destinations', () => apiClient.listRoutingDestinations(), { revalidateOnFocus: false });
  const [draft, setDraft] = useState<Draft | null>(null);
  useUnsavedWork(draft !== null);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [removing, setRemoving] = useState<RoutingRule | null>(null);
  const [removeBusy, setRemoveBusy] = useState(false);
  const [removeError, setRemoveError] = useState<string | null>(null);

  if (!rules.data || !destinations.data) {
    return <Loading error={rules.error || destinations.error} retry={() => { rules.mutate(); destinations.mutate(); }} label="Rules" />;
  }
  const dests = destinations.data;
  const enabledDests = dests.filter((d) => d.enabled);
  const destName = (id: string) => dests.find((d) => d.connectionId === id)?.name;

  const open = (rule?: RoutingRule) => {
    setFormError(null);
    void destinations.mutate();
    setDraft(rule
      ? { ruleId: rule.ruleId, destinationLabel: rule.destinationLabel, markerType: rule.markerType, markerValue: rule.markerValue, scanRegion: rule.scanRegion, destinationConnectionId: rule.destinationConnectionId, enabled: rule.enabled }
      : emptyDraft());
  };
  const set = <K extends keyof Draft>(k: K, v: Draft[K]) => setDraft((d) => (d ? { ...d, [k]: v } : d));

  async function save() {
    if (!draft) return;
    if (!draft.destinationLabel.trim() || !draft.markerValue.trim() || !draft.destinationConnectionId) {
      setFormError('Give the rule a name, what to look for, and where to send it.');
      return;
    }
    setSaving(true);
    setFormError(null);
    try {
      const { ruleId, ...rest } = draft;
      const body: RoutingRuleRequest = { ...rest, destinationLabel: rest.destinationLabel.trim(), markerValue: rest.markerValue.trim() };
      if (ruleId) await apiClient.updateRoutingRule(ruleId, body);
      else await apiClient.createRoutingRule(body);
      await Promise.all([rules.mutate(), destinations.mutate()]);
      setDraft(null);
      toast('Rule saved.');
    } catch (e) {
      setFormError(errorMessage(e, "The rule couldn't be saved."));
    } finally {
      setSaving(false);
    }
  }

  async function remove() {
    if (!removing) return;
    setRemoveBusy(true);
    setRemoveError(null);
    try {
      await apiClient.deleteRoutingRule(removing.ruleId);
      await rules.mutate();
      if (draft?.ruleId === removing.ruleId) setDraft(null);
      setRemoving(null);
      toast('Rule removed.');
    } catch (e) {
      setRemoveError(errorMessage(e, "The rule couldn't be removed."));
    } finally {
      setRemoveBusy(false);
    }
  }

  const currentUnavailable = !!draft?.destinationConnectionId && !enabledDests.some((d) => d.connectionId === draft.destinationConnectionId);
  const placeKind = (d: RoutingDestinationOption) => (d.providerType === 'FILE_SHARE' ? 'File share' : 'Web address');
  const destOptions: [string, string][] = [
    ['', enabledDests.length ? 'Choose a place…' : 'No place is switched on yet'],
    ...enabledDests.map((d) => [d.connectionId, `${d.name} · ${placeKind(d)}`] as [string, string]),
    ...(currentUnavailable && draft ? [[draft.destinationConnectionId, `${destName(draft.destinationConnectionId) ?? 'A place that no longer exists'} (switched off or gone)`] as [string, string]] : []),
  ];

  return (
    <div className="cf-ad-block" role="region" aria-label="Rules">
      <div className="cf-ad-card-head">
        <div className="cf-ad-title"><h3 className="cf-ad-block-title">Rules</h3><HelpTip topic="routingRules" /></div>
        <button type="button" className="cf-btn" data-size="sm" onClick={() => open()}>Add rule</button>
      </div>
      {rules.data.length === 0 ? (
        <p className="cf-ad-hint">No rules yet. A rule sends pages with a barcode or text marker to one of the places above.</p>
      ) : (
        <ul className="cf-ad-conns">
          {rules.data.map((r) => {
            const dest = dests.find((d) => d.connectionId === r.destinationConnectionId);
            return (
              <li key={r.ruleId} className="cf-ad-conn">
                <span className="cf-ad-health" data-h={!r.enabled ? 'off' : dest && dest.enabled ? 'ok' : 'warn'} aria-hidden="true" />
                <div className="cf-ad-conn-main">
                  <div className="cf-ad-rule-sentence">
                    {ruleSentence(r, dest?.name)}
                    {!r.enabled && <span className="cf-ad-warn"> · rule switched off</span>}
                    {r.enabled && dest && !dest.enabled && <span className="cf-ad-warn"> · destination switched off</span>}
                  </div>
                </div>
                <div className="cf-ad-conn-actions">
                  <button type="button" className="cf-btn" data-size="sm" onClick={() => open(r)}>Edit</button>
                  <button type="button" className="cf-btn" data-size="sm" data-variant="ghost" onClick={() => { setRemoveError(null); setRemoving(r); }}>Remove</button>
                </div>
              </li>
            );
          })}
        </ul>
      )}

      <Sheet open={!!draft} title={draft?.ruleId ? 'Edit rule' : 'Add rule'}
        description="Pages with this barcode or text are also sent, as a separate file, to the place you choose."
        onClose={() => { if (!saving) setDraft(null); }}
        footer={<>
          <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={saving} onClick={() => setDraft(null)}>Cancel</button>
          <button type="button" className="cf-btn" data-variant="solid" data-size="sm" disabled={saving} onClick={save}>{saving && <Spinner label="Saving" />}Save rule</button>
        </>}
      >
        {draft && (
          <div className="cf-ad-form">
            <TextField label="Rule name" required value={draft.destinationLabel} placeholder="Title pages" onChange={(v) => set('destinationLabel', v)} hint="Shown in the rule sentence." />
            <SelectField label="Look for" value={draft.markerType} options={MARKERS} onChange={(v) => set('markerType', v)} />
            <TextField label={draft.markerType === 'TEXT_PATTERN' ? 'Text to find' : 'Value to match'} required mono value={draft.markerValue} placeholder="TITLE-001" onChange={(v) => set('markerValue', v)}
              hint={draft.markerType === 'TEXT_PATTERN' ? 'Found anywhere in the area; spaces and decorative bars do not need to match.' : 'The code must match this value exactly.'} />
            <SelectField label="Where on the page" value={draft.scanRegion} options={REGIONS} onChange={(v) => set('scanRegion', v)} />
            <SelectField label="Send to" value={draft.destinationConnectionId} options={destOptions}
              onChange={(v) => set('destinationConnectionId', v)}
              hint={currentUnavailable ? 'This place is switched off or gone. Choose one that is switched on before saving.' : 'The places are the connections listed above.'} />
            <Toggle label="Switched on" checked={draft.enabled} onChange={(v) => set('enabled', v)} hint="Only rules that are switched on are used." />
            {formError && <Notice tone="danger">{formError}</Notice>}
          </div>
        )}
      </Sheet>

      <ConfirmDialog open={!!removing} title="Remove this rule?" danger confirmLabel="Remove rule" busy={removeBusy} error={removeError}
        facts={removing ? [['Rule', ruleSentence(removing, destName(removing.destinationConnectionId))]] : []}
        consequence="Pages with this marker are no longer sent to this place. Files already sent are not affected."
        onConfirm={remove} onCancel={() => setRemoving(null)} />
    </div>
  );
}
