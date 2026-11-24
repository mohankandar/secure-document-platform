# Building LexaVault: platform-core from GitHub Packages

LexaVault's backend uses platform-core's released libraries (task 0056: **0.8.1**, tag `platform-core-v0.8.1`, which needs Spring Boot 4.2 and Java 21). The
version is one property, `platform.version` in `backend/pom.xml`, which imports `platform-bom`. LexaVault uses the
identity modules only: `platform-identity-core`, `-jdbc` and `-spring` in the app, `platform-licensing` (read only, for
identity), what identity 0.8.1 itself needs (`platform-outbound-spring`, `platform-outbound`, `platform-errors` and
Apache HttpClient 5), and `platform-setup` in the setup jar (`backend/securedoc-setup`). Never a snapshot, never a private local
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

## Versions (task 0056)

| What | Version | Where it is set |
|---|---|---|
| Java | 21 | `java.version`, `backend/pom.xml` |
| Spring Boot (app, scan bridge, setup jar) | 4.2.0 | `spring-boot.version`, `backend/pom.xml`; its BOM brings Framework 7.1, Security 7.2, Hibernate 7.4, Tomcat 11, Flyway 12 |
| Jackson | 3.1.8 (Spring Boot's) | `jackson.version` names it for `securedoc-ai`, which does not import the BOM. No Jackson 2 but its annotations, which Jackson 3 uses |
| Liquibase | 4.29.2, not Spring Boot's 5.0.4 | `liquibase.version`, `backend/pom.xml`. 5.x is FSL-1.1-ALv2, not open source; 4.30 and later report usage to the vendor unless switched off |
| platform-core | 0.8.1 | `platform.version`, `backend/pom.xml` |

JSON: `spring.jackson` in both `application.yml` files keeps the app's JSON byte for byte as with Spring Boot 3
(`JsonSettingsContractTest`). Code that makes its own mapper uses `JsonMapper.builderWithJackson2Defaults()`.

## Development and production builds (task 0054)

Since platform-core 0.7.0 the licence keys a build trusts are inside its own jars: `META-INF/platform/licence-keys.json`
and `META-INF/platform/licence-build.properties` (`built-at`, the build's time in UTC) in the app jar and the setup jar.
No option, setting or file at an installation adds a key, and a second copy on the classpath is refused. They come from
`backend/licence-build/`:

| Build | Command | Keys |
|---|---|---|
| **Production: the default** | `mvn clean install`, or `Build-LexaVault-All.ps1 -Production` (`.sh --production`) | `licence-build/production`: production keys only. **No keys file yet**, so setup refuses every licence (`SETUP_KEYS_INVALID`) until the release adds `META-INF/platform/licence-keys.json` there. It never lists a development key |
| Development | `mvn clean install -Pdev-build`, or `Build-LexaVault-All.ps1` (`.sh`) | `licence-build/dev`: the demo licence's development key (the same as `install/demo/development-trusted-keys.json`; a test compares them) with `development-keys=true` |

The local start scripts, the demo and the test tools need a development build and say so when they get another. A jar
that leaves the machine is always a production build, from `clean` (a target folder of a development build still holds
its key); `Build-LexaVault-All -Production` fails if a development key is in either jar.

## Checks

```
cd backend
mvn -B dependency:tree -pl securedoc-api,securedoc-setup -am "-Dincludes=com.documentplatform.platform"
```

- `securedoc-api` (the app): `platform-identity-core`, `platform-identity-jdbc`, `platform-identity-spring`,
  `platform-licensing`, `platform-outbound-spring`, `platform-outbound` and `platform-errors`, all `0.8.1`, compile scope.
  `platform-setup` and `platform-identity-testkit` only in test scope. `platform-notifications`, which platform-outbound
  declares for a proxy password's secret reference, is excluded and not in the app: the app makes its own outbound rules
  (`OutboundRulesConfig`) and refuses a proxy with sign-in. HttpClient (5.6.5, core 5.4.4) comes from Spring Boot's BOM,
  the same versions platform-core is built with.
- `securedoc-setup`: `platform-setup` with `platform-crypto` and `platform-integration` (which bring
  `platform-notifications` and `platform-fields`). These are never on the app's classpath; `Build-LexaVault-All`
  checks the app jar (`BOOT-INF/lib`) for them.

A `401 Unauthorized` from `maven.pkg.github.com` means the token is missing, expired or lacks `read:packages`, or the
server id in `settings.xml` is not `github-platform-core`.
