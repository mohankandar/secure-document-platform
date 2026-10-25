import type { ReactNode } from 'react';

type Props = { label: ReactNode; children: ReactNode; hint?: ReactNode; error?: ReactNode; className?: string };

/** Canonical label/control/help/error wrapper. The child owns its native input semantics. */
export default function Field({ label, children, hint, error, className = '' }: Props) {
  return <label className={`ui-field${className ? ` ${className}` : ''}`}><span className="ui-field-label">{label}</span>{children}{error ? <span className="ui-field-error">{error}</span> : hint ? <span className="ui-field-hint">{hint}</span> : null}</label>;
}
