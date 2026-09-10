package com.eventticket.shared.tenancy;

import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class PersistenceConfig {

    /**
     * Where {@code TenantAwareTransactionManager} used to be.
     *
     * <p>That class existed to do one thing this one cannot: at the start of every transaction
     * it issued {@code SET LOCAL ROLE eventticket_app} and published the tenant with
     * {@code set_config}, so that Postgres itself narrowed every statement that followed.
     * Registering it as the <em>only</em> transaction manager was the point - a second,
     * tenant-blind manager would have been a way to bypass row-level security without noticing.
     *
     * <p>There is nothing to publish here and nothing that would read it, so this is Spring
     * Data's own manager unmodified, and the tenant is applied per query instead
     * (see {@link TenantScope}).
     *
     * <p><strong>It requires a replica set even for one node.</strong> MongoDB implements
     * transactions on the oplog, and a standalone {@code mongod} has none - it does not run
     * them slowly, it refuses them. Postgres has been able to begin a transaction since before
     * replication existed, so this is a genuine new deployment constraint rather than a
     * configuration detail.
     */
    @Bean
    public PlatformTransactionManager transactionManager(MongoDatabaseFactory databaseFactory) {
        return new MongoTransactionManager(databaseFactory);
    }
}
