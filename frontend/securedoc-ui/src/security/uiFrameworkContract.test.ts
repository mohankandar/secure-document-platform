import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import test from 'node:test';
import { SECUREDOCS_BREAKPOINTS_PX, SECUREDOCS_RETIRED_CSS_SELECTOR_PATTERNS, SECUREDOCS_RETIRED_MARKUP_CLASS_PATTERN } from './uiFrameworkPolicy.ts';

const frontendRoot = resolve(process.cwd());
const source = (relative: string) => readFileSync(resolve(frontendRoot, relative), 'utf8');

const routePages = [
  'AuditPage.tsx',
  'AuthenticationAdminPage.tsx',
  'BusinessAdminPage.tsx',
  'CreatePackagePage.tsx',
  'DashboardPage.tsx',
  'IntegrationsPage.tsx',
  'ManualFallbackPage.tsx',
  'PackageDetailPage.tsx',
  'PlatformAdminPage.tsx',
  'ReportsPage.tsx',
  'ReviewPage.tsx',
  'ScanIntakePage.tsx',
  'SupportIncidentDetailPage.tsx',
  'SupportIncidentsPage.tsx',
  'TechnicalIssueDetailPage.tsx',
];

test('canonical page scaffold keeps breadcrumb, title, then primary navigation', () => {
  const scaffold = source('src/components/ui/PageScaffold.tsx');
  const breadcrumb = scaffold.indexOf('<Breadcrumbs');
  const header = scaffold.indexOf('<PageHeader');
  const navigation = scaffold.indexOf('ui-page-primary-navigation');
  assert.ok(breadcrumb >= 0 && header > breadcrumb && navigation > header,
    'PageScaffold must render breadcrumb -> page header -> primary navigation.');
});

test('every top-level route uses PageScaffold instead of hand-rolled page anatomy', () => {
  for (const page of routePages) {
    const body = source(`src/pages/${page}`);
    assert.match(body, /<PageScaffold/, `${page} must use the canonical PageScaffold.`);
    assert.doesNotMatch(body, /<Breadcrumbs\b|<PageHeader\b/,
      `${page} must not hand-roll Breadcrumbs/PageHeader outside PageScaffold.`);
  }
});

test('administration navigation is owned by each page scaffold, not detached above it', () => {
  const app = source('src/App.tsx');
  assert.doesNotMatch(app, /administration-route-shell[^\n]*\{administrationTabs\}/,
    'Administration tabs must not be rendered outside the page header.');

  const pages = ['BusinessAdminPage.tsx', 'IntegrationsPage.tsx', 'AuthenticationAdminPage.tsx', 'PlatformAdminPage.tsx'];
  for (const page of pages) {
    const body = source(`src/pages/${page}`);
    assert.match(body, /primaryNavigation=\{administrationTabs\}/,
      `${page} must place Administration navigation after its title through PageScaffold.`);
  }
});

test('administration tabs are normal-flow page navigation, not a sticky application header', () => {
  const css = source('src/index.css');
  assert.match(css, /\.lv-horizontal-tabs\.administration-tabs\s*\{[^}]*position:\s*static/s,
    'Administration tabs must remain in normal page flow.');
});

test('tokens.css is the only CSS file allowed to contain raw hex colors', () => {
  const cssRoots = [resolve(frontendRoot, 'src')];
  const offenders: string[] = [];
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = resolve(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.endsWith('.css') && entry.name !== 'tokens.css') {
        if (/#[0-9a-fA-F]{3,8}\b/.test(readFileSync(full, 'utf8'))) offenders.push(full);
      }
    }
  };
  cssRoots.forEach(walk);
  assert.deepEqual(offenders, [], `Raw colors must be replaced with SecureDocs tokens: ${offenders.join(', ')}`);
});

test('visual tokens load before application CSS', () => {
  const main = source('src/main.tsx');
  const tokens = main.indexOf("import './tokens.css'");
  const appCss = main.indexOf("import './index.css'");
  assert.ok(tokens >= 0 && appCss > tokens, 'tokens.css must be imported before index.css.');
});

test('OIDC session continuity acceptance script ships identically in root and testing scripts', () => {
  const rootScript = resolve(frontendRoot, '..', '..', 'Test-LexaVault-OidcSessionContinuity.ps1');
  const testScript = resolve(frontendRoot, '..', '..', 'scripts', 'testing', 'Test-LexaVault-OidcSessionContinuity.ps1');
  assert.ok(existsSync(rootScript), 'Root OIDC session continuity script must be packaged.');
  assert.ok(existsSync(testScript), 'Testing OIDC session continuity script must be packaged.');
  assert.equal(readFileSync(rootScript, 'utf8'), readFileSync(testScript, 'utf8'), 'OIDC script copies must not drift.');
});

test('retired primitive class families cannot return to TSX markup', () => {
  const retired = SECUREDOCS_RETIRED_MARKUP_CLASS_PATTERN;
  const offenders: string[] = [];
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = resolve(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.endsWith('.tsx') && retired.test(readFileSync(full, 'utf8'))) offenders.push(full);
    }
  };
  walk(resolve(frontendRoot, 'src'));
  assert.deepEqual(offenders, [], `Retired primitive families must migrate to SecureDocs primitives: ${offenders.join(', ')}`);
});

