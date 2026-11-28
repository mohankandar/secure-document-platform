import { useMemo, useState } from 'react';
import { apiClient } from '../../api/client';
import type { BusinessAdminIdentifierDraft, BusinessIdentifierValidationType } from '../../types';
import { Notice } from '../atoms';
import { SettingsCard, Toggle, Loading, useBusinessAdmin, Sheet, TextField, SelectField, OptionalInt } from './shared';
import { useUnsavedWork } from '../unsavedWork';
import { fieldKind, fieldRule, splitValueLines, valueLines } from './adminRules';
import { DIGITS_PATTERN, LETTERS_DIGITS_PATTERN } from '../identity';
import { dashboardColumnName, fieldNameInSentence } from '../words';

const KINDS: [string, string][] = [
  ['TEXT', 'Text'], ['DIGITS', 'Numbers only'], ['LETTERS_DIGITS', 'Letters and numbers'], ['NUMBER', 'Whole number'], ['DECIMAL', 'Number with decimals'],
  ['MONEY', 'Amount of money'], ['DATE', 'Date'], ['DATE_TIME', 'Date and time'], ['EMAIL', 'Email address'], ['UUID', 'System ID'],
  ['DROPDOWN', 'One of a list of allowed values'], ['ADDRESS', 'US address'], ['CUSTOM_PATTERN', 'Must match a set pattern'],
];
const TAKES_LENGTH = ['TEXT', 'NUMBER', 'EMAIL', 'CUSTOM_PATTERN'];
const TAKES_FORM = ['TEXT', 'NUMBER', 'EMAIL', 'DROPDOWN', 'CUSTOM_PATTERN'];
const TAKES_RANGE = ['NUMBER', 'DECIMAL', 'MONEY'];

/** The type behind a choice, with only the rules it takes. */
export function withKind(d: BusinessAdminIdentifierDraft, kind: string): BusinessAdminIdentifierDraft {
  const validationType = (kind === 'DIGITS' ? 'NUMBER' : kind === 'LETTERS_DIGITS' ? 'TEXT' : kind) as BusinessIdentifierValidationType;
  const length = TAKES_LENGTH.includes(validationType);
  return {
    ...d,
    validationType,
    customPattern: kind === 'DIGITS' ? DIGITS_PATTERN : kind === 'LETTERS_DIGITS' ? LETTERS_DIGITS_PATTERN : kind === 'CUSTOM_PATTERN' ? '' : null,
    exactLength: length ? d.exactLength : null,
    minLength: length ? d.minLength : null,
    maxLength: length ? d.maxLength : null,
    caseNormalization: TAKES_FORM.includes(validationType) ? d.caseNormalization : 'PRESERVE',
    stripSeparators: TAKES_FORM.includes(validationType) && d.stripSeparators,
    allowedValues: validationType === 'DROPDOWN' ? d.allowedValues : [],
    currency: validationType === 'MONEY' ? d.currency || 'USD' : null,
    minValue: TAKES_RANGE.includes(validationType) ? d.minValue : null,
    maxValue: TAKES_RANGE.includes(validationType) ? d.maxValue : null,
  };
}

/** The server suggests Sensitive for these names; the Business Admin decides. */
const suggestsSensitive = (d: BusinessAdminIdentifierDraft) => d.validationType === 'ADDRESS' || /(^|\s)(names?|address(es)?)(\s|$)/i.test(d.label.trim());

const blankIdentifier = (): BusinessAdminIdentifierDraft => ({
  label: '', required: false, primary: false, searchable: true, extractable: false, displayable: true,
  validationType: 'TEXT', exactLength: null, minLength: null, maxLength: null,
  caseNormalization: 'PRESERVE', stripSeparators: false, allowedValues: [], customPattern: null,
  searchMode: 'EXACT', sensitive: undefined, multiValue: false, currency: null, minValue: null, maxValue: null,
});

