/**
 * New case rules (UI task U005): what the server would refuse, said before upload.
 *
 * Pure functions, so the node test runner can check them without a browser.
 */

/**
 * The per-file limit the server enforces on manual upload: `spring.servlet.multipart.max-file-size: 50MB` in
 * `backend/securedoc-api/src/main/resources/application.yml` (Spring reads MB as 1024 × 1024 bytes). A larger file
 * is refused with FILE_TOO_LARGE by `GlobalExceptionHandler`. The screen has no endpoint that reports it, so the
 * value is repeated here; change both together.
 *
 * Manual upload has no page limit and no files-per-case limit on the server today (only machine intake has
 * per-source limits), so the screen states none.
 */
export const MAX_UPLOAD_MB = 50;
export const MAX_UPLOAD_BYTES = MAX_UPLOAD_MB * 1024 * 1024;

/** The rule line under the drop area. */
export const intakeRulesLine = () => `PDF only · up to ${MAX_UPLOAD_MB} MB each`;

export type FileProblem = 'NOT_PDF' | 'TOO_LARGE';

/** What to do, in the words the person sees. */
export const fileProblemText: Record<FileProblem, string> = {
  NOT_PDF: 'Not a PDF — remove it or convert it first',
  TOO_LARGE: `Over ${MAX_UPLOAD_MB} MB — remove it or split it first`,
};

/** The name and type the browser gives: the server accepts only application/pdf (UploadDocumentService). */
export function looksLikePdf(name: string, type: string): boolean {
  return name.toLowerCase().endsWith('.pdf') && (!type || type === 'application/pdf');
}

/** A PDF starts with "%PDF-" within its first 1024 bytes (the same leniency PDF readers allow). */
export function hasPdfHeader(head: Uint8Array): boolean {
  const limit = Math.min(head.length, 1024) - 5;
  for (let i = 0; i <= limit; i += 1) {
    if (head[i] === 0x25 && head[i + 1] === 0x50 && head[i + 2] === 0x44 && head[i + 3] === 0x46 && head[i + 4] === 0x2d) return true;
  }
  return false;
}

/** The first problem the server would refuse the file for, or null. `head` is the file's first bytes. */
export function fileProblem(file: { name: string; type: string; size: number }, head: Uint8Array): FileProblem | null {
  if (!looksLikePdf(file.name, file.type) || !hasPdfHeader(head)) return 'NOT_PDF';
  if (file.size > MAX_UPLOAD_BYTES) return 'TOO_LARGE';
  return null;
}

/**
 * The page count read from the PDF's page tree, or null when it cannot be read with certainty.
 *
 * Reads the root page-tree object (`/Type /Pages` without `/Parent`) and its `/Count`. Fails closed (null) when no
 * root is readable, which is the case when the tree sits in a compressed object stream, or when the file holds
 * root objects with different counts (an edited file). Null shows as "—"; the server counts pages itself.
 */
export function countPdfPages(bytes: Uint8Array): number | null {
  let text = '';
  const step = 0x8000;
  for (let i = 0; i < bytes.length; i += step) text += String.fromCharCode(...bytes.subarray(i, i + step));

  const counts = new Set<number>();
  for (const chunk of text.split(/\bendobj\b/)) {
    let start = -1;
    for (const header of chunk.matchAll(/\d+\s+\d+\s+obj\b/g)) start = header.index ?? start;
    if (start < 0) continue;
    const object = chunk.slice(start);
    if (!/\/Type\s*\/Pages(?![A-Za-z0-9])/.test(object) || /\/Parent\b/.test(object)) continue;
    const count = object.match(/\/Count\s+(\d+)/);
    if (!count) return null;
    counts.add(Number(count[1]));
  }
  if (counts.size !== 1) return null;
  const [count] = counts;
  return count > 0 ? count : null;
}

/** New case pre-fill: `?id=<primary identifier>`. `?for=` is the older spelling and still read. */
export function prefillIdentifier(params: URLSearchParams): string | null {
  const value = (params.get('id') ?? params.get('for'))?.trim();
  return value ? value : null;
}

/** The Scan tab's name for the scanner: the bridge offers no friendly name, so it is named by its site. */
export const scannerDisplayName = (siteId: string) => `Scanner at ${siteId}`;
