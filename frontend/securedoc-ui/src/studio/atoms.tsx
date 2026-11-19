import type { ReactNode } from 'react';
import { stageLabel, type StageKey } from './data';

export function Kbd({ children }: { children: ReactNode }) {
  return <kbd className="cf-kbd">{children}</kbd>;
}

export function StageChip({ stage, status, label: override }: { stage: StageKey; status?: string; label?: string }) {
  const label = override ?? (status === 'REJECTED' ? 'Rejected' : stageLabel(stage));
  return (
    <span className="cf-stage" data-stage={stage}>
      <span className="cf-stage-dot" aria-hidden="true" />
      {label}
    </span>
  );
}

export function Spinner({ label = 'Loading' }: { label?: string }) {
  return <span className="cf-spinner" role="status" aria-label={label} />;
}

export function Notice({ tone = 'neutral', children }: { tone?: 'neutral' | 'danger' | 'mark'; children: ReactNode }) {
  return <div className="cf-notice" data-tone={tone} role={tone === 'danger' ? 'alert' : 'status'}>{children}</div>;
}
