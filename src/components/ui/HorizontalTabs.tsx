import type { ReactNode } from 'react';

type Props = {
  ariaLabel: string;
  children: ReactNode;
  className?: string;
};

/**
 * Shared LexaVault horizontal-tab rail. Visual behavior intentionally mirrors
 * the SDV workspace-tab standard while callers retain their own authorization
 * and state/routing rules.
 */
export default function HorizontalTabs({ ariaLabel, children, className = '' }: Props) {
  return (
    <nav className={`lv-horizontal-tabs${className ? ` ${className}` : ''}`} aria-label={ariaLabel}>
      {children}
    </nav>
  );
}
