import type { ReactNode } from 'react';

export default function InfoGrid({ items, className = '' }: { items: Array<{ label: string; value: ReactNode; emphasis?: boolean }>; className?: string }) {
  return (
    <dl className={`ui-info-grid ${className}`.trim()}>
      {items.map((item) => (
        <div key={item.label} className={item.emphasis ? 'is-emphasis' : ''}>
          <dt>{item.label}</dt>
          <dd>{item.value}</dd>
        </div>
      ))}
    </dl>
  );
}
