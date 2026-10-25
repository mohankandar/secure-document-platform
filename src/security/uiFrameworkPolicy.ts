/**
 * SecureDocs UI Framework durability contract.
 *
 * Keep this file dependency-free: uiFrameworkContract.test.ts runs with Node's
 * built-in test runner so the guardrail works in local builds and CI without
 * adding a style-linting toolchain.
 *
 * CSS custom properties cannot be used as @media query thresholds in current
 * browsers, so responsive breakpoints are governed here and enforced against
 * every source CSS file by the framework contract test.
 */
export const SECUREDOCS_BREAKPOINTS_PX = [640, 768, 900, 1024, 1200, 1280, 1400] as const;

export const SECUREDOCS_RETIRED_MARKUP_CLASS_PATTERN =
  /\b(primary-button|secondary-button|toolbar-button|icon-text-button|button-link|link-button|icon-button|stage-pill|attention-(?:link|review|approval|technical|none|muted|rejected)|platform-status(?:-[\w-]+)?|support-impact|impact-[\w-]+|error-msg|error-message|success-msg|platform-error|platform-success|integration-success|identity-banner|scan-notice|platform-tabs|manual-document-tabs|platform-empty|report-loading|review-loading)\b|\bdashboard-empty(?![-\w])/;

export const SECUREDOCS_RETIRED_CSS_SELECTOR_PATTERNS = [
  /(^|[,{]\s*)button\.primary\b/m,
  /(^|[,{]\s*)button\.secondary\b/m,
  /\.stage-pill\b/,
  /\.platform-status(?:-[\w-]+)?\b/,
  /\.support-impact\b/,
  /\.attention-(?:link|review|approval|technical|none|muted|rejected)\b/,
  /\.primary-button\b/,
  /\.secondary-button\b/,
  /\.toolbar-button\b/,
  /\.icon-text-button\b/,
  /\.link-button\b/,
] as const;
