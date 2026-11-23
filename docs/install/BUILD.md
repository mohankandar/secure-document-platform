# Building LexaVault: platform-core from GitHub Packages

LexaVault's backend uses platform-core's released libraries (task 0041: **0.2.0**, tag `platform-core-v0.2.0`). The
version is one property, `platform.version` in `backend/pom.xml`, which imports `platform-bom`. LexaVault uses the
identity modules only: `platform-identity-core`, `-jdbc` and `-spring` in the app, `platform-licensing` (read only, for
identity), and `platform-setup` in the setup jar (`backend/securedoc-setup`). Never a snapshot, never a private local
repository.

## Where Maven reads them

`backend/pom.xml` declares the repository:

| id | URL |
|---|---|
| `github-platform-core` | `https://maven.pkg.github.com/mohankandar/platform-core` |

GitHub Packages needs a sign-in even to read. Each machine that builds puts it in its own `~/.m2/settings.xml`
(Windows: `%USERPROFILE%\.m2\settings.xml`), under the same id. The token is never written in the file: Maven reads it
from an environment variable.

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <servers>
    <server>
      <id>github-platform-core</id>
      <username>mohankandar</username>
      <password>${env.GITHUB_PACKAGES_TOKEN}</password>
    </server>
  </servers>
</settings>
```

- `GITHUB_PACKAGES_TOKEN`: a GitHub token with `read:packages` only, set as a user environment variable (Windows:
  System, Environment Variables, User variables). Open a new terminal after setting it.
- Never put the token in the repository, a script, a log or a report. Never print it.
- The username is the account that owns the token.

Once downloaded, the artifacts stay in `~/.m2/repository/com/documentplatform/platform/`, so later builds also work
offline (`mvn -o`).

## Checks

```
cd backend
mvn -B dependency:tree -pl securedoc-api,securedoc-setup -am "-Dincludes=com.documentplatform.platform"
```

- `securedoc-api` (the app): `platform-identity-core`, `platform-identity-jdbc`, `platform-identity-spring`,
  `platform-licensing`, all `0.2.0`, compile scope. `platform-setup` only in test scope.
- `securedoc-setup`: `platform-setup` with `platform-crypto` and `platform-integration` (which bring
  `platform-notifications` and `platform-fields`). These are never on the app's classpath; `Build-LexaVault-All`
  checks the app jar (`BOOT-INF/lib`) for them.

A `401 Unauthorized` from `maven.pkg.github.com` means the token is missing, expired or lacks `read:packages`, or the
server id in `settings.xml` is not `github-platform-core`.
