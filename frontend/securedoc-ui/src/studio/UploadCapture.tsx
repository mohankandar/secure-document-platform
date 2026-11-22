import { useRef, useState } from 'react';
import { useSWRConfig } from 'swr';
import { ArrowDown, ArrowUp, Check, FileUp, RotateCcw } from 'lucide-react';
import { apiClient } from '../api/client';
import { errorMessage, formatBytes } from './data';
import { Notice, Spinner } from './atoms';
import type { IdentityGate } from './Intake';
import { useUnsavedWork } from './unsavedWork';
import { countPdfPages, fileProblem, fileProblemText, intakeRulesLine, MAX_UPLOAD_BYTES, tooManyFilesText, tooManyPages, type FileProblem } from './intakeRules';

type DraftState = 'ready' | 'uploading' | 'done' | 'error';
/**
 * `problem`: undefined while the file is being read, null when the server would take it.
 * `pages`: undefined while reading, null when the count cannot be read with certainty (shown as "—").
 */
interface Draft { id: number; file: File; state: DraftState; error?: string; problem?: FileProblem | null; pages?: number | null }

/** Reads the file in the browser only: its first bytes for the PDF header, then the page tree for the count. */
async function inspect(file: File): Promise<{ problem: FileProblem | null; pages: number | null }> {
  try {
    const head = new Uint8Array(await file.slice(0, 1024).arrayBuffer());
    const problem = fileProblem(file, head);
    if (problem || file.size > MAX_UPLOAD_BYTES) return { problem, pages: null };
    const pages = countPdfPages(new Uint8Array(await file.arrayBuffer()));
    return { problem: tooManyPages(pages) ? 'TOO_MANY_PAGES' : null, pages };
  } catch {
    return { problem: 'NOT_PDF', pages: null };
  }
}

const pagesText = (pages: number | null) => (pages === null ? '— pages' : `${pages} ${pages === 1 ? 'page' : 'pages'}`);
const fileKey = (file: File) => `${file.name.toLowerCase()}::${file.size}::${file.lastModified}`;