/** Mirrors BusinessAdminPage's client-side rules; the server remains authoritative. */
export function identifierIssue(list: BusinessAdminIdentifierDraft[]): string | null {
  if (list.length === 0) return 'At least one case field is required.';
  if (list.some((i) => !i.label.trim())) return 'Every case field needs a name.';
  if (list.filter((i) => i.primary).length !== 1) return 'Choose exactly one main ID.';
  const labels = list.map((i) => i.label.trim().toLowerCase());
  if (new Set(labels).size !== labels.length) return 'Each case field needs a different name.';
  for (const i of list) {
    const name = i.label || 'Case field';
    const lengths = [i.exactLength, i.minLength, i.maxLength].filter((v): v is number => v != null);
    if (lengths.some((v) => !Number.isInteger(v) || v < 1 || v > 512)) return `${name}: lengths must be whole numbers from 1 to 512.`;
    if (i.exactLength != null && (i.minLength != null || i.maxLength != null)) return `${name}: exact length cannot be combined with min/max length.`;
    if (i.minLength != null && i.maxLength != null && i.minLength > i.maxLength) return `${name}: minimum length cannot exceed maximum length.`;
    if (i.validationType === 'DROPDOWN' && i.allowedValues.filter((v) => v.trim()).length === 0) return `${name}: at least one allowed value is required.`;
    if (i.validationType === 'DROPDOWN' && i.allowedValues.filter((v) => v.trim()).length > 100) return `${name}: at most 100 allowed values.`;
    if (i.validationType === 'CUSTOM_PATTERN' && !i.customPattern?.trim()) return `${name}: custom pattern is required.`;
    if (i.validationType === 'MONEY' && !/^[A-Za-z]{3}$/.test(i.currency?.trim() ?? '')) return `${name}: a three-letter currency is required.`;
    if (i.primary && i.multiValue) return `${name}: the main ID takes one value.`;
  }
  return null;
}

const toRequest = (list: BusinessAdminIdentifierDraft[]) => list.map((i) => ({
  ...i,
  label: i.label.trim(),
  // Task 0113 (U10): "000 = Club" lines to the codes and their names.
  ...splitValueLines(i.allowedValues),
  customPattern: i.customPattern?.trim() || null,
}));

/** The field's rule spelled out (U007): "Letters and numbers · 6–12 characters · saved in capitals · spaces and dashes removed". */
const rule = (i: BusinessAdminIdentifierDraft) => fieldRule(i);

