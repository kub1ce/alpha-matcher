package ru.urfu.alphamatcher.config;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** V1 is immutable and explicitly uses alpha_matcher. Check before Flyway writes anything. */
@Configuration(proxyBeanMethods = false)
@Profile("!demo")
public class FlywayMigrationConfiguration {
    private static final String SCHEMA = "alpha_matcher";

    @Bean
    FlywayMigrationStrategy checkedSchemaMigration(DataSource applicationDataSource) {
        return flyway -> {
            var configuration = flyway.getConfiguration();
            if (configuration.getDefaultSchema() != null) requireSchema(configuration.getDefaultSchema());
            for (String schema : configuration.getSchemas()) requireSchema(schema);
            checkCatalog(applicationDataSource);
            if (configuration.getDataSource() != applicationDataSource) checkCatalog(configuration.getDataSource());
            flyway.migrate();
        };
    }

    private static void checkCatalog(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            requireSchema(connection.getCatalog());
        } catch (SQLException error) {
            throw new IllegalStateException("Cannot verify the MySQL catalog before Flyway migration", error);
        }
    }

    private static void requireSchema(String schema) {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalStateException("Flyway requires the alpha_matcher catalog: immutable V1 uses it explicitly. "
                    + "Check DB_URL and Flyway schema settings; inspect existing tables and migration history before changing them.");
        }
    }
}
