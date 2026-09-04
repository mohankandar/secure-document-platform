# Final V1 Runtime Fixtures

- `Create-DerivedRoutingFixture.py` creates a dependency-free synthetic 3-page PDF with deterministic routing markers and synthetic sensitive values.
- `DerivedRoutingMockDestination.py` provides a dependency-free local HTTP destination with HEAD health check, chunked/content-length POST support, durable receipt response, saved received PDFs and `events.jsonl` evidence.

These are local verification fixtures only. They are not production runtime components.
