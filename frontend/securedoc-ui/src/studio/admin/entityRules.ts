/** Task 0058: the words and choices of Admin › Entities. Free of React so the rules run under `node --test`. */

export interface EntitySlotLike {
  slotId: string;
  name: string | null;
  status: string;
  current: boolean;
  timeZone: string | null;
  customerTimeZone: string | null;
}

export type EntityAction = 'activate' | 'rename' | 'suspend' | 'resume' | 'timeZone';

export const ENTITY_NAME_MAX = 200;

/** The name a Business Admin gave it, else what it is: an entity without a name yet. */
export function entityName(slot: Pick<EntitySlotLike, 'name'>): string {
  return slot.name?.trim() || 'Not named yet';
}

const LABEL: Record<EntityAction, string> = { activate: 'Activate', rename: 'Rename', suspend: 'Suspend', resume: 'Resume', timeZone: 'Time zone' };
const actions = (...list: EntityAction[]) => list.map((action) => ({ action, label: LABEL[action] }));

/** How the status reads, and what can be done. Anything unknown offers nothing. The entity you are in is not suspended by you. */
export function entityStatus(slot: Pick<EntitySlotLike, 'status' | 'current'>): { label: string; tone?: 'ok'; actions: { action: EntityAction; label: string }[] } {
  switch (slot.status) {
    case 'ACTIVE': return { label: 'Active', tone: 'ok', actions: slot.current ? actions('rename', 'timeZone') : actions('rename', 'timeZone', 'suspend') };
    case 'SUSPENDED': return { label: 'Suspended', actions: actions('rename', 'timeZone', 'resume') };
    case 'NOT_ACTIVATED': return { label: 'Not activated', actions: actions('activate') };
    case 'NOT_IN_LICENCE': return { label: 'Not in the licence', actions: [] };
    default: return { label: 'Unknown', actions: [] };
  }
}

export function timeZoneText(slot: Pick<EntitySlotLike, 'timeZone' | 'customerTimeZone'>): string {
  if (slot.timeZone) return slot.timeZone;
  return slot.customerTimeZone ? `${slot.customerTimeZone} (the customer’s)` : 'Not set';
}

/** The time zone names to choose from, the one already chosen always among them. The server decides which it takes. */
export function timeZoneNames(chosen?: string | null): string[] {
  const all = new Set<string>(['UTC']);
  try {
    for (const name of (Intl as unknown as { supportedValuesOf(key: string): string[] }).supportedValuesOf('timeZone')) all.add(name);
  } catch { /* an older browser: UTC and the one already chosen */ }
  if (chosen) all.add(chosen);
  return [...all].sort();
}
