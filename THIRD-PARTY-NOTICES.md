# Third-Party Notices

This project is licensed under the [MIT License](LICENSE). It depends on the following
third-party libraries, all under permissive licenses compatible with that choice. Licenses were
confirmed against each dependency's published Maven POM metadata (`<licenses>` block) in this
project's local repository, except where noted.

## Runtime dependencies (bundled into `rain-commute-mcp-*.jar`)

These are packaged into the executable jar produced by `mvn package`/`install`, so their license
terms travel with the built artifact.

| Dependency | License |
|---|---|
| Spring Boot / Spring Framework | Apache License 2.0 |
| Spring AI (MCP Server Boot Starter) | Apache License 2.0 |
| MCP Java SDK (`io.modelcontextprotocol.sdk`) | MIT License |
| Jackson 3 (`tools.jackson.*`) | Apache License 2.0 |
| Project Reactor (`reactor-core`) | Apache License 2.0 |
| Reactor Netty / Netty | Apache License 2.0 |
| victools JSON Schema Generator (used by the MCP SDK for tool schemas) | Apache License 2.0 |

## Build/test-only dependencies (not bundled into the shipped jar)

| Dependency | License |
|---|---|
| JUnit 5 (Jupiter) | Eclipse Public License 2.0 |
| AssertJ | Apache License 2.0 |
| Mockito (pulled in transitively by `spring-boot-starter-test`; unused directly) | MIT License |
| JaCoCo (coverage tooling, Maven plugin) | Eclipse Public License 2.0 |

None of the above are copyleft in a way that affects this project's MIT license: Apache-2.0, MIT,
and EPL-2.0 are all permissive and impose no obligation to relicense code that merely depends on
them (as opposed to modifying and redistributing the library itself).
