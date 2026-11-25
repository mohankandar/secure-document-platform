import { useCallback, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { ArrowRight, Lock } from 'lucide-react';
import type { IdentifierDefinition } from '../types';
import { useIdentifierDefinitions } from './data';
import { canonicalIdentity, identityErrors, sortDefinitions } from './identity';
import { Notice, Spinner } from './atoms';
import UploadCapture from './UploadCapture';
import ScanCapture from './ScanCapture';
import { useUnsavedWork } from './unsavedWork';
import { prefillIdentifier, scannedWords, uploadedWords } from './intakeRules';
import useSWR from 'swr';
import { apiClient } from '../api/client';
import { PRIORITIES, PRIORITY_FIELD, PRIORITY_NAMES, priorityChoice, type PriorityCode, SENSITIVITY_FIELD, SENSITIVITY_LEVELS, SENSITIVITY_NAMES, sensitivityChoice, type SensitivityLevelCode } from './words';

type Method = 'upload' | 'scan';

export interface IdentityGate {
  /** Marks every field touched and returns the canonical identity, or null when it is invalid. */
  require: () => Record<string, string> | null;
  valid: boolean;
  /** Task 0021: the "Sensitivity" choice, applied as the case level. Standard unless changed. */
  sensitivity: SensitivityLevelCode;
  /** Task 0073: the "Priority" choice. Nothing is sent for Normal. */
  priority?: PriorityCode;
}

export default function Intake({ entityName }: { entityName: string }) {
  const [params, setParams] = useSearchParams();
  const method: Method = params.get('via') === 'scan' ? 'scan' : 'upload';
  const { data: rawDefinitions, error, isLoading } = useIdentifierDefinitions();
  const definitions = useMemo(() => sortDefinitions(rawDefinitions), [rawDefinitions]);

  const [values, setValues] = useState<Record<string, string>>({});
  const [touched, setTouched] = useState<Set<string>>(() => new Set());
  const [lockedBy, setLockedBy] = useState<string | null>(null);
  const [sensitivity, setSensitivity] = useState<SensitivityLevelCode>('STANDARD');
  const [priority, setPriority] = useState<PriorityCode>('NORMAL');
  const [created, setCreated] = useState<{ packageId: string | null; label: string; method: Method; files: number } | null>(null);
  useUnsavedWork(!created && Object.values(values).some((v) => v.trim() !== ''));

  // "Start a new case for this …" on a rejected case opens New case with ?id=<primary identifier> (UI tasks U003, U005).
  const prefill = prefillIdentifier(params);
  const [prefilled, setPrefilled] = useState(false);
  if (!prefilled && prefill && definitions.length > 0) {
    const primary = definitions.find((d) => d.primary) ?? definitions[0];
    setPrefilled(true);
    setValues((current) => (Object.values(current).some((v) => v.trim() !== '') ? current : { ...current, [primary.key]: prefill }));
  }

  const errors = useMemo(() => identityErrors(definitions, values), [definitions, values]);
  const valid = definitions.length > 0 && Object.keys(errors).length === 0;

  const gate: IdentityGate = {
    valid,
    sensitivity,
    priority: priority === 'NORMAL' ? undefined : priority,
    require: () => {
      setTouched(new Set(definitions.map((d) => d.key)));
      return valid ? canonicalIdentity(definitions, values) : null;
    },
  };

  const lock = useCallback((identity: Record<string, string> | null, reason: string, sensitivityLevel?: string | null, sentPriority?: string | null) => {
    if (identity) {
      setValues(identity);
      setLockedBy(reason);
      // A resumed scan session shows the level it was started with; Standard when it carries none (task 0023 A4).
      setSensitivity(sensitivityChoice(sensitivityLevel));
      if (sentPriority !== undefined) setPriority(priorityChoice(sentPriority));
    } else {
      setLockedBy(null);
    }
  }, []);

  const finish = (packageId: string | null, files = 0) => {
    const primary = definitions.find((d) => d.primary) ?? definitions[0];
    const label = primary ? canonicalIdentity(definitions, values)[primary.key] ?? 'New case' : 'New case';
    setCreated({ packageId, label, method, files });
  };

  const reset = () => {
    setValues({});
    setTouched(new Set());
    setLockedBy(null);
    setSensitivity('STANDARD');
    setPriority('NORMAL');
    setCreated(null);
  };

  if (created) return <Created created={created} entityName={entityName} onReset={reset} />;

  return (
    <div className="cf-intake">
      <header className="cf-page-head">
        <div>
          <h1 className="cf-title">New case</h1>
          <p className="cf-lede">Name the case, add its documents, and LexaVault takes it from there.</p>
        </div>
      </header>

      <div className="cf-intake-grid">
        <section className="cf-panel" aria-labelledby="cf-identity-title">
          <header className="cf-panel-head">
            <div>
              <h2 id="cf-identity-title"><span className="cf-panel-num" aria-hidden="true">1 · </span>Identify the case</h2>
              <p>These values follow the documents through redaction and delivery.</p>
            </div>
          </header>

          {error && <Notice tone="danger">Identity fields couldn&apos;t be loaded.</Notice>}
          {isLoading && <div className="cf-center"><Spinner label="Loading fields" /></div>}

          {lockedBy && (
            <p className="cf-locked"><Lock size={14} aria-hidden="true" /> Locked to {lockedBy}</p>
          )}

          <div className="cf-fields">
            {definitions.map((definition) => (
              <IdentityField
                key={definition.key}
                definition={definition}
                value={values[definition.key] ?? ''}
                error={touched.has(definition.key) ? errors[definition.key] : undefined}
                locked={!!lockedBy}
                onChange={(value) => setValues((v) => ({ ...v, [definition.key]: value }))}
                onBlur={() => setTouched((t) => new Set(t).add(definition.key))}
              />
            ))}
            {definitions.length > 0 && (<>
              <SensitivityField value={sensitivity} locked={!!lockedBy} onChange={setSensitivity} />
              <PriorityField value={priority} locked={!!lockedBy} onChange={setPriority} />
            </>)}
          </div>
        </section>

        <section className="cf-panel" aria-labelledby="cf-capture-title">
          <header className="cf-panel-head">
            <div>
              <h2 id="cf-capture-title"><span className="cf-panel-num" aria-hidden="true">2 · </span>Add the documents</h2>
              <p>Upload PDFs you already have, or scan paper.</p>
            </div>
          </header>

          <div className="cf-method" role="tablist" aria-label="Capture method">
            <button
              type="button" role="tab" aria-selected={method === 'upload'}
              disabled={!!lockedBy && method !== 'upload'}
              onClick={() => setParams({ via: 'upload' }, { replace: true })}
            >
              Upload PDFs
            </button>
            <button
              type="button" role="tab" aria-selected={method === 'scan'}
              disabled={!!lockedBy && method !== 'scan'}
              onClick={() => setParams({ via: 'scan' }, { replace: true })}
            >
              Scan paper
            </button>
          </div>

          <div role="tabpanel">
            {method === 'upload'
              ? <UploadCapture gate={gate} onLock={lock} onCreated={finish} />
              : <ScanCapture gate={gate} onLock={lock} onCreated={finish} />}
          </div>
        </section>
      </div>
    </div>
  );
}

function IdentityField({ definition, value, error, locked, onChange, onBlur }: {
  definition: IdentifierDefinition; value: string; error?: string; locked: boolean;
  onChange: (value: string) => void; onBlur: () => void;
}) {
  const id = `cf-id-${definition.key}`;
  const hintId = `${id}-hint`;
  const hint = definition.exactLength != null ? `${definition.exactLength} characters` : definition.allowedValues.length ? definition.allowedValues.join(' / ') : null;
  return (
    <div className="cf-field" data-primary={definition.primary || undefined} data-invalid={error ? true : undefined}>
      <label htmlFor={id}>
        {definition.label}
        {definition.required ? <span className="cf-field-req">Required</span> : <span className="cf-field-opt">Optional</span>}
      </label>
      {definition.allowedValues.length > 0 ? (
        <select id={id} value={value} disabled={locked} onChange={(e) => onChange(e.target.value)} onBlur={onBlur} aria-invalid={!!error} aria-describedby={hintId}>
          <option value="">Choose…</option>
          {definition.allowedValues.map((v) => <option key={v} value={v}>{v}</option>)}
        </select>
      ) : (
        <input
          id={id}
          value={value}
          readOnly={locked}
          onChange={(e) => onChange(e.target.value)}
          onBlur={onBlur}
          autoComplete="off"
          spellCheck={false}
          inputMode={definition.validationType === 'NUMERIC' ? 'numeric' : undefined}
          aria-invalid={!!error}
          aria-describedby={hintId}
        />
      )}
      <span id={hintId} className="cf-field-hint">{error ?? hint ?? ''}</span>
    </div>
  );
}

/** Task 0021: the case's sensitivity level. The same look as the other New case fields; Standard by default. */
function SensitivityField({ value, locked, onChange }: {
  value: SensitivityLevelCode; locked: boolean; onChange: (value: SensitivityLevelCode) => void;
}) {
  return (
    <div className="cf-field">
      <label htmlFor="cf-id-sensitivity">
        {SENSITIVITY_FIELD.label}
        <span className="cf-field-opt">Optional</span>
      </label>
      <select id="cf-id-sensitivity" value={value} disabled={locked}
        onChange={(e) => onChange(e.target.value as SensitivityLevelCode)} aria-describedby="cf-id-sensitivity-hint">
        {SENSITIVITY_LEVELS.map((level) => <option key={level} value={level}>{SENSITIVITY_NAMES[level]}</option>)}
      </select>
      <span id="cf-id-sensitivity-hint" className="cf-field-hint">{SENSITIVITY_FIELD.hint}</span>
    </div>
  );
}

/** Task 0073: Urgent, Normal or Low; Normal by default. */
function PriorityField({ value, locked, onChange }: { value: PriorityCode; locked: boolean; onChange: (value: PriorityCode) => void }) {
  return (
    <div className="cf-field">
      <label htmlFor="cf-id-priority">
        {PRIORITY_FIELD.label}
        <span className="cf-field-opt">Optional</span>
      </label>
      <select id="cf-id-priority" value={value} disabled={locked} onChange={(e) => onChange(e.target.value as PriorityCode)} aria-describedby="cf-id-priority-hint">
        {PRIORITIES.map((p) => <option key={p} value={p}>{PRIORITY_NAMES[p]}</option>)}
      </select>
      <span id="cf-id-priority-hint" className="cf-field-hint">{PRIORITY_FIELD.hint}</span>
    </div>
  );
}

function Created({ created, entityName, onReset }: {
  created: { packageId: string | null; label: string; method: Method; files: number }; entityName: string; onReset: () => void;
}) {
  // A scan is taken in by the Scanner Bridge, which is set up for an entity: the entity is named only once this entity has the case.
  const { data: here } = useSWR(created.method === 'scan' && created.packageId ? ['created-case', created.packageId] : null,
    () => apiClient.getPackage(created.packageId!).then((p) => p.packageId === created.packageId), { shouldRetryOnError: false, revalidateOnFocus: false });
  return (
    <div className="cf-created">
      <span className="cf-created-seal" aria-hidden="true"><span /><span /><span /></span>
      <p className="cf-eyebrow">Case opened</p>
      <h1 className="cf-title cf-mono">{created.label}</h1>
      <p className="cf-lede">
        {created.method === 'scan' ? scannedWords(entityName, here === true) : uploadedWords(created.files, entityName)} Redaction has started. Anything uncertain will come to review.
      </p>
      <div className="cf-created-actions">
        {created.packageId && (
          <Link to={`/case/${created.packageId}`} className="cf-btn" data-variant="solid">
            Follow this case <ArrowRight size={16} aria-hidden="true" />
          </Link>
        )}
        <button type="button" className="cf-btn" data-variant="ghost" onClick={onReset}>Open another case</button>
      </div>
    </div>
  );
}
