import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Search, X } from 'lucide-react';
import { Notice } from '../atoms';
import type { LvCodeEntry } from '../lvErrorCodes.generated';
import { HELP_WORDS, KIND_WORDS, codeFromAddress, foundText, helpPath, searchGuide, splitCodes, whoActsText } from './guideRules';
import './help.css';

/**
 * Help › Error codes (task 0082): every code of the catalogue, by area. Static text from the app itself: no API call and
 * no internet. /help/error-codes/LV-XXX-NNN opens that entry.
 */
export default function ErrorCodes() {
  const { code: asked } = useParams();
  const wanted = codeFromAddress(asked);
  const [query, setQuery] = useState('');
  const searchRef = useRef<HTMLInputElement>(null);
  const areas = useMemo(() => searchGuide(query), [query]);
  const found = areas.reduce((n, a) => n + a.entries.length, 0);

  // A new address shows its own entry, whatever was searched for before.
  useEffect(() => { setQuery(''); }, [asked]);

  return (
    <div className="cf-help">
      <header className="cf-page-head">
        <div>
          <p className="cf-eyebrow">Help</p>
          <h1 className="cf-title">{HELP_WORDS.title}</h1>
          <p className="cf-lede">{HELP_WORDS.lede}</p>
        </div>
      </header>

      {asked !== undefined && !wanted && (
        <Notice>This code isn&apos;t in the guide. Check how it is written, or search below.</Notice>
      )}

      <div className="cf-help-bar" role="search">
        <label className="cf-search">
          <Search size={15} aria-hidden="true" />
          <span className="cf-sr">{HELP_WORDS.search}</span>
          <input ref={searchRef} value={query} onChange={(e) => setQuery(e.target.value)} placeholder={HELP_WORDS.search} autoComplete="off" spellCheck={false} />
          {query && <button type="button" className="cf-search-clear" onClick={() => { setQuery(''); searchRef.current?.focus(); }} aria-label="Clear search"><X size={14} /></button>}
        </label>
        <p className="cf-muted" role="status">{foundText(found)}</p>
      </div>

      {!query.trim() && (
        <nav className="cf-help-areas" aria-label="Areas">
          {areas.map((a) => <a key={a.key} href={`#area-${a.key}`}>{a.name}</a>)}
        </nav>
      )}

      {areas.map((area) => (
        <section key={area.key} id={`area-${area.key}`} className="cf-help-area" aria-labelledby={`area-${area.key}-title`}>
          <h2 id={`area-${area.key}-title`}>{area.name} <span className="cf-count">{area.entries.length}</span></h2>
          {area.entries.map((e) => <Entry key={`${e.code} ${wanted === e.code}`} entry={e} wanted={wanted === e.code} only={found === 1} />)}
        </section>
      ))}
    </div>
  );
}

function Entry({ entry, wanted, only }: { entry: LvCodeEntry; wanted: boolean; only: boolean }) {
  const ref = useRef<HTMLDetailsElement>(null);
  const [open, setOpen] = useState(wanted);
  useEffect(() => {
    if (!wanted) return;
    ref.current?.scrollIntoView({ block: 'start' });
    ref.current?.querySelector('summary')?.focus({ preventScroll: true });
  }, [wanted]);

  return (
    <details ref={ref} className="cf-help-entry" open={open || only} data-wanted={wanted || undefined} data-code={entry.code}
      onToggle={(e) => { if (!only) setOpen(e.currentTarget.open); }}>
      <summary>
        <h3><code className="cf-code">{entry.code}</code> <span>{entry.title}</span></h3>
      </summary>
      <dl className="cf-help-facts">
        <div><dt>Means</dt><dd>{entry.description}</dd></div>
        <div><dt>Kind</dt><dd>{KIND_WORDS[entry.kind]}{entry.reserved && '. Reserved: not used yet.'}</dd></div>
        <div><dt>Who acts</dt><dd>{whoActsText(entry)}</dd></div>
        <div><dt>What to do</dt><dd><ol>{entry.steps.map((s) => <li key={s}><OtherCodes text={s} own={entry.code} /></li>)}</ol></dd></div>
        <div><dt>Support tells the customer</dt><dd>{entry.supportTells}</dd></div>
      </dl>
    </details>
  );
}

/** A step that names another code: that code's entry, in this tab. */
function OtherCodes({ text, own }: { text: string; own: string }) {
  return <>{splitCodes(text).map((part, i) => (typeof part === 'string' ? part
    : part.code === own ? part.code : <Link key={i} className="cf-lv-link" to={helpPath(part.code)}>{part.code}</Link>))}</>;
}
