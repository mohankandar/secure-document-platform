import type { HTMLAttributes, ReactNode } from 'react';

type Props = HTMLAttributes<HTMLElement> & { children: ReactNode; as?: 'section' | 'article' | 'div' | 'aside' };

/** Canonical SecureDocs surface. Feature classes may add layout only. */
export default function Card({ children, className = '', as: Element = 'section', ...props }: Props) {
  return <Element className={`ui-card${className ? ` ${className}` : ''}`} {...props}>{children}</Element>;
}
