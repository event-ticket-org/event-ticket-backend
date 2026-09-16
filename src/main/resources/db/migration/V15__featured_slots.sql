-- The curated row (KB requirements/009 criterion 14): an ordered set of Events a platform
-- administrator has placed, each shown over a stated period.
--
-- A table rather than a flag on the Event, and the reason is in the two columns a boolean
-- cannot have. `position` says which comes first, and a curated row is an ordering before it
-- is a set. `ends_at` says when the placement stops, and a curated row with no expiry becomes
-- a row nobody revisits - the Events in it are still there long after the reason was.
-- Retrofitting either onto a boolean is a migration and an API change; the table costs one
-- migration now.

CREATE TABLE featured_slot (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- No organization_id, because a slot belongs to the platform rather than to the
    -- Organization whose Event it points at. That is what separates curation from
    -- advertising: an Organization cannot place itself in one (criterion 14).
    event_id          UUID        NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    -- Order within the row. Deliberately not unique: two slots may share a position when
    -- their periods do not overlap, and a curated row scheduled for next week has nothing to
    -- say about this week's. Uniqueness would have to be "unique among slots whose periods
    -- overlap", which is an exclusion constraint answering a question nobody is asking yet.
    position          INTEGER     NOT NULL CHECK (position >= 1),
    starts_at         TIMESTAMPTZ NOT NULL,
    ends_at           TIMESTAMPTZ NOT NULL,
    -- Who placed it and when. criterion 14 asks for curation to be recorded with actor and
    -- instant; these are that record for whatever is currently placed. See the note on the
    -- audit entry in ReplaceFeaturedSlots for the half this cannot answer.
    placed_by_user_id UUID        REFERENCES app_user (id),
    placed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT featured_slot_window CHECK (ends_at > starts_at)
);

-- The access path for the public read: what is showing now, in order.
CREATE INDEX featured_slot_live_idx ON featured_slot (starts_at, ends_at, position);

-- ---------------------------------------------------------------------------
-- No row-level security, and this is the third reason so far for not having it
-- ---------------------------------------------------------------------------
--
-- `event_category` and `city` have none because they are vocabulary nobody owns, and the
-- application cannot write them at all. This one the application *does* write, and still has
-- no policy, because there is no tenant to filter by: a slot belongs to the platform, and a
-- platform administrator is a member of nothing - the membership policy hides everything from
-- them, which is correct and is why DecideOrganization has to adopt a tenant to do its work.
--
-- So the guard is `PlatformAdmins.requireCallerIsPlatformAdmin()`, in the use case, exactly as
-- it is for Organization approval. That is application-level authorisation rather than
-- database-level, and it is worth being explicit that it is: a policy here would have nothing
-- to compare against, because being a platform administrator is a property of the User and not
-- of a tenant the session carries.
--
-- What the database does enforce is the part it can see: a slot must point at a real Event,
-- a period must end after it starts, and a position starts at one.

-- ---------------------------------------------------------------------------
-- The ranked row (criterion 15), which no visitor may compute for themselves
-- ---------------------------------------------------------------------------
--
-- Ranking by Tickets sold means reading ticket_order and order_seat, and both are
-- tenant-scoped: their policies admit the Organization's staff or the buyer, and a visitor
-- reading the home page is neither. So the obvious query returns nothing at all from an
-- unauthenticated request - which is the policy working, not a bug, and is how this was found.
--
-- SECURITY DEFINER rather than a wider policy, the same answer this schema already gives for
-- a platform administrator reading owners (V12) and a buyer holding a seat (V5). Widening
-- ticket_order's policy to let the listing rank would publish every Order in the system to
-- everybody, forever, to serve one row.
--
-- **It returns ids and an order, and never a count.** criterion 15 says the ranking is
-- published and the figures behind it are not: a position says one Event outsold another this
-- week, where a number says what an Organization took, across Organizations, to anybody who
-- loads the page. Keeping the count out of the signature makes that a property of the
-- function rather than of whoever writes the Java next.
--
-- The eligibility predicate is inside the function, so there is no argument that could make it
-- return a draft, an unlisted Event, or one belonging to an Organization that was rejected.
CREATE FUNCTION trending_event_ids(since TIMESTAMPTZ, at TIMESTAMPTZ, max_rows INTEGER)
RETURNS TABLE (event_id UUID, rank INTEGER)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS
$$
    SELECT ranked.event_id,
           row_number() OVER (ORDER BY ranked.sold DESC, ranked.event_id ASC)::INTEGER
      FROM (
            -- Counting the join is the point, which is the opposite of the trap countsFor
            -- documents: there the join multiplied Orders by their seat count and corrupted a
            -- sum of money, and here one row per seat is exactly what "how many tickets moved"
            -- means. Aggregating Orders first would rank by number of transactions, putting ten
            -- single-seat Orders above one Order for forty.
            SELECT o.event_id, count(s.id) AS sold
              FROM ticket_order o
              JOIN order_seat s ON s.order_id = o.id
              JOIN event e ON e.id = o.event_id
             -- paid_at, not created_at: the signal is when money landed. An abandoned checkout
             -- is not demand.
             WHERE o.status = 'PAID'
               AND o.paid_at >= since
               AND e.status = 'PUBLISHED'
               AND e.listed
               AND e.starts_at > at
               AND EXISTS (SELECT 1 FROM organization org
                            WHERE org.id = e.organization_id AND org.status = 'APPROVED')
             GROUP BY o.event_id
           ) ranked
     ORDER BY ranked.sold DESC, ranked.event_id ASC
     LIMIT max_rows
$$;

REVOKE ALL ON FUNCTION trending_event_ids(TIMESTAMPTZ, TIMESTAMPTZ, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION trending_event_ids(TIMESTAMPTZ, TIMESTAMPTZ, INTEGER) TO eventticket_app;
