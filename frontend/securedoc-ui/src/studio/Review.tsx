import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import useSWR, { useSWRConfig } from 'swr';
import { Minus, MousePointerSquareDashed, Plus, X } from 'lucide-react';
import { apiClient } from '../api/client';
import type { FocusedReviewItem, FocusedReviewQueue, ReviewBoundingBox, ReviewFoundText } from '../types';
import { CAP, caseTitle, errorMessage, usePackages, useReviewQueue } from './data';
import { NAV, REVIEW_WORDS, sensitiveTypeName, whyAsked } from './words';
import { PREVIEW_PIXELS_PER_CSS_PIXEL, bulkButtonText, bulkConfirmBody, bulkConfirmTitle, bulkPlan, bulkResultText, bulkWaitingText, maskFoundText, nextZoom } from './reviewRules';
import { Kbd, Notice, Spinner } from './atoms';
import { ConfirmDialog } from './admin/shared';
import { useToast } from './toast';
import { useUnsavedWork } from './unsavedWork';

type Zoom = 'fit' | number;

function errorCode(err: unknown): string | undefined {
  return err && typeof err === 'object' && 'code' in err ? (err as { code?: string }).code : undefined;
}

/** The found text only when the server sent a usable one; anything else hides the row (fail closed). */
function foundTextOf(item: FocusedReviewItem): ReviewFoundText | null {
  const value = item.foundText;
  if (!value || typeof value.text !== 'string' || !value.text.trim()) return null;
  return {
    text: value.text,
    before: typeof value.before === 'string' ? value.before : '',
    after: typeof value.after === 'string' ? value.after : '',
  };
}

