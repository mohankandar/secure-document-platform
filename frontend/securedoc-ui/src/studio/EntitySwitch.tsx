import { useEffect, useState } from 'react';
import type { TenantChoice, TenantSwitchActions } from '../types';
import { hasPendingWork } from '../api/pendingWork';
import { selectedOidcTenant } from '../api/tenantContext';

/**
 * The entity switch (task 0009), now shown inside the account menu (UI task U002). The logic is unchanged: the
 * switching itself is App's single implementation (TenantSwitchActions), the server is asked first, so a refused
 * choice changes nothing on screen, and unsaved work asks for confirmation first.
 */
export interface EntitySwitchState {
  /** True when the person has two or more entities; otherwise the entity is shown as plain text. */
  switchable: boolean;
  /** The entities, read fresh each time the menu opens; null while loading or when they could not be read. */
  choices: TenantChoice[] | null;
  loading: boolean;
  failed: boolean;
  busy: boolean;
  current: string | null;
  /** The entity waiting for "Switch anyway" because there is unsaved work on screen. */
  confirmFor: TenantChoice | null;
  /** A refused switch, in plain words. */
  message: string | null;
  refresh: () => Promise<void>;
  choose: (choice: TenantChoice) => void;
  confirm: () => void;
  cancel: () => void;
  clearMessage: () => void;
}

export function useEntitySwitch(tenantName: string, actions: TenantSwitchActions | undefined, onRefused: () => void): EntitySwitchState {
  const [switchable, setSwitchable] = useState(false);
  const [choices, setChoices] = useState<TenantChoice[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [confirmFor, setConfirmFor] = useState<TenantChoice | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const current = selectedOidcTenant();

  // Checked once per mount: the workspace is remounted for every entity, so this is per entity.
  const hasActions = Boolean(actions);
  useEffect(() => {
    if (!actions) return;
    let live = true;
    actions.list().then((list) => { if (live) setSwitchable(Boolean(list)); }).catch(() => { if (live) setSwitchable(false); });
    return () => { live = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hasActions]);

  async function refresh() {
    if (!actions || !switchable) return;
    setMessage(null);
    setConfirmFor(null);
    setLoading(true);
    setFailed(false);
    try {
      // Read fresh from the server every time the list is opened.
      const list = await actions.list();
      setChoices(list);
      setFailed(!list);
    } catch {
      setChoices(null);
      setFailed(true);
    } finally {
      setLoading(false);
    }
  }

  async function enter(choice: TenantChoice) {
    if (!actions) return;
    setConfirmFor(null);
    setBusy(true);
    const entered = await actions.enter(choice.tenantId);
    // On success this whole workspace is unmounted and rebuilt for the new entity.
    if (!entered) {
      setBusy(false);
      setMessage(`Could not switch to ${choice.name}. You are still in ${tenantName}.`);
      onRefused();
    }
  }

  return {
    switchable: Boolean(actions) && switchable,
    choices, loading, failed, busy, current, confirmFor, message,
    refresh,
    choose: (choice) => {
      if (choice.tenantId === current || busy) return;
      if (hasPendingWork()) setConfirmFor(choice);
      else void enter(choice);
    },
    confirm: () => { if (confirmFor) void enter(confirmFor); },
    cancel: () => setConfirmFor(null),
    clearMessage: () => setMessage(null),
  };
}
