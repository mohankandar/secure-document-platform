import { test, expect, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { appendFileSync } from 'node:fs';

/** axe with the WCAG 2.0 and 2.1 A and AA rules, none turned off (U026 A2.1; shared with task 0089). */
const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];
/** A11Y_SURVEY names the file to write to (outside test-results, which Playwright empties on each run). */
const SURVEY = process.env.A11Y_SURVEY;

export async function scan(page: Page, label: string) {
  // Let entry animations and late answers settle, so axe judges the screen people see.
  await page.waitForTimeout(250);
  const result = await new AxeBuilder({ page }).withTags(TAGS).analyze();
  const found = result.violations.map((v) => ({
    rule: v.id, impact: v.impact, help: v.help, nodes: v.nodes.map((n) => `${n.target.join(' ')} :: ${n.failureSummary?.replace(/\s+/g, ' ').slice(0, 220)}`),
  }));
  if (SURVEY) {
    appendFileSync(SURVEY, `${JSON.stringify({ screen: label, project: test.info().project.name, count: found.reduce((n, v) => n + v.nodes.length, 0), violations: found })}\n`);
    return;
  }
  expect(found, `axe violations on ${label}`).toEqual([]);
}
