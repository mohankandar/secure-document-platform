@echo off
setlocal
cd /d "%~dp0"

echo [LexaVault] Restarting frontend only on port 5173...
for /f "tokens=*" %%P in ('powershell -NoProfile -Command "$p=(Get-NetTCPConnection -LocalPort 5173 -State Listen -ErrorAction SilentlyContinue).OwningProcess ^| Select-Object -Unique; $p"') do (
  if not "%%P"=="" (
    echo [LexaVault] Stopping existing UI process %%P...
    taskkill /PID %%P /F >nul 2>&1
  )
)

if not exist "frontend\securedoc-ui\package.json" (
  echo [LexaVault] ERROR: frontend\securedoc-ui was not found.
  pause
  exit /b 1
)

cd /d "%~dp0frontend\securedoc-ui"
echo [LexaVault] Starting UI at http://localhost:5173 ...
echo [LexaVault] Backend is not restarted.
call npm run dev
endlocal
