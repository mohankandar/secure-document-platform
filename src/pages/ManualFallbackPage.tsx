import { useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { apiClient } from '../api/client';
import type { ApiError, ManualFallbackDetail, ReviewBoundingBox } from '../types';
import Breadcrumbs from '../components/Breadcrumbs';

const MIN_BOX_SIZE = 0.008;
const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value));

const entityLabel = (value: string) => value
  .toLowerCase()
  .split('_')
  .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
  .join(' ');

export default function ManualFallbackPage() {
  const { packageId } = useParams<{ packageId: string }>();
  const navigate = useNavigate();
  const [detail, setDetail] = useState<ManualFallbackDetail | null>(null);
  const [documentId, setDocumentId] = useState('');
  const [pageNumber, setPageNumber] = useState(1);
  const [entityType, setEntityType] = useState('');
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [draftBox, setDraftBox] = useState<ReviewBoundingBox | null>(null);
  const [drawStart, setDrawStart] = useState<{ x: number; y: number } | null>(null);
  const [fullReviewed, setFullReviewed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const canvasRef = useRef<HTMLDivElement>(null);

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

  const pageRegions = useMemo(
    () => detail?.regions.filter((region) => region.documentId === documentId && region.pageNumber === pageNumber) ?? [],
    [detail, documentId, pageNumber]
  );

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

  const addRegion = async () => {
    if (!packageId || !documentId || !draftBox || !entityType) return;
    setBusy(true); setError(null); setSuccess(null);
    try {
      await apiClient.addManualFallbackRegion(packageId, documentId, pageNumber, entityType, draftBox);
      setDraftBox(null);
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
      setSuccess('Redaction area removed.');
      await load();
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message || 'Unable to remove redaction area');
    } finally { setBusy(false); }
  };

  const submit = async () => {
    if (!packageId || !fullReviewed) return;
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
    <div className="review-page">
      <Breadcrumbs items={[{ label: 'Intake Details', to: packageId ? `/packages/${packageId}` : '/' }, { label: 'Manual Processing' }]} />
      <div className="review-header">
        <div>
          <h1>Manual Processing</h1>
          <p>Automatic sensitive-data analysis could not complete. Review every page and mark every area that must be permanently redacted.</p>
        </div>
        {packageId && <Link className="button-link secondary" to={`/packages/${packageId}`}>Back to Intake Details</Link>}
      </div>

      <div className="manual-fallback-warning">
        <strong>Business continuity mode</strong>
        <span>The technical incident remains recorded for IT investigation. This workflow does not bypass redaction or verification.</span>
      </div>

      {error && <div className="error-msg">{error}</div>}
      {success && <div className="success-msg">{success}</div>}

      {!detail ? <div className="card">Loading manual processing…</div> : (
        <div className="manual-fallback-grid">
          <aside className="card manual-fallback-sidebar">
            <div className="review-section-title">Document & page</div>
            <label>Document
              <select value={documentId} onChange={(e) => { setDocumentId(e.target.value); setPageNumber(1); }}>
                {detail.documents.map((document) => (
                  <option key={document.documentId} value={document.documentId}>{document.fileName}</option>
                ))}
              </select>
            </label>
            <div className="manual-page-nav">
              <button className="secondary" disabled={pageNumber <= 1} onClick={() => setPageNumber((value) => value - 1)}>Previous</button>
              <strong>Page {pageNumber} of {selectedDocument?.pageCount ?? 0}</strong>
              <button className="secondary" disabled={!selectedDocument || pageNumber >= selectedDocument.pageCount} onClick={() => setPageNumber((value) => value + 1)}>Next</button>
            </div>

            <div className="review-section-title manual-section-gap">Add redaction</div>
            <label>Sensitive type
              <select value={entityType} onChange={(e) => setEntityType(e.target.value)}>
                {detail.enabledEntityTypes.map((type) => <option key={type} value={type}>{entityLabel(type)}</option>)}
              </select>
            </label>
            <p className="manual-help">Drag a rectangle over the sensitive content, then add it to the redaction list.</p>
            <button className="primary" disabled={!draftBox || busy} onClick={() => void addRegion()}>Add Redaction Area</button>
            {draftBox && <button className="secondary" disabled={busy} onClick={() => setDraftBox(null)}>Clear Draft</button>}
          </aside>

          <section className="review-preview card">
            <div className="review-preview-heading">
              <div><strong>{selectedDocument?.fileName}</strong><span>Page {pageNumber}</span></div>
              <span className="review-reason">Full-document review</span>
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

          <aside className="card manual-fallback-sidebar">
            <div className="review-section-title">Redactions on this page</div>
            {pageRegions.length === 0 ? <p className="manual-help">No redaction areas added on this page.</p> : (
              <div className="manual-region-list">
                {pageRegions.map((region) => (
                  <div key={region.findingId} className="manual-region-row">
                    <span>{entityLabel(region.entityType)}</span>
                    <button className="secondary" disabled={busy} onClick={() => void removeRegion(region.findingId)}>Remove</button>
                  </div>
                ))}
              </div>
            )}

            <div className="review-section-title manual-section-gap">Finish manual processing</div>
            <label className="manual-review-confirmation">
              <input type="checkbox" checked={fullReviewed} onChange={(e) => setFullReviewed(e.target.checked)} />
              <span>I reviewed every page of every document in this manual processing session.</span>
            </label>
            <button className="primary" disabled={!fullReviewed || busy} onClick={() => void submit()}>
              Submit Manual Processing
            </button>
            <p className="manual-help">Submission resumes the normal permanent-redaction, verification, approval, and delivery pipeline.</p>
          </aside>
        </div>
      )}
    </div>
  );
}
