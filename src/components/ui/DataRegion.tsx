import type { ReactNode } from 'react';
import EmptyState from './EmptyState';
import Skeleton from './Skeleton';

type Props = {
  title: string; summary?: ReactNode; actions?: ReactNode; loading?: boolean; empty?: boolean; emptyMessage?: ReactNode; children: ReactNode; className?: string;
};

/** Shared result-region pattern for tables, lists and investigation results. */
export default function DataRegion({ title, summary, actions, loading = false, empty = false, emptyMessage = 'No results.', children, className = '' }: Props) {
  return (
    <section className={`ui-data-region${className ? ` ${className}` : ''}`} aria-busy={loading || undefined}>
      <div className="ui-data-region-header">
        <div><h2>{title}</h2>{summary && <div className="ui-data-region-summary">{summary}</div>}</div>
        {actions && <div className="ui-data-region-actions">{actions}</div>}
      </div>
      {loading ? <Skeleton className="ui-data-state" /> : empty ? <EmptyState>{emptyMessage}</EmptyState> : children}
    </section>
  );
}
