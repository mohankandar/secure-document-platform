import assert from 'node:assert/strict';
import test from 'node:test';
import {
  MAX_UPLOAD_BYTES, countPdfPages, fileProblem, fileProblemText, hasPdfHeader, intakeRulesLine, prefillIdentifier, scannerDisplayName,
} from './intakeRules.ts';

const bytes = (text: string) => new Uint8Array(Buffer.from(text, 'latin1'));
const PDF_HEAD = bytes('%PDF-1.7\n');

/** A small uncompressed PDF with `pages` pages in one flat tree. */
function pdf(pages: number): Uint8Array {
  const kids = Array.from({ length: pages }, (_, i) => `${i + 3} 0 R`).join(' ');
  const pageObjects = Array.from({ length: pages }, (_, i) => `${i + 3} 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n`).join('');
  return bytes(`%PDF-1.7\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n2 0 obj\n<< /Type /Pages /Kids [${kids}] /Count ${pages} >>\nendobj\n${pageObjects}trailer\n<< /Root 1 0 R >>\n%%EOF\n`);
}

test('the rule line states only the limit the server enforces on manual upload', () => {
  assert.equal(intakeRulesLine(), 'PDF only · up to 50 MB each');
  assert.equal(MAX_UPLOAD_BYTES, 50 * 1024 * 1024);
});

test('a file the server would refuse is named with what to do', () => {
  assert.equal(fileProblem({ name: 'Deed.pdf', type: 'application/pdf', size: 1000 }, PDF_HEAD), null);
  assert.equal(fileProblem({ name: 'scanned_batch_14.tif', type: 'image/tiff', size: 1000 }, bytes('II*\0')), 'NOT_PDF');
  assert.equal(fileProblem({ name: 'renamed.pdf', type: 'application/pdf', size: 1000 }, bytes('PK\u0003\u0004')), 'NOT_PDF');
  assert.equal(fileProblem({ name: 'empty.pdf', type: 'application/pdf', size: 0 }, new Uint8Array()), 'NOT_PDF');
  assert.equal(fileProblem({ name: 'Big.pdf', type: 'application/pdf', size: MAX_UPLOAD_BYTES + 1 }, PDF_HEAD), 'TOO_LARGE');
  assert.equal(fileProblem({ name: 'Edge.pdf', type: 'application/pdf', size: MAX_UPLOAD_BYTES }, PDF_HEAD), null);
  assert.equal(fileProblemText.NOT_PDF, 'Not a PDF — remove it or convert it first');
  assert.equal(fileProblemText.TOO_LARGE, 'Over 50 MB — remove it or split it first');
});

test('the PDF header may sit within the first 1024 bytes, not later', () => {
  assert.ok(hasPdfHeader(bytes(' \r\n%PDF-1.4')));
  assert.ok(!hasPdfHeader(bytes(`${' '.repeat(1100)}%PDF-1.4`)));
});

test('page count comes from the root page tree', () => {
  assert.equal(countPdfPages(pdf(1)), 1);
  assert.equal(countPdfPages(pdf(12)), 12);
  // A nested tree: the root holds the total, the child (with /Parent) is ignored.
  const nested = bytes('%PDF-1.7\n2 0 obj\n<</Type/Pages/Kids[5 0 R]/Count 8>>\nendobj\n5 0 obj\n<</Type/Pages/Parent 2 0 R/Kids[6 0 R]/Count 3>>\nendobj\n');
  assert.equal(countPdfPages(nested), 8);
});

test('page count fails closed when it cannot be read with certainty', () => {
  // Page tree inside a compressed object stream: nothing readable.
  assert.equal(countPdfPages(bytes('%PDF-1.7\n7 0 obj\n<< /Type /ObjStm /N 3 /Filter /FlateDecode >>\nstream\nxx\nendstream\nendobj\n')), null);
  // An edited file with two roots that disagree.
  const edited = new Uint8Array([...pdf(3), ...bytes('2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n')]);
  assert.equal(countPdfPages(edited), null);
  // A root without a count, and a zero count.
  assert.equal(countPdfPages(bytes('2 0 obj\n<< /Type /Pages /Kids [] >>\nendobj\n')), null);
  assert.equal(countPdfPages(bytes('2 0 obj\n<< /Type /Pages /Kids [] /Count 0 >>\nendobj\n')), null);
});

test('New case reads ?id= for the primary identifier, and the older ?for=', () => {
  assert.equal(prefillIdentifier(new URLSearchParams('id=LN204901')), 'LN204901');
  assert.equal(prefillIdentifier(new URLSearchParams('for=LN204901')), 'LN204901');
  assert.equal(prefillIdentifier(new URLSearchParams('id=%20%20')), null);
  assert.equal(prefillIdentifier(new URLSearchParams('via=scan')), null);
});

test('the scanner is named by its site', () => {
  assert.equal(scannerDisplayName('HOME-LAB'), 'Scanner at HOME-LAB');
});
