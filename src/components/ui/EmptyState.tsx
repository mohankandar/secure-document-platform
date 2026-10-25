import type { HTMLAttributes, ReactNode } from 'react';

export default function EmptyState({ children, className = '', ...props }: HTMLAttributes<HTMLDivElement> & { children: ReactNode }) {
  return <div className={`ui-empty-state${className ? ` ${className}` : ''}`} {...props}>{children}</div>;
}
