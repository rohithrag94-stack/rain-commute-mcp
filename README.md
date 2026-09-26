# rain-commute-mcp

[![CI](https://github.com/rohithrag94-stack/rain-commute-mcp/actions/workflows/ci.yml/badge.svg)](https://github.com/rohithrag94-stack/rain-commute-mcp/actions/workflows/ci.yml)

An [MCP](https://modelcontextprotocol.io) server that checks whether rain is expected around the time you'd arrive at a destination, given how long your commute takes. Ask your MCP client "will it rain by the time I get to Bengaluru" and it looks up the forecast for the hour you'll actually land in — not just "right now."

![Demo: asking Claude Desktop "I am going to leave office now. How is the weather at Hoofddorp in 30 minutes?" and getting a live rain forecast back via the checkRainOnCommute tool](docs/demo.gif)

## How it works

The server exposes two MCP tools, both backed by the free [Open-Meteo](https://open-meteo.com/) forecast and geocoding APIs:

- **`checkRainOnCommute`** — "will it rain when I get there?" Answers for the hour you'd arrive if you left now.
- **`suggestDepartureTime`** — "should I wait, and for how long?" If leaving now means arriving in rain, finds the soonest departure that arrives dry. See [Deciding when to leave](#deciding-when-to-leave).

`checkRainOnCommute` works like this:

1. Takes a destination as a plain place name or address (e.g. `"Bengaluru"`, `"Eiffel Tower, Paris"`, or a saved shortcut like `"home"` — see [Personalizing it](#personalizing-it)) and a commute duration in minutes (optional — falls back to a configurable default) — no coordinates required.
2. Geocodes the destination to coordinates. If the name matches more than one real place (e.g. "Springfield"), the answer still comes back for the best match, with a note about the others in case that wasn't the one you meant — see [How disambiguation works](#how-disambiguation-works).
3. Computes your arrival time (now + commute duration, rounded up to the next hour — see [How rounding works](#how-rounding-works)) **in the destination's own local timezone**, from its forecast response — not the server's timezone, so results are correct no matter where the user or the server process happens to be.
4. Fetches the hourly forecast for that location and reads off precipitation probability, rain amount, temperature, and wind for the arrival hour.
5. Returns a plain-language verdict — dry, or grab an umbrella — with the temperature and wind alongside. If it's dry when you arrive but rain is forecast within the following 3 hours, it says so, since "dry on arrival" is little comfort if the downpour starts ten minutes later.

Both tools accept the same place names and [saved shortcuts](#personalizing-it).

### How disambiguation works

Some place names match more than one real location — "Springfield" alone matches at least five distinct US cities. Rather than silently picking one and possibly answering for the wrong place with no indication anything was ambiguous, the tool checks up to 5 candidates and mentions any others whose population is at least 20% of the top match's (e.g. asking about "Springfield" gets an answer for Springfield, Missouri, plus a note that Massachusetts and Illinois also matched). A name with one dominant match (e.g. "Paris" — Paris, France vs. Paris, Texas at roughly 1% of its population) gets a clean answer with no extra noise. See `GeocodingClient` in [AGENTS.md](AGENTS.md) for exactly how the threshold was picked.

### Deciding when to leave

`suggestDepartureTime` takes the same destination and commute duration, plus an optional `lookaheadHours` (default 3, capped at 12). It tries a departure every 15 minutes from now through the look-ahead window, works out the arrival hour for each (using the same [rounding rules](#how-rounding-works)), and reports one of:

- **Leave now** — arriving dry already.
- **Wait N minutes** — the earliest departure that arrives in a dry hour.
- **Nothing dry** — every departure it could check arrives in rain; it names the least rainy one and how far ahead it actually checked (the forecast can end before the look-ahead does).

The wait is always given relative to now ("in about 45 minutes"), never as a clock time, because your timezone may differ from the destination's and only the destination's is known.

### Reliability and caching

Open-Meteo is a free public service, so the server doesn't treat one bad response as the final answer: each request has a timeout (5 s), and transient failures — a timeout, a dropped connection, a 5xx, or a 429 — are retried twice with a short, growing delay. A request that can never succeed (a 4xx) fails immediately instead of being hammered. All three numbers are configurable (see [Configuration](#configuration)). If the API is still failing after the retries, you get a plain "couldn't retrieve a forecast" message rather than an error.

Resolved place names are cached in memory for 24 hours (up to 100 names, least recently used dropped first), so asking about "home" or "work" again skips the geocoding round trip. Only successful matches are cached; a failed lookup is never remembered, so a brief outage can't stick to a place name.

### How rounding works

Open-Meteo's `precipitation_probability` and `rain` are **preceding-hour** values — the bucket labelled `20:00` covers rain that fell between 19:00 and 20:00, not 20:00 and 21:00. So an arrival at, say, 20:44 doesn't look up the `20:00` bucket; it rounds up to `21:00`, since that's the bucket whose preceding-hour window (20:00–21:00) is the one that actually contains 20:44. An arrival landing exactly on the hour (e.g. 21:00:00) is the one case that *doesn't* round up — it's already the top of its own window.

## Personalizing it

By default, "how long is your commute" has to be answered every time and every destination needs a real name. Both are optional to state explicitly: create a file at `~/.rain-commute-mcp/config.properties` (Windows: `%USERPROFILE%\.rain-commute-mcp\config.properties`) with whichever of these you want —

```properties
rain-commute.default-commute-minutes=25
rain-commute.locations.home=Bengaluru
rain-commute.locations.work=Electronic City
```

— and you can just ask "will it rain when I get home" without saying how long the commute is. This file is read in addition to the server's own defaults (not instead of them): anything you don't set keeps its built-in value, and it's picked up automatically on every start, no rebuild needed. If you don't create the file at all, everything works exactly as before (30-minute default commute, no shortcuts).

One thing worth knowing when choosing values for `rain-commute.locations.*`: Open-Meteo's geocoder is inconsistent about "specific place, containing city" queries — `Electronic City` resolves fine on its own, but `Electronic City, Bengaluru` doesn't, even though `Eiffel Tower, Paris` does. If a configured location comes back "couldn't find a place," try the bare name first.

## Prerequisites

- Java 25 ([Eclipse Temurin](https://adoptium.net/) or any JDK 25 distribution)
- Maven 3.9+
- An MCP-compatible client (e.g. [MCP Inspector](https://github.com/modelcontextprotocol/inspector), Claude Desktop)

## Build

Don't want to build it yourself? Grab the prebuilt jar from [Releases](https://github.com/rohithrag94-stack/rain-commute-mcp/releases) instead — same thing `mvn clean install` below produces, built and tested by CI, no Maven required.

```bash
mvn clean install
```

This produces an executable jar at `target/rain-commute-mcp-0.1.0.jar` and runs the full test suite with a coverage check (see [Testing](#testing) below).

## Running

The server communicates over stdio (standard MCP transport for local tools), so it's meant to be launched by an MCP client rather than run standalone. To try it directly:

```bash
java -jar target/rain-commute-mcp-0.1.0.jar
```

It will sit waiting for JSON-RPC messages on stdin — that's expected. Use MCP Inspector or a real client to talk to it (see below).

### Testing with MCP Inspector

```bash
npx @modelcontextprotocol/inspector java -jar target/rain-commute-mcp-0.1.0.jar
```

This opens a local web UI where you can call `checkRainOnCommute` and `suggestDepartureTime` directly and inspect the raw request/response.

### Wiring into Claude Desktop

Add an entry to your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "rain-commute": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/rain-commute-mcp-0.1.0.jar"]
    }
  }
}
```

Restart Claude Desktop and the `checkRainOnCommute` and `suggestDepartureTime` tools become available in conversation.

## Configuration

| Property | Default | Description |
|---|---|---|
| `rain-commute.weather-api.base-url` | `https://api.open-meteo.com` | Base URL of the weather forecast API. Override to point at a mock/staging endpoint. |
| `rain-commute.geocoding-api.base-url` | `https://geocoding-api.open-meteo.com` | Base URL of the place-name geocoding API. Override to point at a mock/staging endpoint. |
| `rain-commute.default-commute-minutes` | `30` | Commute duration used when a request omits `commuteMinutes`. |
| `rain-commute.locations.<name>` | *(none)* | A location shortcut, e.g. `rain-commute.locations.home=Bengaluru` — see [Personalizing it](#personalizing-it). Any number of these can be set. |
| `rain-commute.http-timeout` | `5s` | How long one request to Open-Meteo may take before that attempt is abandoned. |
| `rain-commute.http-max-retries` | `2` | How many times a transient failure (timeout, dropped connection, 5xx, 429) is retried. `0` disables retrying. |
| `rain-commute.http-retry-backoff` | `300ms` | Delay before the first retry; doubles on each further one. |
| `spring.ai.mcp.server.name` | `rain-commute-mcp` | MCP server name advertised to clients. |
| `spring.ai.mcp.server.version` | `0.1.0` | MCP server version advertised to clients. |

Set any of these via `src/main/resources/application.properties` (rebuild required), environment variables (e.g. `RAIN_COMMUTE_WEATHER_API_BASE_URL`, `RAIN_COMMUTE_HTTP_TIMEOUT`), `-D` system properties, or the external file described in [Personalizing it](#personalizing-it), which needs no rebuild. When the server is launched by an MCP client, environment variables have to be passed through the client's own `env` setting for that server (most clients don't forward your shell's environment); the external file avoids that.

## Testing

```bash
mvn clean verify
```

Runs the unit test suite and enforces 100% line and method coverage via JaCoCo (`mvn jacoco:report` output lands in `target/site/jacoco/index.html`). Branch coverage is checked against a pinned baseline rather than a ratio — see the comment on the `jacoco-maven-plugin` config in [pom.xml](pom.xml) for why (short version: `javac`'s synthetic `MatchException` handling around pattern-matching `switch` isn't reachable from real tests; [jacoco/jacoco#1514](https://github.com/jacoco/jacoco/issues/1514)).

## Project structure

```
src/main/java/com/rocommute/mcp/
├── RainCommuteMcpApplication.java   # Boot entry point (excluded from coverage — no testable logic)
├── WeatherClientConfig.java         # WebClient (weather + geocoding) + Clock beans
├── RainCommuteProperties.java       # Location shortcuts, default commute, HTTP timeout/retry settings
├── ResilientJsonFetcher.java        # GET + timeout + bounded retry with backoff, shared by both API clients
├── GeocodingClient.java             # Resolves a place name/address to coordinates, with disambiguation + caching
├── ExpiringLruCache.java            # Tiny TTL + LRU cache (no library) backing the geocoding cache
├── ForecastClient.java              # Fetches + parses the hourly forecast into a Forecast
├── Forecast.java                    # Hourly forecast model: arrival-hour rounding, lookups, rain heads-up
├── DepartureAdvisor.java            # Pure logic behind suggestDepartureTime
└── CommuteWeatherService.java       # The two @McpTools and their user-facing messages
```

See [AGENTS.md](AGENTS.md) for a deeper architectural overview, the stack's version-specific gotchas, and conventions for anyone (human or agent) picking this repo up cold.

## Tech stack

- Java 25
- Spring Boot 4.0.7 / Spring Framework 7
- Spring AI 2.0.0 (MCP Server Boot Starter, stdio transport)
- Jackson 3 (`tools.jackson.*` — not classic `com.fasterxml.jackson.*`)
- JUnit 5 + AssertJ + JaCoCo 0.8.14

## License

MIT — see [LICENSE](LICENSE). Third-party dependency licenses (all permissive: Apache-2.0, MIT, EPL-2.0) are listed in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
