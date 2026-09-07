-- requirements/001 criterion 14: an approval says who is accountable for the Organization.
--
-- A platform administrator is a member of nothing. The membership policy admits rows for the
-- caller's active Organization or their own Memberships, and an administrator has neither -
-- so reading the owners of a queue of Organizations returns nothing at all, which is how this
-- was found: the queue came back with every Organization and `owners: []` beside each one.
--
-- SECURITY DEFINER rather than a wider policy, which is the same answer this schema already
-- gives for a buyer taking a hold on an Organization they are not a member of (V5). Widening
-- membership_tenant_isolation to admit administrators would grant them every Membership in
-- the system for every purpose, forever, to serve one screen; a function grants exactly this
-- read and nothing else, and the grant is visible in one place.
--
-- Owners only. An administrator deciding whether to endorse an Organization needs the people
-- accountable for it, not its gate staff - and a function that returned every Membership would
-- be a wider policy wearing a different hat.
--
-- `search_path` is pinned, as it is on every other definer function here: without it the
-- function resolves `membership` against whatever the caller's search path says, which is a
-- way to make a privileged function read a table somebody else chose.
CREATE FUNCTION organization_owner_ids(organization_ids UUID[])
RETURNS TABLE (organization_id UUID, user_id UUID)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS
$$
    SELECT m.organization_id, m.user_id
    FROM membership m
    WHERE m.organization_id = ANY (organization_ids)
      AND m.role = 'OWNER'
$$;

-- The application role may call it; nobody else needs to.
REVOKE ALL ON FUNCTION organization_owner_ids(UUID[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION organization_owner_ids(UUID[]) TO eventticket_app;
