import type { HTMLAttributes, ReactNode } from 'react';

type Kind = 'error' | 'success' | 'warning' | 'info';
const tone: Record<Kind, string> = { error: 'danger', success: 'success', warning: 'warning', info: 'info' };

/** Canonical semantic message/banner surface. */
export default function Alert({ kind, children, className = '', ...props }: HTMLAttributes<HTMLDivElement> & { kind: Kind; children: ReactNode }) {
  return <div className={`ui-alert tone-${tone[kind]}${className ? ` ${className}` : ''}`} role={kind === 'error' ? 'alert' : 'status'} {...props}>{children}</div>;
}
