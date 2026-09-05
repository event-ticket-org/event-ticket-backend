package com.eventticket.shared.tenancy;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
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
    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf, DataSource dataSource) {
        return new TenantAwareTransactionManager(emf, dataSource);
    }
}