export default function Review({ caps }: { caps: string[] }) {
  const [params, setParams] = useSearchParams();
  const caseFilter = params.get('case');
  const toast = useToast();
  const { mutate: mutateGlobal } = useSWRConfig();
  const { data: queue, error, isLoading, mutate } = useReviewQueue(true);
  const { data: packages } = usePackages(caps.includes(CAP.desk));

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [draft, setDraft] = useState<ReviewBoundingBox | null>(null);
  useUnsavedWork(draft !== null);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [decided, setDecided] = useState(0);
  const [zoom, setZoom] = useState<Zoom>('fit');
  const [confirmingBulk, setConfirmingBulk] = useState(false);

  const titles = useMemo(() => {
    const map = new Map<string, string>();
    for (const p of packages ?? []) map.set(p.packageId, caseTitle(p.businessIdentifiers).primary);
    return map;
  }, [packages]);

  const items = useMemo(
    () => (queue?.items ?? []).filter((i) => !caseFilter || i.packageId === caseFilter),
    [queue, caseFilter],
  );
  const selected = items.find((i) => i.findingId === selectedId) ?? items[0] ?? null;
  const index = selected ? items.indexOf(selected) : -1;
  const found = selected ? foundTextOf(selected) : null;
  const bulk = useMemo(() => bulkPlan(items, selected, draft !== null), [items, selected, draft]);

  const select = useCallback((item: FocusedReviewItem | undefined) => {
    if (!item) return;
    setSelectedId(item.findingId);
    setDraft(null);
    setFailure(null);
    document.getElementById(`cf-q-${item.findingId}`)?.scrollIntoView({ block: 'nearest' });
  }, []);

  const removeFromQueue = useCallback((ids: Set<string>) => mutate(
    (current: FocusedReviewQueue | undefined) => current && {
      ...current,
      items: current.items.filter((i) => !ids.has(i.findingId)),
      totalItems: Math.max(0, current.totalItems - ids.size),
    },
    { revalidate: true },
  ), [mutate]);

  /** The next finding to show after `gone` leave the queue: the first one after the current place, else before it. */
  const nextAfter = useCallback((gone: Set<string>) => {
    const later = items.slice(index + 1).find((i) => !gone.has(i.findingId));
    return later ?? [...items.slice(0, Math.max(0, index))].reverse().find((i) => !gone.has(i.findingId));
  }, [index, items]);

  const announceResumed = useCallback((packageId: string) => {
    mutateGlobal('packages');
    toast(`${titles.get(packageId) ?? 'Case'} is fully reviewed and back in processing.`, 'mark');
  }, [mutateGlobal, titles, toast]);

  const decide = useCallback(async (kind: 'redact' | 'keep') => {
    if (!selected || busy) return;
    if (kind === 'redact' && !selected.hasBoundingBox && !draft) {
      setFailure('Drag across the page to mark what should be redacted.');
      return;
    }
    setBusy(true);
    setFailure(null);
    const gone = new Set([selected.findingId]);
    const next = nextAfter(gone);
    try {
      const result = kind === 'redact'
        ? await apiClient.confirmReviewItem(selected.findingId, draft ?? selected.boundingBox ?? undefined)
        : await apiClient.dismissReviewItem(selected.findingId);
      setDecided((n) => n + 1);
      await removeFromQueue(gone);
      if (result.processingResumed) announceResumed(result.packageId);
      if (next) { setSelectedId(next.findingId); setDraft(null); }
    } catch (err) {
      if (errorCode(err) === 'CONFLICT') {
        await removeFromQueue(gone);
        toast(`${errorMessage(err, 'This finding was already resolved.')} Moved to the next one.`);
        if (next) { setSelectedId(next.findingId); setDraft(null); }
      } else {
        setFailure(errorMessage(err, 'The decision could not be saved. Nothing was changed — try again.'));
      }
    } finally {
      setBusy(false);
    }
  }, [announceResumed, busy, draft, nextAfter, removeFromQueue, selected, toast]);

  /**
   * "Redact all like this": each finding is confirmed through its own call, so each one is audited as its own
   * decision. Findings whose place was not found are skipped and stay in the queue.
   */
  const redactAllLikeThis = useCallback(async () => {
    if (!selected || !bulk || busy) return;
    setBusy(true);
    setFailure(null);
    const gone = new Set<string>();
    let redacted = 0;
    let failed = 0;
    const resumed = new Set<string>();
    try {
      for (const item of bulk.toRedact) {
        const box = item.findingId === selected.findingId ? draft ?? item.boundingBox ?? undefined : item.boundingBox ?? undefined;
        try {
          const result = await apiClient.confirmReviewItem(item.findingId, box);
          redacted += 1;
          gone.add(item.findingId);
          if (result.processingResumed) resumed.add(result.packageId);
        } catch (err) {
          if (errorCode(err) === 'CONFLICT') gone.add(item.findingId); // already decided by someone else
          else failed += 1;
        }
      }
    } finally {
      setDecided((n) => n + redacted);
      const next = gone.has(selected.findingId) ? nextAfter(gone) : selected;
      if (gone.size > 0) await removeFromQueue(gone);
      resumed.forEach(announceResumed);
      toast(bulkResultText(redacted, bulk.skipped.length, failed), failed > 0 ? undefined : 'mark');
      if (next && next.findingId !== selected.findingId) { setSelectedId(next.findingId); setDraft(null); }
      setBusy(false);
    }
  }, [announceResumed, bulk, busy, draft, nextAfter, removeFromQueue, selected, toast]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.metaKey || event.ctrlKey || event.altKey || event.repeat || confirmingBulk) return;
      if ((event.target as HTMLElement).closest('input, textarea, select, [role="dialog"]')) return;
      const key = event.key.toLowerCase();
      if (key === 'r' && event.shiftKey) { event.preventDefault(); if (bulk && bulk.toRedact.length > 0 && !busy) setConfirmingBulk(true); }
      else if (key === 'r') { event.preventDefault(); decide('redact'); }
      else if (key === 'v') { event.preventDefault(); decide('keep'); }
      else if (key === 'j' || event.key === 'ArrowDown') { event.preventDefault(); select(items[index + 1]); }
      else if (key === 'k' || event.key === 'ArrowUp') { event.preventDefault(); select(items[index - 1]); }
      else if (event.key === 'Escape' && draft) setDraft(null);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [bulk, busy, confirmingBulk, decide, draft, index, items, select]);

  const grouped = useMemo(() => {
    const map = new Map<string, FocusedReviewItem[]>();
    for (const item of items) map.set(item.packageId, [...(map.get(item.packageId) ?? []), item]);
    return [...map.entries()];
  }, [items]);

  const remaining = items.length;
  const progress = decided + remaining === 0 ? 0 : decided / (decided + remaining);
  const needsMark = !!selected && !selected.hasBoundingBox && !draft;

  return (
    <div className="cf-review">
      <header className="cf-page-head cf-review-head">
        <div>
          <h1 className="cf-title">Review</h1>
          <p className="cf-lede">
            {isLoading ? 'Loading the queue…' : remaining === 0 ? 'The queue is clear.' : (
              <><mark className="cf-mark-text">{remaining} {remaining === 1 ? 'finding' : 'findings'}</mark> need a decision{caseFilter ? ' in this case' : ''}</>
            )}
          </p>
        </div>
        <div className="cf-review-meter" aria-label={`${decided} decided this session`}>
          <span className="cf-doc-meta">{decided} decided this session</span>
          <span className="cf-meter" aria-hidden="true"><span style={{ width: `${progress * 100}%` }} /></span>
        </div>
      </header>

      {caseFilter && (
        <p className="cf-filter-pill">
          Showing <span className="cf-mono">{titles.get(caseFilter) ?? 'one case'}</span>
          <button type="button" onClick={() => setParams({}, { replace: true })} aria-label="Show all cases"><X size={13} /></button>
        </p>
      )}

      {error && <Notice tone="danger">The review queue couldn&apos;t be loaded.</Notice>}
      {isLoading && <div className="cf-center"><Spinner label="Loading queue" /></div>}

      {!isLoading && !selected && (
        <div className="cf-empty cf-empty-large">
          <span className="cf-empty-bars" aria-hidden="true"><span /><span /><span /></span>
          <p className="cf-empty-title">Nothing left to decide</p>
          <p className="cf-empty-text">
            {decided > 0 ? `You cleared ${decided} ${decided === 1 ? 'finding' : 'findings'}. Reviewed cases are already back in processing.` : 'New findings land here when LexaVault is unsure.'}
          </p>
          {caps.includes(CAP.desk) && <Link to="/" className="cf-btn" data-variant="ghost">Back to {NAV.cases}</Link>}
        </div>
      )}

      {selected && (
        <div className="cf-review-grid">
          <nav className="cf-queue" aria-label="Findings queue">
            {grouped.map(([packageId, list]) => (
              <div key={packageId} className="cf-queue-case">
                {!caseFilter && <p className="cf-queue-case-title cf-mono">{titles.get(packageId) ?? 'Case'}</p>}
                <ul>
                  {list.map((item) => {
                    const masked = maskFoundText(foundTextOf(item)?.text);
                    return (
                      <li key={item.findingId}>
                        <button
                          type="button"
                          id={`cf-q-${item.findingId}`}
                          className="cf-queue-item"
                          aria-current={item.findingId === selected.findingId ? 'true' : undefined}
                          onClick={() => select(item)}
                        >
                          <span className="cf-queue-entity">{sensitiveTypeName(item.entityType)}</span>
                          <span className="cf-queue-meta">
                            {!item.hasBoundingBox ? `${REVIEW_WORDS.placeNotFound} · ` : masked ? <><span className="cf-queue-found" aria-label="Text found, masked">{masked}</span> · </> : null}
                            p.{item.pageNumber} · {Math.round(item.confidence * 100)}%
                          </span>
                        </button>
                      </li>
                    );
                  })}
                </ul>
              </div>
            ))}
          </nav>

          <PageStage key={selected.findingId} item={selected} draft={draft} onDraft={setDraft} zoom={zoom} onZoom={setZoom} />

          <aside className="cf-decision" aria-label="Decision">
            <span className="cf-eyebrow">Finding {index + 1} of {remaining}</span>
            <h2 className="cf-decision-entity">{sensitiveTypeName(selected.entityType)}</h2>

            {found && (
              <div className="cf-found">
                <span className="cf-found-label">{REVIEW_WORDS.textFound}</span>
                <span className="cf-found-value cf-mono">{found.text}</span>
                {(found.before || found.after) && (
                  <span className="cf-found-context">“{found.before}<strong>{found.text}</strong>{found.after}”</span>
                )}
              </div>
            )}

            <p className="cf-decision-reason">{whyAsked(selected.reason, selected.confidence, queue?.autoRedactThreshold)}</p>

            <dl className="cf-facts">
              <div><dt>Case</dt><dd className="cf-mono">{titles.get(selected.packageId) ?? '—'}</dd></div>
              <div><dt>Document</dt><dd>{selected.fileName}, page {selected.pageNumber}</dd></div>
            </dl>

            {needsMark && (
              <p className="cf-decision-hint"><MousePointerSquareDashed size={15} aria-hidden="true" /> Location unknown. Drag across the page to mark it before redacting.</p>
            )}
            {draft && (
              <p className="cf-decision-hint"><MousePointerSquareDashed size={15} aria-hidden="true" /> Your mark will be redacted instead of the detected area. <Kbd>Esc</Kbd> clears it.</p>
            )}
            {failure && <Notice tone="danger">{failure}</Notice>}

            <div className="cf-decision-actions">
              <button type="button" className="cf-btn cf-decide" data-variant="redact" onClick={() => decide('redact')} disabled={busy || needsMark}>
                Redact <Kbd>R</Kbd>
              </button>
              <button type="button" className="cf-btn cf-decide" data-variant="ghost" onClick={() => decide('keep')} disabled={busy}>
                Keep visible <Kbd>V</Kbd>
              </button>
            </div>

            {bulk && (
              <div className="cf-bulk">
                <p>{bulkWaitingText(bulk)}</p>
                {bulk.skipped.length > 0 && (
                  <p className="cf-bulk-note">{bulk.skipped.length} of them {bulk.skipped.length === 1 ? 'has' : 'have'} no place on the page and will be skipped.</p>
                )}
                <button type="button" className="cf-btn" data-size="sm" onClick={() => setConfirmingBulk(true)} disabled={busy || bulk.toRedact.length === 0}>
                  {bulkButtonText(bulk)} <Kbd>Shift R</Kbd>
                </button>
                <ConfirmDialog
                  open={confirmingBulk}
                  title={bulkConfirmTitle(bulk)}
                  facts={[['Case', <span className="cf-mono" key="c">{titles.get(selected.packageId) ?? '—'}</span>]]}
                  consequence={bulkConfirmBody(bulk)}
                  confirmLabel="Redact"
                  busy={busy}
                  onCancel={() => setConfirmingBulk(false)}
                  onConfirm={async () => { await redactAllLikeThis(); setConfirmingBulk(false); }}
                />
              </div>
            )}

            <p className="cf-hint"><Kbd>J</Kbd><Kbd>K</Kbd> next / previous</p>
          </aside>
        </div>
      )}
    </div>
  );
}