function IdentifierEditor({ value, isNew, canRemove, sensitiveAvailable, onApply, onRemove, onClose }: {
  value: BusinessAdminIdentifierDraft; isNew: boolean; canRemove: boolean; sensitiveAvailable: boolean;
  onApply: (v: BusinessAdminIdentifierDraft) => string | null; onRemove: () => void; onClose: () => void;
}) {
  const [d, setD] = useState(value);
  const [issue, setIssue] = useState<string | null>(null);
  const set = (p: Partial<BusinessAdminIdentifierDraft>) => setD({ ...d, ...p });
  const exact = d.exactLength != null;
  const locked = !!value.locked;
  const sensitive = d.sensitive ?? (isNew && sensitiveAvailable && suggestsSensitive(d));
  const apply = () => setIssue(onApply({ ...d, sensitive, searchMode: sensitive ? 'EXACT' : d.searchMode }));

  return (
    <Sheet open wide title={isNew ? 'Add case field' : `Edit ${fieldNameInSentence(value.label)}`} onClose={onClose}
      description="Changes wait in the Case fields card. Nothing is saved until you choose Save changes there."
      footer={<>
        {!isNew && <button type="button" className="cf-btn" data-variant="danger" data-size="sm" disabled={!canRemove} onClick={onRemove}
          title={canRemove ? 'Switched off, never deleted: the values stay.' : 'At least one case field is required.'}>Remove</button>}
        <span className="cf-ad-spacer" />
        <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" onClick={onClose}>Cancel</button>
        <button type="button" className="cf-btn" data-variant="solid" data-size="sm" onClick={apply}>{isNew ? 'Add to list' : 'Apply'}</button>
      </>}
    >
      <div className="cf-ad-form">
        <TextField label="Name" required maxLength={128} value={d.label} onChange={(label) => set({ label })} invalid={!d.label.trim() && !!issue} hint="Shown to staff on New case and on Cases." />
        <fieldset className="cf-ad-fieldset">
          <legend>Behavior</legend>
          <Toggle label="Main ID" checked={d.primary} disabled={locked} onChange={(primary) => set(primary ? { primary, required: true, displayable: true } : { primary })} hint="Names the case everywhere. Exactly one field is the main ID; it is always required and shown." />
          <Toggle label="Required when a case is created" checked={d.required} disabled={d.primary} onChange={(required) => set({ required })} />
          <Toggle label="Searchable" checked={d.searchable} onChange={(searchable) => set({ searchable })} hint="Staff can find cases by this value." />
          {d.searchable && (sensitive
            ? <p className="cf-ad-hint">A sensitive value is found by the whole value only.</p>
            : <SelectField label="Found by" value={d.searchMode ?? 'EXACT'} options={[['EXACT', 'The whole value'], ['PREFIX', 'Its beginning'], ['PARTIAL', 'Any part of it']]} onChange={(searchMode) => set({ searchMode })} />)}
          <Toggle label="Sensitive" checked={sensitive} disabled={locked || !sensitiveAvailable} onChange={(on) => set({ sensitive: on })}
            hint={sensitiveAvailable ? 'Kept encrypted, and shown only to people who work on the documents. Names and addresses are sensitive unless you decide otherwise.' : 'Needs the key service, which is not set up here.'} />
          <Toggle label="Takes several values" checked={!!d.multiValue} disabled={locked || d.primary} onChange={(multiValue) => set({ multiValue })} hint="For example two borrowers. Up to 50." />
          <Toggle label="Shown" checked={d.displayable} disabled={d.primary} onChange={(displayable) => set({ displayable })} hint="Can appear on Cases and in the case panel." />
          <Toggle label="AI fills in" checked={d.extractable} onChange={(extractable) => set({ extractable })} hint="When the AI is on, it may suggest this value from the document for staff to confirm." />
        </fieldset>
        <fieldset className="cf-ad-fieldset">
          <legend>Rule</legend>
          <SelectField label="What it can contain" value={fieldKind(d)} options={KINDS} disabled={locked} onChange={(kind) => setD(withKind(d, kind))} />
          {locked && <p className="cf-ad-hint">This field has values: its type, Sensitive, Main ID and several-values stay. To change them, add a new field.</p>}
          {d.validationType === 'MONEY' && <TextField label="Currency" required maxLength={3} value={d.currency ?? ''} onChange={(currency) => set({ currency: currency.toUpperCase() })} hint="One currency per field, for example USD." />}
          {TAKES_RANGE.includes(d.validationType) && (
            <div className="cf-ad-grid3">
              <TextField label="Lowest value" value={d.minValue ?? ''} onChange={(v) => set({ minValue: v || null })} />
              <TextField label="Highest value" value={d.maxValue ?? ''} onChange={(v) => set({ maxValue: v || null })} />
            </div>
          )}
          {TAKES_LENGTH.includes(d.validationType) && (<>
          <div className="cf-ad-grid3">
            <OptionalInt label="Exact length" min={1} max={512} value={d.exactLength}
              onChange={(v) => set({ exactLength: v, minLength: v != null ? null : d.minLength, maxLength: v != null ? null : d.maxLength })} />
            <OptionalInt label="Minimum length" min={1} max={512} disabled={exact} value={d.minLength} onChange={(minLength) => set({ minLength })} />
            <OptionalInt label="Maximum length" min={1} max={512} disabled={exact} value={d.maxLength} onChange={(maxLength) => set({ maxLength })} />
          </div>
          <p className="cf-ad-hint">Exact length replaces minimum and maximum. Lengths are counted after capitals and spaces are applied.</p>
          </>)}
          {d.validationType === 'DROPDOWN' && (
            <div className="cf-field">
              <label htmlFor="idf-allowed"><span>Allowed values</span></label>
              <textarea id="idf-allowed" rows={4} aria-describedby="idf-allowed-hint" value={d.allowedValues.join('\n')}
                onChange={(e) => set({ allowedValues: e.target.value.split(/[\n,]/) })} />
              <span id="idf-allowed-hint" className="cf-field-hint">One per line or separated by commas. To show a name instead of the code, write it after an equals sign, for example <code>000 = Club</code>. The code is what is saved and sent; people pick by the name. With 1–100 values, Reports can also break down by this field.</span>
            </div>
          )}
          {d.validationType === 'CUSTOM_PATTERN' && (
            <TextField label="Custom pattern" required mono maxLength={512} value={d.customPattern ?? ''} placeholder="^[A-Z]{2}[0-9]{8}$"
              onChange={(customPattern) => set({ customPattern })} hint="Regular expression evaluated by the server against the normalized value." />
          )}
          {TAKES_FORM.includes(d.validationType) && (<>
          <SelectField label="Capitals" value={d.caseNormalization} disabled={!isNew}
            options={[['PRESERVE', 'Keep as typed'], ['UPPER', 'Save in capitals'], ['LOWER', 'Save in lower case']]}
            onChange={(caseNormalization) => set({ caseNormalization })} />
          <Toggle label="Remove spaces and dashes" checked={d.stripSeparators} disabled={!isNew} onChange={(stripSeparators) => set({ stripSeparators })} hint="Removes spaces, dashes and similar separators before the value is checked." />
          </>)}
        </fieldset>
        {issue && <Notice tone="danger">{issue}</Notice>}
      </div>
    </Sheet>
  );
}

