# LexaVault LOCAL scripts v3

Important change: the backend now starts from the executable JAR produced by `build-all`, instead of invoking `mvn spring-boot:run`.

Recommended workflow:

```powershell
.\build-all.cmd
.\start-local.cmd
```

Why: the corporate Maven/Nexus setup may not resolve the `spring-boot` plugin prefix from the backend parent POM. Running the built Spring Boot JAR avoids Maven/plugin resolution during application startup.

The script automatically finds the newest:

```text
backend\securedoc-api\target\securedoc-api-*.jar
```

If no JAR exists, it asks you to run `build-all.cmd` first.
