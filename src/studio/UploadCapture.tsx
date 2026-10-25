import { useRef, useState } from 'react';
import { useSWRConfig } from 'swr';
import { ArrowDown, ArrowUp, Check, FileText, FileUp, X } from 'lucide-react';
import { apiClient } from '../api/client';
import { errorMessage, formatBytes } from './data';
import { Notice, Spinner } from './atoms';
import type { IdentityGate } from './Intake';

interface Draft { id: number; file: File; state: 'ready' | 'uploading' | 'done' | 'error' }

const isPdf = (file: File) => file.name.toLowerCase().endsWith('.pdf') && (!file.type || file.type === 'application/pdf');
const fileKey = (file: File) => `${file.name.toLowerCase()}::${file.size}::${file.lastModified}`;

export default function UploadCapture({ gate, onLock, onCreated }: {
  gate: IdentityGate;
  onLock: (identity: Record<string, string> | null, reason: string) => void;
  onCreated: (packageId: string) => void;
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  const nextId = useRef(0);
  const { mutate } = useSWRConfig();
  const [drafts, setDrafts] = useState<Draft[]>([]);
  const [dragging, setDragging] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [phase, setPhase] = useState<'idle' | 'working'>('idle');

  const add = (list: FileList | File[]) => {
    const files = Array.from(list);
    const pdfs = files.filter(isPdf);
    const rejected = files.length - pdfs.length;
    setDrafts((current) => {
      const seen = new Set(current.map((d) => fileKey(d.file)));
      const fresh = pdfs.filter((f) => !seen.has(fileKey(f))).map((file) => ({ id: ++nextId.current, file, state: 'ready' as const }));
      const dupes = pdfs.length - fresh.length;
      const parts = [rejected ? `${rejected} non-PDF ${rejected === 1 ? 'file' : 'files'} skipped` : '', dupes ? `${dupes} duplicate${dupes === 1 ? '' : 's'} skipped` : ''].filter(Boolean);
      setNotice(parts.length ? `${parts.join(' · ')}.` : null);
      return [...current, ...fresh];
    });
  };

  const move = (index: number, delta: number) => setDrafts((current) => {
    const next = [...current];
    const target = index + delta;
    if (target < 0 || target >= next.length) return current;
    [next[index], next[target]] = [next[target], next[index]];
    return next;
  });

  const submit = async () => {
    setFailure(null);
    const identity = gate.require();
    if (!identity) { setFailure('Complete the case identity first.'); return; }
    if (drafts.length === 0) { setFailure('Add at least one PDF.'); return; }

    setPhase('working');
    onLock(identity, 'this upload');
    try {
      const pkg = await apiClient.createPackage({ packageType: 'MANUAL_UPLOAD', businessIdentifiers: identity });
      for (const draft of drafts) {
        setDrafts((current) => current.map((d) => (d.id === draft.id ? { ...d, state: 'uploading' } : d)));
        try {
          await apiClient.uploadDocument(pkg.packageId, draft.file);
          setDrafts((current) => current.map((d) => (d.id === draft.id ? { ...d, state: 'done' } : d)));
        } catch (err) {
          setDrafts((current) => current.map((d) => (d.id === draft.id ? { ...d, state: 'error' } : d)));
          throw err;
        }
      }
      await apiClient.submitPackage(pkg.packageId);
      await mutate('packages');
      onCreated(pkg.packageId);
    } catch (err) {
      setFailure(errorMessage(err, 'The case could not be created.'));
      onLock(null, '');
      setPhase('idle');
    }
  };

  const totalBytes = drafts.reduce((sum, d) => sum + d.file.size, 0);
  const working = phase === 'working';

  return (
    <div className="cf-capture">
      <div
        className="cf-drop"
        data-dragging={dragging || undefined}
        data-compact={drafts.length > 0 || undefined}
        onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
        onDragLeave={() => setDragging(false)}
        onDrop={(e) => { e.preventDefault(); setDragging(false); if (!working) add(e.dataTransfer.files); }}
      >
        <FileUp size={22} aria-hidden="true" />
        <p className="cf-drop-title">Drop PDFs here</p>
        <p className="cf-drop-text">They&apos;re processed in the order listed below.</p>
        <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" onClick={() => inputRef.current?.click()} disabled={working}>
          Choose files
        </button>
        <input
          ref={inputRef}
          type="file"
          accept="application/pdf,.pdf"
          multiple
          className="cf-sr"
          tabIndex={-1}
          onChange={(e) => { if (e.target.files) add(e.target.files); e.target.value = ''; }}
        />
      </div>

      {notice && <Notice>{notice}</Notice>}

      {drafts.length > 0 && (
        <div className="cf-ledger">
          <div className="cf-ledger-head">
            <span>{drafts.length} {drafts.length === 1 ? 'document' : 'documents'}</span>
            <span className="cf-mono">{formatBytes(totalBytes)}</span>
          </div>
          <ol>
            {drafts.map((draft, index) => (
              <li key={draft.id} className="cf-ledger-row" data-state={draft.state}>
                <span className="cf-ledger-order cf-mono" aria-hidden="true">{String(index + 1).padStart(2, '0')}</span>
                <FileText size={16} aria-hidden="true" className="cf-doc-icon" />
                <span className="cf-ledger-name">
                  <span>{draft.file.name}</span>
                  <span className="cf-doc-meta">{formatBytes(draft.file.size)}</span>
                </span>
                {draft.state === 'uploading' && <Spinner label={`Uploading ${draft.file.name}`} />}
                {draft.state === 'done' && <Check size={16} className="cf-ok" aria-label="Uploaded" />}
                {draft.state === 'error' && <span className="cf-danger-text">Failed</span>}
                {!working && (
                  <span className="cf-ledger-tools">
                    <button type="button" className="cf-btn" data-variant="ghost" data-size="icon" onClick={() => move(index, -1)} disabled={index === 0} aria-label={`Move ${draft.file.name} up`}><ArrowUp size={14} /></button>
                    <button type="button" className="cf-btn" data-variant="ghost" data-size="icon" onClick={() => move(index, 1)} disabled={index === drafts.length - 1} aria-label={`Move ${draft.file.name} down`}><ArrowDown size={14} /></button>
                    <button type="button" className="cf-btn" data-variant="ghost" data-size="icon" onClick={() => setDrafts((c) => c.filter((d) => d.id !== draft.id))} aria-label={`Remove ${draft.file.name}`}><X size={14} /></button>
                  </span>
                )}
              </li>
            ))}
          </ol>
        </div>
      )}

      {failure && <Notice tone="danger">{failure}</Notice>}

      <div className="cf-capture-foot">
        <p className="cf-muted">{gate.valid ? (drafts.length ? 'Ready to open the case.' : 'Add the documents to continue.') : 'Complete the identity to continue.'}</p>
        <button type="button" className="cf-btn" data-variant="solid" onClick={submit} disabled={working}>
          {working ? <><Spinner label="Submitting" /> Submitting…</> : 'Open case & submit'}
        </button>
      </div>
    </div>
  );
}
