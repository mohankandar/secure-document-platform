import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import useSWR, { useSWRConfig } from 'swr';
import { Eye, EyeOff, MousePointerSquareDashed, X } from 'lucide-react';
import { apiClient } from '../api/client';
import type { FocusedReviewItem, ReviewBoundingBox } from '../types';
import { CAP, REASON_COPY, caseTitle, errorMessage, humanize, usePackages, useReviewQueue } from './data';
import { Kbd, Notice, Spinner } from './atoms';
import { useToast } from './toast';

export default function Review({ caps }: { caps: string[] }) {
  const [params, setParams] = useSearchParams();
  const caseFilter = params.get('case');
  const toast = useToast();
  const { mutate: mutateGlobal } = useSWRConfig();
  const { data: queue, error, isLoading, mutate } = useReviewQueue(true);
  const { data: packages } = usePackages(caps.includes(CAP.desk));

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [draft, setDraft] = useState<ReviewBoundingBox | null>(null);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [decided, setDecided] = useState(0);

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

  const select = useCallback((item: FocusedReviewItem | undefined) => {
    if (!item) return;
    setSelectedId(item.findingId);
    setDraft(null);
    setFailure(null);
    document.getElementById(`cf-q-${item.findingId}`)?.scrollIntoView({ block: 'nearest' });
  }, []);

  const needsMark = !!selected && !selected.hasBoundingBox && !draft;

  const decide = useCallback(async (kind: 'redact' | 'keep') => {
    if (!selected || busy) return;
    if (kind === 'redact' && !selected.hasBoundingBox && !draft) {
      setFailure('Drag across the page to mark what should be redacted.');
      return;
    }
    setBusy(true);
    setFailure(null);
    const next = items[index + 1] ?? items[index - 1];
    try {
      const result = kind === 'redact'
        ? await apiClient.confirmReviewItem(selected.findingId, draft ?? selected.boundingBox ?? undefined)
        : await apiClient.dismissReviewItem(selected.findingId);
      setDecided((n) => n + 1);
      await mutate(
        (current) => current && { items: current.items.filter((i) => i.findingId !== selected.findingId), totalItems: Math.max(0, current.totalItems - 1) },
        { revalidate: true },
      );
      if (result.processingResumed) {
        mutateGlobal('packages');
        toast(`${titles.get(result.packageId) ?? 'Case'} is fully reviewed and back in processing.`, 'mark');
      }
      if (next) { setSelectedId(next.findingId); setDraft(null); }
    } catch (err) {
      setFailure(errorMessage(err, 'The decision could not be saved.'));
    } finally {
      setBusy(false);
    }
  }, [busy, draft, index, items, mutate, mutateGlobal, selected, titles, toast]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.metaKey || event.ctrlKey || event.altKey) return;
      if ((event.target as HTMLElement).closest('input, textarea, select, [role="dialog"]')) return;
      const key = event.key.toLowerCase();
      if (key === 'r') { event.preventDefault(); decide('redact'); }
      else if (key === 'v') { event.preventDefault(); decide('keep'); }
      else if (key === 'j' || event.key === 'ArrowDown') { event.preventDefault(); select(items[index + 1]); }
      else if (key === 'k' || event.key === 'ArrowUp') { event.preventDefault(); select(items[index - 1]); }
      else if (event.key === 'Escape' && draft) setDraft(null);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [decide, draft, index, items, select]);

  const grouped = useMemo(() => {
    const map = new Map<string, FocusedReviewItem[]>();
    for (const item of items) map.set(item.packageId, [...(map.get(item.packageId) ?? []), item]);
    return [...map.entries()];
  }, [items]);

  const remaining = items.length;
  const progress = decided + remaining === 0 ? 0 : decided / (decided + remaining);

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
          {caps.includes(CAP.desk) && <Link to="/" className="cf-btn" data-variant="ghost">Back to the desk</Link>}
        </div>
      )}

      {selected && (
        <div className="cf-review-grid">
          <nav className="cf-queue" aria-label="Findings queue">
            {grouped.map(([packageId, list]) => (
              <div key={packageId} className="cf-queue-case">
                {!caseFilter && <p className="cf-queue-case-title cf-mono">{titles.get(packageId) ?? 'Case'}</p>}
                <ul>
                  {list.map((item) => (
                    <li key={item.findingId}>
                      <button
                        type="button"
                        id={`cf-q-${item.findingId}`}
                        className="cf-queue-item"
                        aria-current={item.findingId === selected.findingId ? 'true' : undefined}
                        onClick={() => select(item)}
                      >
                        <span className="cf-queue-entity">{humanize(item.entityType)}</span>
                        <span className="cf-queue-meta">p.{item.pageNumber} · {Math.round(item.confidence * 100)}%{!item.hasBoundingBox && ' · unplaced'}</span>
                      </button>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </nav>

          <PageStage key={selected.findingId} item={selected} draft={draft} onDraft={setDraft} />

          <aside className="cf-decision" aria-label="Decision">
            <span className="cf-eyebrow">Finding {index + 1} of {remaining}</span>
            <h2 className="cf-decision-entity">{humanize(selected.entityType)}</h2>
            <p className="cf-decision-reason">{REASON_COPY[selected.reason] ?? humanize(selected.reason)}</p>

            <div className="cf-confidence">
              <div className="cf-confidence-row">
                <span>Model confidence</span>
                <strong className="cf-mono">{Math.round(selected.confidence * 100)}%</strong>
              </div>
              <span className="cf-meter" aria-hidden="true"><span style={{ width: `${selected.confidence * 100}%` }} /></span>
            </div>

            <dl className="cf-facts">
              <div><dt>Case</dt><dd className="cf-mono">{titles.get(selected.packageId) ?? '—'}</dd></div>
              <div><dt>Document</dt><dd>{selected.fileName}</dd></div>
              <div><dt>Page</dt><dd>{selected.pageNumber}</dd></div>
            </dl>

            {needsMark && (
              <p className="cf-decision-hint"><MousePointerSquareDashed size={15} aria-hidden="true" /> Location unknown. Drag across the page to mark it before redacting.</p>
            )}
            {failure && <Notice tone="danger">{failure}</Notice>}

            <div className="cf-decision-actions">
              <button type="button" className="cf-btn cf-decide" data-variant="redact" onClick={() => decide('redact')} disabled={busy || needsMark}>
                <EyeOff size={16} aria-hidden="true" /> Redact <Kbd>R</Kbd>
              </button>
              <button type="button" className="cf-btn cf-decide" data-variant="ghost" onClick={() => decide('keep')} disabled={busy}>
                <Eye size={16} aria-hidden="true" /> Keep visible <Kbd>V</Kbd>
              </button>
            </div>
            <p className="cf-hint"><Kbd>J</Kbd><Kbd>K</Kbd> next / previous</p>
          </aside>
        </div>
      )}
    </div>
  );
}

function PageStage({ item, draft, onDraft }: { item: FocusedReviewItem; draft: ReviewBoundingBox | null; onDraft: (box: ReviewBoundingBox | null) => void }) {
  const { data, error } = useSWR(['review-preview', item.findingId], () => apiClient.getReviewPagePreview(item.findingId), { revalidateOnFocus: false });
  const url = useMemo(() => (data ? URL.createObjectURL(data.blob) : null), [data]);
  useEffect(() => () => { if (url) URL.revokeObjectURL(url); }, [url]);

  const surfaceRef = useRef<HTMLDivElement>(null);
  const origin = useRef<{ x: number; y: number } | null>(null);
  const [live, setLive] = useState<ReviewBoundingBox | null>(null);

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

  return (
    <section className="cf-viewer" aria-label={`Page ${item.pageNumber} of ${item.fileName}`}>
      <div className="cf-viewer-bar">
        <span className="cf-doc-meta">{item.fileName} · page {item.pageNumber}</span>
        {draft && <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" onClick={() => onDraft(null)}>Clear mark</button>}
      </div>
      <div className="cf-viewer-canvas">
        {error && <Notice tone="danger">The page preview is unavailable.</Notice>}
        {!url && !error && <Spinner label="Rendering page" />}
        {url && (
          <div
            ref={surfaceRef}
            className="cf-page"
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
            <img src={url} alt={`Page ${item.pageNumber} of ${item.fileName}`} draggable={false} />
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
