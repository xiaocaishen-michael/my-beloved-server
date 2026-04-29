package com.mbw.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mbw.MbwApplication;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Pins the live {@code /v3/api-docs} payload to a checked-in snapshot
 * (T19 — contract-drift gate per spec.md). Any change to a controller
 * signature, request/response schema or endpoint set surfaces as a
 * failing assertion before front-end consumers regenerate their
 * client.
 *
 * <p>Regeneration after an intentional API change:
 * {@code ./mvnw test -pl mbw-app -Dtest=OpenApiSnapshotIT
 * -Dopenapi.snapshot.update=true}.
 *
 * <p>The non-deterministic {@code info.version} field (mirrors the
 * release-please pom version) is stripped before comparison so the
 * snapshot survives version bumps.
 */
@SpringBootTest(classes = MbwApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OpenApiSnapshotIT {

    private static final Path SNAPSHOT_PATH = Paths.get("src/test/resources/openapi/api-docs.snapshot.json");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.schemas", () -> "account");
        registry.add("spring.flyway.default-schema", () -> "account");
        registry.add("spring.flyway.create-schemas", () -> "true");
        registry.add("spring.modulith.events.jdbc-schema-initialization.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("mbw.auth.jwt.secret", () -> "test-secret-32-bytes-or-more-of-test-entropy-please-do-not-use");
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    void api_docs_match_snapshot() throws Exception {
        String live = rest.getForObject("/v3/api-docs", String.class);
        ObjectNode normalized = (ObjectNode) MAPPER.readTree(live);
        if (normalized.has("info") && normalized.get("info").has("version")) {
            ((ObjectNode) normalized.get("info")).remove("version");
        }
        // servers[].url contains the test's random port; strip to keep
        // the snapshot stable across runs.
        normalized.remove("servers");
        String actual = MAPPER.writeValueAsString(normalized);

        boolean updateMode = Boolean.parseBoolean(System.getProperty("openapi.snapshot.update", "false"));
        if (updateMode || !Files.exists(SNAPSHOT_PATH)) {
            Files.createDirectories(SNAPSHOT_PATH.getParent());
            Files.writeString(SNAPSHOT_PATH, actual + "\n", StandardCharsets.UTF_8);
            return;
        }

        String expected =
                Files.readString(SNAPSHOT_PATH, StandardCharsets.UTF_8).trim();
        assertThat(actual.trim())
                .as("OpenAPI spec drifted; if intentional, regenerate via -Dopenapi.snapshot.update=true")
                .isEqualTo(expected);
    }
}
