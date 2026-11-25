import { HELP_WORDS, helpPath, splitCodes } from './guideRules';

/**
 * Task 0082: a code as a link to its entry in the error code guide. It opens in a new tab, so nothing unsaved on the
 * screen is lost, and the visible code stays the link's name. The new tab gets no session and no window of this one
 * (rel="noopener"): it needs neither, the guide is static text (HelpAlone).
 */
export function LvCodeLink({ code, className = 'cf-code' }: { code: string; className?: string }) {
  return (
    <a href={helpPath(code)} target="_blank" rel="noopener" title={HELP_WORDS.link} className="cf-lv-link">
      <code className={className}>{code}</code>
    </a>
  );
}

/** A text with every code in it linked; anything that is not a known code stays as it is. */
export function LvText({ children, className = 'cf-lv-plain' }: { children: string | null | undefined; className?: string }) {
  return <>{splitCodes(children ?? '').map((part, i) => (typeof part === 'string' ? part : <LvCodeLink key={i} code={part.code} className={className} />))}</>;
}
