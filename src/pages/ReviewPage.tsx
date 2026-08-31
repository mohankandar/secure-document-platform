import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { PointerEvent as ReactPointerEvent } from 'react';
import { apiClient } from '../api/client';
import type {
  ApiError,
  FocusedReviewItem,
  ReviewBoundingBox,
  ReviewResolutionResult,
} from '../types';

type Interaction =
  | { kind: 'draw'; startX: number; startY: number }
  | { kind: 'move'; startX: number; startY: number; box: ReviewBoundingBox }
  | { kind: 'resize'; startX: number; startY: number; box: ReviewBoundingBox }
  | null;

const MIN_BOX_SIZE = 0.01;

function clamp(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, value));
}

function entityLabel(value: string) {
  return value
    .toLowerCase()
    .split('_')
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ');
}

function reasonLabel(value: string) {
  switch (value) {
    case 'LOCATION_UNCERTAIN': return 'Location needs confirmation';
    case 'LOW_CONFIDENCE': return 'Low confidence';
    case 'POLICY_REVIEW': return 'Policy review';
    default: return 'Needs review';
  }
}

function boxChanged(a: ReviewBoundingBox | null, b: ReviewBoundingBox | null) {
  if (!a || !b) return a !== b;
  return ['left', 'top', 'width', 'height'].some((key) =>
    Math.abs(a[key as keyof ReviewBoundingBox] - b[key as keyof ReviewBoundingBox]) > 0.0005
  );
}

