import type { KeyboardEvent } from 'react';

/**
 * Task 0089 (WCAG 2.1.1, the radio group and tabs patterns): a set of buttons with role "radio" or "tab" is one Tab stop,
 * and the arrow keys, Home and End move focus in it and choose. Each button takes tabIndex 0 when chosen, else -1.
 */
export function onArrowKeys(event: KeyboardEvent<HTMLElement>) {
  const items = Array.from(event.currentTarget.querySelectorAll<HTMLButtonElement>('[role="radio"]:not(:disabled), [role="tab"]:not(:disabled)'));
  const i = items.indexOf(document.activeElement as HTMLButtonElement);
  if (i < 0 || event.ctrlKey || event.metaKey || event.altKey) return;
  const radio = items[i].getAttribute('role') === 'radio';
  const to = event.key === 'ArrowRight' || (radio && event.key === 'ArrowDown') ? (i + 1) % items.length
    : event.key === 'ArrowLeft' || (radio && event.key === 'ArrowUp') ? (i + items.length - 1) % items.length
      : event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1 : -1;
  if (to < 0) return;
  event.preventDefault();
  items[to].focus();
  items[to].click();
}