function PageStage({ item, draft, onDraft, zoom, onZoom }: {
  item: FocusedReviewItem;
  draft: ReviewBoundingBox | null;
  onDraft: (box: ReviewBoundingBox | null) => void;
  zoom: Zoom;
  onZoom: (zoom: Zoom) => void;
}) {
  const { data, error } = useSWR(['review-preview', item.findingId], () => apiClient.getReviewPagePreview(item.findingId), { revalidateOnFocus: false });
  const url = useMemo(() => (data ? URL.createObjectURL(data.blob) : null), [data]);
  useEffect(() => () => { if (url) URL.revokeObjectURL(url); }, [url]);

  const canvasRef = useRef<HTMLDivElement>(null);
  const surfaceRef = useRef<HTMLDivElement>(null);
  const origin = useRef<{ x: number; y: number } | null>(null);
  const [live, setLive] = useState<ReviewBoundingBox | null>(null);
  const [naturalWidth, setNaturalWidth] = useState(0);
  const [shownWidth, setShownWidth] = useState(0);

  const point = (event: React.PointerEvent) => {
    const rect = surfaceRef.current!.getBoundingClientRect();
    return {
      x: Math.min(Math.max((event.clientX - rect.left) / rect.width, 0), 1),
      y: Math.min(Math.max((event.clientY - rect.top) / rect.height, 0), 1),
    };
  };
  const boxFrom = (a: { x: number; y: number }, b: { x: number; y: number }): ReviewBoundingBox => ({
    left: Math.min(a.x, b.x), top: Math.min(a.y, b.y), width: Math.abs(a.x - b.x), height: Math.abs(a.y - b.y),
  });

  const shown = live ?? draft;
  const detected = item.hasBoundingBox ? item.boundingBox : null;
  const focus = draft ?? detected ?? null;

  const actualWidth = naturalWidth / PREVIEW_PIXELS_PER_CSS_PIXEL;
  const percent = zoom === 'fit'
    ? (actualWidth > 0 && shownWidth > 0 ? Math.round((shownWidth / actualWidth) * 100) : null)
    : zoom;
  const pageStyle: React.CSSProperties | undefined = zoom !== 'fit' && actualWidth > 0
    ? { width: `${Math.round((actualWidth * zoom) / 100)}px`, maxWidth: 'none' }
    : undefined;

  useEffect(() => {
    const surface = surfaceRef.current;
    if (!surface) return undefined;
    const observer = new ResizeObserver(() => setShownWidth(surface.clientWidth));
    observer.observe(surface);
    return () => observer.disconnect();
  }, [url]);

  // Keep the finding in view whenever the page size changes. Depends on the numbers, not the object, so the
  // queue's 5-second refresh does not pull the page back while the person scrolls.
  const fx = focus ? focus.left + focus.width / 2 : null;
  const fy = focus ? focus.top + focus.height / 2 : null;
  useLayoutEffect(() => {
    const canvas = canvasRef.current;
    const surface = surfaceRef.current;
    if (!canvas || !surface || fx === null || fy === null || shownWidth === 0) return;
    const x = surface.offsetLeft + fx * surface.clientWidth;
    const y = surface.offsetTop + fy * surface.clientHeight;
    canvas.scrollTo({ left: Math.max(0, x - canvas.clientWidth / 2), top: Math.max(0, y - canvas.clientHeight / 2) });
  }, [fx, fy, shownWidth]);

  const step = (direction: 1 | -1) => { if (percent !== null) onZoom(nextZoom(percent, direction)); };

  return (
    <section className="cf-viewer" aria-label={`Page ${item.pageNumber} of ${item.fileName}`}>
      <div className="cf-viewer-bar">
        <span className="cf-doc-meta">{item.fileName} · page {item.pageNumber}</span>
        <div className="cf-viewer-tools">
          {draft && <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" onClick={() => onDraft(null)}>Clear mark</button>}
          <div className="cf-zoom" role="group" aria-label="Zoom">
            <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" onClick={() => step(-1)} disabled={percent === null} aria-label="Zoom out"><Minus size={14} aria-hidden="true" /></button>
            <span className="cf-zoom-value cf-mono" aria-live="polite">{percent === null ? '—' : `${percent}%`}</span>
            <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" onClick={() => step(1)} disabled={percent === null} aria-label="Zoom in"><Plus size={14} aria-hidden="true" /></button>
            <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" onClick={() => onZoom('fit')} aria-pressed={zoom === 'fit'}>Fit</button>
          </div>
        </div>
      </div>
      <div className="cf-viewer-canvas" ref={canvasRef}>
        {error && <Notice tone="danger">The page preview is unavailable.</Notice>}
        {!url && !error && <Spinner label="Rendering page" />}
        {url && (
          <div
            ref={surfaceRef}
            className="cf-page"
            style={pageStyle}
            onPointerDown={(e) => { e.currentTarget.setPointerCapture(e.pointerId); origin.current = point(e); setLive(null); }}
            onPointerMove={(e) => { if (origin.current) setLive(boxFrom(origin.current, point(e))); }}
            onPointerUp={(e) => {
              if (origin.current) {
                const box = boxFrom(origin.current, point(e));
                origin.current = null;
                setLive(null);
                if (box.width > 0.01 && box.height > 0.005) onDraft(box);
              }
            }}
          >
            <img
              src={url}
              alt={`Page ${item.pageNumber} of ${item.fileName}`}
              draggable={false}
              onLoad={(e) => setNaturalWidth(e.currentTarget.naturalWidth)}
            />
            {detected && !draft && <span className="cf-box" data-kind="detected" style={boxStyle(detected)} aria-hidden="true" />}
            {shown && <span className="cf-box" data-kind="drawn" style={boxStyle(shown)} aria-hidden="true" />}
          </div>
        )}
      </div>
    </section>
  );
}

function boxStyle(box: ReviewBoundingBox): React.CSSProperties {
  return { left: `${box.left * 100}%`, top: `${box.top * 100}%`, width: `${box.width * 100}%`, height: `${box.height * 100}%` };
}
