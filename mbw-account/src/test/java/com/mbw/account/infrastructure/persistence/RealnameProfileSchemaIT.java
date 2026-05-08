package com.mbw.account.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Schema-level IT for V12 (realname-verification spec T0).
 *
 * <p>Boots a real {@code postgres:16-alpine} container, lets Flyway run all
 * migrations including V12, then queries {@code information_schema} /
 * {@code pg_indexes} to verify the DDL shape required by plan.md §
 * "数据模型变更（Flyway V12）". No domain / JPA classes involved — this is a
 * pure DDL assertion suite that exists so T0 can RED-GREEN independently of
 * downstream T7 entity mapping.
 *
 * <p>Per plan.md amend: {@code updated_at} is maintained at the application
 * layer (JPA {@code @PreUpdate}) and there is intentionally <b>no</b>
 * trigger; this IT therefore does not assert one.
 */
@SpringBootTest(classes = RealnameProfileSchemaIT.TestApp.class)
@Testcontainers
class RealnameProfileSchemaIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void dataSourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration/account");
        registry.add("spring.flyway.schemas", () -> "account");
        registry.add("spring.flyway.default-schema", () -> "account");
        registry.add("spring.flyway.create-schemas", () -> "true");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void v12_creates_realname_profile_table_with_13_columns() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT column_name, data_type, character_maximum_length, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'account' AND table_name = 'realname_profile'
                ORDER BY ordinal_position
                """);

        // 13 columns, exact order
        assertThat(rows).hasSize(13);
        assertThat(rows)
                .extracting(r -> r.get("column_name"))
                .containsExactly(
                        "id",
                        "account_id",
                        "status",
                        "real_name_enc",
                        "id_card_no_enc",
                        "id_card_hash",
                        "provider_biz_id",
                        "verified_at",
                        "failed_reason",
                        "failed_at",
                        "retry_count_24h",
                        "created_at",
                        "updated_at");

        Map<String, Map<String, Object>> byName =
                rows.stream().collect(java.util.stream.Collectors.toMap(r -> (String) r.get("column_name"), r -> r));

        // Critical types — guards against accidental drift (e.g. TEXT vs VARCHAR, BIGINT vs INT)
        assertThat(byName.get("id").get("data_type")).isEqualTo("bigint");
        assertThat(byName.get("account_id").get("data_type")).isEqualTo("bigint");
        assertThat(byName.get("account_id").get("is_nullable")).isEqualTo("NO");

        assertThat(byName.get("status").get("data_type")).isEqualTo("character varying");
        assertThat(byName.get("status").get("character_maximum_length")).isEqualTo(16);
        assertThat(byName.get("status").get("is_nullable")).isEqualTo("NO");

        assertThat(byName.get("real_name_enc").get("data_type")).isEqualTo("bytea");
        assertThat(byName.get("real_name_enc").get("is_nullable")).isEqualTo("YES");

        assertThat(byName.get("id_card_no_enc").get("data_type")).isEqualTo("bytea");

        assertThat(byName.get("id_card_hash").get("data_type")).isEqualTo("character");
        assertThat(byName.get("id_card_hash").get("character_maximum_length")).isEqualTo(64);
        assertThat(byName.get("id_card_hash").get("is_nullable")).isEqualTo("YES");

        assertThat(byName.get("provider_biz_id").get("data_type")).isEqualTo("character varying");
        assertThat(byName.get("provider_biz_id").get("character_maximum_length"))
                .isEqualTo(64);

        assertThat(byName.get("verified_at").get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(byName.get("failed_reason").get("data_type")).isEqualTo("character varying");
        assertThat(byName.get("failed_reason").get("character_maximum_length")).isEqualTo(32);
        assertThat(byName.get("failed_at").get("data_type")).isEqualTo("timestamp with time zone");

        assertThat(byName.get("retry_count_24h").get("data_type")).isEqualTo("integer");
        assertThat(byName.get("retry_count_24h").get("is_nullable")).isEqualTo("NO");

        assertThat(byName.get("created_at").get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(byName.get("created_at").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("updated_at").get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(byName.get("updated_at").get("is_nullable")).isEqualTo("NO");
    }

    @Test
    void v12_creates_partial_unique_index_on_id_card_hash() {
        // pg_indexes.indexdef carries the full CREATE INDEX statement (incl. WHERE clause)
        String indexdef = jdbc.queryForObject(
                """
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'account'
                  AND tablename = 'realname_profile'
                  AND indexname = 'uk_realname_profile_id_card_hash'
                """,
                String.class);

        assertThat(indexdef).isNotNull();
        // Must be UNIQUE
        assertThat(indexdef).containsIgnoringCase("CREATE UNIQUE INDEX");
        // Must be partial (UNVERIFIED rows have NULL id_card_hash and must be allowed to coexist)
        assertThat(indexdef).containsIgnoringCase("WHERE").containsIgnoringCase("id_card_hash IS NOT NULL");
    }

    @Test
    void v12_creates_index_on_provider_biz_id() {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM pg_indexes
                WHERE schemaname = 'account'
                  AND tablename = 'realname_profile'
                  AND indexname = 'idx_realname_profile_provider_biz_id'
                """,
                Integer.class);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void v12_chk_realname_status_rejects_unknown_value() {
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO account.realname_profile (account_id, status) VALUES (?, ?)", 9999L, "WAT"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Minimal Spring Boot context — only DataSource + Flyway + JdbcTemplate.
     * No JPA / Redis / Web. Mirror of {@link AccountSmsCodeRepositoryImplIT.TestApp}'s
     * "exclude Redis" pattern.
     */
    @Configuration
    @EnableAutoConfiguration(exclude = {RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class})
    static class TestApp {}
}
