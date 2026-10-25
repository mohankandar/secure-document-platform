import { useCallback, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { ArrowRight, Lock, ScanLine, Upload } from 'lucide-react';
import type { IdentifierDefinition } from '../types';
import { useIdentifierDefinitions } from './data';
import { canonicalIdentity, identityErrors, sortDefinitions } from './identity';
import { Notice, Spinner } from './atoms';
import UploadCapture from './UploadCapture';
import ScanCapture from './ScanCapture';

type Method = 'upload' | 'scan';

export interface IdentityGate {
  /** Marks every field touched and returns the canonical identity, or null when it is invalid. */
  require: () => Record<string, string> | null;
  valid: boolean;
}

export default function Intake() {
  const [params, setParams] = useSearchParams();
  const method: Method = params.get('via') === 'scan' ? 'scan' : 'upload';
  const { data: rawDefinitions, error, isLoading } = useIdentifierDefinitions();
  const definitions = useMemo(() => sortDefinitions(rawDefinitions), [rawDefinitions]);

  const [values, setValues] = useState<Record<string, string>>({});
  const [touched, setTouched] = useState<Set<string>>(() => new Set());
  const [lockedBy, setLockedBy] = useState<string | null>(null);
  const [created, setCreated] = useState<{ packageId: string | null; label: string; method: Method } | null>(null);

  const errors = useMemo(() => identityErrors(definitions, values), [definitions, values]);
  const valid = definitions.length > 0 && Object.keys(errors).length === 0;

  const gate: IdentityGate = {
    valid,
    require: () => {
      setTouched(new Set(definitions.map((d) => d.key)));
      return valid ? canonicalIdentity(definitions, values) : null;
    },
  };

  const lock = useCallback((identity: Record<string, string> | null, reason: string) => {
    if (identity) {
      setValues(identity);
      setLockedBy(reason);
    } else {
      setLockedBy(null);
    }
  }, []);

  const finish = (packageId: string | null) => {
    const primary = definitions.find((d) => d.primary) ?? definitions[0];
    const label = primary ? canonicalIdentity(definitions, values)[primary.key] ?? 'New case' : 'New case';
    setCreated({ packageId, label, method });
  };

  const reset = () => {
    setValues({});
    setTouched(new Set());
    setLockedBy(null);
    setCreated(null);
  };

  if (created) return <Created created={created} onReset={reset} />;

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
            <span className="cf-step" aria-hidden="true">1</span>
            <div>
              <h2 id="cf-identity-title">Identify the case</h2>
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
          </div>
        </section>

        <section className="cf-panel" aria-labelledby="cf-capture-title">
          <header className="cf-panel-head">
            <span className="cf-step" aria-hidden="true">2</span>
            <div>
              <h2 id="cf-capture-title">Add the documents</h2>
              <p>Upload PDFs you already have, or scan paper straight into the vault.</p>
            </div>
          </header>

          <div className="cf-method" role="tablist" aria-label="Capture method">
            <button
              type="button" role="tab" aria-selected={method === 'upload'}
              disabled={!!lockedBy && method !== 'upload'}
              onClick={() => setParams({ via: 'upload' }, { replace: true })}
            >
              <Upload size={16} aria-hidden="true" /> Upload PDFs
            </button>
            <button
              type="button" role="tab" aria-selected={method === 'scan'}
              disabled={!!lockedBy && method !== 'scan'}
              onClick={() => setParams({ via: 'scan' }, { replace: true })}
            >
              <ScanLine size={16} aria-hidden="true" /> Scan paper
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
          className={definition.primary ? 'cf-mono' : undefined}
          aria-invalid={!!error}
          aria-describedby={hintId}
        />
      )}
      <span id={hintId} className="cf-field-hint">{error ?? hint ?? '\u00a0'}</span>
    </div>
  );
}

function Created({ created, onReset }: { created: { packageId: string | null; label: string; method: Method }; onReset: () => void }) {
  return (
    <div className="cf-created">
      <span className="cf-created-seal" aria-hidden="true"><span /><span /><span /></span>
      <p className="cf-eyebrow">Case opened</p>
      <h1 className="cf-title cf-mono">{created.label}</h1>
      <p className="cf-lede">
        {created.method === 'scan' ? 'The scan was handed to LexaVault.' : 'All documents were uploaded and submitted.'} Redaction has started. Anything uncertain will come to review.
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
