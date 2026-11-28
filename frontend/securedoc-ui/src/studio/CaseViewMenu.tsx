import { useEffect, useId, useRef, useState } from 'react';
import { SlidersHorizontal } from 'lucide-react';
import { DEFAULT_SORTS, shownColumns, type CaseView, type CaseViewSettings, orderWords, sortLabel } from './caseView';

const COLUMN_WORDS: Record<string, string> = { received: 'Received', uploaded: 'Uploaded', source: 'Source' };

/**
 * Task 0108: the one View menu next to the filter: Sort by, the order switch, Columns and Density. Each change is saved
 * on the server at once; a refused or failed save says so and keeps the menu as it was.
 */
export default function CaseViewMenu({ view, settings, fieldLabel, onSave }: {
  view: CaseView | undefined;
  settings: CaseViewSettings;
  fieldLabel: (key: string) => string;
  onSave: (next: CaseViewSettings) => Promise<unknown>;
}) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const panelId = useId();
  const wrap = useRef<HTMLDivElement>(null);
  const button = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return undefined;
    const onDown = (event: MouseEvent) => { if (!wrap.current?.contains(event.target as Node)) setOpen(false); };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { event.stopPropagation(); setOpen(false); button.current?.focus(); }
    };
    document.addEventListener('mousedown', onDown);
    wrap.current?.addEventListener('keydown', onKey);
    const node = wrap.current;
    return () => { document.removeEventListener('mousedown', onDown); node?.removeEventListener('keydown', onKey); };
  }, [open]);

  const sorts = view?.options.sorts ?? DEFAULT_SORTS;
  const columns = view?.options.columns ?? [];
  const chosen = shownColumns(settings, view?.options.columns);
  const words = orderWords(settings.sort);

  async function change(next: Partial<CaseViewSettings>) {
    setBusy(true);
    setFailed(false);
    try {
      await onSave({ ...settings, ...next });
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="cf-view" ref={wrap}>
      <button ref={button} type="button" className="cf-btn" data-variant="ghost" aria-expanded={open} aria-controls={panelId}
        onClick={() => setOpen((o) => !o)} data-testid="case-view-button">
        <SlidersHorizontal size={15} aria-hidden="true" /> View
      </button>
      {open && (
        <div id={panelId} className="cf-view-panel" role="group" aria-label="View" aria-busy={busy || undefined} data-testid="case-view-menu">
          <fieldset>
            <legend>Sort by</legend>
            {sorts.map((sort) => (
              <label key={sort} className="cf-view-choice">
                <input type="radio" name={`${panelId}-sort`} checked={settings.sort === sort}
                  onChange={() => void change({ sort, order: 'asc' })} />
                {sortLabel(sort, fieldLabel)}
              </label>
            ))}
          </fieldset>
          <fieldset>
            <legend>Order</legend>
            {(['asc', 'desc'] as const).map((order) => (
              <label key={order} className="cf-view-choice">
                <input type="radio" name={`${panelId}-order`} checked={settings.order === order}
                  onChange={() => void change({ order })} />
                {words[order]}
              </label>
            ))}
          </fieldset>
          {columns.length > 0 && (
            <fieldset>
              <legend>Columns</legend>
              {columns.map((column) => (
                <label key={column} className="cf-view-choice">
                  <input type="checkbox" checked={chosen.includes(column)}
                    onChange={() => void change({ columns: chosen.includes(column) ? chosen.filter((c) => c !== column) : [...chosen, column] })} />
                  {COLUMN_WORDS[column] ?? fieldLabel(column)}
                </label>
              ))}
            </fieldset>
          )}
          <fieldset>
            <legend>Density</legend>
            {(['compact', 'comfortable'] as const).map((density) => (
              <label key={density} className="cf-view-choice">
                <input type="radio" name={`${panelId}-density`} checked={settings.density === density}
                  onChange={() => void change({ density })} />
                {density === 'compact' ? 'Compact' : 'Comfortable'}
              </label>
            ))}
          </fieldset>
          {failed && <p className="cf-view-error" role="alert">Your view couldn&apos;t be saved. Try again.</p>}
          <p className="cf-view-note">Saved with your sign-in</p>
        </div>
      )}
    </div>
  );
}
