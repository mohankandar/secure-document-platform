import { useState } from 'react';
import { apiClient } from '../../api/client';
import type { BusinessAdminIdentifierDraft, BusinessIdentifierValidationType } from '../../types';
import { Notice } from '../atoms';
import { SettingsCard, Toggle, Loading, useBusinessAdmin, Sheet, TextField, SelectField, OptionalInt } from './shared';

const VALIDATION_TYPES: [BusinessIdentifierValidationType, string][] = [
  ['TEXT', 'Text'], ['NUMERIC', 'Numeric'], ['ALPHANUMERIC', 'Alphanumeric'], ['EMAIL', 'Email'],
  ['UUID', 'UUID'], ['ALLOW_LIST', 'Allowed values'], ['CUSTOM_PATTERN', 'Custom pattern'],
];

const blankIdentifier = (): BusinessAdminIdentifierDraft => ({
  label: '', required: false, primary: false, searchable: true, extractable: false, displayable: true,
  validationType: 'TEXT', exactLength: null, minLength: null, maxLength: null,
  caseNormalization: 'PRESERVE', stripSeparators: false, allowedValues: [], customPattern: null,
});

/** Mirrors BusinessAdminPage's client-side rules; the server remains authoritative. */
export function identifierIssue(list: BusinessAdminIdentifierDraft[]): string | null {
  if (list.length === 0) return 'At least one identifier is required.';
  if (list.some((i) => !i.label.trim())) return 'Every identifier needs a label.';
  if (list.filter((i) => i.primary).length !== 1) return 'Choose exactly one Primary identifier.';
  const labels = list.map((i) => i.label.trim().toLowerCase());
  if (new Set(labels).size !== labels.length) return 'Identifier labels must be unique.';
  for (const i of list) {
    const name = i.label || 'Identifier';
    const lengths = [i.exactLength, i.minLength, i.maxLength].filter((v): v is number => v != null);
    if (lengths.some((v) => !Number.isInteger(v) || v < 1 || v > 512)) return `${name}: lengths must be whole numbers from 1 to 512.`;
    if (i.exactLength != null && (i.minLength != null || i.maxLength != null)) return `${name}: exact length cannot be combined with min/max length.`;
    if (i.minLength != null && i.maxLength != null && i.minLength > i.maxLength) return `${name}: minimum length cannot exceed maximum length.`;
    if (i.validationType === 'ALLOW_LIST' && i.allowedValues.filter((v) => v.trim()).length === 0) return `${name}: at least one allowed value is required.`;
    if (i.validationType === 'CUSTOM_PATTERN' && !i.customPattern?.trim()) return `${name}: custom pattern is required.`;
  }
  return null;
}

const toRequest = (list: BusinessAdminIdentifierDraft[]) => list.map((i) => ({
  ...i,
  label: i.label.trim(),
  allowedValues: i.allowedValues.map((v) => v.trim()).filter(Boolean),
  customPattern: i.validationType === 'CUSTOM_PATTERN' ? i.customPattern?.trim() || null : null,
}));

function rule(i: BusinessAdminIdentifierDraft) {
  const parts = [VALIDATION_TYPES.find(([v]) => v === i.validationType)?.[1] ?? i.validationType];
  if (i.exactLength != null) parts.push(`${i.exactLength} chars`);
  else if (i.minLength != null || i.maxLength != null) parts.push(`${i.minLength ?? 1}–${i.maxLength ?? '∞'} chars`);
  if (i.validationType === 'ALLOW_LIST') parts.push(`${i.allowedValues.filter(Boolean).length} values`);
  if (i.caseNormalization !== 'PRESERVE') parts.push(i.caseNormalization === 'UPPER' ? 'Uppercased' : 'Lowercased');
  if (i.stripSeparators) parts.push('Separators stripped');
  return parts.join(' · ');
}