export default function UploadCapture({ gate, onLock, onCreated }: {
  gate: IdentityGate;
  onLock: (identity: Record<string, string> | null, reason: string, sensitivity?: string | null) => void;
  onCreated: (packageId: string) => void;
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  const nextId = useRef(0);
  const inFlight = useRef(false);
  const { mutate } = useSWRConfig();
  const [drafts, setDrafts] = useState<Draft[]>([]);
  const [draftPackageId, setDraftPackageId] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [phase, setPhase] = useState<'idle' | 'working'>('idle');
  useUnsavedWork(drafts.some((d) => d.state !== 'done'));

  const setState = (id: number, state: DraftState, error?: string) =>
    setDrafts((current) => current.map((d) => (d.id === id ? { ...d, state, error } : d)));

  const add = (list: FileList | File[]) => {
    const files = Array.from(list);
    const seen = new Set(drafts.map((d) => fileKey(d.file)));
    const fresh: Draft[] = [];
    for (const file of files) {
      if (seen.has(fileKey(file))) continue;
      seen.add(fileKey(file));
      fresh.push({ id: ++nextId.current, file, state: 'ready' });
    }
    const dupes = files.length - fresh.length;
    setNotice(dupes ? `${dupes} duplicate${dupes === 1 ? '' : 's'} skipped.` : null);
    setDrafts((current) => [...current, ...fresh]);
    for (const draft of fresh) {
      void inspect(draft.file).then((result) => setDrafts((current) => current.map((d) => (d.id === draft.id ? { ...d, ...result } : d))));
    }
  };

  const move = (index: number, delta: number) => setDrafts((current) => {
    const next = [...current];
    const target = index + delta;
    if (target < 0 || target >= next.length) return current;
    if (next[index].state === 'done' || next[target].state === 'done') return current;
    [next[index], next[target]] = [next[target], next[index]];
    return next;
  });

  const submit = async () => {
    if (inFlight.current) return;
    setFailure(null);
    const identity = gate.require();
    if (!identity) { setFailure('Complete the case identity first.'); return; }
    if (drafts.length === 0) { setFailure('Add at least one PDF.'); return; }
    if (drafts.some((d) => d.problem !== null)) return; // a refused or unread file: fail closed
    const overLimit = tooManyFilesText(drafts.length);
    if (overLimit) { setFailure(overLimit); return; } // task 0038: the server would refuse the rest (LV-INT-029)

    inFlight.current = true;
    setPhase('working');
    onLock(identity, 'this upload');
    let packageId = draftPackageId;
    try {
      if (!packageId) {
        const pkg = await apiClient.createPackage({ packageType: 'MANUAL_UPLOAD', businessIdentifiers: identity, sensitivityLevel: gate.sensitivity });
        packageId = pkg.packageId;
        setDraftPackageId(packageId);
      }

      let failed = 0;
      for (const draft of drafts) {
        if (draft.state === 'done') continue;
        setState(draft.id, 'uploading');
        try {
          await apiClient.uploadDocument(packageId, draft.file);
          setState(draft.id, 'done');
        } catch (err) {
          failed += 1;
          setState(draft.id, 'error', errorMessage(err, 'Upload failed.'));
        }
      }

      if (failed > 0) {
        setFailure(`${failed} ${failed === 1 ? 'document' : 'documents'} did not upload. Retry, or remove ${failed === 1 ? 'it' : 'them'} to continue. Uploaded documents are kept.`);
        setPhase('idle');
        return;
      }

      await apiClient.submitPackage(packageId);
      await mutate('packages');
      onCreated(packageId);
    } catch (err) {
      setFailure(errorMessage(err, packageId ? 'The case could not be submitted. Your uploads are kept — try again.' : 'The case could not be opened.'));
      if (!packageId) onLock(null, '');
      setPhase('idle');
    } finally {
      inFlight.current = false;
    }
  };

  const totalBytes = drafts.reduce((sum, d) => sum + d.file.size, 0);
  const working = phase === 'working';
  const errors = drafts.filter((d) => d.state === 'error').length;
  const uploaded = drafts.filter((d) => d.state === 'done').length;
  const refused = drafts.filter((d) => d.problem).length;
  const reading = drafts.some((d) => d.problem === undefined);
  const tooManyFiles = tooManyFilesText(drafts.length);
  const label = working ? null : errors > 0 ? 'Retry failed uploads' : draftPackageId ? 'Submit case' : 'Create case';

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
        {/* Canvas NewCase: one centred line "Drop PDFs here or choose files", the rules under it. The list numbers the
            files ("1 · name"), so the order needs no note of its own. */}
        <p className="cf-drop-line">
          <span className="cf-drop-title">Drop PDFs here</span> or{' '}
          <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" onClick={() => inputRef.current?.click()} disabled={working}>
            choose files
          </button>
        </p>
        <p className="cf-drop-rules">{intakeRulesLine()}</p>
        <input
          ref={inputRef}
          type="file"
          accept="application/pdf,.pdf"
          multiple
          className="cf-sr"
          tabIndex={-1}
          aria-label="Choose PDF files"
          onChange={(e) => { if (e.target.files) add(e.target.files); e.target.value = ''; }}
        />
      </div>

      {notice && <Notice>{notice}</Notice>}

      {drafts.length > 0 && (
        <div className="cf-ledger">
          <div className="cf-ledger-head">
            <span>
              {drafts.length} {drafts.length === 1 ? 'document' : 'documents'}
              {draftPackageId && uploaded > 0 && <> · {uploaded} uploaded</>}
            </span>
            <span className="cf-mono">{formatBytes(totalBytes)}</span>
          </div>
          <ol aria-live="polite">
            {drafts.map((draft, index) => (
              <li key={draft.id} className="cf-ledger-row" data-state={draft.problem ? 'refused' : draft.state}>
                {/* Canvas NewCase file table: "1 · name" with its problem under it, then size and pages on the right. */}
                <span className="cf-ledger-name">
                  <span><span className="cf-ledger-order" aria-hidden="true">{index + 1} · </span>{draft.file.name}</span>
                  {draft.problem
                    ? <span className="cf-ledger-problem">{fileProblemText[draft.problem]}</span>
                    : draft.state === 'error' && <span className="cf-ledger-problem">{draft.error}</span>}
                </span>
                <span className="cf-ledger-size">{formatBytes(draft.file.size)}</span>
                <span className="cf-ledger-pages">{draft.problem ? '—' : draft.problem === undefined ? 'Reading…' : pagesText(draft.pages ?? null)}</span>
                {draft.state === 'uploading' && <Spinner label={`Uploading ${draft.file.name}`} />}
                {draft.state === 'done' && <Check size={16} className="cf-ok" aria-label="Uploaded" />}
                {draft.state === 'error' && <RotateCcw size={14} className="cf-danger-text" aria-label="Will retry" />}
                {!working && draft.state !== 'done' && (
                  <span className="cf-ledger-tools">
                    <button type="button" className="cf-btn" data-variant="ghost" data-size="icon" onClick={() => move(index, -1)} disabled={index === 0 || drafts[index - 1]?.state === 'done'} aria-label={`Move ${draft.file.name} up`}><ArrowUp size={14} /></button>
                    <button type="button" className="cf-btn" data-variant="ghost" data-size="icon" onClick={() => move(index, 1)} disabled={index === drafts.length - 1} aria-label={`Move ${draft.file.name} down`}><ArrowDown size={14} /></button>
                    <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" onClick={() => setDrafts((c) => c.filter((d) => d.id !== draft.id))} aria-label={`Remove ${draft.file.name}`}>Remove</button>
                  </span>
                )}
              </li>
            ))}
          </ol>
        </div>
      )}

      {tooManyFiles && failure !== tooManyFiles && <Notice tone="danger">{tooManyFiles}</Notice>}
      {failure && <Notice tone="danger">{failure}</Notice>}

      <div className="cf-capture-foot">
        <p className="cf-muted">
          {refused > 0
            ? `Fix the ${refused === 1 ? 'file' : 'files'} marked in red to continue.`
            : tooManyFiles
            ? 'Remove files to continue.'
            : draftPackageId
            ? 'The case is open as a draft. Identity is locked to it.'
            : gate.valid ? (drafts.length ? 'Ready to open the case.' : 'Add the documents to continue.') : 'Complete the identity to continue.'}
        </p>
        <button type="button" className="cf-btn" data-variant="solid" onClick={submit} disabled={working || drafts.length === 0 || refused > 0 || reading || !!tooManyFiles}>
          {working ? <><Spinner label="Submitting" /> Submitting…</> : label}
        </button>
      </div>
    </div>
  );
}
