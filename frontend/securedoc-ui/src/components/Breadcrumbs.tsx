import { Link } from 'react-router-dom';
import type { BusinessIdentifier } from '../types';
import './Breadcrumbs.css';

export interface BreadcrumbItem {
  label: string;
  to?: string;
}

interface BreadcrumbsProps {
  items: BreadcrumbItem[];
}

export function intakeBreadcrumbLabel(identifier?: BusinessIdentifier | null) {
  if (!identifier?.value) return 'Intake Details';

  const configuredLabel = identifier.label.trim() || 'Intake';
  const conciseLabel = /^contract number$/i.test(configuredLabel) ? 'Contract' : configuredLabel;
  return `${conciseLabel} ${identifier.value}`;
}

export default function Breadcrumbs({ items }: BreadcrumbsProps) {
  const trail: BreadcrumbItem[] = [{ label: 'Dashboard', to: '/' }, ...items];

  return (
    <nav className="breadcrumbs" aria-label="Breadcrumb">
      <ol>
        {trail.map((item, index) => {
          const isCurrent = index === trail.length - 1;
          return (
            <li key={`${item.label}-${index}`}>
              {index > 0 && <span className="breadcrumb-separator" aria-hidden="true">›</span>}
              {!isCurrent && item.to ? (
                <Link to={item.to}>{item.label}</Link>
              ) : (
                <span className="breadcrumb-current" aria-current={isCurrent ? 'page' : undefined}>
                  {item.label}
                </span>
              )}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
