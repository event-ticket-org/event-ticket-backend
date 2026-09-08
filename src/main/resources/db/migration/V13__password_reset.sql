-- requirements/001 criteria 17-21: a way back into an account.
--
-- Deliberately a second table rather than a column on email_verification_token or a shared
-- "token" table with a kind. The two links do different things and are held to different
-- rules - a day against an hour, and one of them opens an account that already holds orders -
-- and a shared table would make the lifetime a property of a row that any caller could read
-- as the other kind.
--
-- Stored hashed, like every other token here: a leaked table must not be a set of working
-- links, and this link is a bearer credential to a whole account (nfr.md, Account recovery).
--
-- No row-level security, and no organization_id. A reset happens before anybody is
-- authenticated - the whole point is that they cannot sign in - so there is no tenant to scope
-- it to and current_app_user_id() is null. That is the same reason email_verification_token
-- has none. The table is reachable only through the two use cases that own it, and neither
-- takes a user id from a request.
CREATE TABLE password_reset_token (
    token_hash  TEXT        PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);

-- Criterion 21 ends outstanding links for a User on every new request, so the lookup by user
-- is on the write path and not just for cleanup.
CREATE INDEX password_reset_user_idx ON password_reset_token (user_id);
