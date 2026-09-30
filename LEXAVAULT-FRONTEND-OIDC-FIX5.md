# LexaVault Shared Auth R1 FIX5 - Browser OIDC

FIX5 is based directly on the validated FIX4 tree.

Changes:
- Start-LexaVault-OIDC.ps1 now starts the frontend in OIDC mode rather than accidentally starting it as LOCAL.
- Frontend uses the existing Authorization Code + PKCE flow with `lexavault-web`.
- Access tokens continue to be sent as Bearer tokens to the LexaVault API.
- OIDC ID token is retained only in sessionStorage for standards-based IdP logout.
- Sign out clears the LexaVault browser session and redirects through the provider end-session endpoint when available.
- LOCAL mode remains unchanged.

Local demo defaults:
- Issuer: http://localhost:8181/realms/document-platform-local
- Web client: lexavault-web
- API audience: lexavault-api
- Redirect: http://localhost:5173/

Start:
```powershell
.\Start-LexaVault-OIDC.ps1 `
  -IssuerUri "http://localhost:8181/realms/document-platform-local" `
  -Audience "lexavault-api" `
  -SkipScannerBridge
```

Do not pass `-SkipFrontend` for the browser test.
