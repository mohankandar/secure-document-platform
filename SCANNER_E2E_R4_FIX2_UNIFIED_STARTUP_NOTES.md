# Scanner E2E R4 FIX2 — Unified Local Startup

This candidate folds Scanner Bridge startup/shutdown into the normal LexaVault local scripts.

## Start

```powershell
# Default: simulator mode
.\start-local.cmd

# Explicit simulator
.\start-local.cmd SIMULATOR

# Physical scanner mode (PRINTER is accepted as an alias)
.\start-local.cmd PHYSICAL
.\start-local.cmd PRINTER
```

`start-local.cmd` now starts PostgreSQL/Ollama as needed, backend, Scanner Bridge, and frontend. If a project-managed Scanner Bridge is already running in the other mode, it is restarted automatically in the requested mode.

## Stop

```powershell
.\stop-local.cmd
```

The normal stop command now stops the Scanner Bridge too, so `stop-scanner-bridge.cmd` is no longer required for the normal local workflow. The standalone bridge scripts remain available for troubleshooting.

## Important physical-scanner limitation

The current R4 Java bridge still contains the fail-closed `PhysicalScannerAcquisitionAdapter`: PHYSICAL/PRINTER mode can be selected and launched by the unified script, but real TWAIN/WIA/vendor hardware acquisition is not implemented in this candidate. Simulator mode remains fully usable. Real printer/scanner acquisition requires a separate hardware-adapter implementation/certification step.
