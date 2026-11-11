package ru.urfu.alphamatcher.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashSet;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Opt-in only: the caller must supply a fresh owned MySQL server, never a working database. */
@EnabledIfEnvironmentVariable(named = "ALPHA_MATCHER_MIGRATION_SMOKE_URL", matches = ".+")
class FlywayMigrationSmokeTest {
    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing owned smoke setting: " + name);
        return value;
    }

    private ApplicationContextRunner application(String url) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class))
                .withUserConfiguration(FlywayMigrationConfiguration.class)
                .withPropertyValues("spring.profiles.active=migration-smoke", "spring.datasource.url=" + url,
                        "spring.datasource.username=migration_smoke", "spring.datasource.password=" + env("ALPHA_MATCHER_MIGRATION_SMOKE_PASSWORD"),
                        "spring.datasource.hikari.maximum-pool-size=2");
    }

    @Test void freshMigrationValidationAndWrongCatalogLeaveOtherSchemasUntouched() throws Exception {
        String url = env("ALPHA_MATCHER_MIGRATION_SMOKE_URL");
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/alpha_matcher\\?.*"));
        try (Connection admin = DriverManager.getConnection(url, "migration_smoke_admin", env("ALPHA_MATCHER_MIGRATION_SMOKE_ROOT_PASSWORD"))) {
            try (var statement = admin.createStatement(); var row = statement.executeQuery("SELECT @@datadir,@@server_uuid")) {
                assertTrue(row.next());
                Path expected = Path.of(env("ALPHA_MATCHER_MIGRATION_SMOKE_DATADIR")).toRealPath();
                assertTrue(expected.getParent().getFileName().toString().startsWith("alpha-matcher-flyway-"));
                assertEquals(expected, Path.of(row.getString(1)).toRealPath());
                assertEquals(env("ALPHA_MATCHER_MIGRATION_SMOKE_UUID"), row.getString(2));
            }
            Set<String> schemasBefore = values(admin, "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA");
            assertFalse(schemasBefore.contains("alpha_matcher_dev"));
            assertEquals(Set.of(), tables(admin, "alpha_matcher"));
            assertEquals(Set.of("marker"), tables(admin, "unrelated_probe"));

            application(url).run(context -> {
                assertNull(context.getStartupFailure());
                Flyway flyway = context.getBean(Flyway.class);
                assertTrue(flyway.validateWithResult().validationSuccessful);
                assertEquals(1, flyway.info().applied().length);
                assertEquals("1", flyway.info().applied()[0].getVersion().toString());
            });
            assertEquals(Set.of("client_type", "document_type", "legal_form", "contract_type", "contract_party_role",
                    "operation_party_role", "operation_status", "client", "contract", "contract_party", "operation",
                    "operation_party", "operation_contract_candidate", "flyway_schema_history"), tables(admin, "alpha_matcher"));
            assertEquals(schemasBefore, values(admin, "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA"));
            assertEquals(Set.of("keep"), values(admin, "SELECT value FROM unrelated_probe.marker"));

            try (var statement = admin.createStatement()) {
                statement.executeUpdate("INSERT INTO alpha_matcher.legal_form(code,name) VALUES ('KEEP','Existing row')");
            }
            application(url).run(context -> {
                assertNull(context.getStartupFailure());
                assertTrue(context.getBean(Flyway.class).validateWithResult().validationSuccessful);
                assertEquals(1, context.getBean(Flyway.class).info().applied().length);
            });
            assertEquals(Set.of("Existing row"), values(admin, "SELECT name FROM alpha_matcher.legal_form WHERE code='KEEP'"));

            // A deliberately mismatched test catalog, created only after ownership checks.
            try (var statement = admin.createStatement()) {
                statement.executeUpdate("CREATE DATABASE alpha_matcher_dev");
                statement.executeUpdate("GRANT SELECT ON alpha_matcher_dev.* TO 'migration_smoke'@'127.0.0.1'");
            }
            Set<String> schemasWithProbe = values(admin, "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA");
            application(url.replace("/alpha_matcher?", "/alpha_matcher_dev?")).run(context -> {
                Throwable failure = context.getStartupFailure();
                assertNotNull(failure);
                while (failure.getCause() != null) failure = failure.getCause();
                assertInstanceOf(IllegalStateException.class, failure);
                assertTrue(failure.getMessage().contains("Flyway requires the alpha_matcher catalog"));
            });
            assertEquals(Set.of(), tables(admin, "alpha_matcher_dev"));
            assertEquals(schemasWithProbe, values(admin, "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA"));
            assertEquals(Set.of("marker"), tables(admin, "unrelated_probe"));
            assertEquals(Set.of("keep"), values(admin, "SELECT value FROM unrelated_probe.marker"));
        }
    }

    private static Set<String> tables(Connection connection, String schema) throws Exception {
        return values(connection, "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='" + schema + "'");
    }

    private static Set<String> values(Connection connection, String sql) throws Exception {
        Set<String> values = new HashSet<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            while (rows.next()) values.add(rows.getString(1));
        }
        return values;
    }
}
