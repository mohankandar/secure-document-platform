import { useEffect, useMemo, useRef, useState, type CSSProperties, type KeyboardEvent as ReactKeyboardEvent, type PointerEvent as ReactPointerEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { apiClient } from '../api/client';
import type { ApiError, ManualFallbackDetail, PackageDetail, ReviewBoundingBox } from '../types';
import PageScaffold from '../components/ui/PageScaffold';
import HorizontalTabs from '../components/ui/HorizontalTabs';

const MIN_BOX_SIZE = 0.008;
const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value));

const entityLabel = (value: string) => value
  .toLowerCase()
  .split('_')
  .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
  .join(' ');

interface ManualPageThumbnailProps {
  packageId: string;
  documentId: string;
  fileName: string;
  pageNumber: number;
  selected: boolean;
  redactionCount: number;
  selectedPreviewUrl?: string | null;
  eager?: boolean;
  onSelect: () => void;
}

function ManualPageThumbnail({
  packageId,
  documentId,
  fileName,
  pageNumber,
  selected,
  redactionCount,
  selectedPreviewUrl,
  eager = false,
  onSelect,
}: ManualPageThumbnailProps) {
  const buttonRef = useRef<HTMLButtonElement>(null);
  const thumbnailUrlRef = useRef<string | null>(null);
  const [visible, setVisible] = useState(selected || eager);
  const [thumbnailUrl, setThumbnailUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);

  useEffect(() => {
    const element = buttonRef.current;
    if (!element || visible || typeof IntersectionObserver === 'undefined') {
      if (selected || eager) setVisible(true);
      return;
    }

    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        setVisible(true);
        observer.disconnect();
      }
    }, { rootMargin: '260px 0px' });
    observer.observe(element);
    return () => observer.disconnect();
  }, [eager, selected, visible]);

  useEffect(() => {
    if (selected) {
      setVisible(true);
      buttonRef.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    }
  }, [selected]);

  useEffect(() => {
    if (!visible || selectedPreviewUrl || thumbnailUrlRef.current) return;
    let active = true;
    setLoading(true);
    setLoadFailed(false);

    apiClient.getManualFallbackPagePreview(packageId, documentId, pageNumber)
      .then((preview) => {
        if (!active) return;
        const localUrl = URL.createObjectURL(preview.blob);
        thumbnailUrlRef.current = localUrl;
        setThumbnailUrl(localUrl);
      })
      .catch(() => {
        if (active) setLoadFailed(true);
      })
      .finally(() => {
        if (active) setLoading(false);
      });

    return () => { active = false; };
  }, [packageId, documentId, pageNumber, visible, selectedPreviewUrl]);

  useEffect(() => () => {
    if (thumbnailUrlRef.current) URL.revokeObjectURL(thumbnailUrlRef.current);
  }, []);

  const displayUrl = selectedPreviewUrl ?? thumbnailUrl;

  return (
    <button
      ref={buttonRef}
      type="button"
      className={`manual-page-thumbnail${selected ? ' selected' : ''}`}
      onClick={onSelect}
      aria-current={selected ? 'page' : undefined}
      aria-label={`Open page ${pageNumber}${redactionCount > 0 ? `, ${redactionCount} redaction areas` : ''}`}
    >
      <div className="manual-thumbnail-image-wrap">
        {displayUrl ? (
          <img src={displayUrl} alt={`Thumbnail of page ${pageNumber} of ${fileName}`} draggable={false} />
        ) : loadFailed ? (
          <div className="manual-thumbnail-placeholder error">Preview unavailable</div>
        ) : (
          <div className="manual-thumbnail-placeholder">{loading || visible ? 'Loading preview…' : `Page ${pageNumber}`}</div>
        )}
        <span className="manual-thumbnail-page-number">{pageNumber}</span>
        {redactionCount > 0 && <span className="manual-thumbnail-badge">{redactionCount}</span>}
      </div>
    </button>
  );
}

