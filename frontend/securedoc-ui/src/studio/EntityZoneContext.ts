import { createContext, useContext } from 'react';
import { zoneOrBrowser } from './entityZone';

/** Task 0058: the entity's time zone (from GET /me), or null when none is set. */
export const EntityZoneContext = createContext<string | null>(null);

/** The zone days and times are shown in: the entity's, else the browser's. */
export function useEntityZone(): string {
  return zoneOrBrowser(useContext(EntityZoneContext));
}

/** The entity's own answer, null when none is set. */
export function useEntityZoneSet(): string | null {
  return useContext(EntityZoneContext);
}
