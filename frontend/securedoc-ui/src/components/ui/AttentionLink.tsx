import { Link } from 'react-router-dom';

export type AttentionValue = 'REVIEW' | 'APPROVAL' | 'TECHNICAL' | 'REJECTED' | 'NONE' | string;

type Props = {
  value: AttentionValue;
  to?: string;
  label?: string;
  className?: string;
};

function attentionTone(value: AttentionValue) {
  switch (value.toUpperCase()) {
    case 'REVIEW': return 'warning';
    case 'APPROVAL': return 'info';
    case 'TECHNICAL': return 'danger';
    case 'REJECTED': return 'neutral';
    default: return 'neutral';
  }
}

function attentionLabel(value: AttentionValue) {
  switch (value.toUpperCase()) {
    case 'REVIEW': return 'Review';
    case 'APPROVAL': return 'Approval';
    case 'TECHNICAL': return 'Technical';
    case 'REJECTED': return 'Rejected';
    case 'NONE': return '—';
    default: return value.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, (letter) => letter.toUpperCase());
  }
}

/**
 * Canonical actionable-attention primitive.
 * Kept separate from StatusBadge because attention can navigate to the work that needs action.
 */
export default function AttentionLink({ value, to, label, className = '' }: Props) {
  const classes = ['ui-attention', `tone-${attentionTone(value)}`, className].filter(Boolean).join(' ');
  const content = label ?? attentionLabel(value);
  return to
    ? <Link className={classes} to={to}>{content}</Link>
    : <span className={classes}>{content}</span>;
}