function IdentifierEditor({ value, isNew, canRemove, onApply, onRemove, onClose }: {
  value: BusinessAdminIdentifierDraft; isNew: boolean; canRemove: boolean;
  onApply: (v: BusinessAdminIdentifierDraft) => string | null; onRemove: () => void; onClose: () => void;
}) {
  const [d, setD] = useState(value);
  const [issue, setIssue] = useState<string | null>(null);
  const set = (p: Partial<BusinessAdminIdentifierDraft>) => setD({ ...d, ...p });
  const exact = d.exactLength != null;

  return (
    <Sheet open wide title={isNew ? 'Add identifier' : `Edit ${value.label}`} onClose={onClose}
      description="Changes are staged in the Identifiers card. Nothing is saved until you choose Save changes there."
      footer={<>
        {!isNew && <button type="button" className="cf-btn" data-variant="danger" data-size="sm" disabled={!canRemove} onClick={onRemove}
          title={canRemove ? undefined : 'At least one identifier is required.'}>Remove</button>}
        <span className="cf-ad-spacer" />
        <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" onClick={onClose}>Cancel</button>
        <button type="button" className="cf-btn" data-variant="solid" data-size="sm" onClick={() => setIssue(onApply(d))}>{isNew ? 'Add to list' : 'Apply'}</button>
      </>}
    >
      <div className="cf-ad-form">
        <TextField label="Label" required maxLength={128} value={d.label} onChange={(label) => set({ label })} invalid={!d.label.trim() && !!issue} hint="Shown to staff at intake and on the Desk." />
        <fieldset className="cf-ad-fieldset">
          <legend>Behavior</legend>
          <Toggle label="Primary identifier" checked={d.primary} onChange={(primary) => set(primary ? { primary, required: true, displayable: true } : { primary })} hint="Names the case everywhere. Exactly one field is primary; it is always required and shown." />
          <Toggle label="Required at intake" checked={d.required} disabled={d.primary} onChange={(required) => set({ required })} />
          <Toggle label="Searchable" checked={d.searchable} onChange={(searchable) => set({ searchable })} hint="Staff can find cases by this value." />
          <Toggle label="Displayable" checked={d.displayable} disabled={d.primary} onChange={(displayable) => set({ displayable })} hint="Eligible to appear on the Desk and case drawer." />
          <Toggle label="Auto-extract" checked={d.extractable} onChange={(extractable) => set({ extractable })} hint="Document Intelligence may propose this value from the document." />
        </fieldset>
        <fieldset className="cf-ad-fieldset">
          <legend>Validation</legend>
          <SelectField label="Validation type" value={d.validationType} options={VALIDATION_TYPES}
            onChange={(validationType) => set({ validationType, customPattern: validationType === 'CUSTOM_PATTERN' ? d.customPattern : null })} />
          <div className="cf-ad-grid3">
            <OptionalInt label="Exact length" min={1} max={512} value={d.exactLength}
              onChange={(v) => set({ exactLength: v, minLength: v != null ? null : d.minLength, maxLength: v != null ? null : d.maxLength })} />
            <OptionalInt label="Minimum length" min={1} max={512} disabled={exact} value={d.minLength} onChange={(minLength) => set({ minLength })} />
            <OptionalInt label="Maximum length" min={1} max={512} disabled={exact} value={d.maxLength} onChange={(maxLength) => set({ maxLength })} />
          </div>
          <p className="cf-ad-hint">Exact length replaces min/max. Lengths are measured after normalization.</p>
          {d.validationType === 'ALLOW_LIST' && (
            <div className="cf-field">
              <label htmlFor="idf-allowed"><span>Allowed values</span></label>
              <textarea id="idf-allowed" rows={4} value={d.allowedValues.join('\n')}
                onChange={(e) => set({ allowedValues: e.target.value.split(/[\n,]/) })} />
              <span className="cf-field-hint">One per line or comma-separated. 1–100 values also make this field eligible as a reporting dimension.</span>
            </div>
          )}
          {d.validationType === 'CUSTOM_PATTERN' && (
            <TextField label="Custom pattern" required mono maxLength={512} value={d.customPattern ?? ''} placeholder="^[A-Z]{2}[0-9]{8}$"
              onChange={(customPattern) => set({ customPattern })} hint="Regular expression evaluated by the server against the normalized value." />
          )}
          <SelectField label="Case normalization" value={d.caseNormalization}
            options={[['PRESERVE', 'Preserve as entered'], ['UPPER', 'Convert to uppercase'], ['LOWER', 'Convert to lowercase']]}
            onChange={(caseNormalization) => set({ caseNormalization })} />
          <Toggle label="Strip separators" checked={d.stripSeparators} onChange={(stripSeparators) => set({ stripSeparators })} hint="Removes spaces, hyphens and similar separators before validation." />
        </fieldset>
        {issue && <Notice tone="danger">{issue}</Notice>}
      </div>
    </Sheet>
  );
}

