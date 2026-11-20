import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { HELP, type HelpKey } from './helpTexts';

/**
 * UI task U026 part B: the "?" help next to a setting's label on the Admin settings screens. An accessible toggletip:
 * a button named "About <setting>", which shows a short note next to it in a live region, so screen readers announce
 * it. The button again, Esc, or a click or tap outside closes it. On a coarse pointer the tap area is 44 px.
 * The texts live in helpTexts.ts.
 */
export function HelpTip({ topic }: { topic: HelpKey }) {
  const { name, text } = HELP[topic];
  const [open, setOpen] = useState(false);
  const noteId = useId();
  const wrap = useRef<HTMLSpanElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  const bubble = useRef<HTMLSpanElement>(null);

  useEffect(() => {
    if (!open) return;
    const onPointer = (e: PointerEvent) => { if (!wrap.current?.contains(e.target as Node)) setOpen(false); };
    // Capture phase: Esc closes the note first, not the sheet or dialog the "?" sits in.
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      e.preventDefault();
      e.stopPropagation();
      setOpen(false);
      if (wrap.current?.contains(document.activeElement)) button.current?.focus();
    };
    document.addEventListener('pointerdown', onPointer, true);
    document.addEventListener('keydown', onKey, true);
    return () => { document.removeEventListener('pointerdown', onPointer, true); document.removeEventListener('keydown', onKey, true); };
  }, [open]);

  // Keep the note inside the window: shift it left when it would run past the right edge.
  useLayoutEffect(() => {
    const el = bubble.current;
    if (!open || !el) return;
    el.style.removeProperty('--cf-help-shift');
    const overflow = el.getBoundingClientRect().right - (document.documentElement.clientWidth - 16);
    if (overflow > 0) el.style.setProperty('--cf-help-shift', `${-Math.ceil(overflow)}px`);
  }, [open]);

  return (
    <span ref={wrap} className="cf-help">
      <button ref={button} type="button" className="cf-help-button" aria-label={`About ${name}`} aria-expanded={open}
        aria-controls={noteId} data-help={topic} onClick={() => setOpen((o) => !o)}>
        <span aria-hidden="true">?</span>
      </button>
      {/* The live region is always there; the note is put into it on open, so it is announced. */}
      <span id={noteId} aria-live="polite" className="cf-help-live">
        {open && <span ref={bubble} className="cf-help-note">{text}</span>}
      </span>
    </span>
  );
}
