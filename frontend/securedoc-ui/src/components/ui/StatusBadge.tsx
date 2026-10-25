function humanize(value: string) {
  return value.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function tone(value: string) {
  const normalized = value.toUpperCase();
  if (['COMPLETED', 'EXPORTED', 'DELIVERED', 'ACTIVE', 'READY', 'HEALTHY', 'APPROVED', 'SUCCESS', 'CONFIGURED', 'AVAILABLE', 'ENFORCED'].includes(normalized)) return 'success';
  if (['FAILED', 'BLOCKED', 'EXPIRED', 'REJECTED', 'INVALID_SIGNATURE', 'INVALID', 'UNHEALTHY', 'DENIED', 'ATTENTION_REQUIRED', 'TECHNICAL_ATTENTION', 'NOT_READY'].includes(normalized)) return 'danger';
  if (['PENDING_REVIEW', 'IN_REVIEW', 'NEEDS_REVIEW', 'PENDING_FINAL_APPROVAL', 'REVIEW', 'RETRY_PENDING', 'NOT_YET_VALID', 'NEEDS_APPROVAL', 'CANDIDATE', 'DEGRADED', 'CONTINUED'].includes(normalized)) return 'warning';
  if (['PROCESSING', 'REPROCESSING', 'SUBMITTED', 'DELIVERING', 'OBSERVE', 'OPEN'].includes(normalized)) return 'info';
  return 'neutral';
}

export default function StatusBadge({ value, label }: { value: string; label?: string }) {
  return <span className={`ui-status-badge tone-${tone(value)}`}>{label || humanize(value)}</span>;
}
