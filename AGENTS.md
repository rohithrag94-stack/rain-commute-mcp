# AGENTS.md

Context for AI coding agents (and humans) picking up this repo cold. Read this before re-deriving anything below via web search — it was already verified once, the hard way.

## What this is

A Spring AI MCP server (stdio transport) exposing one tool, `checkRainOnCommute`, that checks the rain forecast at a destination for the hour you'd actually arrive, given a commute duration. Backed by the free Open-Meteo API. See [README.md](README.md) for build/run/test commands and the Claude Desktop / MCP Inspector wiring — not duplicated here.

Licensed MIT (see [LICENSE](LICENSE)); dependency licenses are tracked in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) — update that file if you add a new runtime dependency.

## File map

```
src/main/java/com/rocommute/mcp/
├── RainCommuteMcpApplication.java   # @SpringBootApplication + main(). No business logic; excluded from coverage.
├── WeatherClientConfig.java         # @Bean WebClient x2 (weatherWebClient, geocodingWebClient -- both baseUrl-
│                                     # configured, built from a shared Builder.clone() so neither leaks its
│                                     # baseUrl into the other) and @Bean Clock. Exists solely to make
│                                     # CommuteWeatherService/GeocodingClient constructor-injectable/testable.
├── RainCommuteProperties.java       # @ConfigurationProperties(prefix = "rain-commute") -- location aliases
│                                     # (locations.*) and defaultCommuteMinutes. See "Personalization" below.
├── GeocodingClient.java             # Resolves a free-text place name/address to (lat, lng, label,
│                                     # alternateLabels) via Open-Meteo's geocoding API. Returns Optional.empty()
│                                     # on no-match or API failure -- CommuteWeatherService doesn't distinguish
│                                     # the two. See "Geocoding disambiguation" below for alternateLabels.
└── CommuteWeatherService.java       # The @McpTool. Resolves any configured location alias, falls back to the
                                      # configured default commute duration if omitted, then fetchRainOutcome()
                                      # geocodes and returns a sealed RainOutcome (NoLocation / NoForecast /
                                      # NoCoverage / Result record), dispatched via a pattern-matching switch
                                      # with a guarded pattern.

src/test/java/com/rocommute/mcp/
├── CommuteWeatherServiceTest.java   # Stubs the weather + geocoding APIs with two independent in-process
│                                     # com.sun.net.httpserver.HttpServer instances (JDK built-in, zero extra
│                                     # test deps) and a Clock.fixed(...) for determinism. Two servers (not one
│                                     # with two contexts) so "weather API down" and "geocoding API down" are
│                                     # independently testable. The geocoding stub also captures the raw "name"
│                                     # query param it received, so alias-resolution tests can prove the
│                                     # resolved place actually reached the geocoder, not just that the final
│                                     # message looks plausible.
├── GeocodingClientTest.java         # Same HttpServer-stub pattern, tests GeocodingClient in isolation,
│                                     # including the population-ratio alternates heuristic against fixtures
│                                     # mirroring real Open-Meteo responses (see "Geocoding disambiguation").
├── RainCommutePropertiesTest.java   # Direct tests of the properties class's own defaults/getters/setters,
│                                     # matching WeatherClientConfigTest's per-collaborator convention.
└── WeatherClientConfigTest.java     # Calls the @Bean factory methods directly — no Spring context needed.

.github/workflows/
├── ci.yml                          # Build + test + coverage on every push/PR to main.
└── release.yml                     # On a "v*" tag push: build, verify, then `gh release create` with the
                                      # built jar attached -- see "Release jar" in README for why (skips
                                      # requiring Maven for anyone who just wants to run the server).
```

## Timezone correctness (do not regress this)

`CommuteWeatherService.fetchRainOutcome` computes the arrival hour as `clock.instant().plus(commuteDuration)`, converted via `ZonedDateTime.ofInstant(..., destinationZone)` where `destinationZone` comes from the forecast response's own `"timezone"` field (Open-Meteo returns an IANA zone id, e.g. `"Asia/Kolkata"`, when queried with `timezone=auto`) — **never** from `clock`'s configured zone or the server's `ZoneId.systemDefault()`. This app has no fixed deployment target (it runs as a local stdio subprocess on whatever machine the MCP client happens to be on), so "the server's zone" is meaningless data that happens to sometimes coincide with the right answer and sometimes doesn't. An earlier version used `LocalDateTime.now(clock)`, which silently applied the *clock's* zone to the arrival time before comparing it against the destination's zone-labelled hourly buckets — correct only when the two zones happened to match by coincidence, silently wrong (wrong hour, or a spurious "doesn't cover the arrival time") whenever they didn't. Verified concretely: this dev machine's own `ZoneId.systemDefault()` is `Europe/Berlin`, and a real end-to-end call for "Bengaluru" (`Asia/Kolkata`, UTC+5:30) and one for "New York" (US Eastern, UTC-4 in DST) each independently returned the correct destination-local arrival hour, matching neither `Europe/Berlin` nor each other. `forecast.has(TIMEZONE_FIELD)` is checked and treated as a hard failure (`NoForecast`) if absent, specifically so a future API change can never cause a silent fallback to the wrong zone — see `missingTimezone_returnsCouldNotRetrieveMessage` in `CommuteWeatherServiceTest`, which is the regression test for all of this.

