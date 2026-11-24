import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { ChevronDown } from 'lucide-react';
import type { TenantSwitchActions } from '../types';
import { Item } from './AccountMenu';
import { useEntitySwitch } from './EntitySwitch';

const ITEM_SELECTOR = '[role="menuitemradio"]';

/**
 * The entity in the top bar (task 0058): its name is always shown, on every screen and at every width. A person with more
 * than one entity switches here (the switch was inside the account menu until now; its logic is unchanged): the server is
 * asked first, unsaved work asks before it is lost, and a refused switch changes nothing. The same keys as the account menu.
 */
export default function EntityMenu({ name, tenantSwitch }: { name: string; tenantSwitch?: TenantSwitchActions }) {
  const [open, setOpen] = useState(false);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const menuId = useId();
  const entities = useEntitySwitch(name, tenantSwitch, () => {
    setOpen(false);
    setTimeout(() => buttonRef.current?.focus(), 0);
  });

  function close(returnFocus = true) {
    setOpen(false);
    entities.cancel();
    if (returnFocus) buttonRef.current?.focus();
  }

  function openMenu() {
    entities.clearMessage();
    setOpen(true);
    void entities.refresh();
  }

  // Focus the first item once the list is there, and "Stay" when the switch asks first.
  useEffect(() => {
    if (!open) return;
    const target = entities.confirmFor
      ? panelRef.current?.querySelector<HTMLElement>('[data-confirm-stay]')
      : panelRef.current?.querySelector<HTMLElement>(ITEM_SELECTOR) ?? panelRef.current;
    if (target && (target === panelRef.current || !panelRef.current?.contains(document.activeElement) || document.activeElement === panelRef.current)) target.focus();
  }, [open, entities.loading, entities.confirmFor]);

  useEffect(() => {
    if (!open) return;
    const onPointer = (event: MouseEvent) => {
      if (!panelRef.current?.contains(event.target as Node) && !buttonRef.current?.contains(event.target as Node)) close(false);
    };
    document.addEventListener('mousedown', onPointer);
    return () => document.removeEventListener('mousedown', onPointer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  function onMenuKey(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      close();
      return;
    }
    if (event.key === 'Tab') {
      if (!entities.confirmFor) close(false);
      return;
    }
    if (entities.confirmFor) return;
    const items = Array.from(panelRef.current?.querySelectorAll<HTMLElement>(ITEM_SELECTOR) ?? []);
    if (!items.length) return;
    const index = items.indexOf(document.activeElement as HTMLElement);
    let next = -1;
    if (event.key === 'ArrowDown') next = (index + 1) % items.length;
    else if (event.key === 'ArrowUp') next = (index - 1 + items.length) % items.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = items.length - 1;
    if (next >= 0) {
      event.preventDefault();
      items[next].focus();
    }
  }

  if (!entities.switchable) {
    return <span className="cf-entity cf-entity-name" title={name}><span className="cf-sr">Entity: </span>{name}</span>;
  }

  return (
    <div className="cf-acct cf-entity">
      <button ref={buttonRef} type="button" className="cf-acct-button cf-entity-button" title={name} aria-haspopup="menu" aria-expanded={open}
        aria-controls={open ? menuId : undefined} disabled={entities.busy} onClick={() => (open ? close() : openMenu())}
        onKeyDown={(event) => { if ((event.key === 'ArrowDown' || event.key === 'ArrowUp') && !open) { event.preventDefault(); openMenu(); } }}>
        <span className="cf-entity-name">{name}</span>
        <span className="cf-sr">, switch entity</span>
        <ChevronDown size={14} aria-hidden="true" />
      </button>

      {open && (
        <div ref={panelRef} className="cf-acct-panel" tabIndex={-1} onKeyDown={onMenuKey}>
          {entities.confirmFor ? (
            <div role="alertdialog" aria-label="Unsaved work" aria-describedby={`${menuId}-confirm`} className="cf-acct-confirm">
              <p id={`${menuId}-confirm`}>
                You have unsaved or in-progress work on this screen. Switching to {entities.confirmFor.name} discards it.
              </p>
              <div className="cf-acct-confirm-actions">
                <button type="button" className="cf-btn" data-size="sm" data-confirm-stay onClick={() => close()}>Stay</button>
                <button type="button" className="cf-btn" data-size="sm" data-variant="solid" onClick={() => entities.confirm()}>Switch anyway</button>
              </div>
            </div>
          ) : entities.loading ? (
            <div className="cf-acct-note" role="status">Loading your entities…</div>
          ) : entities.failed || !entities.choices ? (
            <div className="cf-acct-note" role="status">Your entities could not be loaded. Close this and try again.</div>
          ) : (
            <div id={menuId} role="menu" aria-label="Entities" className="cf-acct-menu">
              {entities.choices.map((choice) => {
                const isCurrent = choice.tenantId === entities.current;
                return (
                  <Item key={choice.tenantId} radio checked={isCurrent} disabled={entities.busy}
                    onSelect={() => entities.choose(choice)} hint={isCurrent ? 'Current' : 'Switch'}>
                    {choice.name}
                  </Item>
                );
              })}
            </div>
          )}
        </div>
      )}

      {entities.message && <span role="alert" className="cf-acct-message">{entities.message}</span>}
    </div>
  );
}
