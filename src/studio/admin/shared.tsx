import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import useSWR from 'swr';
import { apiClient } from '../../api/client';
import type { BusinessAdminConfiguration } from '../../types';
import { errorMessage } from '../data';
import { Notice, Spinner } from '../atoms';
import { useToast } from '../toast';

export function useBusinessAdmin() {
  return useSWR<BusinessAdminConfiguration>('business-admin', () => apiClient.getBusinessAdminConfiguration(), { revalidateOnFocus: false });
}

export function Loading({ error, retry, label }: { error?: unknown; retry: () => void; label: string }) {
  if (error) {
    return (
      <Notice tone="danger">
        {errorMessage(error, `${label} could not be loaded.`)}{' '}
        <button type="button" className="cf-btn" data-size="sm" onClick={retry}>Retry</button>
      </Notice>
    );
  }
  return <div className="cf-center"><Spinner label={`Loading ${label.toLowerCase()}`} /></div>;
}

/**
 * A form card with explicit dirty state. Save is only enabled when the draft differs
 * from what the server returned; Reset discards local edits.
 */
export function SettingsCard<T>({ title, description, value, children, onSave, savedMessage }: {
  title: string;
  description?: string;
  value: T;
  children: (draft: T, set: (next: T) => void) => ReactNode;
  onSave: (draft: T) => Promise<unknown>;
  savedMessage: string;
}) {
  const toast = useToast();
  const [draft, setDraft] = useState<T>(value);
  const [baseline, setBaseline] = useState<T>(value);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (value !== baseline) {
    setBaseline(value);
    setDraft(value);
  }
  const dirty = JSON.stringify(draft) !== JSON.stringify(value);

  async function save() {
    setSaving(true);
    setError(null);
    try {
      await onSave(draft);
      toast(savedMessage);
    } catch (e) {
      setError(errorMessage(e, 'The change could not be saved.'));
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="cf-ad-card" aria-label={title}>
      <header className="cf-ad-card-head">
        <div>
          <h2>{title}</h2>
          {description && <p>{description}</p>}
        </div>
        {dirty && <span className="cf-ad-dirty">Unsaved</span>}
      </header>
      <div className="cf-ad-card-body">{children(draft, setDraft)}</div>
      {error && <Notice tone="danger">{error}</Notice>}
      <footer className="cf-ad-card-foot">
        <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={!dirty || saving} onClick={() => { setDraft(value); setError(null); }}>Reset</button>
        <button type="button" className="cf-btn" data-variant="solid" data-size="sm" disabled={!dirty || saving} onClick={save}>
          {saving && <Spinner label="Saving" />}Save changes
        </button>
      </footer>
    </section>
  );
}

export function Toggle({ label, checked, onChange, hint, disabled }: { label: string; checked: boolean; onChange: (v: boolean) => void; hint?: string; disabled?: boolean }) {
  return (
    <label className="cf-ad-toggle">
      <input type="checkbox" role="switch" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />
      <span className="cf-ad-toggle-track" aria-hidden="true" />
      <span className="cf-ad-toggle-text">
        <span>{label}</span>
        {hint && <span className="cf-ad-hint">{hint}</span>}
      </span>
    </label>
  );
}

export function NumberField({ label, value, onChange, min, max, step = 1, suffix, hint }: {
  label: string; value: number; onChange: (v: number) => void; min?: number; max?: number; step?: number; suffix?: string; hint?: string;
}) {
  const invalid = Number.isNaN(value) || (min !== undefined && value < min) || (max !== undefined && value > max);
  return (
    <div className="cf-field cf-ad-number" data-invalid={invalid || undefined}>
      <label>
        <span>{label}</span>
        {suffix && <span className="cf-field-opt">{suffix}</span>}
      </label>
      <input type="number" inputMode="decimal" value={Number.isNaN(value) ? '' : value} min={min} max={max} step={step} onChange={(e) => onChange(e.target.valueAsNumber)} aria-invalid={invalid || undefined} />
      <span className="cf-field-hint">{invalid ? `Enter a value${min !== undefined ? ` from ${min}` : ''}${max !== undefined ? ` to ${max}` : ''}.` : hint}</span>
    </div>
  );
}

/** Side sheet built on the native modal <dialog>, so focus trapping and Escape come from the platform. */
export function Sheet({ open, title, description, onClose, children, footer, wide }: {
  open: boolean; title: string; description?: string; onClose: () => void; children: ReactNode; footer?: ReactNode; wide?: boolean;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (open && !d.open) d.showModal();
    if (!open && d.open) d.close();
  }, [open]);
  return (
    <dialog ref={ref} className="cf-ad-sheet" data-wide={wide || undefined} aria-labelledby={titleId} onCancel={(e) => { e.preventDefault(); onClose(); }}>
      {open && (
        <div className="cf-ad-sheet-inner">
          <header className="cf-ad-sheet-head">
            <div>
              <h2 id={titleId}>{title}</h2>
              {description && <p>{description}</p>}
            </div>
            <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" onClick={onClose} aria-label="Close">Close</button>
          </header>
          <div className="cf-ad-sheet-body">{children}</div>
          {footer && <footer className="cf-ad-sheet-foot">{footer}</footer>}
        </div>
      )}
    </dialog>
  );
}

