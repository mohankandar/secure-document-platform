import type { IdentifierDefinition } from '../types';
import { DIGITS_PATTERN, HIDDEN_VALUE, readAddress, readValues, writeAddress, writeValues, type AddressParts } from '../studio/identity';

interface Props {
  id: string;
  definition: IdentifierDefinition;
  value: string;
  locked: boolean;
  invalid: boolean;
  describedBy?: string;
  onChange: (value: string) => void;
  onBlur?: () => void;
}

const ADDRESS_PARTS: [keyof AddressParts, string][] = [
  ['street', 'Street'], ['street2', 'Apartment, suite (optional)'], ['city', 'City'], ['state', 'State'], ['zip', 'ZIP'],
];

/** The date and time input takes no offset; what it shows is the entered local time. */
const localDateTime = (value: string) => value.replace(/(:\d{2})?(Z|[+-]\d{2}:\d{2})$/, '');

function One({ id, definition, value, locked, invalid, describedBy, onChange, onBlur, label }: Props & { label?: string }) {
  const common = { id, onBlur, 'aria-invalid': invalid, 'aria-describedby': describedBy, 'aria-label': label };
  if (definition.validationType === 'ADDRESS') {
    const address = readAddress(value);
    return (
      <span className="cf-address" role="group" aria-label={label ?? definition.label}>
        {ADDRESS_PARTS.map(([part, name], i) => (
          <input key={part} id={i === 0 ? id : `${id}-${part}`} value={address[part]} readOnly={locked} placeholder={name} aria-label={`${label ?? definition.label}: ${name}`}
            autoComplete="off" spellCheck={false} maxLength={part === 'state' ? 2 : 512} data-part={part} aria-invalid={invalid} aria-describedby={describedBy} onBlur={onBlur}
            onChange={(e) => onChange(writeAddress({ ...address, [part]: e.target.value }))} />
        ))}
      </span>
    );
  }
  if (definition.allowedValues.length > 0) {
    return (
      <select {...common} value={value} disabled={locked} onChange={(e) => onChange(e.target.value)}>
        <option value="">Choose…</option>
        {definition.allowedValues.map((v) => <option key={v} value={v}>{v}</option>)}
      </select>
    );
  }
  const type = definition.validationType === 'DATE' ? 'date' : definition.validationType === 'DATE_TIME' ? 'datetime-local'
    : definition.validationType === 'EMAIL' ? 'email' : 'text';
  const inputMode = definition.validationType === 'NUMBER' && definition.customPattern === DIGITS_PATTERN ? 'numeric'
    : definition.validationType === 'DECIMAL' || definition.validationType === 'MONEY' ? 'decimal' : undefined;
  const input = (
    <input {...common} type={type} value={type === 'datetime-local' ? localDateTime(value) : value} readOnly={locked} autoComplete="off" spellCheck={false}
      inputMode={inputMode} onChange={(e) => onChange(e.target.value)} />
  );
  return definition.validationType === 'MONEY'
    ? <span className="cf-money">{input}<span className="cf-field-hint">{definition.currency}</span></span> : input;
}

/** Task 0061: one case field, whatever its type; a field that takes several values shows one row for each. */
export default function CaseFieldInput(props: Props) {
  const { id, definition, value, locked, onChange } = props;
  // A sensitive value is not read back (a scan session in progress): it is shown as hidden.
  if (value === HIDDEN_VALUE) return <input id={id} value={value} readOnly aria-label={`${definition.label}: hidden`} />;
  if (!definition.multiValue) return <One {...props} />;
  const values = readValues(value);
  const set = (next: string[]) => onChange(writeValues(next));
  return (
    <span className="cf-several">
      {values.map((one, i) => (
        <span key={i} className="cf-several-row">
          <One {...props} id={i === 0 ? id : `${id}-${i}`} value={one} label={values.length > 1 ? `${definition.label} ${i + 1}` : undefined}
            onChange={(next) => set(values.map((v, j) => (j === i ? next : v)))} />
          {!locked && values.length > 1 && (
            <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" aria-label={`Remove ${definition.label} ${i + 1}`}
              onClick={() => set(values.filter((_, j) => j !== i))}>Remove</button>
          )}
        </span>
      ))}
      {!locked && values.length < 50 && (
        <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" onClick={() => set([...values, ''])}>Add another</button>
      )}
    </span>
  );
}
