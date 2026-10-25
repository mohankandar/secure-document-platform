import type { HTMLAttributes } from 'react';

export default function Skeleton({ className = '', ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={`ui-skeleton${className ? ` ${className}` : ''}`} role="status" aria-label="Loading" {...props} />;
}
