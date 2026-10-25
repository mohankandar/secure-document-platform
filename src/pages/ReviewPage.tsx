import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import type { PointerEvent as ReactPointerEvent } from 'react';
import { apiClient } from '../api/client';
import { intakeBreadcrumbLabel } from '../components/Breadcrumbs';
import PageScaffold from '../components/ui/PageScaffold';
import Icon from '../components/ui/Icon';
import type {
  ApiError,
  FocusedReviewItem,
  PackageDetail,
  ReviewBoundingBox,
  ReviewResolutionResult,
} from '../types';
import './ReviewPage.css';

type Interaction =
  | { kind: 'draw'; startX: number; startY: number }
  | { kind: 'move'; startX: number; startY: number; box: ReviewBoundingBox }
  | { kind: 'resize'; startX: number; startY: number; box: ReviewBoundingBox }
  | null;

const MIN_BOX_SIZE = 0.01;

function clamp(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, value));
}

const ENTITY_ACRONYMS = new Set(['SSN', 'EIN', 'ITIN', 'DOB', 'ID', 'IBAN', 'VIN', 'NPI']);

function entityLabel(value: string) {
  return value
    .split('_')
    .map((part) => {
      const upper = part.toUpperCase();
      if (ENTITY_ACRONYMS.has(upper)) return upper;
      const lower = part.toLowerCase();
      return lower.charAt(0).toUpperCase() + lower.slice(1);
    })
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

function reasonTone(value: string) {
  switch (value) {
    case 'LOCATION_UNCERTAIN': return 'info';
    case 'POLICY_REVIEW': return 'violet';
    default: return 'warning';
  }
}

function reasonExplanation(value: string) {
  switch (value) {
    case 'LOCATION_UNCERTAIN':
      return 'The sensitive content was identified, but its exact location needs your confirmation.';
    case 'LOW_CONFIDENCE':
      return 'AI found potentially sensitive content, but confidence is below the automatic-redaction threshold.';
    default:
      return 'Tenant policy requires a human decision for this finding.';
  }
}

function confidenceBand(confidence: number) {
  if (confidence < 0.6) return { label: 'Weak signal', tone: 'danger' };
  if (confidence < 0.75) return { label: 'Moderate signal', tone: 'warning' };
  return { label: 'Strong signal', tone: 'success' };
}

function boxChanged(a: ReviewBoundingBox | null, b: ReviewBoundingBox | null) {
  if (!a || !b) return a !== b;
  return ['left', 'top', 'width', 'height'].some((key) =>
    Math.abs(a[key as keyof ReviewBoundingBox] - b[key as keyof ReviewBoundingBox]) > 0.0005
  );
}

function isTypingTarget(target: EventTarget | null) {
  if (!(target instanceof HTMLElement)) return false;
  return target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName);
}