/**
 * Deliberate confirmation for high-impact operations. Shows the exact target facts and the
 * consequence; when `acknowledge` is set the confirm button stays disabled until it is ticked.
 */
export function ConfirmDialog({ open, title, facts, consequence, confirmLabel, danger, acknowledge, busy, error, onConfirm, onCancel }: {
  open: boolean; title: string; facts: [string, ReactNode][]; consequence: ReactNode; confirmLabel: string; danger?: boolean;
  acknowledge?: string; busy?: boolean; error?: string | null; onConfirm: () => void; onCancel: () => void;
}) {
  const [ack, setAck] = useState(false);
  const [wasOpen, setWasOpen] = useState(open);
  if (open !== wasOpen) { setWasOpen(open); if (open) setAck(false); }
  return (
    <Sheet open={open} title={title} onClose={onCancel}
      footer={<>
        <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" onClick={onCancel} disabled={busy}>Cancel</button>
        <button type="button" className="cf-btn" data-variant={danger ? 'danger' : 'solid'} data-size="sm" disabled={busy || (!!acknowledge && !ack)} onClick={onConfirm}>
          {busy && <Spinner label="Working" />}{confirmLabel}
        </button>
      </>}
    >
      <dl className="cf-ad-facts">
        {facts.map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}
      </dl>
      <div className="cf-ad-consequence">{consequence}</div>
      {acknowledge && (
        <label className="cf-ad-ack"><input type="checkbox" checked={ack} onChange={(e) => setAck(e.target.checked)} /> {acknowledge}</label>
      )}
      {error && <Notice tone="danger">{error}</Notice>}
    </Sheet>
  );
}

export function TextField({ label, value, onChange, hint, placeholder, required, maxLength, type = 'text', disabled, invalid, mono }: {
  label: string; value: string; onChange: (v: string) => void; hint?: ReactNode; placeholder?: string; required?: boolean;
  maxLength?: number; type?: string; disabled?: boolean; invalid?: boolean; mono?: boolean;
}) {
  const id = useId();
  return (
    <div className="cf-field" data-invalid={invalid || undefined}>
      <label htmlFor={id}><span>{label}</span>{!required && <span className="cf-field-opt">Optional</span>}</label>
      <input id={id} type={type} value={value} placeholder={placeholder} maxLength={maxLength} disabled={disabled} aria-invalid={invalid || undefined}
        className={mono ? 'cf-ad-mono' : undefined} onChange={(e) => onChange(e.target.value)} />
      {hint && <span className="cf-field-hint">{hint}</span>}
    </div>
  );
}

export function SelectField<T extends string>({ label, value, options, onChange, hint, disabled }: {
  label: string; value: T; options: readonly (T | [T, string])[]; onChange: (v: T) => void; hint?: ReactNode; disabled?: boolean;
}) {
  const id = useId();
  return (
    <div className="cf-field">
      <label htmlFor={id}><span>{label}</span></label>
      <select id={id} value={value} disabled={disabled} onChange={(e) => onChange(e.target.value as T)}>
        {options.map((o) => { const [v, l] = Array.isArray(o) ? o : [o, humanize(o)]; return <option key={v} value={v}>{l}</option>; })}
      </select>
      {hint && <span className="cf-field-hint">{hint}</span>}
    </div>
  );
}

/** Optional integer input: empty string means "not set" (null), matching the production request models. */
export function OptionalInt({ label, value, onChange, min, max, disabled, hint }: {
  label: string; value: number | null | undefined; onChange: (v: number | null) => void; min?: number; max?: number; disabled?: boolean; hint?: ReactNode;
}) {
  const id = useId();
  return (
    <div className="cf-field">
      <label htmlFor={id}><span>{label}</span><span className="cf-field-opt">Optional</span></label>
      <input id={id} type="number" inputMode="numeric" min={min} max={max} disabled={disabled} value={value ?? ''}
        onChange={(e) => onChange(e.target.value === '' ? null : Number(e.target.value))} />
      {hint && <span className="cf-field-hint">{hint}</span>}
    </div>
  );
}

const ACRONYMS = new Set(['SSN', 'API', 'SFTP', 'OIDC', 'SAML', 'SDV', 'URL', 'ID', 'S3', 'SSO', 'HTTP', 'HTTPS']);
export const humanize = (s: string) =>
  s.split('_').map((w, i) => (ACRONYMS.has(w) ? w : i === 0 ? w.charAt(0) + w.slice(1).toLowerCase() : w.toLowerCase())).join(' ');

export function when(iso?: string | null) {
  if (!iso) return '—';
  return new Date(iso).toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}
