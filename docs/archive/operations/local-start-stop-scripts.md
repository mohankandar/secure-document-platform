# LexaVault LOCAL start/stop scripts

Place all four script files in the project root, beside:

- `backend\`
- `frontend\`
- `docker\`

## Start

PowerShell:

```powershell
.\Start-LexaVault-Local.ps1
```

Or double-click:

```text
start-local.cmd
```

If `gemma3:12b` is not installed:

```powershell
.\Start-LexaVault-Local.ps1 -PullModelIfMissing
```

The start script:

1. verifies Java, Maven, Node/NPM, Tesseract and Ollama;
2. uses existing PostgreSQL on port 5432, or starts `docker\compose\postgres.yml` if PostgreSQL is not running;
3. uses existing Ollama on port 11434, or starts `ollama serve`;
4. requires the approved local acceptance model `gemma3:12b`;
5. starts the Spring Boot backend with the `local` profile on port 8080;
6. starts Vite on port 5173;
7. records only processes it launched under `.run\`.

## Stop app only

```powershell
.\Stop-LexaVault-Local.ps1
```

This stops the backend/frontend process trees started by the start script.

PostgreSQL and Ollama stay running.

## Stop app + infrastructure started by the script

```powershell
.\Stop-LexaVault-Local.ps1 -IncludeInfrastructure
```

This additionally stops PostgreSQL and/or Ollama only when the start script recorded that it started them.

It deliberately does not kill an unrelated PostgreSQL or Ollama instance that was already running before LexaVault started.
