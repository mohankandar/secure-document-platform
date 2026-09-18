# LOCAL Run

## Prerequisites

- Java 21
- Node.js / npm
- Ollama
- Tesseract
- PostgreSQL on `localhost:5432`, or Docker so the start script can launch the repository PostgreSQL compose service
- a previously built backend JAR and frontend dependencies/build prerequisites

The repository LOCAL start script uses Ollama `qwen3.5:4b` as the validated V1 LOCAL/demo reference model. The product/provider architecture remains model configurable; `gemma3:12b` is optional for larger hardware.

## Recommended workflow

From the repository root on Windows:

```powershell
.\build-all.cmd
.\start-local.cmd              # default Scanner Bridge mode: SIMULATOR
```

Scanner mode can also be selected explicitly:

```powershell
.\start-local.cmd SIMULATOR
.\start-local.cmd PHYSICAL
.\start-local.cmd PRINTER      # alias for PHYSICAL
```

The start script:

1. verifies Java, Node/npm, Ollama, and Tesseract;
2. reuses PostgreSQL on port 5432 or starts the repository Docker Compose PostgreSQL service;
3. reuses Ollama on port 11434 or starts `ollama serve`;
4. requires `qwen3.5:4b` by default (or can pull it when `Start-LexaVault-Local.ps1 -PullModelIfMissing` is used);
5. starts the already-built executable `securedoc-api-*.jar` with the `local` profile on port 8080;
6. starts the already-built Scanner Bridge on loopback port 10090 in the selected mode;
7. starts the Vite frontend on port 5173;
8. records only processes it launched under `.run/`.

If a project-managed Scanner Bridge is already running in the other acquisition mode, the launcher restarts that bridge in the requested mode. `SIMULATOR` is the normal QA/development mode. `PHYSICAL` / `PRINTER` now selects the Windows WIA physical-acquisition candidate; it never silently falls back to the simulator. Full physical readiness still requires successful E2E verification against the target Windows scanner/driver.

The backend intentionally starts from the built executable JAR rather than `mvn spring-boot:run`, avoiding Maven plugin-resolution dependency during normal local startup.

## URLs

- UI: `http://localhost:5173`
- Backend: `http://localhost:8080`
- Ollama: `http://localhost:11434`
- Scanner Bridge: `http://127.0.0.1:10090`

## Stop application processes

```powershell
.\stop-local.cmd
```

or:

```powershell
.\Stop-LexaVault-Local.ps1
```

This stops the backend/frontend process trees and the project-managed Scanner Bridge launched by the start script, while leaving pre-existing PostgreSQL/Ollama infrastructure alone unless explicitly requested.

## Stop infrastructure started by the script

```powershell
.\Stop-LexaVault-Local.ps1 -IncludeInfrastructure
```

PostgreSQL/Ollama are stopped only when the start script recorded that it launched them.

## Scanner simulator workflow

In `SIMULATOR` mode use the Scanner Intake UI in this order:

1. Enter the tenant business identifiers.
2. Select **Start Scan**.
3. Select **Select Test PDF** and choose the PDF.
4. Wait for **DOCUMENT RECEIVED**, then select **Submit**.

Do not pre-place the PDF in `C:\LexaVaultTest\scanner-inbox` before **Start Scan**. Pre-existing unclaimed input is intentionally rejected by the stale-file/misassociation guard.

The standalone `start-scanner-bridge.cmd` / `Start-LexaVault-ScannerBridge.ps1` and corresponding stop scripts remain available for focused bridge troubleshooting, but they are no longer required for the normal LOCAL workflow.

## Physical scanner workflow (R5 WIA candidate)

On a Windows workstation with a WIA-compatible scanner:

1. Load the complete paper package into the scanner/ADF.
2. Start LexaVault with `./start-local.cmd PHYSICAL` (or `PRINTER`).
3. Open **Scan Documents**, enter the tenant business identifiers, and select **Start Scan**.
4. Wait for the WIA acquisition to finish and the UI to reach document-ready state.
5. Select **Submit** and confirm the package/document enters the normal LexaVault processing lifecycle.

The bridge defaults to WIA, 300 DPI, `AUTO` source and simplex. Optional workstation settings include `SECUREDOC_SCAN_BRIDGE_PHYSICAL_DEVICE_NAME`, `SECUREDOC_SCAN_BRIDGE_PHYSICAL_DEVICE_ID`, `SECUREDOC_SCAN_BRIDGE_PHYSICAL_SOURCE` (`AUTO|FEEDER|FLATBED`), `SECUREDOC_SCAN_BRIDGE_PHYSICAL_DUPLEX`, `SECUREDOC_SCAN_BRIDGE_PHYSICAL_DPI`, `SECUREDOC_SCAN_BRIDGE_PHYSICAL_MAX_PAGES`, and `SECUREDOC_SCAN_BRIDGE_PHYSICAL_TIMEOUT_SECONDS`. If multiple WIA scanners exist and no selector is configured, Windows may show its scanner picker. Hardware that is not exposed through WIA remains unavailable; the bridge does not fall back to simulator.

## Configuration references

- AI/provider profiles: `AI_PROVIDER_CONFIGURATION.md`
- local AI behavior: `LOCAL_AI_SETUP.md`
- OCR: `OCR_CONFIGURATION.md`
