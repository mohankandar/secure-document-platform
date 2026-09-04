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
.\start-local.cmd
```

The start script:

1. verifies Java, Node/npm, Ollama, and Tesseract;
2. reuses PostgreSQL on port 5432 or starts the repository Docker Compose PostgreSQL service;
3. reuses Ollama on port 11434 or starts `ollama serve`;
4. requires `qwen3.5:4b` by default (or can pull it when `Start-LexaVault-Local.ps1 -PullModelIfMissing` is used);
5. starts the already-built executable `securedoc-api-*.jar` with the `local` profile on port 8080;
6. starts the Vite frontend on port 5173;
7. records only processes it launched under `.run/`.

The backend intentionally starts from the built executable JAR rather than `mvn spring-boot:run`, avoiding Maven plugin-resolution dependency during normal local startup.

## URLs

- UI: `http://localhost:5173`
- Backend: `http://localhost:8080`
- Ollama: `http://localhost:11434`

## Stop application processes

```powershell
.\stop-local.cmd
```

or:

```powershell
.\Stop-LexaVault-Local.ps1
```

This stops the backend/frontend process trees launched by the start script and leaves pre-existing infrastructure alone.

## Stop infrastructure started by the script

```powershell
.\Stop-LexaVault-Local.ps1 -IncludeInfrastructure
```

PostgreSQL/Ollama are stopped only when the start script recorded that it launched them.

## Configuration references

- AI/provider profiles: `AI_PROVIDER_CONFIGURATION.md`
- local AI behavior: `LOCAL_AI_SETUP.md`
- OCR: `OCR_CONFIGURATION.md`
