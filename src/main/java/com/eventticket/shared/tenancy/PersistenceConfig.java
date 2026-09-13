package com.eventticket.shared.tenancy;

import com.eventticket.shared.persistence.LastWriteStore;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class PersistenceConfig {

    /**
     * Replaces Spring Boot's default transaction manager so that every transaction in the
     * application carries a tenant. Registering it as the only {@link PlatformTransactionManager}
     * is deliberate: a second, tenant-blind manager would be a way to bypass row-level
     * security without noticing.
     */
    /**
     * {@code lastWrites} is an {@link ObjectProvider} because read routing is optional. With no
     * replica configured the bean does not exist, the transaction manager is handed null, and no
     * write pays the extra round trip to record a log position it would never be asked for.
     */
    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf, DataSource dataSource,
                                                         ObjectProvider<LastWriteStore> lastWrites) {
        return new TenantAwareTransactionManager(emf, dataSource, lastWrites.getIfAvailable());
    }
}