export default function ReviewPage() {
  const [searchParams] = useSearchParams();
  const packageId = searchParams.get('packageId') || undefined;
  const [items, setItems] = useState<FocusedReviewItem[]>([]);
  const [totalItems, setTotalItems] = useState(0);
  const [scopedPackage, setScopedPackage] = useState<PackageDetail | null>(null);
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
  const [resolvedThisSession, setResolvedThisSession] = useState(0);
  const previewRef = useRef<HTMLDivElement>(null);

  const selected = useMemo(
    () => items.find((item) => item.findingId === selectedId) || null,
    [items, selectedId]
  );
  const selectedIndex = useMemo(
    () => items.findIndex((item) => item.findingId === selectedId),
    [items, selectedId]
  );

  const scopedIdentifier = useMemo(() => {
    if (!scopedPackage) return null;
    return scopedPackage.businessIdentifiers
      ?.filter((identifier) => identifier.displayable)
      .sort((a, b) => Number(b.primary) - Number(a.primary) || a.displayOrder - b.displayOrder)[0] || null;
  }, [scopedPackage]);

  const breadcrumbItems = packageId
    ? [
        { label: intakeBreadcrumbLabel(scopedIdentifier), to: `/packages/${packageId}` },
        { label: 'Manual Review' },
      ]
    : [{ label: 'Manual Review' }];

  const reviewGroups = useMemo(() => {
    const groups: Array<{ key: string; fileName: string; pageNumber: number; items: FocusedReviewItem[] }> = [];
    const byKey = new Map<string, typeof groups[number]>();
    for (const item of items) {
      const key = `${item.documentId}:${item.pageNumber}`;
      let group = byKey.get(key);
      if (!group) {
        group = { key, fileName: item.fileName, pageNumber: item.pageNumber, items: [] };
        byKey.set(key, group);
        groups.push(group);
      }
      group.items.push(item);
    }
    return groups;
  }, [items]);

  const reasonCounts = useMemo(() => {
    const counts = { LOW_CONFIDENCE: 0, LOCATION_UNCERTAIN: 0, POLICY_REVIEW: 0 } as Record<string, number>;
    for (const item of items) counts[item.reason] = (counts[item.reason] || 0) + 1;
    return counts;
  }, [items]);

  const loadQueue = useCallback(async (preferredId?: string | null) => {
    setError(null);
    try {
      const queue = await apiClient.listReviewItems(100, packageId);
      setItems(queue.items);
      setTotalItems(queue.totalItems);
      const candidate = preferredId && queue.items.some((item) => item.findingId === preferredId)
        ? preferredId
        : queue.items[0]?.findingId || null;
      setSelectedId(candidate);
    } catch (err) {
      const apiErr = err as ApiError;
      setError(apiErr.message || 'Unable to load Manual Review items');
    } finally {
      setLoading(false);
    }
  }, [packageId]);

  useEffect(() => {
    loadQueue();
  }, [loadQueue]);

  useEffect(() => {
    let active = true;
    if (!packageId) {
      setScopedPackage(null);
      return () => { active = false; };
    }
    apiClient.getPackage(packageId)
      .then((pkg) => { if (active) setScopedPackage(pkg); })
      .catch(() => { if (active) setScopedPackage(null); });
    return () => { active = false; };
  }, [packageId]);

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
      .then((preview) => {
        if (!active) return;
        if (preview.pageNumber !== selected.pageNumber) {
          throw { message: `Preview page mismatch. Expected page ${selected.pageNumber}.` } as ApiError;
        }
        localUrl = URL.createObjectURL(preview.blob);
        setPreviewUrl((old) => {
          if (old) URL.revokeObjectURL(old);
          return localUrl;
        });
      })
      .catch((err: ApiError) => {
        if (active) {
          const message = err.message || 'Unable to load the review page';
          setPreviewError(message);
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
      setResolvedThisSession((count) => count + 1);
      setSuccess(result.processingResumed
        ? 'Review complete. Processing has resumed in the background.'
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

  const step = (direction: 1 | -1) => {
    if (items.length === 0) return;
    const next = clamp((selectedIndex < 0 ? 0 : selectedIndex) + direction, 0, items.length - 1);
    setSelectedId(items[next].findingId);
  };

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.metaKey || event.ctrlKey || event.altKey || isTypingTarget(event.target)) return;
      if (resolving || !selected) return;
      const key = event.key.toLowerCase();
      if (key === 'j' || key === 'arrowdown') { event.preventDefault(); step(1); }
      else if (key === 'k' || key === 'arrowup') { event.preventDefault(); step(-1); }
      else if (key === 'e') {
        event.preventDefault();
        if (!editing) setEditing(true);
        else if (box) setEditing(false);
      }
      else if (key === 'c' && box) { event.preventDefault(); void resolve('confirm'); }
      else if (key === 'n') { event.preventDefault(); setConfirmDismiss(true); }
      else if (key === 'escape') {
        if (confirmDismiss) setConfirmDismiss(false);
        else if (editing && box) setEditing(false);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  });

  if (loading) {
    return (
      <PageScaffold className="review-page rw-page" breadcrumbs={breadcrumbItems} title="Manual Review">
        <div className="ui-loading-state">Loading Manual Review…</div>
      </PageScaffold>
    );
  }

  const sessionTotal = resolvedThisSession + totalItems;
  const sessionPercent = sessionTotal === 0 ? 100 : Math.round((resolvedThisSession / sessionTotal) * 100);
  const band = selected ? confidenceBand(selected.confidence) : null;

  return (
    <PageScaffold className="review-page rw-page" breadcrumbs={breadcrumbItems} eyebrow="Human review" title="Manual Review"
      description={packageId
        ? `Resolve only the findings that need a human decision for this intake${scopedIdentifier ? ` · ${scopedIdentifier.label} ${scopedIdentifier.value}` : ''}.`
        : 'Resolve only findings that currently need a human decision; high-confidence findings continue automatically.'}
      actions={
        <div className="rw-header-actions">
          {packageId && <Link className="ui-button secondary" to="/review">View all reviews</Link>}
          <button className="ui-button secondary icon rw-glyph" onClick={() => loadQueue(selectedId)} aria-label="Refresh review queue">
            <Icon name="refresh" />
          </button>
        </div>
      }>

      {error && <div className="ui-alert tone-danger">{error}</div>}
      {success && <div className="ui-alert tone-success" role="status">{success}</div>}

      {items.length === 0 ? (
        <div className="ui-card rw-empty">
          <div className="rw-empty-mark"><Icon name="check" /></div>
          <h2>{"You're all caught up"}</h2>
          <p>{packageId
            ? 'This intake has no findings currently waiting for Manual Review.'
            : 'There are no findings currently waiting for Manual Review.'}</p>
          {resolvedThisSession > 0 && (
            <p className="rw-empty-session">{resolvedThisSession} {resolvedThisSession === 1 ? 'decision' : 'decisions'} recorded this session.</p>
          )}
          {packageId && <Link className="ui-button secondary" to={`/packages/${packageId}`}>Back to Intake Details</Link>}
        </div>
      ) : (
        <>
          <section className="rw-session" aria-label="Review session">
            <div className="rw-session-count">
              <strong>{totalItems}</strong>
              <span>need a decision</span>
            </div>
            <div className="rw-session-meter">
              <div className="rw-session-meter-label">
                <span>This session</span>
                <span className="rw-mono">{resolvedThisSession} resolved · {sessionPercent}%</span>
              </div>
              <div className="rw-session-track" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={sessionPercent} aria-label="Session progress">
                <div style={{ width: `${sessionPercent}%` }} />
              </div>
            </div>
            <ul className="rw-session-reasons" aria-label="Queue composition">
              <li data-tone="warning"><span className="rw-dot" />{reasonCounts.LOW_CONFIDENCE || 0} low confidence</li>
              <li data-tone="info"><span className="rw-dot" />{reasonCounts.LOCATION_UNCERTAIN || 0} location</li>
              <li data-tone="violet"><span className="rw-dot" />{reasonCounts.POLICY_REVIEW || 0} policy</li>
            </ul>
            <div className="rw-keys" aria-label="Keyboard shortcuts">
              <span><kbd>J</kbd><kbd>K</kbd> move</span>
              <span><kbd>E</kbd> adjust</span>
              <span><kbd>C</kbd> confirm</span>
              <span><kbd>N</kbd> not sensitive</span>
            </div>
          </section>

          <div className="rw-desk">
            <aside className="rw-queue" aria-label="Review queue">
              <div className="rw-queue-head">
                <span>Queue</span>
                <span className="rw-mono">{selectedIndex + 1} / {items.length}</span>
              </div>
              {totalItems > items.length && (
                <div className="rw-queue-limit">Showing first {items.length} of {totalItems}</div>
              )}
              <div className="rw-queue-list">
                {reviewGroups.map((group) => (
                  <div className="rw-group" key={group.key}>
                    <div className="rw-group-head">
                      <span className="rw-group-file">{group.fileName}</span>
                      <span className="rw-group-page rw-mono">p.{group.pageNumber}</span>
                    </div>
                    {group.items.map((item) => {
                      const active = item.findingId === selectedId;
                      return (
                        <button
                          key={item.findingId}
                          type="button"
                          className={`rw-item ${active ? 'is-active' : ''}`}
                          data-tone={reasonTone(item.reason)}
                          aria-current={active ? 'true' : undefined}
                          onClick={() => setSelectedId(item.findingId)}
                        >
                          <span className="rw-item-main">
                            <span className="rw-item-type">{entityLabel(item.entityType)}</span>
                            <span className="rw-item-reason">{reasonLabel(item.reason)}</span>
                          </span>
                          <span className="rw-item-confidence rw-mono">{Math.round(item.confidence * 100)}%</span>
                        </button>
                      );
                    })}
                  </div>
                ))}
              </div>
            </aside>

            <section className="rw-stage" aria-label="Page preview">
              {selected && (
                <>
                  <div className="rw-stage-head">
                    <div className="rw-stage-title">
                      <span className="rw-file-badge">PDF</span>
                      <div>
                        <strong>{selected.fileName}</strong>
                        <span>Page {selected.pageNumber}</span>
                      </div>
                    </div>
                    <div className="rw-stage-nav">
                      <button type="button" className="ui-button secondary icon rw-glyph" onClick={() => step(-1)} disabled={selectedIndex <= 0} aria-label="Previous finding">
                        <Icon name="chevronUp" />
                      </button>
                      <button type="button" className="ui-button secondary icon rw-glyph" onClick={() => step(1)} disabled={selectedIndex >= items.length - 1} aria-label="Next finding">
                        <Icon name="chevronDown" />
                      </button>
                    </div>
                  </div>

                  <div className={`rw-frame ${editing ? 'is-editing' : ''}`}>
                    {previewLoading && <div className="rw-frame-state">Rendering page…</div>}
                    {!previewLoading && previewError && (
                      <div className="rw-frame-state is-error">
                        <strong>Unable to render page {selected.pageNumber}.</strong>
                        <span>{previewError}</span>
                        <button className="ui-button secondary" onClick={() => loadQueue(selected.findingId)}>Refresh review item</button>
                      </div>
                    )}
                    {previewUrl && (
                      <div
                        className="rw-canvas"
                        ref={previewRef}
                        onPointerDown={handleCanvasPointerDown}
                        onPointerMove={handlePointerMove}
                        onPointerUp={stopInteraction}
                        onPointerCancel={stopInteraction}
                      >
                        <img src={previewUrl} alt={`Page ${selected.pageNumber} of ${selected.fileName}`} draggable={false} />
                        {box && (
                          <div
                            className={`rw-box ${editing ? 'is-editable' : ''}`}
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
                            <span className="rw-box-tag">{entityLabel(selected.entityType)}</span>
                            {editing && (
                              <button
                                type="button"
                                className="rw-box-handle"
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

                  <div className={`rw-toolbar ${editing ? 'is-editing' : ''}`}>
                    <span className="rw-toolbar-hint">
                      <span className="rw-dot" />
                      {box
                        ? editing ? 'Drag the box to move it. Use the corner handle to resize.' : 'Highlighted area proposed for redaction.'
                        : 'Draw a box around the sensitive value.'}
                    </span>
                    {box && !editing && (
                      <button className="ui-button secondary small" onClick={() => setEditing(true)}>Adjust area</button>
                    )}
                    {editing && (
                      <div className="rw-toolbar-actions">
                        {originalBox && boxChanged(box, originalBox) && (
                          <button className="ui-button tertiary small" onClick={() => setBox(originalBox)}>Reset</button>
                        )}
                        {!originalBox && box && (
                          <button className="ui-button tertiary small" onClick={() => setBox(null)}>Clear</button>
                        )}
                        <button className="ui-button secondary small" onClick={() => box && setEditing(false)} disabled={!box}>Done</button>
                      </div>
                    )}
                  </div>
                </>
              )}
            </section>

            <aside className="rw-decision" aria-label="Decision">
              {selected && band && (
                <>
                  <div className="rw-decision-head">
                    <span className="rw-reason" data-tone={reasonTone(selected.reason)}>{reasonLabel(selected.reason)}</span>
                    <h2>{entityLabel(selected.entityType)}</h2>
                  </div>

                  <div className="rw-confidence" data-tone={band.tone}>
                    <div className="rw-confidence-row">
                      <span>AI confidence</span>
                      <strong className="rw-mono">{Math.round(selected.confidence * 100)}%</strong>
                    </div>
                    <div className="rw-confidence-track">
                      <div style={{ width: `${clamp(selected.confidence, 0, 1) * 100}%` }} />
                    </div>
                    <span className="rw-confidence-band">{band.label}</span>
                  </div>

                  <p className="rw-explain">{reasonExplanation(selected.reason)}</p>

                  <dl className="rw-facts">
                    <div><dt>Document</dt><dd title={selected.fileName}>{selected.fileName}</dd></div>
                    <div><dt>Page</dt><dd className="rw-mono">{selected.pageNumber}</dd></div>
                    <div>
                      <dt>Location</dt>
                      <dd className={box ? 'is-ok' : 'is-missing'}>{box ? 'Selected' : 'Needs selection'}</dd>
                    </div>
                  </dl>

                  <div className="rw-actions">
                    <button
                      className="ui-button primary rw-confirm"
                      disabled={resolving || !box}
                      onClick={() => resolve('confirm')}
                    >
                      <Icon name="shield" />
                      {resolving ? 'Saving…' : 'Confirm & Submit'}
                      <kbd>C</kbd>
                    </button>
                    {!confirmDismiss ? (
                      <button
                        className="ui-button secondary rw-dismiss"
                        disabled={resolving}
                        onClick={() => setConfirmDismiss(true)}
                      >
                        Not Sensitive
                        <kbd>N</kbd>
                      </button>
                    ) : (
                      <div className="rw-dismiss-confirm" role="alertdialog" aria-label="Confirm not sensitive">
                        <span>Mark this item as not sensitive? It will not be redacted.</span>
                        <div>
                          <button className="ui-button secondary" disabled={resolving} onClick={() => setConfirmDismiss(false)}>Cancel</button>
                          <button className="ui-button secondary rw-dismiss-final" disabled={resolving} onClick={() => resolve('dismiss')}>Yes, not sensitive</button>
                        </div>
                      </div>
                    )}
                  </div>

                  <p className="rw-note">
                    When the last review item is resolved, processing resumes automatically in the background.
                  </p>
                </>
              )}
            </aside>
          </div>
        </>
      )}

      {items.length > 0 && selected && (
        <div className="rw-mobile-bar" aria-label="Review decision actions">
          <div className="rw-mobile-copy">
            <strong>{entityLabel(selected.entityType)}</strong>
            <span>{reasonLabel(selected.reason)} · {Math.round(selected.confidence * 100)}%</span>
          </div>
          {!confirmDismiss ? (
            <div className="rw-mobile-buttons">
              <button className="ui-button secondary" disabled={resolving} onClick={() => setConfirmDismiss(true)}>Not Sensitive</button>
              <button className="ui-button primary" disabled={resolving || !box} onClick={() => void resolve('confirm')}>{resolving ? 'Saving…' : 'Confirm'}</button>
            </div>
          ) : (
            <div className="rw-mobile-buttons">
              <span>Mark as not sensitive?</span>
              <button className="ui-button secondary" disabled={resolving} onClick={() => setConfirmDismiss(false)}>Cancel</button>
              <button className="ui-button secondary rw-dismiss-final" disabled={resolving} onClick={() => void resolve('dismiss')}>Yes</button>
            </div>
          )}
        </div>
      )}
    </PageScaffold>
  );
}
