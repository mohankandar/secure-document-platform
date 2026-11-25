import { test, expect, type Page } from '@playwright/test';
import { scan } from './scan';
import { STATES, openState } from './states0089';

/**
 * Task 0089: the states of states0089.ts, each with the checks U026 used: axe (WCAG 2.0 and 2.1, A and AA, no rule off) in
 * light and dark, and, in light (the layout is the same in dark): no sideways scroll and dialogs inside the window at 640 px
 * (200 % of 1280 px) and at 320 px (reflow), axe again at 320 px (tables that scroll there must be reachable), and nothing
 * cut off with the text spacing of WCAG 1.4.12.
 */
/** What makes the page scroll sideways; nothing when it does not. */
const sideways = (page: Page) => page.evaluate(() => {
  const width = document.documentElement.clientWidth;
  if (document.documentElement.scrollWidth <= width) return [];
  const scrolls = (el: Element | null): boolean => Boolean(el && el !== document.body && (/auto|scroll|hidden/.test(getComputedStyle(el).overflowX) || scrolls(el.parentElement)));
  const wide = [...document.querySelectorAll<HTMLElement>('.cf *')].filter((el) => el.checkVisibility() && el.getBoundingClientRect().right > width + 1 && !scrolls(el.parentElement));
  return [`${document.documentElement.scrollWidth} px`, ...wide.map((el) => `${el.tagName.toLowerCase()}.${String(el.className)} to ${Math.round(el.getBoundingClientRect().right)}`)].slice(0, 14);
});
/** Review is a desktop and tablet screen (owner, U021): not reflowed to 320 px, as in reflow.spec.ts. */
const NO_REFLOW = /^(Page to check|Reject: from Review)/;

/** Texts that do not fit their own box and are cut (an ellipsis or a clipped line). */
const cutOff = (page: Page) => page.evaluate(() => [...document.querySelectorAll<HTMLElement>('.cf button, .cf th, .cf td, .cf h1, .cf h2, .cf h3, .cf label, .cf summary, .cf p, .cf dd, .cf dt, .cf .cf-notice, .cf .cf-ad-tag')]
  .filter((el) => el.checkVisibility() && !el.closest('.cf-sr, .sr-only') && /hidden|clip/.test(getComputedStyle(el).overflow) && (el.scrollWidth > el.clientWidth + 1 || el.scrollHeight > el.clientHeight + 1))
  .map((el) => `${el.className || el.tagName}: ${el.textContent!.trim().slice(0, 50)}`));

for (const s of STATES) {
  test(`${s.task} ${s.name}`, async ({ page }) => {
    await openState(page, s);
    await scan(page, `${s.task} ${s.name}`);
    if (test.info().project.name !== 'light') return;
    for (const width of NO_REFLOW.test(s.name) ? [640] : [640, 320]) {
      await page.setViewportSize({ width, height: 900 });
      await page.waitForTimeout(100);
      expect.soft(await sideways(page), `sideways scroll at ${width} px`).toEqual([]);
      const dialog = page.locator('dialog[open]');
      if (await dialog.count()) {
        const box = (await dialog.boundingBox())!;
        expect.soft(box.x, `dialog at ${width} px`).toBeGreaterThanOrEqual(0);
        expect.soft(box.x + box.width, `dialog at ${width} px`).toBeLessThanOrEqual(width);
      }
      if (width === 320) await scan(page, `${s.task} ${s.name} (320 px)`);
    }
    await page.setViewportSize({ width: 1440, height: 900 });
    const before = await cutOff(page);
    await page.addStyleTag({ content: '* { line-height: 1.5 !important; letter-spacing: .12em !important; word-spacing: .16em !important } p { margin-bottom: 2em !important }' });
    expect.soft((await cutOff(page)).filter((x) => !before.includes(x)), 'cut off with text spacing').toEqual([]);
  });
}
