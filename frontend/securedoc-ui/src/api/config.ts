import { getValidAccessToken, isLocalSecurityMode } from './auth';

export const API_BASE_URL = '/api/v1';
export const DEFAULT_TENANT_ID = '00000000-0000-0000-0000-000000000001';
export const DEFAULT_ACTOR_ID = '00000000-0000-0000-0000-000000000099';
export const DEFAULT_ACTOR_ROLES = 'DOCUMENT_PROCESSOR';

export const LOCAL_DEMO_ACTOR_KEY = 'lexavault-local-demo-actor-id';
export function getLocalDemoActorId(): string | null {
  return typeof window === 'undefined' ? null : window.sessionStorage.getItem(LOCAL_DEMO_ACTOR_KEY);
}
export function setLocalDemoActorId(actorId: string | null) {
  if (typeof window === 'undefined') return;
  if (actorId) window.sessionStorage.setItem(LOCAL_DEMO_ACTOR_KEY, actorId);
  else window.sessionStorage.removeItem(LOCAL_DEMO_ACTOR_KEY);
}

let currentRoles = DEFAULT_ACTOR_ROLES;
let currentCapabilities: string[] = [];

export function setCurrentRoles(roles: string) { currentRoles = roles; }
export function getCurrentRoles(): string { return currentRoles; }
export function setCurrentCapabilities(capabilities: string[]) { currentCapabilities = [...capabilities]; }
export function getCurrentCapabilities(): string[] { return [...currentCapabilities]; }
export function hasCurrentCapability(capability: string): boolean { return currentCapabilities.includes(capability); }

export function generateCorrelationId(): string {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID();
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

async function identityHeaders(): Promise<Record<string, string>> {
  if (isLocalSecurityMode()) {
    return {
      'X-Tenant-Id': DEFAULT_TENANT_ID,
      'X-Actor-Id': getLocalDemoActorId() ?? DEFAULT_ACTOR_ID,
      'X-Actor-Roles': currentRoles,
    };
  }
  const token = await getValidAccessToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export async function defaultHeaders(): Promise<Record<string, string>> {
  return { 'Content-Type': 'application/json', ...(await identityHeaders()), 'X-Correlation-Id': generateCorrelationId() };
}

export async function baseHeaders(): Promise<Record<string, string>> {
  return { ...(await identityHeaders()), 'X-Correlation-Id': generateCorrelationId() };
}
