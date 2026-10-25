import type { ButtonHTMLAttributes, ReactNode } from 'react';

type Variant = 'primary' | 'secondary' | 'tertiary' | 'danger';
type Props = ButtonHTMLAttributes<HTMLButtonElement> & { children: ReactNode; variant?: Variant; size?: 'default' | 'small'; iconOnly?: boolean };

/** Canonical SecureDocs button. Links use the same .ui-button variant classes. */
export default function Button({ children, className = '', variant = 'secondary', size = 'default', iconOnly = false, ...props }: Props) {
  const classes = ['ui-button', variant, size === 'small' ? 'small' : '', iconOnly ? 'icon' : '', className].filter(Boolean).join(' ');
  return <button className={classes} {...props}>{children}</button>;
}
