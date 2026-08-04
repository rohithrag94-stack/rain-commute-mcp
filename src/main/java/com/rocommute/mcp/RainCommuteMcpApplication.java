package com.rocommute.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Boot entry point for the rain-commute MCP server. Holds no business logic of its own — see
 * {@link CommuteWeatherService} for the tool implementation — so it's excluded from the JaCoCo
 * coverage gate (see {@code pom.xml}); {@link #main} starts a real stdio MCP server that blocks
 * reading standard input, which isn't something a unit test can safely invoke.
 */
@SpringBootApplication
public class RainCommuteMcpApplication {
    public static void main(String[] args) {
        SpringApplication.run(RainCommuteMcpApplication.class, args);
    }
}
