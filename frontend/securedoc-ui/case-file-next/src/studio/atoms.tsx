import type { ReactNode } from 'react';
import { stageLabel, type StageKey } from './data';

export function Kbd({ children }: { children: ReactNode }) {
  return <kbd className="cf-kbd">{children}</kbd>;
}

export function StageChip({ stage, status, label: override }: { stage: StageKey; status?: string; label?: string }) {
  const label = override ?? (status === 'REJECTED' ? 'Rejected' : status === 'FAILED' ? 'Needs attention' : stage === 'unknown' ? 'Unrecognized' : stageLabel(stage));
  return (
    <span className="cf-stage" data-stage={stage}>
      <span className="cf-stage-dot" aria-hidden="true" />
      {label}
    </span>
  );
}

/**
 * The redaction strip: one mark per open finding. Highlighter marks still need a person;
 * an empty strip on a delivered case is drawn as a solid ink bar (everything settled).
 */
export function FindingStrip({ open, stage }: { open: number; stage: StageKey }) {
  if (open > 0) {
    const shown = Math.min(open, 12);
    return (
      <span className="cf-strip" aria-label={`${open} open finding${open === 1 ? '' : 's'}`}>
        {Array.from({ length: shown }, (_, i) => <span key={i} className="cf-strip-mark" />)}
        {open > shown && <span className="cf-strip-more">+{open - shown}</span>}
      </span>
    );
  }
  if (stage === 'delivered') return <span className="cf-strip" aria-label="All findings settled"><span className="cf-strip-settled" /></span>;
  return <span className="cf-strip-none" aria-label="No open findings">—</span>;
}

export function Spinner({ label = 'Loading' }: { label?: string }) {
  return <span className="cf-spinner" role="status" aria-label={label} />;
}

export function Notice({ tone = 'neutral', children }: { tone?: 'neutral' | 'danger' | 'mark'; children: ReactNode }) {
  return <div className="cf-notice" data-tone={tone} role={tone === 'danger' ? 'alert' : 'status'}>{children}</div>;
}
