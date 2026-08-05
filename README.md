# rain-commute-mcp

An [MCP](https://modelcontextprotocol.io) server that checks whether rain is expected around the time you'd arrive at a destination, given how long your commute takes. Ask your MCP client "will it rain by the time I get to Bengaluru" and it looks up the forecast for the hour you'll actually land in — not just "right now."

## How it works

The server exposes a single MCP tool, `checkRainOnCommute`, backed by the free [Open-Meteo](https://open-meteo.com/) forecast and geocoding APIs:

1. Takes a destination as a plain place name or address (e.g. `"Bengaluru"`, `"Eiffel Tower, Paris"`) and a commute duration in minutes — no coordinates required.
2. Geocodes the destination to coordinates.
3. Computes your arrival time (now + commute duration, floored to the hour) **in the destination's own local timezone**, from its forecast response — not the server's timezone, so results are correct no matter where the user or the server process happens to be.
4. Fetches the hourly forecast for that location and reads off precipitation probability and rain amount for the arrival hour.
5. Returns a plain-language verdict — dry, or grab an umbrella.

## Prerequisites

- Java 25 ([Eclipse Temurin](https://adoptium.net/) or any JDK 25 distribution)
- Maven 3.9+
- An MCP-compatible client (e.g. [MCP Inspector](https://github.com/modelcontextprotocol/inspector), Claude Desktop)

## Build

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

This opens a local web UI where you can call `checkRainOnCommute` directly and inspect the raw request/response.

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

Restart Claude Desktop and the `checkRainOnCommute` tool becomes available in conversation.

## Configuration

| Property | Default | Description |
|---|---|---|
| `rain-commute.weather-api.base-url` | `https://api.open-meteo.com` | Base URL of the weather forecast API. Override to point at a mock/staging endpoint. |
| `rain-commute.geocoding-api.base-url` | `https://geocoding-api.open-meteo.com` | Base URL of the place-name geocoding API. Override to point at a mock/staging endpoint. |
| `spring.ai.mcp.server.name` | `rain-commute-mcp` | MCP server name advertised to clients. |
| `spring.ai.mcp.server.version` | `0.1.0` | MCP server version advertised to clients. |

Set any of these via `src/main/resources/application.properties`, environment variables (e.g. `RAIN_COMMUTE_WEATHER_API_BASE_URL`), or `-D` system properties.

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
├── GeocodingClient.java             # Resolves a place name/address to coordinates
└── CommuteWeatherService.java       # The @McpTool and its forecast logic
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
