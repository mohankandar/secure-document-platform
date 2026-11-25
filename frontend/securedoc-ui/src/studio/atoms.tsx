import { Children, type ReactNode } from 'react';
import { stageLabel, type StageKey } from './data';
import { exportStatusLabel, exportTone, reasonLabel, type ExportState } from './exportRules';
import { LvCodeLink, LvText } from './help/LvCodeLink';

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

/** Task 0029: "Exporting · ready" … "Exported", one colour per export state (studio.css [data-export]). */
export function ExportChip({ state }: { state: ExportState }) {
  const label = exportStatusLabel(state);
  if (!label) return null;
  return (
    <span className="cf-stage" data-export={exportTone(state) ?? undefined} data-testid="export-status">
      <span className="cf-stage-dot" aria-hidden="true" />
      {label}
    </span>
  );
}

export function Spinner({ label = 'Loading' }: { label?: string }) {
  return <span className="cf-spinner" role="status" aria-label={label} />;
}

export function Notice({ tone = 'neutral', children }: { tone?: 'neutral' | 'danger' | 'mark'; children: ReactNode }) {
  return <div className="cf-notice" data-tone={tone} role={tone === 'danger' ? 'alert' : 'status'}>{Children.map(children, (c) => (typeof c === 'string' ? <LvText>{c}</LvText> : c))}</div>;
}

/** Task 0029: "Destination refused the file · LV-DLV-004", the code in the code style (it never breaks inside).
 * Task 0082: the code links to its entry in the guide; `plain` where the code stands inside another link. */
export function CodeLabel({ code, plain }: { code: string | null | undefined; plain?: boolean }) {
  const label = reasonLabel(code);
  const cut = label.lastIndexOf(' · ');
  return <>{label.slice(0, cut)} · {plain ? <code className="cf-code">{label.slice(cut + 3)}</code> : <LvCodeLink code={label.slice(cut + 3)} />}</>;
}
