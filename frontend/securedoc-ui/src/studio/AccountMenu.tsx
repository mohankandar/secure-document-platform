import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { Check, ChevronDown } from 'lucide-react';
import type { CurrentUser, TenantSwitchActions } from '../types';
import { ROLE_OPTIONS } from '../security/uiAuthorization';
import { useEntitySwitch } from './EntitySwitch';
import { useThemePreference, type ThemePreference } from './theme';
import { initials } from './userName';

const THEMES: { value: ThemePreference; label: string }[] = [
  { value: 'light', label: 'Light' },
  { value: 'dark', label: 'Dark' },
  { value: 'system', label: 'System' },
];

const ITEM_SELECTOR = '[role="menuitem"], [role="menuitemradio"]';

/**
 * The one account button at the top right (UI spec finding 1): the person, the entity switch, the theme, the local
 * test role (local security mode only) and sign out. A real menu: Enter, Space or ↓ opens it on the first item,
 * ↑/↓/Home/End move, Esc closes and returns focus to the button, Tab closes.
 */
export default function AccountMenu({ me, organizationName, onSignOut, localMode, selectedRole, onRoleChange, tenantSwitch }: {
  me: CurrentUser;
  organizationName?: string;
  onSignOut: () => void;
  localMode: boolean;
  selectedRole: string;
  onRoleChange: (role: string) => void;
  tenantSwitch?: TenantSwitchActions;
}) {
  const [open, setOpen] = useState(false);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const menuId = useId();
  const [theme, setTheme] = useThemePreference();
  const name = displayName(me.username);
  // A refused switch closes the menu; focus returns to the button once it is enabled again.
  const entities = useEntitySwitch(me.tenantName, localMode ? undefined : tenantSwitch, () => {
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

  // Focus the first item when the menu opens, and keep focus inside it while the entity list loads.
  useEffect(() => {
    if (!open) return;
    const target = entities.confirmFor
      ? panelRef.current?.querySelector<HTMLElement>('[data-confirm-stay]')
      : panelRef.current?.querySelector<HTMLElement>(ITEM_SELECTOR);
    if (target && !panelRef.current?.contains(document.activeElement)) target.focus();
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

  function onButtonKey(event: KeyboardEvent<HTMLButtonElement>) {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      if (!open) openMenu();
    }
  }

  function onMenuKey(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      close();
      return;
    }
    if (event.key === 'Tab') {
      close(false);
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

  const roleNames = me.roles.map((r) => ROLE_OPTIONS.find((o) => o.value === r)?.label ?? sentence(r));

  return (
    <div className="cf-acct">
      <button
        ref={buttonRef}
        type="button"
        className="cf-acct-button"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        aria-label={`Account menu. ${name}, ${me.tenantName}`}
        disabled={entities.busy}
        onClick={() => (open ? close() : openMenu())}
        onKeyDown={onButtonKey}
      >
        <span className="cf-acct-avatar" aria-hidden="true">{initials(me.username)}</span>
        <span className="cf-acct-text" aria-hidden="true">
          <span className="cf-acct-name">{name}</span>
          <span className="cf-acct-entity">{me.tenantName}</span>
        </span>
        <ChevronDown size={14} aria-hidden="true" />
      </button>

      {open && (
        <div ref={panelRef} className="cf-acct-panel" onKeyDown={onMenuKey}>
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
          ) : (
            <div id={menuId} role="menu" aria-label="Account" className="cf-acct-menu">
              <div className="cf-acct-person" role="presentation">
                <span className="cf-acct-avatar" aria-hidden="true">{initials(me.username)}</span>
                <span className="cf-acct-person-text">
                  <span className="cf-acct-person-name">{name}</span>
                  <span className="cf-acct-person-id">{me.username}</span>
                  <span className="cf-acct-person-roles">{roleNames.join(', ')}</span>
                </span>
              </div>

              <Section label={organizationName ? `${organizationName} · Entity` : 'Entity'}>
                {!entities.switchable ? (
                  <div className="cf-acct-plain">{me.tenantName}</div>
                ) : entities.loading ? (
                  <div className="cf-acct-note" role="status">Loading your entities…</div>
                ) : entities.failed || !entities.choices ? (
                  <div className="cf-acct-note" role="status">Your entities could not be loaded. Close this menu and try again.</div>
                ) : (
                  <div role="group" aria-label="Entities">
                    {entities.choices.map((choice) => {
                      const isCurrent = choice.tenantId === entities.current;
                      return (
                        <Item key={choice.tenantId} radio checked={isCurrent} disabled={entities.busy}
                          onSelect={() => entities.choose(choice)}
                          hint={isCurrent ? 'Current' : 'Switch'}>
                          {choice.name}
                        </Item>
                      );
                    })}
                  </div>
                )}
              </Section>

              <Section label="Theme">
                <div role="group" aria-label="Theme" className="cf-acct-row">
                  {THEMES.map((t) => (
                    <Item key={t.value} radio compact checked={theme === t.value} onSelect={() => setTheme(t.value)}>{t.label}</Item>
                  ))}
                </div>
              </Section>

              {localMode && (
                <Section label="Test role · local only">
                  <div role="group" aria-label="Test role">
                    {ROLE_OPTIONS.map((role) => (
                      <Item key={role.value} radio checked={selectedRole === role.value}
                        onSelect={() => { close(); if (role.value !== selectedRole) onRoleChange(role.value); }}>
                        {role.label}
                      </Item>
                    ))}
                  </div>
                </Section>
              )}

              <div className="cf-acct-sep" role="separator" />
              <Item onSelect={() => { setOpen(false); onSignOut(); }}>Sign out</Item>
            </div>
          )}
        </div>
      )}

      {entities.message && <span role="alert" className="cf-acct-message">{entities.message}</span>}
    </div>
  );
}

function Section({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="cf-acct-section" role="presentation">
      <span className="cf-acct-label" aria-hidden="true">{label}</span>
      {children}
    </div>
  );
}

function Item({ children, onSelect, radio, checked, disabled, hint, compact }: {
  children: ReactNode; onSelect: () => void; radio?: boolean; checked?: boolean; disabled?: boolean; hint?: string; compact?: boolean;
}) {
  return (
    <button
      type="button"
      role={radio ? 'menuitemradio' : 'menuitem'}
      aria-checked={radio ? Boolean(checked) : undefined}
      aria-disabled={disabled || undefined}
      tabIndex={-1}
      className="cf-acct-item"
      data-compact={compact || undefined}
      onClick={() => { if (!disabled) onSelect(); }}
    >
      {radio && <span className="cf-acct-check" aria-hidden="true">{checked && <Check size={14} />}</span>}
      <span className="cf-acct-item-text">{children}</span>
      {hint && !checked && <span className="cf-acct-hint" aria-hidden="true">{hint}</span>}
    </button>
  );
}

export function displayName(username: string): string {
  const local = username.split('@')[0] ?? username;
  const parts = local.split(/[._-]+/).filter(Boolean);
  if (parts.length >= 2) return `${cap(parts[0])}. ${cap(parts[parts.length - 1])}`.replace(/^(\w)\w*\./, '$1.');
  return cap(local);
}

function cap(word: string): string {
  return word.charAt(0).toUpperCase() + word.slice(1);
}

function sentence(token: string): string {
  const lower = token.replace(/_/g, ' ').toLowerCase();
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}