export default function ReviewPage() {
  const [items, setItems] = useState<FocusedReviewItem[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [resolving, setResolving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [box, setBox] = useState<ReviewBoundingBox | null>(null);
  const [originalBox, setOriginalBox] = useState<ReviewBoundingBox | null>(null);
  const [editing, setEditing] = useState(false);
  const [confirmDismiss, setConfirmDismiss] = useState(false);
  const [interaction, setInteraction] = useState<Interaction>(null);
  const previewRef = useRef<HTMLDivElement>(null);

  const selected = useMemo(
    () => items.find((item) => item.findingId === selectedId) || null,
    [items, selectedId]
  );

  const loadQueue = useCallback(async (preferredId?: string | null) => {
    setError(null);
    try {
      const data = await apiClient.listReviewItems(100);
      setItems(data);
      const candidate = preferredId && data.some((item) => item.findingId === preferredId)
        ? preferredId
        : data[0]?.findingId || null;
      setSelectedId(candidate);
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message || 'Unable to load Manual Review items');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadQueue();
  }, [loadQueue]);

  useEffect(() => {
    if (!selected) {
      setBox(null);
      setOriginalBox(null);
      setEditing(false);
      setPreviewUrl((old) => {
        if (old) URL.revokeObjectURL(old);
        return null;
      });
      return;
    }

    const nextBox = selected.boundingBox || null;
    setBox(nextBox);
    setOriginalBox(nextBox);
    setEditing(!selected.hasBoundingBox);
    setConfirmDismiss(false);
    setInteraction(null);
    setPreviewUrl((old) => {
      if (old) URL.revokeObjectURL(old);
      return null;
    });
    setPreviewLoading(true);
    setPreviewError(null);
    setError(null);

    let active = true;
    let localUrl: string | null = null;
    apiClient.getReviewPagePreview(selected.findingId)
      .then((blob) => {
        if (!active) return;
        localUrl = URL.createObjectURL(blob);
        setPreviewUrl((old) => {
          if (old) URL.revokeObjectURL(old);
          return localUrl;
        });
      })
      .catch((err: ApiError) => {
        if (active) {
          const message = err.message || 'Unable to load the review page';
          setPreviewError(message);
          setError(message);
        }
      })
      .finally(() => {
        if (active) setPreviewLoading(false);
      });

    return () => {
      active = false;
      if (localUrl) URL.revokeObjectURL(localUrl);
    };
  }, [selected?.findingId]);

  const point = (event: ReactPointerEvent) => {
    const rect = previewRef.current?.getBoundingClientRect();
    if (!rect) return { x: 0, y: 0 };
    return {
      x: clamp((event.clientX - rect.left) / rect.width, 0, 1),
      y: clamp((event.clientY - rect.top) / rect.height, 0, 1),
    };
  };

  const handleCanvasPointerDown = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (!editing || !previewUrl || event.target !== event.currentTarget) return;
    const p = point(event);
    event.currentTarget.setPointerCapture(event.pointerId);
    setInteraction({ kind: 'draw', startX: p.x, startY: p.y });
    setBox({ left: p.x, top: p.y, width: MIN_BOX_SIZE, height: MIN_BOX_SIZE });
  };

  const handleBoxPointerDown = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (!editing || !box) return;
    event.stopPropagation();
    const p = point(event);
    event.currentTarget.setPointerCapture(event.pointerId);
    setInteraction({ kind: 'move', startX: p.x, startY: p.y, box: { ...box } });
  };

  const handleResizePointerDown = (event: ReactPointerEvent<HTMLButtonElement>) => {
    if (!editing || !box) return;
    event.stopPropagation();
    const p = point(event);
    event.currentTarget.setPointerCapture(event.pointerId);
    setInteraction({ kind: 'resize', startX: p.x, startY: p.y, box: { ...box } });
  };

  const handlePointerMove = (event: ReactPointerEvent) => {
    if (!interaction) return;
    const p = point(event);

    if (interaction.kind === 'draw') {
      const left = clamp(Math.min(interaction.startX, p.x), 0, 1 - MIN_BOX_SIZE);
      const top = clamp(Math.min(interaction.startY, p.y), 0, 1 - MIN_BOX_SIZE);
      const width = Math.min(
        Math.max(MIN_BOX_SIZE, Math.abs(p.x - interaction.startX)),
        1 - left
      );
      const height = Math.min(
        Math.max(MIN_BOX_SIZE, Math.abs(p.y - interaction.startY)),
        1 - top
      );
      setBox({ left, top, width, height });
      return;
    }

    if (interaction.kind === 'move') {
      const dx = p.x - interaction.startX;
      const dy = p.y - interaction.startY;
      setBox({
        ...interaction.box,
        left: clamp(interaction.box.left + dx, 0, 1 - interaction.box.width),
        top: clamp(interaction.box.top + dy, 0, 1 - interaction.box.height),
      });
      return;
    }

    const width = clamp(interaction.box.width + (p.x - interaction.startX), MIN_BOX_SIZE, 1 - interaction.box.left);
    const height = clamp(interaction.box.height + (p.y - interaction.startY), MIN_BOX_SIZE, 1 - interaction.box.top);
    setBox({ ...interaction.box, width, height });
  };

  const stopInteraction = () => setInteraction(null);

  const resolve = async (resolution: 'confirm' | 'dismiss') => {
    if (!selected) return;
    if (resolution === 'confirm' && !box) {
      setError('Draw or adjust the sensitive area before confirming this item.');
      return;
    }
    setConfirmDismiss(false);
    setResolving(true);
    setError(null);
    setSuccess(null);
    try {
      let result: ReviewResolutionResult;
      if (resolution === 'confirm') {
        result = await apiClient.confirmReviewItem(selected.findingId, box || undefined);
      } else {
        result = await apiClient.dismissReviewItem(selected.findingId);
      }
      setSuccess(result.processingResumed
        ? 'Review complete. Automatic processing has resumed.'
        : resolution === 'confirm'
          ? 'Finding confirmed. Continue with the remaining review items.'
          : 'Item marked not sensitive. Continue with the remaining review items.');
      await loadQueue();
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message || 'Unable to resolve this review item');
    } finally {
      setResolving(false);
    }
  };

  if (loading) {
    return <div className="review-loading">Loading Manual Review…</div>;
  }

  return (
    <div className="review-page">
      <div className="review-header">
        <div>
          <h1>Manual Review</h1>
          <p>Only findings that need a human decision appear here.</p>
        </div>
        <div className="review-header-actions">
          <span className="review-count">{items.length} need attention</span>
          <button className="secondary" onClick={() => loadQueue(selectedId)}>Refresh</button>
        </div>
      </div>

      {error && <div className="error-msg">{error}</div>}
      {success && <div className="success-msg">{success}</div>}

      {items.length === 0 ? (
        <div className="review-empty card">
          <div className="review-empty-icon">✓</div>
          <h2>You're all caught up</h2>
          <p>There are no findings waiting for Manual Review.</p>
        </div>
      ) : (
        <div className="review-workbench-grid">
          <aside className="review-queue card">
            <div className="review-section-title">Needs attention</div>
            <div className="review-queue-list">
              {items.map((item) => (
                <button
                  key={item.findingId}
                  className={`review-queue-item ${item.findingId === selectedId ? 'active' : ''}`}
                  onClick={() => setSelectedId(item.findingId)}
                >
                  <div className="review-queue-type">{entityLabel(item.entityType)}</div>
                  <div className="review-queue-file">{item.fileName}</div>
                  <div className="review-queue-meta">
                    <span>Page {item.pageNumber}</span>
                    <span>{Math.round(item.confidence * 100)}%</span>
                  </div>
                </button>
              ))}
            </div>
          </aside>

          <section className="review-preview card">
            {selected && (
              <>
                <div className="review-preview-heading">
                  <div>
                    <strong>{selected.fileName}</strong>
                    <span>Page {selected.pageNumber}</span>
                  </div>
                  <span className={`review-reason review-reason-${selected.reason.toLowerCase()}`}>
                    {reasonLabel(selected.reason)}
                  </span>
                </div>

                <div className={`review-page-frame ${editing ? 'editing' : ''}`}>
                  {previewLoading && <div className="review-preview-loading">Rendering page…</div>}
                  {!previewLoading && previewError && (
                    <div className="review-preview-loading">
                      Preview unavailable: {previewError}
                    </div>
                  )}
                  {previewUrl && (
                    <div
                      className="review-page-canvas"
                      ref={previewRef}
                      onPointerDown={handleCanvasPointerDown}
                      onPointerMove={handlePointerMove}
                      onPointerUp={stopInteraction}
                      onPointerCancel={stopInteraction}
                    >
                      <img src={previewUrl} alt={`Page ${selected.pageNumber} of ${selected.fileName}`} draggable={false} />
                      {box && (
                        <div
                          className={`review-bounding-box ${editing ? 'editable' : ''}`}
                          style={{
                            left: `${box.left * 100}%`,
                            top: `${box.top * 100}%`,
                            width: `${box.width * 100}%`,
                            height: `${box.height * 100}%`,
                          }}
                          onPointerDown={handleBoxPointerDown}
                          onPointerMove={handlePointerMove}
                          onPointerUp={stopInteraction}
                          onPointerCancel={stopInteraction}
                        >
                          {editing && (
                            <button
                              type="button"
                              className="review-resize-handle"
                              aria-label="Resize sensitive area"
                              onPointerDown={handleResizePointerDown}
                              onPointerMove={handlePointerMove}
                              onPointerUp={stopInteraction}
                              onPointerCancel={stopInteraction}
                            />
                          )}
                        </div>
                      )}
                    </div>
                  )}
                </div>

                <div className="review-preview-toolbar">
                  <span>
                    {box
                      ? editing ? 'Drag the box to move it. Use the corner handle to resize.' : 'Highlighted area proposed for redaction.'
                      : 'Draw a box around the sensitive value.'}
                  </span>
                  {box && !editing && (
                    <button className="link-button" onClick={() => setEditing(true)}>Adjust area</button>
                  )}
                  {editing && (
                    <div className="review-toolbar-actions">
                      {originalBox && boxChanged(box, originalBox) && (
                        <button className="link-button" onClick={() => setBox(originalBox)}>Reset</button>
                      )}
                      {!originalBox && box && (
                        <button className="link-button" onClick={() => setBox(null)}>Clear</button>
                      )}
                      <button className="link-button" onClick={() => box && setEditing(false)} disabled={!box}>Done</button>
                    </div>
                  )}
                </div>
              </>
            )}
          </section>

          <aside className="review-decision card">
            {selected && (
              <>
                <div className="review-section-title">Review finding</div>
                <div className="review-entity-name">{entityLabel(selected.entityType)}</div>
                <div className="review-confidence-row">
                  <span>AI confidence</span>
                  <strong>{Math.round(selected.confidence * 100)}%</strong>
                </div>
                <div className="review-confidence-track">
                  <div style={{ width: `${clamp(selected.confidence, 0, 1) * 100}%` }} />
                </div>

                <div className="review-info-box">
                  {selected.reason === 'LOCATION_UNCERTAIN'
                    ? 'The sensitive content was identified, but its exact location needs your confirmation.'
                    : selected.reason === 'LOW_CONFIDENCE'
                      ? 'AI found potentially sensitive content, but confidence is below the automatic-redaction threshold.'
                      : 'Tenant policy requires a human decision for this finding.'}
                </div>

                <div className="review-context-list">
                  <div><span>Document</span><strong>{selected.fileName}</strong></div>
                  <div><span>Page</span><strong>{selected.pageNumber}</strong></div>
                  <div><span>Location</span><strong>{box ? 'Selected' : 'Needs selection'}</strong></div>
                </div>

                <div className="review-decision-actions">
                  <button
                    className="primary review-confirm"
                    disabled={resolving || !box}
                    onClick={() => resolve('confirm')}
                  >
                    {resolving ? 'Saving…' : 'Confirm & Submit'}
                  </button>
                  {!confirmDismiss ? (
                    <button
                      className="secondary review-dismiss"
                      disabled={resolving}
                      onClick={() => setConfirmDismiss(true)}
                    >
                      Not Sensitive
                    </button>
                  ) : (
                    <div className="review-dismiss-confirm">
                      <span>Mark this item as not sensitive?</span>
                      <div>
                        <button className="secondary" disabled={resolving} onClick={() => setConfirmDismiss(false)}>Cancel</button>
                        <button className="secondary review-dismiss-final" disabled={resolving} onClick={() => resolve('dismiss')}>Yes, not sensitive</button>
                      </div>
                    </div>
                  )}
                </div>

                <p className="review-auto-note">
                  When the last review item is resolved, processing resumes automatically.
                </p>
              </>
            )}
          </aside>
        </div>
      )}
    </div>
  );
}