export default function ManualFallbackPage() {
  const { packageId } = useParams<{ packageId: string }>();
  const navigate = useNavigate();
  const [detail, setDetail] = useState<ManualFallbackDetail | null>(null);
  const [packageDetail, setPackageDetail] = useState<PackageDetail | null>(null);
  const [documentId, setDocumentId] = useState('');
  const [pageNumber, setPageNumber] = useState(1);
  const [entityType, setEntityType] = useState('');
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [draftBox, setDraftBox] = useState<ReviewBoundingBox | null>(null);
  const [drawStart, setDrawStart] = useState<{ x: number; y: number } | null>(null);
  const [reviewAttested, setReviewAttested] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [continuityExpanded, setContinuityExpanded] = useState(true);
  const [pagesCollapsed, setPagesCollapsed] = useState(false);
  const [pagesPanelWidth, setPagesPanelWidth] = useState(420);
  const pagesResizeRef = useRef<{ startX: number; startWidth: number; pointerId: number } | null>(null);
  const canvasRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const timer = window.setTimeout(() => setContinuityExpanded(false), 4500);
    return () => window.clearTimeout(timer);
  }, []);

  useEffect(() => {
    if (!packageId) return;
    let active = true;
    apiClient.getPackage(packageId)
      .then((pkg) => { if (active) setPackageDetail(pkg); })
      .catch(() => { if (active) setPackageDetail(null); });
    return () => { active = false; };
  }, [packageId]);

  const load = async () => {
    if (!packageId) return;
    setError(null);
    try {
      const data = await apiClient.getManualFallback(packageId);
      setDetail(data);
      if (!documentId && data.documents.length > 0) setDocumentId(data.documents[0].documentId);
      if (!entityType && data.enabledEntityTypes.length > 0) setEntityType(data.enabledEntityTypes[0]);
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message || 'Unable to load manual processing');
    }
  };

  useEffect(() => { void load(); }, [packageId]);

  const selectedDocument = useMemo(
    () => detail?.documents.find((document) => document.documentId === documentId) ?? null,
    [detail, documentId]
  );

  const primaryIdentifier = useMemo(() => packageDetail?.businessIdentifiers
    ?.filter((identifier) => identifier.displayable)
    .sort((a, b) => Number(b.primary) - Number(a.primary) || a.displayOrder - b.displayOrder)[0] ?? null,
  [packageDetail]);

  const businessIdentityLabel = primaryIdentifier?.label ?? 'Document';
  const businessIdentityValue = primaryIdentifier?.value ?? selectedDocument?.fileName ?? 'Document';

  const selectedDocumentRegions = useMemo(
    () => detail?.regions.filter((region) => region.documentId === documentId) ?? [],
    [detail, documentId]
  );

  const pageRegions = useMemo(
    () => selectedDocumentRegions.filter((region) => region.pageNumber === pageNumber),
    [selectedDocumentRegions, pageNumber]
  );

  const redactionsByPage = useMemo(() => {
    const counts = new Map<number, number>();
    selectedDocumentRegions.forEach((region) => counts.set(region.pageNumber, (counts.get(region.pageNumber) ?? 0) + 1));
    return counts;
  }, [selectedDocumentRegions]);

  const modifiedPageCount = redactionsByPage.size;

  useEffect(() => {
    if (!packageId || !documentId || !selectedDocument) return;
    if (pageNumber > selectedDocument.pageCount) setPageNumber(1);
  }, [documentId, selectedDocument?.pageCount]);

  useEffect(() => {
    if (!packageId || !documentId || !selectedDocument || pageNumber < 1 || pageNumber > selectedDocument.pageCount) return;
    let active = true;
    let localUrl: string | null = null;
    setPreviewLoading(true);
    setDraftBox(null);
    setError(null);
    apiClient.getManualFallbackPagePreview(packageId, documentId, pageNumber)
      .then((preview) => {
        if (!active) return;
        localUrl = URL.createObjectURL(preview.blob);
        setPreviewUrl((old) => {
          if (old) URL.revokeObjectURL(old);
          return localUrl;
        });
      })
      .catch((err: ApiError) => {
        if (active) setError(err.message || `Unable to render page ${pageNumber}`);
      })
      .finally(() => { if (active) setPreviewLoading(false); });
    return () => {
      active = false;
      if (localUrl) URL.revokeObjectURL(localUrl);
    };
  }, [packageId, documentId, pageNumber, detail?.regions.length]);

  const point = (event: ReactPointerEvent<HTMLDivElement>) => {
    const rect = canvasRef.current?.getBoundingClientRect();
    if (!rect) return { x: 0, y: 0 };
    return {
      x: clamp((event.clientX - rect.left) / rect.width, 0, 1),
      y: clamp((event.clientY - rect.top) / rect.height, 0, 1),
    };
  };

  const pointerDown = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (!previewUrl || event.target !== event.currentTarget) return;
    const p = point(event);
    event.currentTarget.setPointerCapture(event.pointerId);
    setDrawStart(p);
    setDraftBox({ left: p.x, top: p.y, width: MIN_BOX_SIZE, height: MIN_BOX_SIZE });
  };

  const pointerMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (!drawStart) return;
    const p = point(event);
    const left = clamp(Math.min(drawStart.x, p.x), 0, 1 - MIN_BOX_SIZE);
    const top = clamp(Math.min(drawStart.y, p.y), 0, 1 - MIN_BOX_SIZE);
    const width = Math.min(Math.max(MIN_BOX_SIZE, Math.abs(p.x - drawStart.x)), 1 - left);
    const height = Math.min(Math.max(MIN_BOX_SIZE, Math.abs(p.y - drawStart.y)), 1 - top);
    setDraftBox({ left, top, width, height });
  };

  const pointerUp = () => setDrawStart(null);

  const selectDocument = (nextDocumentId: string) => {
    if (nextDocumentId === documentId) return;
    setDocumentId(nextDocumentId);
    setPageNumber(1);
    setDraftBox(null);
    setReviewAttested(false);
    setSuccess(null);
  };

  const selectPage = (nextPage: number) => {
    if (!selectedDocument) return;
    setPageNumber(clamp(nextPage, 1, selectedDocument.pageCount));
    setDraftBox(null);
    setSuccess(null);
  };

  const startPagesResize = (event: ReactPointerEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.currentTarget.setPointerCapture(event.pointerId);
    pagesResizeRef.current = { startX: event.clientX, startWidth: pagesPanelWidth, pointerId: event.pointerId };
  };

  const resizePages = (event: ReactPointerEvent<HTMLDivElement>) => {
    const resize = pagesResizeRef.current;
    if (!resize || resize.pointerId !== event.pointerId) return;
    setPagesPanelWidth(clamp(resize.startWidth + (resize.startX - event.clientX), 260, 720));
  };

  const stopPagesResize = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (pagesResizeRef.current?.pointerId !== event.pointerId) return;
    pagesResizeRef.current = null;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
  };

  const resizePagesByKeyboard = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
    event.preventDefault();
    const step = event.shiftKey ? 48 : 24;
    setPagesPanelWidth((current) => clamp(current + (event.key === 'ArrowLeft' ? step : -step), 260, 720));
  };

  const manualGridStyle = { '--manual-pages-width': `${pagesPanelWidth}px` } as CSSProperties;

  const addRegion = async () => {
    if (!packageId || !documentId || !draftBox || !entityType) return;
    setBusy(true); setError(null); setSuccess(null);
    try {
      await apiClient.addManualFallbackRegion(packageId, documentId, pageNumber, entityType, draftBox);
      setDraftBox(null);
      setReviewAttested(false);
      setSuccess(`Redaction area added on page ${pageNumber}.`);
      await load();
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message || 'Unable to add redaction area');
    } finally { setBusy(false); }
  };

  const removeRegion = async (findingId: string) => {
    if (!packageId) return;
    setBusy(true); setError(null); setSuccess(null);
    try {
      await apiClient.removeManualFallbackRegion(packageId, findingId);
      setReviewAttested(false);
      setSuccess('Redaction area removed.');
      await load();
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message || 'Unable to remove redaction area');
    } finally { setBusy(false); }
  };

  const submit = async () => {
    if (!packageId || !reviewAttested) return;
    setBusy(true); setError(null); setSuccess(null);
    try {
      await apiClient.submitManualFallback(packageId, true);
      setSuccess('Manual review submitted. Permanent redaction and delivery have resumed.');
      window.setTimeout(() => navigate(`/packages/${packageId}`), 700);
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message || 'Unable to submit manual processing');
    } finally { setBusy(false); }
  };

  return (
    <PageScaffold className="review-page manual-processing-page" breadcrumbs={[{ label: 'Intake Details', to: packageId ? `/packages/${packageId}` : '/' }, { label: 'Manual Processing' }]} title="Manual Processing"
      eyebrow="Controlled recovery"
      description="Complete required document handling while preserving redaction, verification, and incident traceability."
      actions={packageId ? <Link className="ui-button secondary" to={`/packages/${packageId}`}>Back to Intake Details</Link> : undefined}>

      <div className={`manual-fallback-warning${continuityExpanded ? ' expanded' : ' compact'}`}>
        <div className="manual-fallback-warning-copy">
          <strong>Business continuity mode</strong>
          <span>{continuityExpanded
            ? 'The technical incident remains recorded for IT investigation. This workflow does not bypass redaction or verification.'
            : 'Technical incident retained for IT.'}</span>
        </div>
        <button type="button" className="manual-warning-toggle" onClick={() => setContinuityExpanded((value) => !value)}>
          {continuityExpanded ? 'Collapse' : 'Details'}
        </button>
      </div>

      {error && <div className="ui-alert tone-danger">{error}</div>}
      {success && <div className="ui-alert tone-success">{success}</div>}

      {!detail ? <div className="ui-card">Loading manual processing…</div> : (
        <div className={`manual-fallback-grid${pagesCollapsed ? ' pages-collapsed' : ''}`} style={manualGridStyle}>
          <aside className="ui-card manual-fallback-sidebar">
            <div className="review-section-title">Business & tools</div>
            <div className="manual-document-summary">
              <span className="manual-field-label">{businessIdentityLabel}</span>
              <strong title={businessIdentityValue}>{businessIdentityValue}</strong>
              {detail.documents.length > 1 && <span>Choose the document and page from the Pages panel.</span>}
            </div>
            <div className="review-section-title manual-section-gap">Add redaction</div>
            <label>Sensitive type
              <select value={entityType} onChange={(e) => setEntityType(e.target.value)}>
                {detail.enabledEntityTypes.map((type) => <option key={type} value={type}>{entityLabel(type)}</option>)}
              </select>
            </label>
            <p className="manual-help">Drag a rectangle over the sensitive content, then add it to the redaction list.</p>
            <button className="ui-button primary" disabled={!draftBox || busy} onClick={() => void addRegion()}>Add Redaction Area</button>
            {draftBox && <button className="ui-button secondary" disabled={busy} onClick={() => setDraftBox(null)}>Clear Draft</button>}

            <div className="manual-sidebar-divider" />
            <div className="review-section-title">Redactions on this page</div>
            {pageRegions.length === 0 ? <p className="manual-help">No redaction areas added on this page.</p> : (
              <div className="manual-region-list">
                {pageRegions.map((region) => (
                  <div key={region.findingId} className="manual-region-row">
                    <span>{entityLabel(region.entityType)}</span>
                    <button className="ui-button secondary" disabled={busy} onClick={() => void removeRegion(region.findingId)}>Remove</button>
                  </div>
                ))}
              </div>
            )}

            <div className="manual-sidebar-divider" />
            <div className="review-section-title">Finish manual processing</div>
            <label className="manual-review-confirmation">
              <input type="checkbox" checked={reviewAttested} onChange={(e) => setReviewAttested(e.target.checked)} />
              <span>I confirm that I reviewed this document as necessary and marked all sensitive information that must be permanently redacted.</span>
            </label>
            <button className="ui-button primary" disabled={!reviewAttested || busy} onClick={() => void submit()}>
              Submit Manual Processing
            </button>
            <p className="manual-help">Submission resumes the normal permanent-redaction, verification, approval, and delivery pipeline.</p>
          </aside>

          <section className="review-preview card">
            <div className="review-preview-heading">
              <div><strong>{selectedDocument?.fileName}</strong><span>Page {pageNumber}</span></div>
              <span className="review-reason">Business continuity review</span>
            </div>
            <div className="review-page-frame editing">
              {previewLoading && <div className="review-preview-loading">Rendering page…</div>}
              {previewUrl && (
                <div className="review-page-canvas" ref={canvasRef}
                     onPointerDown={pointerDown} onPointerMove={pointerMove}
                     onPointerUp={pointerUp} onPointerCancel={pointerUp}>
                  <img src={previewUrl} alt={`Page ${pageNumber} of ${selectedDocument?.fileName ?? 'document'}`} draggable={false} />
                  {pageRegions.map((region) => (
                    <div key={region.findingId} className="review-bounding-box"
                         style={{ left: `${region.left * 100}%`, top: `${region.top * 100}%`, width: `${region.width * 100}%`, height: `${region.height * 100}%` }} />
                  ))}
                  {draftBox && (
                    <div className="review-bounding-box editable"
                         style={{ left: `${draftBox.left * 100}%`, top: `${draftBox.top * 100}%`, width: `${draftBox.width * 100}%`, height: `${draftBox.height * 100}%` }} />
                  )}
                </div>
              )}
            </div>
          </section>

          <aside className={`ui-card manual-fallback-pages-panel${pagesCollapsed ? ' collapsed' : ''}`}>
            {pagesCollapsed ? (
              <button
                type="button"
                className="manual-pages-expand-button"
                onClick={() => setPagesCollapsed(false)}
                aria-label="Expand page previews"
                title="Expand page previews"
              >
                <span aria-hidden="true">◀</span>
                <span>Pages</span>
              </button>
            ) : (
              <>
                <div
                  className="manual-pages-resize-handle"
                  role="separator"
                  aria-orientation="vertical"
                  aria-label="Resize page preview panel"
                  aria-valuemin={260}
                  aria-valuemax={720}
                  aria-valuenow={Math.round(pagesPanelWidth)}
                  tabIndex={0}
                  onPointerDown={startPagesResize}
                  onPointerMove={resizePages}
                  onPointerUp={stopPagesResize}
                  onPointerCancel={stopPagesResize}
                  onKeyDown={resizePagesByKeyboard}
                >
                  <span />
                </div>
                <div className="manual-pages-heading">
                  <div>
                    <div className="review-section-title">Pages</div>
                    <span>{selectedDocument?.pageCount ?? 0} pages · {modifiedPageCount} modified · {selectedDocumentRegions.length} redactions</span>
                  </div>
                  <button
                    type="button"
                    className="manual-pages-toggle"
                    onClick={() => setPagesCollapsed(true)}
                    aria-label="Collapse page previews"
                    title="Collapse page previews"
                  >
                    Pages <span aria-hidden="true">▶</span>
                  </button>
                </div>
                {detail.documents.length > 1 && (
                  <HorizontalTabs ariaLabel="Documents in this package" >
                    {detail.documents.map((document) => (
                      <button
                        key={document.documentId}
                        type="button"
                        className={document.documentId === documentId ? 'selected' : ''}
                        title={document.fileName}
                        onClick={() => selectDocument(document.documentId)}
                      >
                        {document.fileName}
                      </button>
                    ))}
                  </HorizontalTabs>
                )}
                <p className="manual-help manual-thumbnail-help">Scroll the page images and click the page you recognize. Drag the left edge to resize this panel.</p>
                <div className="manual-thumbnail-list" aria-label="Document page previews">
                  {packageId && selectedDocument && Array.from({ length: selectedDocument.pageCount }, (_, index) => {
                    const thumbnailPage = index + 1;
                    return (
                      <ManualPageThumbnail
                        key={`${selectedDocument.documentId}:${thumbnailPage}`}
                        packageId={packageId}
                        documentId={selectedDocument.documentId}
                        fileName={selectedDocument.fileName}
                        pageNumber={thumbnailPage}
                        selected={thumbnailPage === pageNumber}
                        redactionCount={redactionsByPage.get(thumbnailPage) ?? 0}
                        selectedPreviewUrl={thumbnailPage === pageNumber ? previewUrl : null}
                        eager={thumbnailPage <= 6}
                        onSelect={() => selectPage(thumbnailPage)}
                      />
                    );
                  })}
                </div>
              </>
            )}
          </aside>
        </div>
      )}
    </PageScaffold>
  );
}