test('canonical primitive components are present and shared result/filter patterns consume them', () => {
  for (const component of ['Button.tsx', 'Card.tsx', 'Alert.tsx', 'EmptyState.tsx', 'Skeleton.tsx', 'Field.tsx', 'StatusBadge.tsx', 'AttentionLink.tsx', 'HorizontalTabs.tsx']) {
    assert.ok(existsSync(resolve(frontendRoot, 'src', 'components', 'ui', component)), `${component} must ship in the framework.`);
  }
  assert.match(source('src/components/ui/FilterPanel.tsx'), /import Card from '.\/Card'/);
  assert.match(source('src/components/ui/DataRegion.tsx'), /import EmptyState from '.\/EmptyState'/);
  assert.match(source('src/components/ui/DataRegion.tsx'), /import Skeleton from '.\/Skeleton'/);
});

test('responsive CSS uses only the documented breakpoint set', () => {
  const allowed = new Set(SECUREDOCS_BREAKPOINTS_PX.map(String));
  const offenders: string[] = [];
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = resolve(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.endsWith('.css')) {
        const css = readFileSync(full, 'utf8');
        for (const match of css.matchAll(/@media\s*\([^)]*(?:max|min)-width\s*:\s*(\d+)px/g)) {
          if (!allowed.has(match[1])) offenders.push(`${full}:${match[1]}px`);
        }
      }
    }
  };
  walk(resolve(frontendRoot, 'src'));
  assert.deepEqual(offenders, [], `Use only the SecureDocs breakpoint contract: ${offenders.join(', ')}`);
});

test('surface consistency comes from ui-card, not an important override across legacy card families', () => {
  const css = source('src/index.css');
  assert.match(css, /\.ui-card\s*\{[^}]*border:\s*1px solid var\(--ui-border\)/s);
  assert.doesNotMatch(css, /\.card[^{}]*\{[^}]*!important/s, 'Legacy card !important unification must not return.');
});

test('upload submit actions stay in normal flow and cannot obscure the document drop zone', () => {
  const css = source('src/pages/CreatePackagePage.css') + source('src/index.css');
  assert.match(css, /\.intake-submit-panel\s*\{[^}]*position:\s*static/s);
  assert.doesNotMatch(css, /\.intake-submit-panel\s*\{[^}]*position:\s*sticky/s);
});


test('button variants cannot bypass the canonical ui-button primitive', () => {
  const offenders: string[] = [];
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = resolve(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.endsWith('.tsx')) {
        const body = readFileSync(full, 'utf8');
        for (const match of body.matchAll(/className="([^"]*)"/g)) {
          const tokens = match[1].split(/\s+/).filter(Boolean);
          if ((tokens.includes('primary') || tokens.includes('secondary') || tokens.includes('tertiary')) && !tokens.includes('ui-button')) {
            offenders.push(`${full}:${match[1]}`);
          }
        }
      }
    }
  };
  walk(resolve(frontendRoot, 'src'));
  assert.deepEqual(offenders, [], `Button variants must include ui-button: ${offenders.join(', ')}`);
});

test('retired primitive selectors cannot survive in CSS', () => {
  const banned = SECUREDOCS_RETIRED_CSS_SELECTOR_PATTERNS;
  const offenders: string[] = [];
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = resolve(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.endsWith('.css')) {
        const body = readFileSync(full, 'utf8');
        for (const pattern of banned) if (pattern.test(body)) offenders.push(`${full}:${pattern}`);
      }
    }
  };
  walk(resolve(frontendRoot, 'src'));
  assert.deepEqual(offenders, [], `Retired CSS primitive selectors must be deleted: ${offenders.join(', ')}`);
});

test('actionable dashboard attention uses the sanctioned AttentionLink primitive', () => {
  const dashboard = source('src/pages/DashboardPage.tsx');
  assert.match(dashboard, /import AttentionLink from '..\/components\/ui\/AttentionLink'/);
  assert.match(dashboard, /<AttentionLink value="REVIEW"/);
  assert.match(dashboard, /<AttentionLink value="APPROVAL"/);
  assert.match(dashboard, /<AttentionLink value="TECHNICAL"/);
  assert.doesNotMatch(dashboard, /attention-(?:link|review|approval|technical|none|muted|rejected)/);
});


test('framework guardrail is wired into the normal repository build', () => {
  const buildScript = resolve(frontendRoot, '..', '..', 'Build-LexaVault-All.ps1');
  assert.ok(existsSync(buildScript), 'Build-LexaVault-All.ps1 must be packaged.');
  const body = readFileSync(buildScript, 'utf8');
  assert.match(body, /npm run test:ui-framework/, 'Normal LexaVault build must execute the UI framework contract.');
});

test('standalone framework verification scripts ship identically in root and scripts/testing', () => {
  const rootScript = resolve(frontendRoot, '..', '..', 'Test-LexaVault-UiFramework.ps1');
  const testScript = resolve(frontendRoot, '..', '..', 'scripts', 'testing', 'Test-LexaVault-UiFramework.ps1');
  assert.ok(existsSync(rootScript), 'Root UI framework verification script must be packaged.');
  assert.ok(existsSync(testScript), 'Testing UI framework verification script must be packaged.');
  assert.equal(readFileSync(rootScript, 'utf8'), readFileSync(testScript, 'utf8'), 'UI framework verification script copies must not drift.');
});
