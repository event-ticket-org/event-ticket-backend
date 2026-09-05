package com.eventticket.shared;

/**
 * The one statement that tells Postgres who the caller is.
 *
 * <p>{@code set_config(name, value, true)} is the function form of {@code SET LOCAL}, used
 * because {@code SET LOCAL} cannot take a bind parameter and building that string by
 * concatenation would put an injection site in the one place we can least afford one. Being
 * transaction-local, the setting is discarded on commit or rollback, so a pooled connection
 * never carries one request's tenant into the next.
 */
final class TenantSql {

    /**
     * Drops to the unprivileged runtime role for the rest of the transaction. Without this
     * the connection user is a superuser in development and test, and superusers bypass every
     * row-level security policy - FORCE included. It is not parameterised because a role name
     * cannot be a bind parameter; the value is a constant defined in {@code V3__runtime_role.sql}
     * and never comes from a request.
     */
    static final String ASSUME_RUNTIME_ROLE = "set local role eventticket_app";

    static final String SET_TENANT =
            "select set_config('app.organization_id', ?, true), set_config('app.user_id', ?, true)";

    private TenantSql() {}
}
