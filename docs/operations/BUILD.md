# LexaVault Full Build Script

Place these files in the project root beside:

```text
backend\
frontend\
docker\
```

## Normal full build

```powershell
.\Build-LexaVault-All.ps1
```

or:

```text
build-all.cmd
```

This runs the whole backend Maven reactor from the single backend root:

```text
backend\
  mvn clean install
```

Then it verifies and builds the frontend:

```text
frontend\securedoc-ui\
  npm ci                # only when node_modules is missing
  npx tsc --noEmit
  npm run test:ui-framework
  npm run build
```

You do not need to enter individual Maven modules.

## Force clean frontend dependencies

```powershell
.\Build-LexaVault-All.ps1 -RefreshNodeModules
```

## Skip backend tests for a faster local build

```powershell
.\Build-LexaVault-All.ps1 -SkipTests
```

For official verification / Final Acceptance, do not use `-SkipTests`.

The script stops immediately on the first failed backend or frontend build step.
