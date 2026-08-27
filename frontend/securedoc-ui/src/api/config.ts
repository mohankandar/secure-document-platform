/**
 * API configuration for local/demo mode.
 * All values are configurable in this single file.
 */
export const API_BASE_URL = '/api/v1';

export const DEFAULT_TENANT_ID = '00000000-0000-0000-0000-000000000001';
export const DEFAULT_ACTOR_ID = '00000000-0000-0000-0000-000000000099';

/**
 * Generates a new correlation ID per request using crypto.randomUUID().
 */
export function generateCorrelationId(): string {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID();
  }
  // Fallback for environments without crypto.randomUUID
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export function defaultHeaders(): Record<string, string> {
  return {
    'Content-Type': 'application/json',
    'X-Tenant-Id': DEFAULT_TENANT_ID,
    'X-Actor-Id': DEFAULT_ACTOR_ID,
    'X-Correlation-Id': generateCorrelationId(),
  };
}

/**
 * Returns headers suitable for non-JSON requests (upload/download).
 * Does not include Content-Type so the browser can set it for FormData.
 */
export function baseHeaders(): Record<string, string> {
  return {
    'X-Tenant-Id': DEFAULT_TENANT_ID,
    'X-Actor-Id': DEFAULT_ACTOR_ID,
    'X-Correlation-Id': generateCorrelationId(),
  };
}
