package com.eventticket.shared.persistence.routing;

import com.eventticket.shared.persistence.freshness.InMemoryLastWriteStore;
import com.eventticket.shared.persistence.freshness.LastWriteStore;
import com.eventticket.shared.persistence.freshness.ReplicaFreshness;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

/**
 * Wires read routing, when and only when a replica has been configured.
 *
 * <h2>Absent property, absent feature</h2>
 *
 * <p>Everything here is conditional on {@code app.datasource.replica.url}. With it unset - a
 * fresh clone, the test suite, the single-node development stack - not one bean in this class
 * exists, Spring Boot autoconfigures its usual {@code DataSource}, and the application behaves
 * exactly as it did before this package was written. That is the property the regression test
 * pins.
 *
 * <h2>Exactly one DataSource bean, and this is not a stylistic choice</h2>
 *
 * <p>Boot's {@code DataSourceAutoConfiguration} backs off on
 * {@code @ConditionalOnMissingBean(DataSource.class)}, and {@code JdbcTemplateAutoConfiguration}
 * needs {@code @ConditionalOnSingleCandidate(DataSource.class)}. Three places inject by type -
 * {@code PersistenceConfig}, {@code TenantPublisher}, and {@code ApiTest}'s {@code JdbcTemplate},
 * which is the base class of every HTTP test.
 *
 * <p>So the primary and replica are built as plain objects inside the bean method and are
 * <strong>never registered as beans</strong>. Two unqualified {@code DataSource} beans would
 * fail the context with {@code NoUniqueBeanDefinitionException} and take the whole test suite
 * with them.
 *
 * <h2>Why the lazy proxy is mandatory</h2>
 *
 * <p>{@code AbstractRoutingDataSource} chooses its target when {@code getConnection()} is called,
 * and {@code TenantAwareTransactionManager.doBegin} asks for a connection immediately in order to
 * issue {@code SET LOCAL ROLE}. Without {@link LazyConnectionDataSourceProxy} the routing key
 * would be read before the transaction's read-only flag is established, and every single
 * transaction would land on the primary.
 *
 * <p><strong>That failure is silent</strong> - everything works, nothing errors, the replica just
 * never receives a query - which is exactly why the end-to-end check has to assert that a read
 * really does arrive at the replica rather than assuming it from the configuration.
 *
 * <p>The proxy defers the physical connection to the first statement, by which point the flag is
 * set. {@code TenantPublisher} reaches the same connection through the same proxy, so
 * {@code SET LOCAL ROLE} and {@code set_config} land on whichever node serves the query - and
 * row-level security has been verified to enforce correctly on a standby, all ten policies.
 */
@Configuration
@ConditionalOnProperty("app.datasource.replica.url")
public class ReplicaRoutingConfig {

    /**
     * The single {@code DataSource} the rest of the application sees.
     *
     * <p>The replica reuses the primary's username and password, and that is a property of
     * physical replication rather than a shortcut: a streaming standby is a byte-for-byte copy,
     * so its roles and their passwords are the same ones. A separate credential would be a
     * second thing to rotate for no gain - and would have had to be added to
     * {@code DeploymentConfigurationTest}'s list.
     */
    @Bean
    public DataSource dataSource(
            @Value("${spring.datasource.url}") String primaryUrl,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password,
            @Value("${app.datasource.replica.url}") String replicaUrl,
            ReplicaFreshness freshness) {

        DataSource primary = build(primaryUrl, username, password);
        DataSource replica = build(replicaUrl, username, password);
        return new LazyConnectionDataSourceProxy(
                new ReadWriteRoutingDataSource(primary, replica, freshness));
    }

    /**
     * The freshness poller needs the replica directly, not through the router - asking the
     * router for a connection in order to decide what the router should do is circular.
     */
    @Bean
    public ReplicaFreshness replicaFreshness(
            @Value("${app.datasource.replica.url}") String replicaUrl,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password,
            LastWriteStore lastWrites) {
        return new ReplicaFreshness(build(replicaUrl, username, password), lastWrites);
    }

    @Bean
    public LastWriteStore lastWriteStore() {
        return new InMemoryLastWriteStore();
    }

    private static DataSource build(String url, String username, String password) {
        return DataSourceBuilder.create().url(url).username(username).password(password).build();
    }
}
