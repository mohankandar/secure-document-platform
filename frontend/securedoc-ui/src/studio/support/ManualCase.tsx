import { useEffect, useMemo, useRef, useState, type CSSProperties, type PointerEvent as ReactPointerEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import useSWR, { useSWRConfig } from 'swr';
import { Minus, Plus } from 'lucide-react';
import { apiClient } from '../../api/client';
import type { ManualFallbackDetail, ReviewBoundingBox } from '../../types';
import { Notice, Spinner, StageChip } from '../atoms';
import { LoadErrorCard, checked, isRecord } from '../failure';
import { caseTitle, errorMessage, statusInfo, usePackage } from '../data';
import { useToast } from '../toast';
import { NAV, sensitiveTypeName } from '../words';
import { PREVIEW_PIXELS_PER_CSS_PIXEL, nextZoom } from '../reviewRules';
import { canFinish, pagesLine } from './supportRules';
import './support.css';

/**
 * UI task U012 (finding 9): Continue manually in the Case File look. The same server calls as before: marks are added
 * and removed one by one, and finishing sends the case on through redaction, checking and approval.
 */

const MIN_BOX = 0.008;
const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));
type Zoom = 'fit' | number;

const isManual = (v: unknown) => isRecord(v) && Array.isArray(v.documents) && Array.isArray(v.regions) && Array.isArray(v.enabledEntityTypes);