## Rounding direction (do not regress this either)

`precipitation_probability` and `rain` in Open-Meteo's hourly response are **preceding-hour** aggregates, not readings for the hour starting at that timestamp — verified directly against the live parameter table at https://open-meteo.com/en/docs (`precipitation_probability`: "Probability of precipitation with more than 0.1 mm of the *preceding hour*"; `rain`: "Rain from large scale weather systems of the *preceding hour*"). Concretely: the bucket labelled `"20:00"` describes rain that fell between 19:00 and 20:00, **not** 20:00 and 21:00. This means the target-hour lookup in `fetchRainOutcome` has to round the arrival time **up** to the next hour (except when it lands exactly on one), not floor it down — an arrival at 20:44 is inside the window the `21:00` bucket describes, so looking up `20:00` would silently return the wrong hour's data. An earlier version floored instead of rounding up, which is why this got caught late: it *looks* plausible (both are "snap to an hour"), produces a valid-looking bucket match every time (so it never surfaces as a `NoCoverage` error), and is off by exactly one hour in the common case. Regression test: `arrivalTime_isRoundedUpToNextHour_notFlooredDown` in `CommuteWeatherServiceTest`; the exact-hour edge case (which must *not* round up) is covered separately by `exactHourArrival_isNotRoundedUpToNextHour`, since it needs its own `Clock.fixed` — `FIXED_CLOCK`'s `:15:30` offset can never land on an exact minute boundary no matter how many whole commute-minutes are added to it.

## Personalization: location aliases and a default commute duration

`checkRainOnCommute` accepts `commuteMinutes` as a boxed `Integer` with `@McpToolParam(required = false)`, not a primitive `int` -- verified via `javap` against the actual `spring-ai-mcp-annotations-2.0.0.jar` that `required()` on `@McpToolParam` defaults to `true` (an `AnnotationDefault` of `Z#10 -> true`), so making a parameter genuinely optional needs both the explicit `required = false` *and* a nullable type, since a primitive can't represent "the caller omitted this." When `commuteMinutes` is `null`, `checkRainOnCommute` falls back to `RainCommuteProperties.getDefaultCommuteMinutes()` (baked-in default: 30, itself overridable -- see below).

`destination` also gets resolved against `RainCommuteProperties.getLocations()` (case-insensitively, trimmed) before geocoding runs -- so a user can say "will it rain at home" and, if they've configured `rain-commute.locations.home=Bengaluru`, the tool geocodes "Bengaluru", not the literal word "home". `CommuteWeatherServiceTest`'s geocoding stub captures the raw `name` query parameter it actually received specifically to prove this substitution happens before the HTTP call, not just that the final message text happens to look right.

Both the per-user location aliases and the default commute duration are meant to be user-editable *without rebuilding the jar*, so they live in an external file layered on top of the bundled `application.properties` via:

```
spring.config.import=optional:file:${user.home}/.rain-commute-mcp/config.properties
```

Verified live (not assumed): `${user.home}` resolves correctly at this stage of Spring Boot's config-loading pipeline (it's a JVM system property, available before the `Environment` is fully assembled), and properties in the imported file take precedence over the ones in the classpath `application.properties` that declared the import -- confirmed by setting `rain-commute.default-commute-minutes=2` in a real file at that path and observing the tool actually use 2 minutes instead of the bundled 30, and by configuring `rain-commute.locations.home=Bengaluru` there and confirming an MCP Inspector call with `destination=home` sent `name=Bengaluru` to the real geocoding API. The `optional:` prefix matters: without it, a user who never creates this file would get a startup failure instead of the bundled defaults.