function IdentifierList({ d, set }: { d: BusinessAdminIdentifierDraft[]; set: (n: BusinessAdminIdentifierDraft[]) => void }) {
  const [editing, setEditing] = useState<number | 'new' | null>(null);

  const apply = (next: BusinessAdminIdentifierDraft, at: number | 'new') => {
    let list = at === 'new' ? [...d, next] : d.map((x, i) => (i === at ? next : x));
    if (next.primary) {
      const idx = at === 'new' ? list.length - 1 : at;
      list = list.map((x, i) => (i === idx ? x : { ...x, primary: false }));
    }
    const issue = identifierIssue(list);
    if (issue && !/^Choose exactly one Primary/.test(issue)) return issue;
    set(list);
    setEditing(null);
    return null;
  };

  const remove = (at: number) => {
    const wasPrimary = d[at].primary;
    const next = d.filter((_, i) => i !== at);
    if (wasPrimary && next.length > 0) next[0] = { ...next[0], primary: true, required: true, displayable: true };
    set(next);
    setEditing(null);
  };

  const move = (i: number, by: -1 | 1) => {
    const j = i + by;
    if (j < 0 || j >= d.length) return;
    const next = [...d];
    [next[i], next[j]] = [next[j], next[i]];
    set(next);
  };

  const issue = identifierIssue(d);
  return (
    <>
      <div className="cf-ad-table-wrap">
        <table className="cf-ad-table">
          <thead>
            <tr><th scope="col">Order</th><th scope="col">Field</th><th scope="col">Primary</th><th scope="col">Required</th><th scope="col">Searchable</th><th scope="col">Auto-extract</th><th scope="col">Shown</th><th scope="col"><span className="sr-only">Edit</span></th></tr>
          </thead>
          <tbody>
            {d.map((idf, i) => (
              <tr key={idf.id ?? `new-${i}`}>
                <td className="cf-ad-order">
                  <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={i === 0} onClick={() => move(i, -1)} aria-label={`Move ${idf.label || 'field'} up`}>↑</button>
                  <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={i === d.length - 1} onClick={() => move(i, 1)} aria-label={`Move ${idf.label || 'field'} down`}>↓</button>
                </td>
                <th scope="row">
                  <span className="cf-ad-cell-title">{idf.label || <em>Unnamed</em>}{!idf.id && <span className="cf-ad-tag">New</span>}</span>
                  <span className="cf-ad-cell-sub">{rule(idf)}</span>
                </th>
                <td><input type="radio" name="primary-identifier" aria-label={`Make ${idf.label} primary`} checked={idf.primary} onChange={() => set(d.map((x, j) => (j === i ? { ...x, primary: true, required: true, displayable: true } : { ...x, primary: false })))} /></td>
                <td><input type="checkbox" aria-label={`${idf.label} required`} checked={idf.required} disabled={idf.primary} onChange={(e) => set(d.map((x, j) => (j === i ? { ...x, required: e.target.checked } : x)))} /></td>
                <td><input type="checkbox" aria-label={`${idf.label} searchable`} checked={idf.searchable} onChange={(e) => set(d.map((x, j) => (j === i ? { ...x, searchable: e.target.checked } : x)))} /></td>
                <td><input type="checkbox" aria-label={`${idf.label} auto-extract`} checked={idf.extractable} onChange={(e) => set(d.map((x, j) => (j === i ? { ...x, extractable: e.target.checked } : x)))} /></td>
                <td><input type="checkbox" aria-label={`${idf.label} shown`} checked={idf.displayable} disabled={idf.primary} onChange={(e) => set(d.map((x, j) => (j === i ? { ...x, displayable: e.target.checked } : x)))} /></td>
                <td><button type="button" className="cf-btn" data-size="sm" onClick={() => setEditing(i)}>Edit<span className="sr-only"> {idf.label}</span></button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="cf-ad-inline-actions">
        <button type="button" className="cf-btn" data-size="sm" onClick={() => setEditing('new')}>Add identifier</button>
        {issue && <p className="cf-ad-warn" role="status">{issue}</p>}
      </div>
      {editing !== null && (
        <IdentifierEditor
          key={String(editing)}
          isNew={editing === 'new'}
          value={editing === 'new' ? { ...blankIdentifier(), primary: d.length === 0, required: d.length === 0 } : d[editing]}
          canRemove={d.length > 1}
          onApply={(v) => apply(v, editing)}
          onRemove={() => editing !== 'new' && remove(editing)}
          onClose={() => setEditing(null)}
        />
      )}
    </>
  );
}

export default function FieldsArea({ canIdentifiers, canColumns }: { canIdentifiers: boolean; canColumns: boolean }) {
  const { data, error, mutate } = useBusinessAdmin();
  if (!data) return <Loading error={error} retry={() => mutate()} label="Case fields" />;

  return (
    <div className="cf-ad-stack">
      {canIdentifiers && (
        <SettingsCard
          title="Identifiers"
          description="Captured at intake. Order here is the order staff see. The primary identifier names the case everywhere."
          value={data.identifiers as BusinessAdminIdentifierDraft[]}
          savedMessage="Identifiers saved."
          onSave={async (d) => {
            const issue = identifierIssue(d);
            if (issue) throw new Error(issue);
            return mutate(await apiClient.updateBusinessIdentifiers(toRequest(d)), { revalidate: false });
          }}
        >
          {(d, set) => <IdentifierList d={d} set={set} />}
        </SettingsCard>
      )}

      {canColumns && (
        <SettingsCard
          title="Desk columns"
          description="Allowed columns can be turned on by anyone. Default columns are on for new users. The primary identifier is always allowed."
          value={data.dashboardColumns}
          savedMessage="Desk columns saved."
          onSave={async (d) => mutate(await apiClient.updateDashboardColumns(d), { revalidate: false })}
        >
          {(d, set) => (
            <div className="cf-ad-list">
              {[...d].sort((a, b) => a.displayOrder - b.displayOrder).map((col) => (
                <div key={col.key} className="cf-ad-row">
                  <span className="cf-ad-row-label">
                    {col.label}{col.primary && <span className="cf-ad-tag">Primary</span>}
                    <span className="cf-ad-cell-sub">{col.kind === 'IDENTIFIER' ? 'Business identifier' : 'Operational field'}</span>
                  </span>
                  <Toggle label="Allowed" checked={col.allowed} disabled={col.primary} onChange={(v) => set(d.map((x) => (x.key === col.key ? { ...x, allowed: v, defaultVisible: v ? x.defaultVisible : false } : x)))} />
                  <Toggle label="Default" checked={col.defaultVisible} disabled={!col.allowed} onChange={(v) => set(d.map((x) => (x.key === col.key ? { ...x, defaultVisible: v } : x)))} />
                </div>
              ))}
            </div>
          )}
        </SettingsCard>
      )}
    </div>
  );
}