export default function ManualCase() {
  const { packageId = '' } = useParams();
  const navigate = useNavigate();
  const toast = useToast();
  const { mutate: mutateGlobal } = useSWRConfig();
  const request = `GET /packages/${packageId}/manual-fallback`;
  const { data: detail, error, mutate } = useSWR<ManualFallbackDetail>(['manual-fallback', packageId],
    () => checked(request, apiClient.getManualFallback(packageId), isManual), { revalidateOnFocus: false });
  const { data: pkg } = usePackage(packageId);

  const [documentId, setDocumentId] = useState('');
  const [pageNumber, setPageNumber] = useState(1);
  const [entityType, setEntityType] = useState('');
  const [draft, setDraft] = useState<ReviewBoundingBox | null>(null);
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [zoom, setZoom] = useState<Zoom>('fit');

  useEffect(() => {
    if (!detail) return;
    if (!documentId && detail.documents.length > 0) setDocumentId(detail.documents[0].documentId);
    if (!entityType && detail.enabledEntityTypes.length > 0) setEntityType(detail.enabledEntityTypes[0]);
  }, [detail, documentId, entityType]);

  const document = detail?.documents.find((d) => d.documentId === documentId) ?? null;
  const docRegions = useMemo(() => detail?.regions.filter((r) => r.documentId === documentId) ?? [], [detail, documentId]);
  const pageRegions = docRegions.filter((r) => r.pageNumber === pageNumber);
  const marksByPage = useMemo(() => {
    const m = new Map<number, number>();
    docRegions.forEach((r) => m.set(r.pageNumber, (m.get(r.pageNumber) ?? 0) + 1));
    return m;
  }, [docRegions]);

  useEffect(() => { if (document && pageNumber > document.pageCount) setPageNumber(1); }, [document, pageNumber]);

  const title = pkg ? caseTitle(pkg.businessIdentifiers ?? []) : null;
  const stage = pkg ? statusInfo(pkg.status) : null;

  function pickDocument(id: string) {
    if (id === documentId) return;
    setDocumentId(id);
    setPageNumber(1);
    setDraft(null);
    setConfirmed(false);
  }

  function pickPage(n: number) {
    if (!document) return;
    setPageNumber(clamp(n, 1, document.pageCount));
    setDraft(null);
  }

  async function act(work: () => Promise<unknown>, done: string, fallback: string) {
    setBusy(true);
    setFailure(null);
    try {
      await work();
      await mutate();
      toast(done, 'mark');
      return true;
    } catch (e) {
      setFailure(errorMessage(e, fallback));
      return false;
    } finally {
      setBusy(false);
    }
  }

  const addMark = () => draft && entityType && document && act(async () => {
    await apiClient.addManualFallbackRegion(packageId, document.documentId, pageNumber, entityType, draft);
    setDraft(null);
    setConfirmed(false);
  }, `Mark added on page ${pageNumber}.`, 'The mark could not be added. Nothing was changed.');

  const removeMark = (findingId: string) => act(async () => {
    await apiClient.removeManualFallbackRegion(packageId, findingId);
    setConfirmed(false);
  }, 'Mark removed.', 'The mark could not be removed. Nothing was changed.');

  async function finish() {
    if (!canFinish(confirmed, busy)) return;
    setBusy(true);
    setFailure(null);
    try {
      await apiClient.submitManualFallback(packageId, true);
      await Promise.all([mutateGlobal('packages'), mutateGlobal(['package', packageId])]);
      toast('Sent to approval. Redaction and checking run first.', 'mark');
      navigate(`/case/${packageId}`);
    } catch (e) {
      setFailure(errorMessage(e, 'The case could not be finished. Nothing was changed.'));
    } finally {
      setBusy(false);
    }
  }

  const caseLink = (
    <nav className="cf-eyebrow cf-sp-crumbs" aria-label="Breadcrumb">
      <Link to="/">{NAV.cases}</Link><span aria-hidden="true">›</span>
      <Link to={`/case/${packageId}`} className="cf-mono">{title ? [title.primary, title.secondary].filter(Boolean).join(' · ') : 'Case'}</Link>
    </nav>
  );

  if (error && !detail) {
    return (
      <div className="cf-sp">
        <header className="cf-page-head"><div>{caseLink}<h1 className="cf-title">Continue manually</h1></div></header>
        <LoadErrorCard title="Continue manually couldn't be opened" error={error} request={request} onRetry={() => void mutate()} />
      </div>
    );
  }

  return (
    <div className="cf-sp cf-mc">
      <header className="cf-page-head">
        <div>
          {caseLink}
          <div className="cf-pb-title">
            <h1 className="cf-title">Continue manually</h1>
            {stage && <StageChip stage={stage.stage} status={pkg?.status} label={stage.stage === 'stopped' ? undefined : stage.label} />}
          </div>
        </div>
      </header>

      <Notice tone="mark">
        Automatic processing stopped. Mark every sensitive value by hand. Your marks are still checked, and the case still goes through approval before delivery.
      </Notice>

      {!detail ? <p className="cf-muted"><Spinner label="Loading" /> Loading the documents…</p> : (
        <div className="cf-mc-grid">
          <section className="cf-pb-card cf-mc-pages" aria-label="Pages">
            <h2>Pages</h2>
            <p className="cf-muted cf-mc-small">{document ? pagesLine(document.pageCount, marksByPage.size) : 'No documents'}</p>
            {detail.documents.length > 1 && (
              <div className="cf-mc-docs" role="group" aria-label="Documents">
                {detail.documents.map((d) => (
                  <button key={d.documentId} type="button" className="cf-mc-doc" aria-pressed={d.documentId === documentId} title={d.fileName} onClick={() => pickDocument(d.documentId)}>
                    {d.fileName}
                  </button>
                ))}
              </div>
            )}
            <div className="cf-mc-thumbs">
              {document && Array.from({ length: document.pageCount }, (_, i) => i + 1).map((n) => (
                <Thumb key={`${document.documentId}:${n}`} packageId={packageId} documentId={document.documentId} pageNumber={n}
                  selected={n === pageNumber} marks={marksByPage.get(n) ?? 0} eager={n <= 6} onSelect={() => pickPage(n)} />
              ))}
            </div>
          </section>

          {document ? (
            <PageViewer packageId={packageId} documentId={document.documentId} fileName={document.fileName} pageNumber={pageNumber} pageCount={document.pageCount}
              marks={pageRegions} draft={draft} onDraft={setDraft} zoom={zoom} onZoom={setZoom} version={detail.regions.length} />
          ) : <div className="cf-viewer"><p className="cf-muted cf-mc-pad">This case has no documents to mark.</p></div>}

          <aside className="cf-pb-card cf-mc-side" aria-label="Mark a value">
            <h2>Mark a value</h2>
            <div className="cf-field">
              <label htmlFor="cf-mc-type"><span>What is it?</span></label>
              <select id="cf-mc-type" value={entityType} onChange={(e) => setEntityType(e.target.value)}>
                {detail.enabledEntityTypes.map((t) => <option key={t} value={t}>{sensitiveTypeName(t)}</option>)}
              </select>
            </div>
            <p className="cf-muted cf-mc-small">Drag a box over the value on the page, then add it.</p>
            <button type="button" className="cf-btn" data-variant="solid" disabled={!draft || !entityType || busy} onClick={() => void addMark()}>Add mark</button>

            <div className="cf-mc-section">
              <h3>Marked on this page ({pageRegions.length})</h3>
              {pageRegions.length === 0 ? <p className="cf-muted cf-mc-small">Nothing marked on this page yet.</p> : (
                <ul className="cf-mc-marks">
                  {pageRegions.map((r) => (
                    <li key={r.findingId}>
                      <span>{sensitiveTypeName(r.entityType)}</span>
                      <button type="button" className="cf-btn" data-size="sm" disabled={busy} onClick={() => void removeMark(r.findingId)}>Remove</button>
                    </li>
                  ))}
                </ul>
              )}
            </div>

            <div className="cf-mc-section">
              <label className="cf-mc-check">
                <input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />
                <span>I checked every page and marked all sensitive values.</span>
              </label>
              <button type="button" className="cf-btn" data-variant="solid" disabled={!canFinish(confirmed, busy)} onClick={() => void finish()}>
                {busy && <Spinner label="Working" />}Finish and send to approval
              </button>
            </div>
            {failure && <p className="cf-danger-text" role="alert">{failure}</p>}
          </aside>
        </div>
      )}
    </div>
  );
}

