const PAGE_WIDTH = 850;
const PAGE_HEIGHT = 1100;

const SAMPLE_VALUES: Record<string, string> = {
  SSN: '512-44-8391',
  ACCOUNT_NUMBER: 'Acct 0049 2210 7781',
  DATE_OF_BIRTH: 'DOB 04/17/1984',
  DRIVERS_LICENSE: 'WA DL OKAFOR*R4021',
  PHONE_NUMBER: '(206) 555-0148',
  EMAIL_ADDRESS: 'borrower@mail.example',
  ADDRESS: '1420 Alder St, Seattle WA',
  PERSON_NAME: 'Rosa M. Alvarez',
};

function hash(text: string): number {
  let value = 0;
  for (let i = 0; i < text.length; i += 1) value = (value * 31 + text.charCodeAt(i)) | 0;
  return Math.abs(value);
}

export async function renderPagePng(options: {
  title: string;
  pageNumber: number;
  pageCount?: number;
  seed: string;
  highlights?: Array<{ left: number; top: number; width: number; height: number; entityType: string }>;
}): Promise<Blob> {
  const canvas = document.createElement('canvas');
  canvas.width = PAGE_WIDTH;
  canvas.height = PAGE_HEIGHT;
  const ctx = canvas.getContext('2d');
  if (!ctx) return new Blob([], { type: 'image/png' });

  ctx.fillStyle = '#ffffff';
  ctx.fillRect(0, 0, PAGE_WIDTH, PAGE_HEIGHT);
  ctx.fillStyle = '#1f2937';
  ctx.font = '600 26px Georgia, serif';
  ctx.fillText(options.title.replace(/\.pdf$/i, '').replace(/_/g, ' '), 72, 96);
  ctx.font = '13px Arial, sans-serif';
  ctx.fillStyle = '#6b7280';
  ctx.fillText(`Northwind Lending · Page ${options.pageNumber}${options.pageCount ? ` of ${options.pageCount}` : ''}`, 72, 124);
  ctx.fillStyle = '#d1d5db';
  ctx.fillRect(72, 140, PAGE_WIDTH - 144, 1);

  const seed = hash(`${options.seed}-${options.pageNumber}`);
  ctx.fillStyle = '#9ca3af';
  for (let line = 0; line < 30; line += 1) {
    const y = 180 + line * 28;
    if (line % 7 === 6) continue;
    const width = 260 + ((seed >> (line % 16)) % 420);
    ctx.fillRect(72, y, Math.min(width, PAGE_WIDTH - 144), 8);
  }

  ctx.font = '15px "Courier New", monospace';
  (options.highlights || []).forEach((box) => {
    const x = box.left * PAGE_WIDTH;
    const y = box.top * PAGE_HEIGHT;
    const w = box.width * PAGE_WIDTH;
    const h = Math.max(box.height * PAGE_HEIGHT, 22);
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(x - 4, y - 4, w + 8, h + 8);
    ctx.fillStyle = '#111827';
    ctx.fillText(SAMPLE_VALUES[box.entityType] || '•••• •••• ••••', x, y + h * 0.72);
  });

  ctx.fillStyle = '#e5e7eb';
  ctx.fillRect(72, PAGE_HEIGHT - 90, 180, 40);
  ctx.fillStyle = '#6b7280';
  ctx.font = '11px Arial, sans-serif';
  ctx.fillText('Synthetic demo document', 72, PAGE_HEIGHT - 32);

  return new Promise((resolve) => canvas.toBlob((blob) => resolve(blob || new Blob([], { type: 'image/png' })), 'image/png'));
}

export function buildRedactedPdf(fileName: string, pageCount: number): Blob {
  const pages = Math.max(1, Math.min(pageCount, 20));
  const objects: string[] = [];
  objects.push('<< /Type /Catalog /Pages 2 0 R >>');
  const kids = Array.from({ length: pages }, (_, i) => `${4 + i * 2} 0 R`).join(' ');
  objects.push(`<< /Type /Pages /Kids [${kids}] /Count ${pages} >>`);
  objects.push('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>');
  for (let i = 0; i < pages; i += 1) {
    const content = `BT /F1 20 Tf 72 720 Td (${fileName.replace(/[()\\]/g, '')} - redacted) Tj ET BT /F1 11 Tf 72 696 Td (LexaVault demo artifact - page ${i + 1} of ${pages}) Tj ET 0 0 0 rg 72 600 220 18 re f 72 560 160 18 re f`;
    objects.push(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents ${5 + i * 2} 0 R >>`);
    objects.push(`<< /Length ${content.length} >>\nstream\n${content}\nendstream`);
  }
  let body = '%PDF-1.4\n';
  const offsets: number[] = [];
  objects.forEach((object, index) => {
    offsets.push(body.length);
    body += `${index + 1} 0 obj\n${object}\nendobj\n`;
  });
  const xref = body.length;
  body += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n${offsets.map((o) => `${String(o).padStart(10, '0')} 00000 n \n`).join('')}`;
  body += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`;
  return new Blob([body], { type: 'application/pdf' });
}

export function toCsv(rows: Array<Record<string, string | number | boolean | null | undefined>>): string {
  if (!rows.length) return '';
  const headers = Object.keys(rows[0]);
  const escape = (value: unknown) => {
    const text = value == null ? '' : String(value);
    return /[",\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
  };
  return [headers.join(','), ...rows.map((row) => headers.map((h) => escape(row[h])).join(','))].join('\n');
}
