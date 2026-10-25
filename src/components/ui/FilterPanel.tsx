import type { ReactNode } from 'react';
import Card from './Card';

/** Shared investigation/search filter surface. Feature pages own their filter semantics. */
export default function FilterPanel({ title, description, actions, children, className = '' }: { title?: string; description?: string; actions?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <Card className={`ui-filter-panel${className ? ` ${className}` : ''}`}>
      {(title || description || actions) && <div className="ui-filter-panel-header"><div>{title && <h2>{title}</h2>}{description && <p>{description}</p>}</div>{actions && <div className="ui-filter-panel-actions">{actions}</div>}</div>}
      {children}
    </Card>
  );
}
