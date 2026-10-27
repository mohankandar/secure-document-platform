import { useState } from 'react';
import useSWR from 'swr';
import { apiClient } from '../../api/client';
import type { RoutingDestinationOption, RoutingMarkerType, RoutingRule, RoutingRuleRequest, RoutingScanRegion } from '../../types';
import { errorMessage } from '../data';
import { Notice, Spinner } from '../atoms';
import { useToast } from '../toast';
import { ConfirmDialog, Loading, SelectField, Sheet, TextField, Toggle, humanize } from './shared';

type Draft = RoutingRuleRequest & { ruleId?: string };

const MARKERS: [RoutingMarkerType, string][] = [['TEXT_PATTERN', 'Text pattern'], ['BARCODE', 'Barcode'], ['QR_CODE', 'QR code']];
const REGIONS: [RoutingScanRegion, string][] = [['TOP_RIGHT', 'Top right'], ['BOTTOM_LEFT', 'Bottom left'], ['BOTH', 'Both']];

const emptyDraft = (): Draft => ({ destinationLabel: '', markerType: 'TEXT_PATTERN', markerValue: '', scanRegion: 'TOP_RIGHT', destinationConnectionId: '', enabled: true });

/**
 * Derived-document routing rules under Tenant IT → Integrations:
 * deterministic markers map pages to an approved derived-delivery destination.
 */
export default function RoutingRules() {
  const toast = useToast();
  const rules = useSWR<RoutingRule[]>('routing-rules', () => apiClient.listRoutingRules(), { revalidateOnFocus: false });
  const destinations = useSWR<RoutingDestinationOption[]>('routing-destinations', () => apiClient.listRoutingDestinations(), { revalidateOnFocus: false });
  const [draft, setDraft] = useState<Draft | null>(null);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [removing, setRemoving] = useState<RoutingRule | null>(null);
  const [removeBusy, setRemoveBusy] = useState(false);
  const [removeError, setRemoveError] = useState<string | null>(null);

  if (!rules.data || !destinations.data) {
    return <Loading error={rules.error || destinations.error} retry={() => { rules.mutate(); destinations.mutate(); }} label="Routing rules" />;
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
      setFormError('Destination label, marker value and destination connection are required.');
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
      toast('Routing rule saved. Derived routing will use the approved redacted-document destination.');
    } catch (e) {
      setFormError(errorMessage(e, 'The routing rule could not be saved.'));
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
      toast('Routing rule removed.');
    } catch (e) {
      setRemoveError(errorMessage(e, 'The routing rule could not be removed.'));
    } finally {
      setRemoveBusy(false);
    }
  }

  const currentUnavailable = !!draft?.destinationConnectionId && !enabledDests.some((d) => d.connectionId === draft.destinationConnectionId);
  const destOptions: [string, string][] = [
    ['', enabledDests.length ? 'Select destination…' : 'No enabled derived destinations'],
    ...enabledDests.map((d) => [d.connectionId, `${d.name} · ${humanize(d.providerType)}`] as [string, string]),
    ...(currentUnavailable && draft ? [[draft.destinationConnectionId, `${destName(draft.destinationConnectionId) ?? 'Unknown'} (unavailable)`] as [string, string]] : []),
  ];

  return (
    <section className="cf-ad-card" aria-label="Derived document routing">
      <header className="cf-ad-card-head">
        <div>
          <h2>Derived document routing</h2>
          <p>Map deterministic markers to destinations configured under Integrations. The full redacted master stays intact and is delivered independently.</p>
        </div>
        <button type="button" className="cf-btn" data-size="sm" onClick={() => open()}>Add rule</button>
      </header>
      {rules.data.length === 0 ? (
        <div className="cf-ad-card-body"><p className="cf-ad-hint">No derived routing rules configured.</p></div>
      ) : (
        <ul className="cf-ad-conns">
          {rules.data.map((r) => {
            const dest = dests.find((d) => d.connectionId === r.destinationConnectionId);
            return (
              <li key={r.ruleId} className="cf-ad-conn">
                <span className="cf-ad-health" data-h={!r.enabled ? 'off' : dest && dest.enabled ? 'ok' : 'warn'} aria-hidden="true" />
                <div className="cf-ad-conn-main">
                  <div className="cf-ad-conn-name">
                    {r.destinationLabel}
                    <span className="cf-ad-tag">{humanize(r.markerType)}</span>
                    {!r.enabled && <span className="cf-ad-tag">Disabled</span>}
                  </div>
                  <div className="cf-ad-conn-meta">
                    <span className="cf-mono">{r.markerValue}</span>
                    <span>Scan {humanize(r.scanRegion).toLowerCase()}</span>
                    <span>To {dest?.name ?? 'unknown destination'}</span>
                    {dest && !dest.enabled && <span className="cf-danger-text">Destination disabled</span>}
                    {!dest && <span className="cf-danger-text">Destination not found</span>}
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

      <Sheet open={!!draft} title={draft?.ruleId ? 'Edit routing rule' : 'New routing rule'}
        description="Only enabled rules are scanned during derived routing."
        onClose={() => { if (!saving) setDraft(null); }}
        footer={<>
          <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={saving} onClick={() => setDraft(null)}>Cancel</button>
          <button type="button" className="cf-btn" data-variant="solid" data-size="sm" disabled={saving} onClick={save}>{saving && <Spinner label="Saving" />}{draft?.ruleId ? 'Update rule' : 'Add rule'}</button>
        </>}
      >
        {draft && (
          <div className="cf-ad-form">
            <TextField label="Destination label" required value={draft.destinationLabel} placeholder="South Carolina" onChange={(v) => set('destinationLabel', v)} />
            <SelectField label="Marker type" value={draft.markerType} options={MARKERS} onChange={(v) => set('markerType', v)} />
            <TextField label="Marker value" required mono value={draft.markerValue} placeholder="77777777" onChange={(v) => set('markerValue', v)}
              hint={draft.markerType === 'TEXT_PATTERN' ? 'Text markers are normalized, so decorative bars and spaces do not need to match exactly.' : undefined} />
            <SelectField label="Fixed scan region" value={draft.scanRegion} options={REGIONS} onChange={(v) => set('scanRegion', v)} />
            <SelectField label="Approved destination" value={draft.destinationConnectionId} options={destOptions}
              onChange={(v) => set('destinationConnectionId', v)}
              hint={currentUnavailable ? 'The current destination is disabled or missing. Choose an enabled destination before saving.' : 'Technical endpoint and authentication settings stay under Integrations.'} />
            <Toggle label="Enabled" checked={draft.enabled} onChange={(v) => set('enabled', v)} hint="Only enabled rules are scanned during derived routing." />
            {formError && <Notice tone="danger">{formError}</Notice>}
          </div>
        )}
      </Sheet>

      <ConfirmDialog open={!!removing} title="Remove routing rule?" danger confirmLabel="Remove rule" busy={removeBusy} error={removeError}
        facts={removing ? [['Destination label', removing.destinationLabel], ['Marker', `${humanize(removing.markerType)} · ${removing.markerValue}`], ['Destination', destName(removing.destinationConnectionId) ?? 'Unknown']] : []}
        consequence="Pages carrying this marker will no longer be routed to this destination. Deliveries already made are not affected."
        onConfirm={remove} onCancel={() => setRemoving(null)} />
    </section>
  );
}
