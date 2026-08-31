# Local OCR Setup — Unit 17

Unit 17 adds provider-neutral OCR with a local/on-prem Tesseract adapter. OCR is used only for PDF pages whose embedded/native text is not sufficiently usable; normal digital pages continue to use PDFBox text + coordinates.

## Runtime requirements

- Tesseract 5.x available on the application host
- English language data (`eng`) for the current V1 acceptance path
- Java 21 and the existing LexaVault local prerequisites

Confirm the executable is reachable:

```bash
tesseract --version
```

If `tesseract` is not on `PATH`, point LexaVault to the executable:

```bash
export SECUREDOC_OCR_TESSERACT_EXECUTABLE=/path/to/tesseract
```

On Windows PowerShell:

```powershell
$env:SECUREDOC_OCR_TESSERACT_EXECUTABLE = "C:\\Program Files\\Tesseract-OCR\\tesseract.exe"
```

## Configuration

The defaults are intentionally local/on-prem:

```yaml
securedoc:
  ocr:
    tesseract:
      executable: tesseract
      language: eng
      page-segmentation-mode: 3
      timeout-seconds: 60
      render-dpi: 220
      min-native-characters: 32
      minimum-page-confidence: 0.55
      blank-page-dark-pixel-ratio: 0.0015
```

Environment overrides:

- `SECUREDOC_OCR_TESSERACT_EXECUTABLE`
- `SECUREDOC_OCR_TESSERACT_LANGUAGE`
- `SECUREDOC_OCR_TESSERACT_PSM`
- `SECUREDOC_OCR_TESSERACT_TIMEOUT_SECONDS`
- `SECUREDOC_OCR_RENDER_DPI`
- `SECUREDOC_OCR_MIN_NATIVE_CHARACTERS`
- `SECUREDOC_OCR_MIN_PAGE_CONFIDENCE`
- `SECUREDOC_OCR_BLANK_DARK_PIXEL_RATIO`

## Safety behavior

- Native PDF text is preferred for meaningful digital pages.
- Sparse/image-only pages are rendered to PNG in memory and sent through the OCR port.
- Tesseract TSV word boxes are normalized into the same top-left coordinate system already used by Unit 16 findings/redaction.
- OCR confidence is carried with spans. A finding on an OCR page is capped by the confidence of the OCR spans that locate it; low OCR confidence can therefore force review instead of irreversible automatic redaction.
- A non-blank page that produces no readable OCR text fails closed as a technical processing failure.
- A non-blank OCR page below the configured minimum page confidence also fails closed. Provider retry/failover belongs to the later exception/IT-provider unit.
- Genuinely blank pages are allowed and flow through the existing no-finding safe-output path.
- AI remains the primary sensitive-content discovery layer after OCR normalization. Tesseract does not decide what is sensitive and does not redact PDFs.

## Current V1 boundary

OCR is page-aware. A mixed PDF may use native extraction on some pages and OCR on other pages while feeding both into the exact same Unit 16 AI/evidence/policy/redaction pipeline.

The current persistence model does not store positional spans, so detection re-normalizes the original artifact to recover trusted coordinates. For OCR pages this means OCR may run a second time during the same processing path. This is a known performance optimization opportunity, not a second detection architecture; avoid introducing a large layout-persistence redesign until runtime profiling shows it is necessary.
