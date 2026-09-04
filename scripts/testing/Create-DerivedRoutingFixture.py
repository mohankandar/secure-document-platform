#!/usr/bin/env python3
"""Create a dependency-free 3-page PDF for LexaVault V1 final derived-routing regression.

Page 1/2: TOP_RIGHT text routing marker 77777777 (South Carolina)
Page 3:   BOTTOM_LEFT text routing marker 88888888 (Atlanta)
Each page also includes synthetic sensitive values so the normal Qwen/redaction pipeline runs.
All data is synthetic and intended only for local verification.
"""
from pathlib import Path
import sys

PAGE_W, PAGE_H = 612, 792

def esc(text: str) -> str:
    return text.replace('\\', '\\\\').replace('(', '\\(').replace(')', '\\)')

def text(x, y, size, value):
    return f"BT /F1 {size} Tf {x} {y} Td ({esc(value)}) Tj ET\n"

def stream_for(page_no: int) -> bytes:
    parts = [
        text(72, 680, 12, f"LexaVault Final Regression Fixture - Page {page_no}"),
        text(72, 640, 11, f"Synthetic SSN: 111-22-33{40+page_no}"),
        text(72, 615, 11, f"Date of Birth: 01/0{page_no}/1980"),
        text(72, 590, 11, f"Credit Score: {710+page_no}"),
        text(72, 565, 11, f"Credit Card: 4111 1111 1111 1111"),
    ]
    if page_no in (1, 2):
        parts.append(text(430, 750, 14, "|||||77777777|||||"))
    else:
        parts.append(text(36, 36, 14, "|||||88888888|||||"))
    return ''.join(parts).encode('latin-1')

def build_pdf(path: Path):
    objects = []
    def add(data: bytes) -> int:
        objects.append(data)
        return len(objects)

    catalog = add(b"<< /Type /Catalog /Pages 2 0 R >>")
    pages_obj = add(b"")
    font = add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    page_ids = []
    for n in range(1, 4):
        content = stream_for(n)
        content_id = add(b"<< /Length %d >>\nstream\n" % len(content) + content + b"endstream")
        page_id = add((f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {PAGE_W} {PAGE_H}] "
                       f"/Resources << /Font << /F1 {font} 0 R >> >> /Contents {content_id} 0 R >>").encode())
        page_ids.append(page_id)
    objects[pages_obj-1] = ("<< /Type /Pages /Kids [" + " ".join(f"{i} 0 R" for i in page_ids) + f"] /Count {len(page_ids)} >>").encode()

    out = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = [0]
    for i, obj in enumerate(objects, 1):
        offsets.append(len(out))
        out += f"{i} 0 obj\n".encode() + obj + b"\nendobj\n"
    xref = len(out)
    out += f"xref\n0 {len(objects)+1}\n".encode()
    out += b"0000000000 65535 f \n"
    for off in offsets[1:]:
        out += f"{off:010d} 00000 n \n".encode()
    out += (f"trailer\n<< /Size {len(objects)+1} /Root {catalog} 0 R >>\n"
            f"startxref\n{xref}\n%%EOF\n").encode()
    path.write_bytes(out)

if __name__ == '__main__':
    target = Path(sys.argv[1] if len(sys.argv) > 1 else 'lexavault-derived-routing-fixture.pdf').resolve()
    build_pdf(target)
    print(target)
