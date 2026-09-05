-- Venues with their reusable Seat Maps (KB requirements/002) and the Event lifecycle
-- through publication (requirements/003).
--
-- Two representations of a seat map, because they answer different questions.
--
--   A Venue's Seat Map is a *document*. It is written whole by PUT /venues/{id}/seat-map,
--   read whole, and no query ever asks about one seat in it. JSONB, one row, one write -
--   a 2,000-seat edit is a single statement rather than a two-thousand-row diff.
--
--   An Event's Seat Map is *relational*. Its seats are held, sold and scanned individually,
--   and KB invariant 5 makes "at most one active hold per seat" a database constraint. That
--   needs rows.
--
-- Publishing is the moment one becomes the other (KB invariant 8), and from then on nothing
-- a sold Ticket depends on may change under it (invariant 12). The triggers below are what
-- make that last sentence true rather than aspirational.

CREATE TABLE venue (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    name            TEXT        NOT NULL,
    address         TEXT,
    -- A column of its own rather than part of the address, because the public listing
    -- filters on it (requirements/002 criterion 1) and free text cannot be filtered.
    city            TEXT        NOT NULL,
    timezone        TEXT        NOT NULL,
    -- requirements/002 criterion 2: a Venue has exactly one Seat Map, created empty.
    seat_map        JSONB       NOT NULL DEFAULT '{"seats": [], "elements": []}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX venue_org_idx ON venue (organization_id);

CREATE TABLE event (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    -- Deleting a Venue takes its unpublished Events with it; a Venue with a published Event
    -- cannot be deleted at all (requirements/002 criterion 11), enforced by the trigger
    -- venue_in_use below.
    venue_id        UUID        NOT NULL REFERENCES venue (id) ON DELETE CASCADE,
    title           TEXT        NOT NULL,
    description     TEXT,
    cover_image_url TEXT,
    starts_at       TIMESTAMPTZ NOT NULL,
    listed          BOOLEAN     NOT NULL DEFAULT TRUE,
    status          TEXT        NOT NULL DEFAULT 'DRAFT'
                    CHECK (status IN ('DRAFT', 'PUBLISHED', 'SALES_CLOSED', 'COMPLETED', 'CANCELLED')),
    published_at    TIMESTAMPTZ,
    -- The non-sellable furniture - stage, entrance, aisle, bar - copied from the Venue at
    -- publish. Frozen with the seats, but never ticketed, so it stays a document.
    map_elements    JSONB       NOT NULL DEFAULT '[]'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX event_org_idx   ON event (organization_id, created_at DESC);
CREATE INDEX event_venue_idx ON event (venue_id);
-- The public listing's access path (requirements/009): published, listed, by start time.
CREATE INDEX event_public_idx ON event (starts_at, id)
    WHERE published_at IS NOT NULL AND listed;

CREATE TABLE event_pricing_tier (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID   NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    event_id        UUID   NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    -- Tier names originate in the Seat Map, which is why they are text and not a foreign
    -- key: the Seat Map is a document and has nothing to point at.
    name            TEXT   NOT NULL,
    -- Null means unpriced. Publishing is refused while any tier in use is unpriced
    -- (requirements/003 criterion 3), so the null is the precondition, not a defect.
    amount          BIGINT CHECK (amount IS NULL OR amount >= 0),
    currency        TEXT   NOT NULL DEFAULT 'VND' CHECK (currency IN ('VND')),
    CONSTRAINT event_pricing_tier_unique UNIQUE (event_id, name)
);

CREATE TABLE event_seat (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID             NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    event_id        UUID             NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    label           TEXT             NOT NULL,
    x               DOUBLE PRECISION NOT NULL,
    y               DOUBLE PRECISION NOT NULL,
    tier_name       TEXT             NOT NULL,
    -- Sellability is live, not frozen: SeatAvailability publishes NOT_FOR_SALE, and
    -- requirements/003 criterion 11 lets capacity grow after publish.
    for_sale        BOOLEAN          NOT NULL DEFAULT TRUE,
    CONSTRAINT event_seat_label_unique UNIQUE (event_id, label)
);
CREATE INDEX event_seat_event_idx ON event_seat (event_id);

-- ---------------------------------------------------------------------------
-- Immutability after publish (KB invariants 8-12, requirements/003 criterion 7).
--
-- In the database rather than in a use case. The rule is absolute, and an application check
-- protects only the code paths someone remembered to put it in - the next use case to touch
-- these tables gets the guarantee for free here and cannot opt out of it. It is also the
-- only version a test can attack directly, by issuing the forbidden UPDATE.
--
-- SECURITY DEFINER on the lookups: a trigger runs under row-level security like anything
-- else, and a guard that fails open when it cannot see the parent row is not a guard.
-- ---------------------------------------------------------------------------

CREATE FUNCTION event_is_published(event_id UUID) RETURNS BOOLEAN
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS
'SELECT EXISTS (SELECT 1 FROM event WHERE id = $1 AND published_at IS NOT NULL)';

CREATE FUNCTION venue_has_published_event(venue_id UUID) RETURNS BOOLEAN
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS
'SELECT EXISTS (SELECT 1 FROM event e WHERE e.venue_id = $1 AND e.published_at IS NOT NULL)';

CREATE FUNCTION event_seat_is_frozen_after_publish() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF event_is_published(OLD.event_id) THEN
            RAISE EXCEPTION 'seat % cannot be removed from a published event', OLD.label
            USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF event_is_published(NEW.event_id) THEN
            RAISE EXCEPTION 'seat % cannot be added to a published event', NEW.label
            USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF event_is_published(OLD.event_id)
       AND (NEW.event_id  IS DISTINCT FROM OLD.event_id
         OR NEW.label     IS DISTINCT FROM OLD.label
         OR NEW.x         IS DISTINCT FROM OLD.x
         OR NEW.y         IS DISTINCT FROM OLD.y
         OR NEW.tier_name IS DISTINCT FROM OLD.tier_name) THEN
        RAISE EXCEPTION 'the seat map of a published event is immutable (seat %)', OLD.label
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER event_seat_frozen
    BEFORE INSERT OR UPDATE OR DELETE ON event_seat
    FOR EACH ROW EXECUTE FUNCTION event_seat_is_frozen_after_publish();

CREATE FUNCTION event_is_frozen_after_publish() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.published_at IS NULL THEN
        RETURN NEW;
    END IF;
    IF NEW.venue_id IS DISTINCT FROM OLD.venue_id THEN
        RAISE EXCEPTION 'the venue of a published event cannot change'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.map_elements IS DISTINCT FROM OLD.map_elements THEN
        RAISE EXCEPTION 'the seat map of a published event is immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    -- Publication does not reverse. Cancelling is the way back, and it voids tickets rather
    -- than pretending the event was never on sale (KB invariant 22).
    IF NEW.published_at IS NULL OR NEW.status = 'DRAFT' THEN
        RAISE EXCEPTION 'a published event cannot return to draft'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER event_frozen
    BEFORE UPDATE ON event
    FOR EACH ROW EXECUTE FUNCTION event_is_frozen_after_publish();

CREATE FUNCTION venue_refuse_delete_while_in_use() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF venue_has_published_event(OLD.id) THEN
        RAISE EXCEPTION 'venue % is used by a published event', OLD.name
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER venue_in_use
    BEFORE DELETE ON venue
    FOR EACH ROW EXECUTE FUNCTION venue_refuse_delete_while_in_use();

-- ---------------------------------------------------------------------------
-- Row-level security (ADR-0004), with one addition: a published Event is public.
--
-- requirements/003 criterion 13 gives every published Event a page reachable by anyone with
-- the link, and requirements/009 lists them across Organizations. So each policy below reads
-- "this tenant's rows, plus whatever a published Event already shows the world". A Draft is
-- not covered by that and stays invisible outside its own Organization.
--
-- WITH CHECK stays tenant-only throughout: publishing makes a row readable, never writable.
-- ---------------------------------------------------------------------------

ALTER TABLE venue              ENABLE ROW LEVEL SECURITY;
ALTER TABLE venue              FORCE  ROW LEVEL SECURITY;
ALTER TABLE event              ENABLE ROW LEVEL SECURITY;
ALTER TABLE event              FORCE  ROW LEVEL SECURITY;
ALTER TABLE event_pricing_tier ENABLE ROW LEVEL SECURITY;
ALTER TABLE event_pricing_tier FORCE  ROW LEVEL SECURITY;
ALTER TABLE event_seat         ENABLE ROW LEVEL SECURITY;
ALTER TABLE event_seat         FORCE  ROW LEVEL SECURITY;

CREATE POLICY venue_tenant_isolation ON venue
    USING (organization_id = current_organization_id() OR venue_has_published_event(id))
    WITH CHECK (organization_id = current_organization_id());

CREATE POLICY event_tenant_isolation ON event
    USING (organization_id = current_organization_id() OR published_at IS NOT NULL)
    WITH CHECK (organization_id = current_organization_id());

CREATE POLICY event_pricing_tier_tenant_isolation ON event_pricing_tier
    USING (organization_id = current_organization_id() OR event_is_published(event_id))
    WITH CHECK (organization_id = current_organization_id());

CREATE POLICY event_seat_tenant_isolation ON event_seat
    USING (organization_id = current_organization_id() OR event_is_published(event_id))
    WITH CHECK (organization_id = current_organization_id());