function usePagePreview(packageId: string, documentId: string, pageNumber: number, enabled: boolean, version = 0) {
  const { data, error } = useSWR(enabled ? ['manual-preview', packageId, documentId, pageNumber, version] : null,
    () => apiClient.getManualFallbackPagePreview(packageId, documentId, pageNumber), { revalidateOnFocus: false });
  const url = useMemo(() => (data ? URL.createObjectURL(data.blob) : null), [data]);
  useEffect(() => () => { if (url) URL.revokeObjectURL(url); }, [url]);
  return { url, error };
}

function Thumb({ packageId, documentId, pageNumber, selected, marks, eager, onSelect }: {
  packageId: string; documentId: string; pageNumber: number; selected: boolean; marks: number; eager: boolean; onSelect: () => void;
}) {
  const ref = useRef<HTMLButtonElement>(null);
  const [visible, setVisible] = useState(eager || selected);
  useEffect(() => {
    const el = ref.current;
    if (visible || !el || typeof IntersectionObserver === 'undefined') { if (!visible) setVisible(true); return undefined; }
    const io = new IntersectionObserver((entries) => { if (entries.some((e) => e.isIntersecting)) { setVisible(true); io.disconnect(); } }, { rootMargin: '240px 0px' });
    io.observe(el);
    return () => io.disconnect();
  }, [visible]);
  useEffect(() => { if (selected) ref.current?.scrollIntoView({ block: 'nearest' }); }, [selected]);
  const { url, error } = usePagePreview(packageId, documentId, pageNumber, visible);
  return (
    <button ref={ref} type="button" className="cf-mc-thumb" aria-current={selected ? 'page' : undefined} onClick={onSelect}
      aria-label={`Page ${pageNumber}${marks ? `, ${marks} mark${marks === 1 ? '' : 's'}` : ''}`}>
      {url ? <img src={url} alt="" draggable={false} /> : <span className="cf-mc-thumb-empty">{error ? 'No preview' : ''}</span>}
      <span className="cf-mc-thumb-label">{pageNumber}{marks ? ` · ${marks} mark${marks === 1 ? '' : 's'}` : ''}</span>
    </button>
  );
}

