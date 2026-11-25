import { test, expect, type Page } from '@playwright/test';

/** Keyboard-only helpers: real key presses, and what has focus after each (U026 A2.2; shared with task 0089). */

export interface Focused { name: string; tag: string; ring: number }

/** What has focus, and how thick its visible focus indicator is (outline, or a box-shadow ring). */
export async function focused(page: Page): Promise<Focused> {
  const f = await measure(page);
  if (f.tag === 'body' || f.ring >= 2) return f;
  await page.waitForTimeout(250); // a ring that is still fading in
  return measure(page);
}

async function measure(page: Page): Promise<Focused> {
  return page.evaluate(() => {
    const el = document.activeElement as HTMLElement | null;
    if (!el || el === document.body) return { name: '', tag: 'body', ring: 0 };
    const s = getComputedStyle(el);
    const outline = s.outlineStyle !== 'none' ? parseFloat(s.outlineWidth) : 0;
    // box-shadow rings: the largest spread among "0 0 0 Npx" parts.
    const spreads = [...s.boxShadow.matchAll(/(?:^|,)\s*(?:rgba?\([^)]*\)|#\w+|[a-z]+)?\s*0px 0px 0px (\d+(?:\.\d+)?)px/g)].map((m) => parseFloat(m[1]));
    const inset = /inset/.test(s.boxShadow) ? Math.max(0, ...spreads) : 0;
    // A text field's ring is its border and the shadow around it, in one colour (U026: 1 px + 1 px).
    const one = s.boxShadow.match(/^((?:rgba?|color)\([^)]*\)) 0px 0px 0px (\d+(?:\.\d+)?)px(?! inset)/);
    const field = one && one[1] === s.borderTopColor && s.borderTopStyle === 'solid' ? parseFloat(one[2]) + parseFloat(s.borderTopWidth) : 0;
    // A search box draws the same ring on the box around its input.
    const box = el instanceof HTMLInputElement ? el.closest<HTMLElement>('.cf-search') : null;
    const bs = box ? getComputedStyle(box) : null;
    const search = bs && /0px 0px 0px 1px/.test(bs.boxShadow) && bs.boxShadow.startsWith(bs.borderTopColor) ? 1 + parseFloat(bs.borderTopWidth) : 0;
    // The calendar or clock button inside a date or time field: the browser draws its own ring on it (seen in a picture,
    // task 0089), and the field itself then does not match :focus, so the ring can't be read from here.
    const picker = el instanceof HTMLInputElement && ['date', 'time', 'datetime-local'].includes(el.type) && !el.matches(':focus') ? 2 : 0;
    // A focus ring drawn on a sibling (the switch track) counts for the hidden switch input.
    let sibling = 0;
    if (el instanceof HTMLInputElement && el.nextElementSibling) {
      const n = getComputedStyle(el.nextElementSibling);
      sibling = n.outlineStyle !== 'none' ? parseFloat(n.outlineWidth) : 0;
    }
    const label = el instanceof HTMLInputElement || el instanceof HTMLSelectElement || el instanceof HTMLTextAreaElement ? el.labels?.[0]?.textContent : null;
    const name = (el.getAttribute('aria-label') || label || el.textContent || el.getAttribute('title') || '').replace(/\s+/g, ' ').trim();
    return { name, tag: el.tagName.toLowerCase(), ring: Math.max(outline, inset, sibling, field, search, picker, ...spreads) };
  });
}

/** Presses Tab until focus is on something whose name matches, checking every stop on the way has a visible ring. */
export async function tabTo(page: Page, name: RegExp, max = 60, key: 'Tab' | 'Shift+Tab' = 'Tab') {
  for (let i = 0; i < max; i += 1) {
    await page.keyboard.press(key);
    const f = await focused(page);
    if (f.tag !== 'body') expect(f.ring, `focus ring on "${f.name}" (${f.tag})`).toBeGreaterThanOrEqual(2);
    if (name.test(f.name)) return f;
  }
  throw new Error(`No Tab stop matching ${name} within ${max} presses`);
}

export interface Walk { stops: string[]; unreached: string[]; outOfOrder: string[]; offScreen: string[]; noRing: string[] }

/**
 * Task 0089: Tab from the top of the page (or of the open modal dialog) to the end. Every stop must have a visible ring of
 * at least 2 px, be inside the window, and come after the one before it in reading order; every control that can be used
 * must be reached. Ends when focus leaves the page or comes round again; a trap shows as "No way out".
 */
export async function walk(page: Page, max = 320): Promise<Walk> {
  await page.evaluate(() => {
    const w = window as unknown as { __walk: Element[] };
    w.__walk = [];
    const dialog = document.querySelector('dialog[open]');
    const start = dialog ? (dialog.contains(document.activeElement) ? document.activeElement : null) : document.querySelector<HTMLElement>('.cf-skip');
    if (start instanceof HTMLElement) { start.focus(); w.__walk.push(start); }
  });
  const result: Walk = { stops: [], unreached: [], outOfOrder: [], offScreen: [], noRing: [] };
  for (let i = 0; ; i += 1) {
    if (i >= max) throw new Error(`No way out of the page after ${max} Tab presses: ${result.stops.slice(-6).join(' | ')}`);
    await page.keyboard.press('Tab');
    const f = await focused(page);
    const at = await page.evaluate(() => {
      const seen = (window as unknown as { __walk: Element[] }).__walk;
      const el = document.activeElement;
      if (!el || el === document.body) return 'end' as const;
      const before = seen[seen.length - 1];
      if (el === before) return 'same' as const; // the parts of a date or time field
      if (seen.includes(el)) return 'end' as const;
      seen.push(el);
      const own = el.getBoundingClientRect();
      const r = el instanceof HTMLInputElement && (own.width <= 1 || own.height <= 1) && el.nextElementSibling ? el.nextElementSibling.getBoundingClientRect() : own;
      return {
        inside: r.bottom > 0 && r.top < window.innerHeight && r.right > 0 && r.left < window.innerWidth,
        follows: !before || Boolean(before.compareDocumentPosition(el) & Node.DOCUMENT_POSITION_FOLLOWING),
      };
    });
    if (at === 'end') break;
    if (at === 'same') continue;
    const name = `${f.name.slice(0, 60)} (${f.tag})`;
    result.stops.push(name);
    if (f.ring < 2) result.noRing.push(`${name}: ${f.ring}px`);
    if (!at.inside) result.offScreen.push(name);
    if (!at.follows) result.outOfOrder.push(name);
  }
  result.unreached = await page.evaluate(() => {
    const reached = (window as unknown as { __walk: Element[] }).__walk;
    const root = document.querySelector('dialog[open]') ?? document.body;
    return [...root.querySelectorAll<HTMLElement>('a[href], button, input, select, textarea, summary, [tabindex="0"]')].filter((el) => {
      if (reached.includes(el) || el.matches(':disabled') || el.tabIndex < 0) return false;
      if (!el.checkVisibility({ visibilityProperty: true })) return false;
      if (el instanceof HTMLInputElement && el.type === 'radio') return !reached.some((r) => r instanceof HTMLInputElement && r.type === 'radio' && r.name === el.name);
      return true;
    }).map((el) => `${el.tagName.toLowerCase()} ${(el.getAttribute('aria-label') || el.textContent || '').trim().slice(0, 60)}`);
  });
  return result;
}

/** The whole page (or dialog) by Tab, with nothing wrong on the way; the stops, in order. */
export async function expectWalk(page: Page): Promise<string[]> {
  const w = await walk(page);
  expect({ unreached: w.unreached, outOfOrder: w.outOfOrder, offScreen: w.offScreen, noRing: w.noRing }).toEqual({ unreached: [], outOfOrder: [], offScreen: [], noRing: [] });
  // Nothing on the screen is placed in another order than it is read and tabbed.
  const moved = await page.evaluate(() => [...document.querySelectorAll<HTMLElement>('#cf-main *, dialog[open] *')]
    .filter((el) => { const s = getComputedStyle(el); return s.order !== '0' || (/reverse/.test(s.flexDirection) && s.display.includes('flex')) || el.tabIndex > 0; }).map((el) => el.className || el.tagName));
  expect(moved).toEqual([]);
  test.info().annotations.push({ type: 'stops', description: w.stops.join(' > ') });
  return w.stops;
}

/** Tab `n` times in an open modal dialog: never the page behind it (past the last button Tab goes to the browser's own controls, shown as the body). */
export async function staysInDialog(page: Page, n = 14) {
  for (let i = 0; i < n; i += 1) {
    await page.keyboard.press('Tab');
    expect(await page.evaluate(() => { const d = document.querySelector('dialog[open]'); return Boolean(d) && (d!.contains(document.activeElement) || document.activeElement === document.body); })).toBe(true);
  }
}

/** The name of what has focus, as the screen reader gets it from aria-label or the text. */
export const focusName = async (page: Page) => (await focused(page)).name;
