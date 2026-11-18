import { useEffect, useId, useRef, useState } from 'react';
import { ChevronDown } from 'lucide-react';
import type { TenantChoice, TenantSwitchActions } from '../types';
import { hasPendingWork } from '../api/pendingWork';
import { selectedOidcTenant } from '../api/tenantContext';

/**
 * The organization name at the top right. For a person with two or more organizations it is the switch;
 * otherwise it is plain text. The switching itself is App's single implementation (TenantSwitchActions).
 */
export default function EntitySwitch({ label, tenantName, actions }: { label: string; tenantName: string; actions?: TenantSwitchActions }) {
  const [switchable, setSwitchable] = useState(false);
  const [open, setOpen] = useState(false);
  const [choices, setChoices] = useState<TenantChoice[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [confirmFor, setConfirmFor] = useState<TenantChoice | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const panelId = useId();
  const current = selectedOidcTenant();

  // Checked once per mount: the workspace is remounted for every organization, so this is per organization.
  const hasActions = Boolean(actions);
  useEffect(() => {
    if (!actions) return;
    let live = true;
    actions.list().then((list) => { if (live) setSwitchable(Boolean(list)); }).catch(() => { if (live) setSwitchable(false); });
    return () => { live = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hasActions]);

  useEffect(() => {
    if (!open) return;
    const onPointer = (event: MouseEvent) => {
      if (!panelRef.current?.contains(event.target as Node) && !triggerRef.current?.contains(event.target as Node)) close(false);
    };
    document.addEventListener('mousedown', onPointer);
    return () => document.removeEventListener('mousedown', onPointer);
  }, [open]);

  useEffect(() => {
    if (open && !loading) panelRef.current?.querySelector<HTMLButtonElement>('button:not([disabled])')?.focus();
  }, [open, loading, confirmFor]);

  if (!actions || !switchable) return <span className="cf-me-org">{label}</span>;

  function close(returnFocus = true) {
    setOpen(false);
    setConfirmFor(null);
    if (returnFocus) triggerRef.current?.focus();
  }

  async function openPanel() {
    if (!actions) return;
    setMessage(null);
    setConfirmFor(null);
    setOpen(true);
    setLoading(true);
    try {
      // Read fresh from the server every time the list is opened.
      setChoices(await actions.list());
    } catch {
      setChoices(null);
    } finally {
      setLoading(false);
    }
  }

  async function enter(choice: TenantChoice) {
    if (!actions) return;
    setConfirmFor(null);
    setBusy(true);
    const entered = await actions.enter(choice.tenantId);
    // On success this whole workspace is unmounted and rebuilt for the new organization.
    if (!entered) {
      setBusy(false);
      setOpen(false);
      setMessage(`Could not switch to ${choice.name}. You are still in ${tenantName}.`);
      triggerRef.current?.focus();
    }
  }

  function choose(choice: TenantChoice) {
    if (choice.tenantId === current) return;
    if (hasPendingWork()) setConfirmFor(choice);
    else void enter(choice);
  }

  return (
    <span className="cf-org-switch">
      <button
        ref={triggerRef}
        type="button"
        className="cf-org-switch-trigger"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        aria-label={`Switch organization. Current organization: ${tenantName}`}
        disabled={busy}
        onClick={() => (open ? close() : void openPanel())}
      >
        <span>{label}</span>
        <ChevronDown size={14} aria-hidden="true" />
      </button>

      {open && (
        <div
          ref={panelRef}
          id={panelId}
          role="dialog"
          aria-label="Switch organization"
          className="cf-org-switch-panel"
          onKeyDown={(event) => { if (event.key === 'Escape') { event.stopPropagation(); close(); } }}
        >
          {confirmFor ? (
            <div role="alertdialog" aria-label="Unsaved work" aria-describedby={`${panelId}-confirm`} className="cf-org-switch-confirm">
              <p id={`${panelId}-confirm`}>
                You have unsaved or in-progress work on this screen. Switching to {confirmFor.name} discards it.
              </p>
              <div className="cf-org-switch-actions">
                <button type="button" className="cf-btn" data-size="sm" onClick={() => close()}>Stay</button>
                <button type="button" className="cf-btn" data-size="sm" data-variant="solid" onClick={() => void enter(confirmFor)}>Switch anyway</button>
              </div>
            </div>
          ) : loading ? (
            <p role="status" className="cf-org-switch-note">Loading organizations…</p>
          ) : !choices ? (
            <p role="status" className="cf-org-switch-note">Your organizations could not be loaded. Close this and try again.</p>
          ) : (
            <ul className="cf-org-switch-list">
              {choices.map((choice) => {
                const isCurrent = choice.tenantId === current;
                return (
                  <li key={choice.tenantId}>
                    <button
                      type="button"
                      className="cf-org-switch-option"
                      aria-current={isCurrent ? 'true' : undefined}
                      disabled={isCurrent || busy}
                      onClick={() => choose(choice)}
                    >
                      <span>{choice.name}</span>
                      {isCurrent && <span className="cf-org-switch-current">Current</span>}
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      )}

      {message && <span role="alert" className="cf-org-switch-message">{message}</span>}
    </span>
  );
}
