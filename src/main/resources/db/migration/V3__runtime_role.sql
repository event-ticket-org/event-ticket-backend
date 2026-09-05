-- A non-superuser role for the application to run as.
--
-- Row-level security has one absolute exemption: a superuser bypasses every policy,
-- and FORCE does not change that. The connection user owns the schema and runs
-- migrations, and in development and test environments it is a superuser - so the
-- policies added in V2 were silently doing nothing.
--
-- The fix is not to stop being a superuser (migrations need those rights) but to
-- stop *acting* as one. TenantAwareTransactionManager issues SET LOCAL ROLE at the
-- start of every transaction, so all application queries run as this unprivileged
-- role and the policies apply. Being transaction-local, it reverts on commit, and
-- Flyway - which runs outside that transaction manager - keeps its own rights.

DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'eventticket_app') THEN
        CREATE ROLE eventticket_app NOLOGIN NOSUPERUSER NOBYPASSRLS;
    END IF;
END
$do$;

-- The connecting user must be a member of the role to SET ROLE to it.
GRANT eventticket_app TO CURRENT_USER;

GRANT USAGE ON SCHEMA public TO eventticket_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO eventticket_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO eventticket_app;

-- Tables added by later migrations inherit the same grants, so a new domain table is
-- reachable by the application without anyone remembering to grant it.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO eventticket_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO eventticket_app;
