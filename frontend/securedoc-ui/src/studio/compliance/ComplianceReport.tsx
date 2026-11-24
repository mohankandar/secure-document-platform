import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react';
import { createPortal } from 'react-dom';
import { apiClient } from '../../api/client';
import { Notice } from '../atoms';
import { useToast } from '../toast';
import { useEntityZone } from '../EntityZoneContext';
import {
  DEFAULT_PERIOD, PERIODS, SUMMARY_CONTENTS, countQuery, customRangeProblem, defaultCustomRange,
  errorText, fileNameFromDisposition, localToday, presetRange, rangeLabel, recordCountWords, requestBody, type PeriodKey,
} from './complianceRules';
import './compliance.css';

/**
 * UI task U022: the "Compliance report" button and its dialog, on Reports (summary edition, Business Administrator)
 * and Admin › Audit trail (full audit trail edition, IT Administrator). The page decides whether to show the button
 * (complianceEdition); the server still checks every request.
 */
export function ComplianceReportButton({ edition, entityName }: { edition: 'SUMMARY' | 'FULL'; entityName: string }) {
  const [open, setOpen] = useState(false);
  const button = useRef<HTMLButtonElement>(null);
  // The dialog is drawn at the Case File root (where the colour tokens live), above every page section.
  const host = open ? (button.current?.closest('.cf') as HTMLElement | null) ?? document.body : null;
  const close = () => {
    setOpen(false);
    // Focus goes back to the button that opened the dialog.
    requestAnimationFrame(() => button.current?.focus());
  };
  return (
    <>
      <button ref={button} type="button" className="cf-btn" data-variant="solid" aria-haspopup="dialog" onClick={() => setOpen(true)}>
        Compliance report
      </button>
      {open && host && createPortal(<ComplianceReportDialog edition={edition} entityName={entityName} onClose={close} />, host)}
    </>
  );
}

const FOCUSABLE = 'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])';

