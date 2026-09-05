package com.eventticket.shared.tenancy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;

/**
 * Publishes the tenant into the database session of a transaction that has already begun.
 *
 * <p>Normally {@link TenantAwareTransactionManager} does this once, at transaction start, and
 * nothing else needs to. The exception is a use case that establishes who the caller is as
 * part of its own work: token refresh authenticates from a refresh token and only then knows
 * the User, by which point the transaction is open and its tenant setting is empty. Setting
 * the ThreadLocal alone would not help - Postgres was told the tenant at begin, and the
 * policies read the database setting, not the JVM.
 *
 * <p>Deliberately narrow. If this appears in an ordinary use case, the tenant should have
 * come from the access token instead, and that use case is doing authentication it has no
 * business doing.
 */
@Component
public class TenantPublisher {

    private final DataSource dataSource;

    public TenantPublisher(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void adopt(UUID userId, UUID organizationId) {
        TenantContext.set(userId, organizationId);
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement statement = connection.prepareStatement(TenantSql.SET_TENANT)) {
            statement.setString(1, TenantContext.organizationIdAsSetting());
            statement.setString(2, TenantContext.userIdAsSetting());
            statement.execute();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not publish the tenant to the open transaction", e);
        }
    }
}
