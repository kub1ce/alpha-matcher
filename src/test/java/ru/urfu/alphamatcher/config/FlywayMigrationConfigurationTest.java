package ru.urfu.alphamatcher.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import ru.urfu.alphamatcher.Application;
import ru.urfu.alphamatcher.demo.DemoDataService;

class FlywayMigrationConfigurationTest {
    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final Flyway flyway = mock(Flyway.class);
    private final Configuration configuration = mock(Configuration.class);
    private final FlywayMigrationStrategy strategy = new FlywayMigrationConfiguration().checkedSchemaMigration(dataSource);

    private void setup(String catalog) throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn(catalog);
        when(flyway.getConfiguration()).thenReturn(configuration);
        when(configuration.getDataSource()).thenReturn(dataSource);
        when(configuration.getSchemas()).thenReturn(new String[0]);
    }

    @Test void matchingCatalogMigratesAfterClosingGuardConnection() throws SQLException {
        setup("alpha_matcher");
        strategy.migrate(flyway);
        var order = inOrder(connection, flyway);
        order.verify(connection).close();
        order.verify(flyway).migrate();
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"alpha_matcher_dev", "unrelated"})
    void wrongOrMissingCatalogIsRejectedBeforeMigration(String catalog) throws SQLException {
        setup(catalog);
        assertThrows(IllegalStateException.class, () -> strategy.migrate(flyway));
        verify(flyway, never()).migrate();
        verify(connection).close();
        verify(connection, never()).createStatement();
    }

    @Test void explicitForeignDefaultSchemaIsRejectedBeforeConnecting() throws SQLException {
        setup("alpha_matcher");
        when(configuration.getDefaultSchema()).thenReturn("unrelated");
        assertThrows(IllegalStateException.class, () -> strategy.migrate(flyway));
        verify(dataSource, never()).getConnection();
        verify(flyway, never()).migrate();
    }

    @Test void explicitForeignSchemasAreRejectedBeforeConnecting() throws SQLException {
        setup("alpha_matcher");
        when(configuration.getSchemas()).thenReturn(new String[]{"alpha_matcher", "unrelated"});
        assertThrows(IllegalStateException.class, () -> strategy.migrate(flyway));
        verify(dataSource, never()).getConnection();
        verify(flyway, never()).migrate();
    }

    @Test void separateFlywayDataSourceMustAlsoSelectExpectedCatalog() throws SQLException {
        setup("alpha_matcher");
        DataSource migrationSource = mock(DataSource.class);
        Connection migrationConnection = mock(Connection.class);
        when(configuration.getDataSource()).thenReturn(migrationSource);
        when(migrationSource.getConnection()).thenReturn(migrationConnection);
        when(migrationConnection.getCatalog()).thenReturn("unrelated");
        assertThrows(IllegalStateException.class, () -> strategy.migrate(flyway));
        verify(migrationConnection).close();
        verify(flyway, never()).migrate();
    }

    @Test void connectionFailureIsPreservedAndDoesNotMigrate() throws SQLException {
        setup("alpha_matcher");
        SQLException failure = new SQLException("fixture connection failure");
        when(dataSource.getConnection()).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> strategy.migrate(flyway)).getCause());
        verify(flyway, never()).migrate();
    }

    @Test void defaultConfigurationSelectsLegacySchemaWithoutAutomaticSchemaCreation() throws Exception {
        Properties properties = new Properties();
        try (var input = getClass().getResourceAsStream("/application.properties")) { properties.load(input); }
        var sources = new MutablePropertySources();
        sources.addFirst(new PropertiesPropertySource("application", properties));
        var resolver = new PropertySourcesPropertyResolver(sources);
        assertEquals("jdbc:mysql://localhost:3306/alpha_matcher", resolver.getProperty("spring.datasource.url"));
        assertEquals("false", resolver.getProperty("spring.flyway.create-schemas"));
    }

    @Test void demoStartsWithoutDataSourceFlywayOrDatabaseConnection() {
        try (var context = new SpringApplicationBuilder(Application.class).web(WebApplicationType.SERVLET).run(
                "--spring.profiles.active=demo", "--spring.main.banner-mode=off", "--logging.level.root=ERROR",
                "--DB_URL=jdbc:mysql://127.0.0.1:1/must_not_connect", "--server.port=0",
                "--vaadin.productionMode=true", "--vaadin.launch-browser=false")) {
            assertTrue(context.getBeansOfType(DataSource.class).isEmpty());
            assertTrue(context.getBeansOfType(Flyway.class).isEmpty());
            assertTrue(context.getBeansOfType(FlywayMigrationStrategy.class).isEmpty());
            assertNotNull(context.getBean(DemoDataService.class));
        }
    }
}
