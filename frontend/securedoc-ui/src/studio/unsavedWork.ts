import { useEffect } from 'react';
import { registerUnsavedWork } from '../api/pendingWork';

/**
 * Registers unsaved or in-progress work on screen while `active` is true, so an organization switch
 * asks for confirmation first. The registration is removed when `active` becomes false or the screen unmounts.
 */
export function useUnsavedWork(active: boolean): void {
  useEffect(() => (active ? registerUnsavedWork() : undefined), [active]);
}