function ComplianceReportDialog({ edition, entityName, onClose }: { edition: 'SUMMARY' | 'FULL'; entityName: string; onClose: () => void }) {
  const toast = useToast();
  const id = useId();
  const titleId = `${id}-title`;
  const rangeId = `${id}-range`;
  const problemId = `${id}-problem`;
  const dialog = useRef<HTMLElement>(null);
  const firstFocus = useRef<HTMLButtonElement>(null);

  const zone = useEntityZone();
  const today = useMemo(() => localToday(new Date(), zone), [zone]);
  const [period, setPeriod] = useState<PeriodKey>(DEFAULT_PERIOD);
  const [custom, setCustom] = useState(() => defaultCustomRange(today));
  const [fullTrail, setFullTrail] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const problem = period === 'CUSTOM' ? customRangeProblem(custom.from, custom.to, today) : null;
  const range = period === 'CUSTOM' ? (problem ? null : custom) : presetRange(period, today);

  // Focus moves into the dialog on open.
  useEffect(() => { firstFocus.current?.focus(); }, []);

  // Task 0019 A1: the appendix's record count for the chosen period (full edition only). While it loads, or if it
  // fails, the card shows without a count; an answer for an earlier period is never shown.
  const [recordCount, setRecordCount] = useState<ReturnType<typeof recordCountWords>>(null);
  const countFrom = range?.from ?? '';
  const countTo = range?.to ?? '';
  useEffect(() => {
    setRecordCount(null);
    if (edition !== 'FULL' || !countFrom || !countTo) return;
    const controller = new AbortController();
    apiClient.auditRecordCount(countQuery(period, { from: countFrom, to: countTo }, zone), controller.signal)
      .then((r) => { if (!controller.signal.aborted) setRecordCount(recordCountWords(r?.count)); })
      .catch(() => { /* no count: the card shows without it */ });
    return () => controller.abort();
  }, [edition, period, countFrom, countTo, zone]);

  // Wherever focus is while the dialog is open (even on the page behind it), Esc and Tab belong to the dialog.
  const busyRef = useRef(busy);
  busyRef.current = busy;
  useEffect(() => {
    const onDocumentKey = (e: KeyboardEvent) => {
      if (!dialog.current || dialog.current.contains(document.activeElement)) return;
      if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); if (!busyRef.current) onClose(); }
      else if (e.key === 'Tab') { e.preventDefault(); (firstFocus.current ?? dialog.current).focus(); }
    };
    document.addEventListener('keydown', onDocumentKey, true);
    return () => document.removeEventListener('keydown', onDocumentKey, true);
  }, [onClose]);

  // Esc closes when not busy; Tab stays inside the dialog.
  function onKeyDown(e: ReactKeyboardEvent<HTMLElement>) {
    if (e.key === 'Escape') {
      e.stopPropagation();
      e.preventDefault();
      if (!busy) onClose();
      return;
    }
    if (e.key !== 'Tab' || !dialog.current) return;
    const items = [...dialog.current.querySelectorAll<HTMLElement>(FOCUSABLE)].filter((el) => el.offsetParent !== null || el === document.activeElement);
    if (!items.length) { e.preventDefault(); dialog.current.focus(); return; }
    const first = items[0];
    const last = items[items.length - 1];
    const active = document.activeElement;
    if (e.shiftKey && (active === first || active === dialog.current || !dialog.current.contains(active))) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && (active === last || !dialog.current.contains(active))) { e.preventDefault(); first.focus(); }
  }

  async function create() {
    if (busy || !range) return;
    setError('');
    setBusy(true);
    // The buttons are disabled while busy, so focus is kept on the dialog itself.
    dialog.current?.focus();
    try {
      const body = requestBody(period, custom, zone, edition, fullTrail);
      const { pdf, disposition } = await apiClient.createComplianceReport(body);
      // Download, then let go of everything: the PDF, the response and the file hash are never kept.
      const url = URL.createObjectURL(pdf);
      const a = document.createElement('a');
      a.href = url;
      a.download = fileNameFromDisposition(disposition);
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
      setBusy(false);
      onClose();
      toast('Compliance report downloaded.');
    } catch (e) {
      const status = (e as { httpStatus?: number } | null)?.httpStatus;
      setError(errorText(typeof status === 'number' ? status : undefined));
      setBusy(false);
      requestAnimationFrame(() => firstFocus.current?.focus());
    }
  }

  return (
    // Clicking the dimmed page does nothing, so a click can't lose the form.
    // A press on the dimmed page also keeps focus where it was.
    <div className="cf-cr-scrim" onMouseDown={(e) => { if (e.target === e.currentTarget) e.preventDefault(); }}>
      <section ref={dialog} className="cf-cr" role="dialog" aria-modal="true" aria-labelledby={titleId} aria-busy={busy || undefined}
        tabIndex={-1} onKeyDown={onKeyDown}>
        <div>
          <h2 id={titleId} className="cf-cr-title">Compliance report</h2>
          <div className="cf-cr-sub">{entityName} · one entity per report</div>
        </div>

        <fieldset className="cf-cr-period" disabled={busy}>
          <legend>Period</legend>
          <div className="cf-cr-segment">
            {PERIODS.map((p) => (
              <button key={p.key} ref={p.key === period ? firstFocus : undefined} type="button" aria-pressed={period === p.key}
                aria-describedby={period === p.key ? rangeId : undefined}
                onClick={() => { setPeriod(p.key); setError(''); }}>
                {p.label}
              </button>
            ))}
          </div>
          {period === 'CUSTOM' && (
            <div className="cf-cr-dates">
              <label>
                <span>From</span>
                <input type="date" value={custom.from} required aria-invalid={problem ? true : undefined} aria-describedby={problem ? problemId : undefined}
                  onChange={(e) => { setCustom((c) => ({ ...c, from: e.target.value })); setError(''); }} />
              </label>
              <label>
                <span>To</span>
                <input type="date" value={custom.to} required aria-invalid={problem ? true : undefined} aria-describedby={problem ? problemId : undefined}
                  onChange={(e) => { setCustom((c) => ({ ...c, to: e.target.value })); setError(''); }} />
              </label>
            </div>
          )}
          {problem
            ? <p id={problemId} className="cf-cr-problem" role="alert">{problem}</p>
            : <div id={rangeId} className="cf-cr-range">{range ? rangeLabel(range.from, range.to) : ''}</div>}
        </fieldset>

        <div className="cf-cr-contents">
          <div className="cf-cr-label">What&apos;s in it</div>
          {edition === 'SUMMARY' ? (
            <>
              <ul className="cf-cr-list">
                {SUMMARY_CONTENTS.map((line) => <li key={line}><span aria-hidden="true">✓</span>{line}</li>)}
              </ul>
              <div className="cf-cr-note">The full audit trail is added by your IT Administrator, from Admin › Audit trail.</div>
            </>
          ) : (
            <>
              <div className="cf-cr-muted">Everything in the summary edition: policy and its changes, keeping and deleting with proof, case outcomes, audit counts.</div>
              <label className="cf-cr-check">
                <input type="checkbox" checked={fullTrail} disabled={busy} onChange={(e) => setFullTrail(e.target.checked)} />
                <span>
                  <b>Include full audit trail</b>
                  <span className="cf-cr-muted" data-testid="cr-trail-note">
                    Every record in the period, added as an appendix
                    {recordCount && <> · <span className="cf-mono" data-testid="cr-record-count">{recordCount.number}</span> {recordCount.unit}</>}
                    . Cases appear by case ID only.
                  </span>
                </span>
              </label>
            </>
          )}
        </div>

        <Notice tone="mark">Contains no borrower names, file names, loan numbers or redacted values.</Notice>

        {error && <Notice tone="danger">{error}</Notice>}

        <div className="cf-cr-foot">
          <span className="cf-cr-note">Creating it is recorded in the audit trail.</span>
          <button type="button" className="cf-btn cf-cr-action" onClick={onClose} disabled={busy}>Cancel</button>
          <button type="button" className="cf-btn cf-cr-action" data-variant="solid" onClick={() => void create()} disabled={busy || !range}>
            {busy ? 'Creating…' : 'Create PDF'}
          </button>
        </div>
      </section>
    </div>
  );
}
