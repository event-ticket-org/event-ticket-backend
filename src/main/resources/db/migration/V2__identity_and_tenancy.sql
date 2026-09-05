-- Identity, tenancy and the audit trail.  Knowledge base: requirements/001.
--
-- Tenancy note. Users and Organizations are deliberately NOT row-level-security
-- scoped: a User is a global identity that may hold Memberships in several
-- Organizations (KB invariant 2, requirements/001 criterion 11), and an
-- Organization is the tenant rather than a row inside one. RLS applies to tables
-- that belong to a tenant: membership and audit_entry here, and every domain
-- table added later.

CREATE TABLE app_user (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email          TEXT        NOT NULL,
    display_name   TEXT        NOT NULL,
    -- Null until the person sets one. An Owner may invite an address that has no
    -- account yet (requirements/001 criterion 8): the User row exists so the
    -- Membership has something to attach to, and registering completes it.
    password_hash  TEXT,
    email_verified BOOLEAN     NOT NULL DEFAULT FALSE,
    platform_admin BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Email is the login identifier and is compared case-insensitively.
CREATE UNIQUE INDEX app_user_email_key ON app_user (lower(email));

-- Statuses and roles are TEXT with a check constraint rather than Postgres ENUM
-- types. ALTER TYPE ... ADD VALUE cannot run inside a transaction, which makes
-- every future migration that adds a status awkward, and an enum buys nothing
-- here that a check constraint does not.

CREATE TABLE organization (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'PENDING_APPROVAL'
                    CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED')),
    decision_reason TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE membership (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    user_id         UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role            TEXT        NOT NULL CHECK (role IN ('OWNER', 'MANAGER', 'GATE_STAFF')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- KB invariant 2: at most one Membership per User per Organization.
    CONSTRAINT membership_unique_per_org UNIQUE (organization_id, user_id)
);
CREATE INDEX membership_user_idx ON membership (user_id);

-- Verification tokens are stored hashed: a leaked table must not be a set of
-- working verification links.
CREATE TABLE email_verification_token (
    token_hash  TEXT        PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);
CREATE INDEX email_verification_user_idx ON email_verification_token (user_id);

-- Refresh tokens are server-side and revocable; this table is what makes ADR-0005
-- work. Removing a Membership or changing a Role takes effect here, at refresh.
CREATE TABLE refresh_token (
    token_hash             TEXT        PRIMARY KEY,
    user_id                UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    active_organization_id UUID        REFERENCES organization (id) ON DELETE CASCADE,
    expires_at             TIMESTAMPTZ NOT NULL,
    revoked_at             TIMESTAMPTZ
);
CREATE INDEX refresh_token_user_idx ON refresh_token (user_id);

-- Append-only. KB invariant 23.
CREATE TABLE audit_entry (
    id              BIGSERIAL PRIMARY KEY,
    organization_id UUID        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    actor_user_id   UUID        REFERENCES app_user (id),
    action          TEXT        NOT NULL,
    subject         TEXT,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX audit_entry_org_idx ON audit_entry (organization_id, occurred_at DESC);

-- ---------------------------------------------------------------------------
-- Row-level security (knowledge base ADR-0004).
--
-- The tenant is carried in the transaction-local setting app.organization_id,
-- set by TenantAwareTransactionManager at transaction start. The second argument
-- to current_setting is missing_ok: outside a request there is no tenant, and
-- the policies then match nothing rather than raising.
--
-- FORCE is essential. Without it the table owner - which is the application's
-- own role - bypasses every policy silently, and the protection is theatre.
-- ---------------------------------------------------------------------------

CREATE FUNCTION current_organization_id() RETURNS UUID
LANGUAGE sql STABLE AS
'SELECT NULLIF(current_setting(''app.organization_id'', true), '''')::uuid';

CREATE FUNCTION current_app_user_id() RETURNS UUID
LANGUAGE sql STABLE AS
'SELECT NULLIF(current_setting(''app.user_id'', true), '''')::uuid';

ALTER TABLE membership  ENABLE ROW LEVEL SECURITY;
ALTER TABLE membership  FORCE  ROW LEVEL SECURITY;
ALTER TABLE audit_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_entry FORCE  ROW LEVEL SECURITY;

-- A Membership is visible within its Organization, and also to the User it
-- belongs to: GET /me lists a User's memberships across every Organization
-- (requirements/001 criterion 11), which an organization-only policy would hide.
CREATE POLICY membership_tenant_isolation ON membership
    USING (organization_id = current_organization_id()
           OR user_id = current_app_user_id())
    WITH CHECK (organization_id = current_organization_id());

CREATE POLICY audit_entry_tenant_isolation ON audit_entry
    USING (organization_id = current_organization_id())
    WITH CHECK (organization_id = current_organization_id());