`RainCommuteProperties` binds via a plain `@Component @ConfigurationProperties(prefix = "rain-commute")` class (JavaBean getters/setters, no builder/constructor-binding) -- Spring Boot's component scan picks this up automatically in a `@SpringBootApplication`, no `@EnableConfigurationProperties` registration needed. Its `Map<String, String> locations` field binds arbitrary user-defined keys under `rain-commute.locations.*` (e.g. `rain-commute.locations.work=...`), which is the reason it's a `@ConfigurationProperties` map rather than individual `@Value` injections like `WeatherClientConfig` uses elsewhere -- `@Value` has no clean way to bind an open-ended set of caller-chosen keys.

**A real geocoding gotcha found while verifying this, worth knowing before writing your own location aliases**: Open-Meteo's geocoder found `Electronic City` on its own, but returned zero results for `Electronic City, Bengaluru` (verified live via direct `curl` to `geocoding-api.open-meteo.com`) -- unlike `Eiffel Tower, Paris`, which resolves fine. It's not consistent about "specific place, containing city" compound queries. If a configured alias value returns `NoLocation`, try the bare place name before assuming the alias mechanism itself is broken.

## Geocoding disambiguation

`GeocodingClient.geocode` requests `count=5` (not 1) from Open-Meteo and surfaces same-named alternates on `GeoLocation.alternateLabels()`, which `CommuteWeatherService` appends as a parenthetical note on a successful verdict (`checkRainOnCommute`'s `alsoConsiderSuffix`) -- e.g. asking about "Springfield" gets an answer for Springfield, Missouri *and* a note that Massachusetts and Illinois also matched. This exists because the previous behavior (`count=1`, silently take the top match) could confidently answer for the wrong place with no error and no signal anything was ambiguous.

The threshold for "worth mentioning" is a population ratio, not a fixed rank cutoff, and the exact number (**20%**) was picked by checking real Open-Meteo responses rather than guessing -- see the constants' javadoc in `GeocodingClient`:
- `Springfield` returns five real US candidates ranging 16.8k-170k people; the smallest that's still genuinely plausible is ~35% of the largest. Genuinely ambiguous, worth asking about.
- `Paris` returns Paris, France (2.1M) then Paris, Texas (24.8k, ~1.2% of France's population) and smaller. Not worth interrupting a confident answer for.

20% cleanly separates those two real cases. Alternates are also capped at 2 (`MAX_ALTERNATES`) and sorted by population descending, since Open-Meteo's response order is *not* population-sorted (verified: the live Springfield response above comes back Missouri/Illinois/Massachusetts/Ohio/Tennessee by population 170k/114k/154k/59k/17k -- Illinois and Massachusetts are out of population order in the raw response). If the primary match has no `population` field to compare against, no alternates are surfaced at all -- there's nothing to judge prominence by, so the code doesn't guess.

Labels also changed from "Name, Country" to "Name, Admin region, Country" (e.g. `Springfield, Missouri, United States`) specifically because same-named US alternates are otherwise indistinguishable by country alone -- all five Springfields would have rendered as "Springfield, United States". Regression tests for all of the above are in `GeocodingClientTest` (using fixtures that mirror the real Springfield/Paris data above) and `CommuteWeatherServiceTest.alternates_getSurfacedInSuccessfulVerdictMessage` (proving the suffix reaches the actual tool response, not just `GeocodingClient`'s own return value).

## Version-specific gotchas (verified against live docs/jars, not assumed)

This stack moved fast between Spring AI 1.x-era tutorials (what most existing blog posts/LLM training data describe) and what's actually current. If something below contradicts a cached assumption, trust this file — it was checked against the real jars via `javap`, not just docs.

- **Spring Boot 4.0.7 / Spring Framework 7** is the version paired with **Spring AI 2.0.0** (not Boot 3.x — a common stale assumption). Java 25 has first-class support on Boot 4.0.x.
- **MCP tool registration changed**: tools are `@McpTool` / `@McpToolParam` from `org.springframework.ai.mcp.annotation`, auto-discovered on *any* Spring bean (e.g. a plain `@Service`). There is **no** `MethodToolCallbackProvider` bean to wire up — that was the 1.0.0-era pattern. Don't reintroduce it.
- **Jackson 3, not classic Jackson 2**: Spring Boot 4's `spring-boot-starter-jackson` pulls in `tools.jackson.core:jackson-databind:3.x`. The whole package tree renamed `com.fasterxml.jackson.*` → `tools.jackson.*`, **except** `jackson-annotations`, which stays under `com.fasterxml.jackson.annotation` for cross-version compat. Method names mostly held steady (`asInt()`, `asDouble()`, `has()`, `get()`, `forEach()` via `Iterable`), but `asText()` is deprecated in favor of `asString()` — use the latter in new code.
- Coverage tooling: **JaCoCo needs ≥0.8.14** to read Java 25 bytecode (class file version 69); older versions fail with "Unsupported class file major version 69".
- **`spring.main.web-application-type=none` (required for a stdio-only server) disables `WebClientAutoConfiguration`**, so no `WebClient.Builder` bean exists — even though `spring-boot-starter-webflux` is on the classpath. `WeatherClientConfig` provides one manually (`@Bean WebClient.Builder webClientBuilder()`). Don't remove it assuming the starter will supply it; it won't, in this `web-application-type`.
- **Open-Meteo's geocoding API (`geocoding-api.open-meteo.com/v1/search`) omits the `"results"` key entirely on no-match** (verified live: `?name=zzzznotarealplace` returns `{"generationtime_ms":...}`, no `results` at all), rather than returning `"results": []`. `GeocodingClient.geocode` checks `!response.has("results")` first for this reason; don't assume an empty-array check alone is sufficient. Also note: the geocoding endpoint is a **separate host/base-URL** from the forecast endpoint (`geocoding-api.open-meteo.com` vs `api.open-meteo.com`), hence the two separately-configurable `WebClient` beans in `WeatherClientConfig` — don't try to merge them into one client with one base URL.
- **An empty `logging.pattern.console=` (the trick used by the official Spring AI stdio examples, meant to keep stdout clean of log lines so it doesn't corrupt the MCP JSON-RPC stream) is fatal on the Logback version bundled with Spring Boot 4** — it aborts startup with `ERROR ... PatternLayout("") - Empty or null pattern`, with no further diagnostics on either stream since logging itself failed to initialize. Use `logging.level.root=OFF` instead, which achieves the same "silent stdout" goal without depending on pattern parsing at all.
- **`reactor-netty-http`'s HTTP/3 support triggers a Java 21+ "restricted method" native-access warning on first HTTP call, even though this app never uses HTTP/3.** `spring-boot-starter-webflux` transitively pulls `netty-codec-http3`, whose `Quic` availability-check class (`io.netty.handler.codec.quic.Quic`) unconditionally probes for a native QUIC/BoringSSL library via `System::loadLibrary` when `WebClient`/`HttpClient` is first built — Netty catches the resulting failure and falls back to plain HTTP cleanly, but the probe itself is what JEP 472 warns about, regardless of success. Excluding just the per-platform native artifact (`netty-codec-native-quic`) stops the probe from ever succeeding but **not** the warning (the probe attempt itself trips it); excluding `netty-codec-http3` entirely breaks `reactor-netty-http` at runtime with `NoClassDefFoundError` for `Quic`, since it references those Java classes unconditionally, not just when HTTP/3 is actually negotiated. The fix that actually silences the warning — and the one the warning text itself recommends — is granting native access via the `Enable-Native-Access: ALL-UNNAMED` jar manifest attribute (the manifest equivalent of the `--enable-native-access=ALL-UNNAMED` JVM flag), set via `maven-jar-plugin`'s `<archive><manifestEntries>` on the base jar so `spring-boot:repackage` carries it into the executable jar — see `pom.xml`. `netty-transport-native-epoll` and `netty-resolver-dns-native-macos` are excluded too as genuinely-dead weight (this app has no epoll/kqueue/macOS-native code path), but that's separate cleanup, not what fixes the warning.
- **Neither of the two bugs above was caught by the unit test suite despite 100% line/method coverage** — the tests construct `CommuteWeatherService`/`WeatherClientConfig` directly and never load a Spring `ApplicationContext` or read `application.properties`, so context-wiring and property-parsing failures are invisible to them. Coverage numbers only prove the Java logic runs; they say nothing about whether the Spring context actually boots. **Always do at least one real end-to-end run (`java -jar target/*.jar`, or via MCP Inspector) after touching `WeatherClientConfig` or `application.properties`** — see "Manual end-to-end testing" below.

## Testing conventions

- `CommuteWeatherService` takes `WebClient` and `Clock` via constructor injection specifically so tests can swap in a fixed clock and a local HTTP stub instead of hitting the real Open-Meteo API or dealing with non-deterministic "now".
- Prefer the in-process `com.sun.net.httpserver.HttpServer` pattern already in `CommuteWeatherServiceTest` over adding a mocking/stubbing library (WireMock, MockWebServer, etc.) for simple JSON-stub-a-GET-endpoint needs — it's JDK-builtin, zero new dependencies, and exercises the real WebClient HTTP path.
- Coverage gate is `mvn verify` (not just `test`) — JaCoCo's `check` goal runs in the `verify` phase.
- **Branch coverage on `CommuteWeatherService.checkRainOnCommute`'s switch will never hit 100% via ratio.** `javac` wraps pattern-matching `switch` cases (JEP 441) in a synthetic `MatchException` catch/throw for exhaustiveness that JaCoCo counts as branches but that is not reachable from application-level tests (confirmed: [jacoco/jacoco#1514](https://github.com/jacoco/jacoco/issues/1514), [#1219](https://github.com/jacoco/jacoco/issues/1219)). The `pom.xml` JaCoCo `check` rule pins `BRANCH` to a `MISSEDCOUNT` ceiling (currently 4, the known synthetic baseline) instead of a `COVEREDRATIO`, so a genuinely new missed branch still fails the build. If you add a new `switch`-over-sealed-type case and the missed-branch count grows only by the number of new cases' synthetic paths, that's expected — don't chase it by rewriting to if/else chains; re-verify the new baseline and update the `<maximum>` with a comment explaining the delta.
- `RainCommuteMcpApplication` is excluded from JaCoCo coverage entirely (see `pom.xml` `<excludes>`). Its `main()` starts a real MCP stdio server that blocks reading stdin — not safely unit-testable, and there's no independent logic in it worth testing.

## Manual end-to-end testing

`mvn verify`'s 100% coverage does **not** exercise the Spring context or `application.properties` (see above) — it's not a substitute for actually starting the server. Two ways to do that:

1. **Standalone**, to catch startup failures fast: `java -jar target/rain-commute-mcp-0.1.0.jar` with stdin left open (don't redirect from `/dev/null`/`NUL` — that's immediate EOF, which a stdio server correctly treats as "client disconnected" and exits, which looks identical to a real crash unless you check carefully). A healthy server just sits there silently.
2. **MCP Inspector CLI**, to actually call the tool: the web UI (`npx @modelcontextprotocol/inspector java -jar ...`, opens `localhost:6274`) is fine for poking around interactively, but its positional-argument parsing chokes on `-jar` (a token starting with `-` inside the target command breaks its variadic-arg collection) and its own "Servers" landing page in recent versions has no in-page tool-calling UI — connecting there only proves the JSON-RPC handshake works, not that a tool call succeeds. The **CLI mode with an explicit config file** sidesteps both problems and is the reliable option:

   ```json
   // mcp-config.json
   { "mcpServers": { "rain-commute": { "command": "java", "args": ["-jar", "target/rain-commute-mcp-0.1.0.jar"] } } }
   ```

   ```bash
   npx @modelcontextprotocol/inspector --cli --config mcp-config.json --server rain-commute --method tools/list

   npx @modelcontextprotocol/inspector --cli --config mcp-config.json --server rain-commute \
     --method tools/call --tool-name checkRainOnCommute \
     --tool-arg destination=Bengaluru --tool-arg commuteMinutes=30
   ```
3. **Testing the external config override** (`spring.config.import`, location aliases, default commute minutes -- see "Personalization" above): create a real file at `~/.rain-commute-mcp/config.properties` (or the OS-appropriate `${user.home}`), then call the tool omitting `commuteMinutes` and/or passing a configured alias as `destination`, and confirm the *effective* values changed -- e.g. set `rain-commute.default-commute-minutes=2`, omit `commuteMinutes` in the call, and check the returned arrival hour reflects +2 minutes rather than the bundled +30. Remove the file afterward if it was only for testing; nothing in the repo depends on it existing.

## Local dev environment notes (this machine, Windows)

- Neither `java` nor `mvn` is on `PATH` by default in fresh shells. JDK lives at `C:\Program Files\Eclipse Adoptium\jdk-25.0.4.7-hotspot`, Maven at `C:\apache-maven-3.9.16`. Export both onto `PATH` (and set `JAVA_HOME`) per-session before building.
- `rtk` (token-optimized CLI proxy, see the user's global `RTK.md`) is expected to wrap most shell commands. Its binary isn't on `PATH` either — call it via full path (`/c/Users/ragro/rtk.exe`) or add that directory to `PATH`.
- **rtk's git-command rewriting mangles `revision:path` colon syntax** (e.g. `git show origin/main:.gitignore` becomes `origin\main;.gitignore` and fails) — this happens even calling `git` directly or via absolute binary path, since the rewrite happens at the hook/harness level before the shell sees it. Avoid that syntax; use `git merge`/working-tree reads instead when you need remote file contents.
- Git identity for this repo is set **locally** (`git config user.name`/`user.email` inside this repo, not `--global`) to `Rohith <rohithrag94@gmail.com>`, deliberately overriding the machine's global git identity (which is tied to a different GitHub account/email and would misattribute commits on push).
