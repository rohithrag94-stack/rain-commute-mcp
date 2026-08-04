# AGENTS.md

Context for AI coding agents (and humans) picking up this repo cold. Read this before re-deriving anything below via web search — it was already verified once, the hard way.

## What this is

A Spring AI MCP server (stdio transport) exposing one tool, `checkRainOnCommute`, that checks the rain forecast at a destination for the hour you'd actually arrive, given a commute duration. Backed by the free Open-Meteo API. See [README.md](README.md) for build/run/test commands and the Claude Desktop / MCP Inspector wiring — not duplicated here.

Licensed MIT (see [LICENSE](LICENSE)); dependency licenses are tracked in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) — update that file if you add a new runtime dependency.

## File map

```
src/main/java/com/rocommute/mcp/
├── RainCommuteMcpApplication.java   # @SpringBootApplication + main(). No business logic; excluded from coverage.
├── WeatherClientConfig.java         # @Bean WebClient (baseUrl-configured) and @Bean Clock. Exists solely to make
│                                     # CommuteWeatherService constructor-injectable/testable.
└── CommuteWeatherService.java       # The @McpTool. fetchRainOutcome() returns a sealed RainOutcome
                                      # (NoForecast / NoCoverage / Result record), dispatched via a
                                      # pattern-matching switch with a guarded pattern.

src/test/java/com/rocommute/mcp/
├── CommuteWeatherServiceTest.java   # Stubs the weather API with an in-process com.sun.net.httpserver.HttpServer
│                                     # (JDK built-in, zero extra test deps) and a Clock.fixed(...) for determinism.
└── WeatherClientConfigTest.java     # Calls the @Bean factory methods directly — no Spring context needed.
```

## Version-specific gotchas (verified against live docs/jars, not assumed)

This stack moved fast between Spring AI 1.x-era tutorials (what most existing blog posts/LLM training data describe) and what's actually current. If something below contradicts a cached assumption, trust this file — it was checked against the real jars via `javap`, not just docs.

- **Spring Boot 4.0.7 / Spring Framework 7** is the version paired with **Spring AI 2.0.0** (not Boot 3.x — a common stale assumption). Java 25 has first-class support on Boot 4.0.x.
- **MCP tool registration changed**: tools are `@McpTool` / `@McpToolParam` from `org.springframework.ai.mcp.annotation`, auto-discovered on *any* Spring bean (e.g. a plain `@Service`). There is **no** `MethodToolCallbackProvider` bean to wire up — that was the 1.0.0-era pattern. Don't reintroduce it.
- **Jackson 3, not classic Jackson 2**: Spring Boot 4's `spring-boot-starter-jackson` pulls in `tools.jackson.core:jackson-databind:3.x`. The whole package tree renamed `com.fasterxml.jackson.*` → `tools.jackson.*`, **except** `jackson-annotations`, which stays under `com.fasterxml.jackson.annotation` for cross-version compat. Method names mostly held steady (`asInt()`, `asDouble()`, `has()`, `get()`, `forEach()` via `Iterable`), but `asText()` is deprecated in favor of `asString()` — use the latter in new code.
- Coverage tooling: **JaCoCo needs ≥0.8.14** to read Java 25 bytecode (class file version 69); older versions fail with "Unsupported class file major version 69".

## Testing conventions

- `CommuteWeatherService` takes `WebClient` and `Clock` via constructor injection specifically so tests can swap in a fixed clock and a local HTTP stub instead of hitting the real Open-Meteo API or dealing with non-deterministic "now".
- Prefer the in-process `com.sun.net.httpserver.HttpServer` pattern already in `CommuteWeatherServiceTest` over adding a mocking/stubbing library (WireMock, MockWebServer, etc.) for simple JSON-stub-a-GET-endpoint needs — it's JDK-builtin, zero new dependencies, and exercises the real WebClient HTTP path.
- Coverage gate is `mvn verify` (not just `test`) — JaCoCo's `check` goal runs in the `verify` phase.
- **Branch coverage on `CommuteWeatherService.checkRainOnCommute`'s switch will never hit 100% via ratio.** `javac` wraps pattern-matching `switch` cases (JEP 441) in a synthetic `MatchException` catch/throw for exhaustiveness that JaCoCo counts as branches but that is not reachable from application-level tests (confirmed: [jacoco/jacoco#1514](https://github.com/jacoco/jacoco/issues/1514), [#1219](https://github.com/jacoco/jacoco/issues/1219)). The `pom.xml` JaCoCo `check` rule pins `BRANCH` to a `MISSEDCOUNT` ceiling (currently 4, the known synthetic baseline) instead of a `COVEREDRATIO`, so a genuinely new missed branch still fails the build. If you add a new `switch`-over-sealed-type case and the missed-branch count grows only by the number of new cases' synthetic paths, that's expected — don't chase it by rewriting to if/else chains; re-verify the new baseline and update the `<maximum>` with a comment explaining the delta.
- `RainCommuteMcpApplication` is excluded from JaCoCo coverage entirely (see `pom.xml` `<excludes>`). Its `main()` starts a real MCP stdio server that blocks reading stdin — not safely unit-testable, and there's no independent logic in it worth testing.

## Local dev environment notes (this machine, Windows)

- Neither `java` nor `mvn` is on `PATH` by default in fresh shells. JDK lives at `C:\Program Files\Eclipse Adoptium\jdk-25.0.4.7-hotspot`, Maven at `C:\apache-maven-3.9.16`. Export both onto `PATH` (and set `JAVA_HOME`) per-session before building.
- `rtk` (token-optimized CLI proxy, see the user's global `RTK.md`) is expected to wrap most shell commands. Its binary isn't on `PATH` either — call it via full path (`/c/Users/ragro/rtk.exe`) or add that directory to `PATH`.
- **rtk's git-command rewriting mangles `revision:path` colon syntax** (e.g. `git show origin/main:.gitignore` becomes `origin\main;.gitignore` and fails) — this happens even calling `git` directly or via absolute binary path, since the rewrite happens at the hook/harness level before the shell sees it. Avoid that syntax; use `git merge`/working-tree reads instead when you need remote file contents.
- Git identity for this repo is set **locally** (`git config user.name`/`user.email` inside this repo, not `--global`) to `Rohith <rohithrag94@gmail.com>`, deliberately overriding the machine's global git identity (which is tied to a different GitHub account/email and would misattribute commits on push).