function IdentifierList({ d, set, switchedOff, sensitiveAvailable }: {
  d: BusinessAdminIdentifierDraft[]; set: (n: BusinessAdminIdentifierDraft[]) => void; switchedOff: BusinessAdminIdentifierDraft[]; sensitiveAvailable: boolean;
}) {
  const [editing, setEditing] = useState<number | 'new' | null>(null);
  useUnsavedWork(editing !== null);

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
  const off = switchedOff.filter((x) => !d.some((y) => y.id === x.id));
  return (
    <>
      <div className="cf-ad-table-wrap">
        <table className="cf-ad-table">
          <thead>
            <tr><th scope="col"><span className="cf-sr">Order</span></th><th scope="col" className="cf-ad-col-field">Field</th><th scope="col">Main ID</th><th scope="col">Required</th><th scope="col">Searchable</th><th scope="col">AI fills in</th><th scope="col">Shown</th><th scope="col"><span className="cf-sr">Edit</span></th></tr>
          </thead>
          <tbody>
            {d.map((idf, i) => (
              <tr key={idf.id ?? `new-${i}`}>
                <td className="cf-ad-order">
                  <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={i === 0} onClick={() => move(i, -1)} aria-label={`Move ${fieldNameInSentence(idf.label) || 'field'} up`}>↑</button>
                  <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={i === d.length - 1} onClick={() => move(i, 1)} aria-label={`Move ${fieldNameInSentence(idf.label) || 'field'} down`}>↓</button>
                </td>
                <th scope="row">
                  <span className="cf-ad-cell-title">{idf.label || <em>Unnamed</em>}{!idf.id && <span className="cf-ad-tag">New</span>}</span>
                  <span className="cf-ad-cell-sub">{rule(idf)}</span>
                </th>
                <td><input type="radio" name="primary-identifier" aria-label={`Make ${fieldNameInSentence(idf.label)} the main ID`} checked={idf.primary} disabled={idf.multiValue || idf.locked || d.some((x) => x.primary && x.locked)} onChange={() => set(d.map((x, j) => (j === i ? { ...x, primary: true, required: true, displayable: true } : { ...x, primary: false })))} /></td>
                <td><input type="checkbox" aria-label={`${idf.label}: required`} checked={idf.required} disabled={idf.primary} onChange={(e) => set(d.map((x, j) => (j === i ? { ...x, required: e.target.checked } : x)))} /></td>
                <td><input type="checkbox" aria-label={`${idf.label}: searchable`} checked={idf.searchable} onChange={(e) => set(d.map((x, j) => (j === i ? { ...x, searchable: e.target.checked } : x)))} /></td>
                <td><input type="checkbox" aria-label={`${idf.label}: AI fills in`} checked={idf.extractable} onChange={(e) => set(d.map((x, j) => (j === i ? { ...x, extractable: e.target.checked } : x)))} /></td>
                <td><input type="checkbox" aria-label={`${idf.label}: shown`} checked={idf.displayable} disabled={idf.primary} onChange={(e) => set(d.map((x, j) => (j === i ? { ...x, displayable: e.target.checked } : x)))} /></td>
                <td><button type="button" className="cf-btn" data-size="sm" onClick={() => setEditing(i)}>Edit<span className="sr-only"> {idf.label}</span></button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="cf-ad-inline-actions">
        <button type="button" className="cf-btn" data-size="sm" onClick={() => setEditing('new')}>Add case field</button>
        {issue && <p className="cf-ad-warn" role="status">{issue}</p>}
      </div>
      {off.length > 0 && (
        <div className="cf-ad-list" aria-label="Switched off">
          {off.map((x) => (
            <div key={x.id} className="cf-ad-row">
              <span className="cf-ad-row-label">{x.label}<span className="cf-ad-tag">Switched off</span><span className="cf-ad-cell-sub">{rule(x)}</span></span>
              <button type="button" className="cf-btn" data-size="sm" onClick={() => set([...d, { ...x, primary: false }])}>Switch on<span className="sr-only"> {x.label}</span></button>
            </div>
          ))}
        </div>
      )}
      {editing !== null && (
        <IdentifierEditor
          key={String(editing)}
          isNew={editing === 'new'}
          value={editing === 'new' ? { ...blankIdentifier(), primary: d.length === 0, required: d.length === 0 } : d[editing]}
          canRemove={d.length > 1}
          sensitiveAvailable={sensitiveAvailable}
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
  // Task 0113 (U10): each allowed value as the admin edits it, "000 = Club" where it has a name.
  const editable = useMemo(() => ((data?.identifiers ?? []) as BusinessAdminIdentifierDraft[]).map((i) => ({
    ...i, allowedValues: valueLines(i.allowedValues, (i as { allowedValueNames?: Record<string, string> }).allowedValueNames),
  })), [data?.identifiers]);
  if (!data) return <Loading error={error} retry={() => mutate()} label="Case fields" />;

  return (
    <div className="cf-ad-stack">
      {canIdentifiers && (
        <SettingsCard
          title="Case fields"
          help="caseFields"
          description="Captured when a case is created. The order here is the order staff see. The main ID names the case everywhere."
          value={editable}
          savedMessage="Case fields saved."
          onSave={async (d) => {
            const issue = identifierIssue(d);
            if (issue) throw new Error(issue);
            return mutate(await apiClient.updateBusinessIdentifiers(toRequest(d)), { revalidate: false });
          }}
        >
          {(d, set) => <IdentifierList d={d} set={set} switchedOff={(data.switchedOffIdentifiers ?? []) as BusinessAdminIdentifierDraft[]} sensitiveAvailable={data.sensitiveFieldsAvailable === true} />}
        </SettingsCard>
      )}

      {canColumns && (
        <SettingsCard
          title="Columns on Cases"
          help="columnsOnCases"
          description="Allowed columns can be turned on by anyone. Default columns are on for new users. The main ID is always allowed."
          value={data.dashboardColumns}
          savedMessage="Columns on Cases saved."
          onSave={async (d) => mutate(await apiClient.updateDashboardColumns(d), { revalidate: false })}
        >
          {(d, set) => (
            <div className="cf-ad-list">
              {[...d].sort((a, b) => a.displayOrder - b.displayOrder).map((col) => (
                <div key={col.key} className="cf-ad-row">
                  <span className="cf-ad-row-label">
                    {dashboardColumnName(col)}{col.primary && <span className="cf-ad-tag">Main ID</span>}
                    <span className="cf-ad-cell-sub">{col.kind === 'IDENTIFIER' ? 'Case field' : 'Set by LexaVault'}</span>
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