function PageViewer({ packageId, documentId, fileName, pageNumber, pageCount, marks, draft, onDraft, zoom, onZoom, version }: {
  packageId: string; documentId: string; fileName: string; pageNumber: number; pageCount: number;
  marks: ReadonlyArray<ReviewBoundingBox & { findingId: string }>; draft: ReviewBoundingBox | null; onDraft: (b: ReviewBoundingBox | null) => void;
  zoom: Zoom; onZoom: (z: Zoom) => void; version: number;
}) {
  const { url, error } = usePagePreview(packageId, documentId, pageNumber, true, version);
  const surfaceRef = useRef<HTMLDivElement>(null);
  const origin = useRef<{ x: number; y: number } | null>(null);
  const [live, setLive] = useState<ReviewBoundingBox | null>(null);
  const [naturalWidth, setNaturalWidth] = useState(0);
  const [shownWidth, setShownWidth] = useState(0);

  useEffect(() => {
    const surface = surfaceRef.current;
    if (!surface) return undefined;
    const observer = new ResizeObserver(() => setShownWidth(surface.clientWidth));
    observer.observe(surface);
    return () => observer.disconnect();
  }, [url]);

  const point = (e: ReactPointerEvent) => {
    const rect = surfaceRef.current!.getBoundingClientRect();
    return { x: clamp((e.clientX - rect.left) / rect.width, 0, 1), y: clamp((e.clientY - rect.top) / rect.height, 0, 1) };
  };
  const boxFrom = (a: { x: number; y: number }, b: { x: number; y: number }): ReviewBoundingBox => ({
    left: Math.min(a.x, b.x), top: Math.min(a.y, b.y), width: Math.abs(a.x - b.x), height: Math.abs(a.y - b.y),
  });

  const actualWidth = naturalWidth / PREVIEW_PIXELS_PER_CSS_PIXEL;
  const percent = zoom === 'fit' ? (actualWidth > 0 && shownWidth > 0 ? Math.round((shownWidth / actualWidth) * 100) : null) : zoom;
  const pageStyle: CSSProperties | undefined = zoom !== 'fit' && actualWidth > 0 ? { width: `${Math.round((actualWidth * zoom) / 100)}px`, maxWidth: 'none' } : undefined;
  const step = (d: 1 | -1) => { if (percent !== null) onZoom(nextZoom(percent, d)); };
  const shown = live ?? draft;

  return (
    <section className="cf-viewer" aria-label={`Page ${pageNumber} of ${fileName}`}>
      <div className="cf-viewer-bar">
        <span className="cf-doc-meta">{fileName} · page {pageNumber} of {pageCount}</span>
        <div className="cf-viewer-tools">
          {draft && <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" onClick={() => onDraft(null)}>Clear box</button>}
          <div className="cf-zoom" role="group" aria-label="Zoom">
            <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" onClick={() => step(-1)} disabled={percent === null} aria-label="Zoom out"><Minus size={14} aria-hidden="true" /></button>
            <span className="cf-zoom-value cf-mono" aria-live="polite">{percent === null ? '—' : `${percent}%`}</span>
            <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" onClick={() => step(1)} disabled={percent === null} aria-label="Zoom in"><Plus size={14} aria-hidden="true" /></button>
            <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" onClick={() => onZoom('fit')} aria-pressed={zoom === 'fit'}>Fit</button>
          </div>
        </div>
      </div>
      <div className="cf-viewer-canvas">
        {error && <Notice tone="danger">The page preview is unavailable.</Notice>}
        {!url && !error && <Spinner label="Rendering page" />}
        {url && (
          <div ref={surfaceRef} className="cf-page" style={pageStyle}
            onPointerDown={(e) => { e.currentTarget.setPointerCapture(e.pointerId); origin.current = point(e); setLive(null); }}
            onPointerMove={(e) => { if (origin.current) setLive(boxFrom(origin.current, point(e))); }}
            onPointerUp={(e) => {
              if (!origin.current) return;
              const box = boxFrom(origin.current, point(e));
              origin.current = null;
              setLive(null);
              if (box.width > MIN_BOX && box.height > MIN_BOX / 2) onDraft(box);
            }}>
            <img src={url} alt={`Page ${pageNumber} of ${fileName}`} draggable={false} onLoad={(e) => setNaturalWidth(e.currentTarget.naturalWidth)} />
            {marks.map((m) => <span key={m.findingId} className="cf-box" data-kind="drawn" style={boxStyle(m)} aria-hidden="true" />)}
            {shown && <span className="cf-box" data-kind="detected" style={boxStyle(shown)} aria-hidden="true" />}
          </div>
        )}
      </div>
    </section>
  );
}

function boxStyle(b: ReviewBoundingBox): CSSProperties {
  return { left: `${b.left * 100}%`, top: `${b.top * 100}%`, width: `${b.width * 100}%`, height: `${b.height * 100}%` };
}
