package com.eventticket.shared;

import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;

/**
 * Publishes the current tenant into the database session as soon as a transaction begins,
 * so that the row-level security policies in {@code V2__identity_and_tenancy.sql} have
 * something to match against.
 *
 * <p>Doing this at transaction start rather than in application code is the whole point of
 * knowledge base ADR-0004: an isolation rule that has to be remembered on every query is one
 * that will eventually be forgotten on exactly one query, and application-layer filtering
 * fails silently when it is.
 *
 * <p>{@code set_config(name, value, true)} is the function form of {@code SET LOCAL}. It is
 * used rather than the statement form because {@code SET LOCAL} cannot take a bind
 * parameter, and building that string by concatenation would be an injection site in the one
 * place we can least afford one. Being transaction-local, the setting is discarded on commit
 * or rollback, so a pooled connection never carries one request's tenant into the next.
 */
public class TenantAwareTransactionManager extends JpaTransactionManager {

    public TenantAwareTransactionManager(EntityManagerFactory emf, DataSource dataSource) {
        super(emf);
        setDataSource(dataSource);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);

        DataSource dataSource = getDataSource();
        if (dataSource == null) {
            return;
        }
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            try (Statement role = connection.createStatement()) {
                role.execute(TenantSql.ASSUME_RUNTIME_ROLE);
            }
            try (PreparedStatement statement = connection.prepareStatement(TenantSql.SET_TENANT)) {
                statement.setString(1, TenantContext.organizationIdAsSetting());
                statement.setString(2, TenantContext.userIdAsSetting());
                statement.execute();
            }
        } catch (SQLException e) {
            throw new CannotCreateTransactionException("Could not set the tenant for this transaction", e);
        }
    }
}
