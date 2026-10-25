import type { ReactNode } from 'react';
import Breadcrumbs, { type BreadcrumbItem } from '../Breadcrumbs';
import PageHeader from './PageHeader';

type Props = {
  className?: string;
  breadcrumbs: BreadcrumbItem[];
  title: string;
  eyebrow?: string;
  description?: string;
  actions?: ReactNode;
  primaryNavigation?: ReactNode;
  children: ReactNode;
};

/**
 * Canonical LexaVault page anatomy: breadcrumb -> title/actions -> primary navigation -> content.
 * Feature-level tabs and filters belong inside children, after the primary navigation.
 */
export default function PageScaffold({ className = '', breadcrumbs, title, eyebrow, description, actions, primaryNavigation, children }: Props) {
  return (
    <div className={`ui-page-scaffold${className ? ` ${className}` : ''}`}>
      <Breadcrumbs items={breadcrumbs} />
      <PageHeader eyebrow={eyebrow} title={title} description={description} actions={actions} />
      {primaryNavigation && <div className="ui-page-primary-navigation">{primaryNavigation}</div>}
      <div className="ui-page-content">{children}</div>
    </div>
  );
}
