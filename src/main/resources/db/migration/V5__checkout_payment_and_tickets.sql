-- Buying (KB requirements/004), payment (005) and ticket delivery (006).
--
-- The Seat Hold is the design decision everything else here follows from. It is not a table.
-- An Event Seat carries its own availability - held_until, held_by_order_id, sold_at - so a
-- second hold on a seat has nowhere to exist. KB invariant 5 asks that the database win the
-- race rather than application code; a row that cannot be written twice is a stronger answer
-- than a constraint forbidding the second write.
--
-- It also makes invariant 7 - "expiry releases the Event Seat with no trace" - literally
-- true. A lapsed hold is a timestamp in the past. Nothing releases it, nothing sweeps it,
-- and the next buyer's update overwrites it.
--
-- Adding these columns needs no change to the freeze trigger from V4: that trigger refuses
-- changes to label, x, y and tier_name specifically, so a new column describing availability
-- is permitted by construction. Which is the contract's own line - "Frozen at publish.
-- Availability is live."

ALTER TABLE event_seat
    ADD COLUMN held_until       TIMESTAMPTZ,
    ADD COLUMN held_by_order_id UUID,
    ADD COLUMN sold_at          TIMESTAMPTZ;

-- The public seat map is read by anyone with a link and no tenant at all, so every input to
-- availability has to live on the seat. A ticket is not readable without a tenant, which is
-- why "sold" is recorded here as well as being a ticket's existence.
CREATE INDEX event_seat_held_idx ON event_seat (event_id, held_until)
    WHERE held_until IS NOT NULL;

-- "order" is reserved in SQL. Naming the table around the keyword costs one word here and
-- saves quoting it in every statement and mapping for the life of the schema.
CREATE TABLE ticket_order (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    event_id        UUID        NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    -- The buyer is a User, not a Member. They are almost never a member of the Organization
    -- whose event they are buying from, which is why the policies below have a second branch.
    buyer_user_id   UUID        NOT NULL REFERENCES app_user (id),
    status          TEXT        NOT NULL DEFAULT 'AWAITING_PAYMENT'
                    CHECK (status IN ('AWAITING_PAYMENT', 'PAID', 'EXPIRED', 'CANCELLED')),
    total_amount    BIGINT      NOT NULL CHECK (total_amount >= 0),
    currency        TEXT        NOT NULL DEFAULT 'VND' CHECK (currency IN ('VND')),
    hold_expires_at TIMESTAMPTZ,
    -- requirements/005 criterion 9: a confirmation that arrives after the holds lapsed must
    -- not silently keep the money. There is no refund machinery until requirements/008, so
    -- the Order carries the flag a human or that phase acts on.
    refund_required BOOLEAN     NOT NULL DEFAULT FALSE,
    paid_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ticket_order_buyer_idx ON ticket_order (buyer_user_id, created_at DESC, id DESC);
CREATE INDEX ticket_order_event_idx ON ticket_order (event_id);

ALTER TABLE event_seat
    ADD CONSTRAINT event_seat_held_by_order_fk
    FOREIGN KEY (held_by_order_id) REFERENCES ticket_order (id) ON DELETE SET NULL;

-- The price a seat was bought at, captured when the hold was taken. KB invariant 10: a later
-- price change applies only to later sales, and it cannot reach back to a row that already
-- recorded its own amount.
CREATE TABLE order_seat (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID   NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    buyer_user_id   UUID   NOT NULL REFERENCES app_user (id),
    order_id        UUID   NOT NULL REFERENCES ticket_order (id) ON DELETE CASCADE,
    event_seat_id   UUID   NOT NULL REFERENCES event_seat (id) ON DELETE CASCADE,
    label           TEXT   NOT NULL,
    tier_name       TEXT   NOT NULL,
    amount          BIGINT NOT NULL CHECK (amount >= 0),
    currency        TEXT   NOT NULL DEFAULT 'VND' CHECK (currency IN ('VND')),
    CONSTRAINT order_seat_unique UNIQUE (order_id, event_seat_id)
);
CREATE INDEX order_seat_order_idx ON order_seat (order_id);

-- Not tenant-scoped, on purpose. A webhook arrives with no tenant and has to find the session
-- before it can know which tenant to adopt; scoping this table makes that a chicken and egg
-- solvable only by punching a hole in the policies. It is addressed by an unguessable
-- identifier and is platform plumbing rather than a tenant's queryable data - the same
-- category as refresh_token in V2.
CREATE TABLE payment_session (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id     UUID        NOT NULL REFERENCES ticket_order (id) ON DELETE CASCADE,
    -- The tenant this session belongs to, carried here for one reason: a webhook arrives with
    -- no tenant, and this is the only row it can read before it has one. It adopts these and
    -- everything after that runs under the policies like any other request.
    organization_id UUID     NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    buyer_user_id   UUID     NOT NULL REFERENCES app_user (id),
    provider     TEXT        NOT NULL,
    -- The provider's own handle for this attempt. A confirmation names this, never our id.
    provider_ref TEXT        NOT NULL,
    status       TEXT        NOT NULL DEFAULT 'AWAITING_PAYMENT'
                 CHECK (status IN ('AWAITING_PAYMENT', 'PAID', 'FAILED', 'EXPIRED')),
    next_action  JSONB       NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT payment_session_provider_ref_unique UNIQUE (provider, provider_ref)
);
CREATE INDEX payment_session_order_idx ON payment_session (order_id);

-- requirements/005 criterion 4, as a constraint rather than a check. Webhooks are delivered
-- concurrently, not merely twice, so "look for it, then handle it" loses the race it exists
-- to win. The insert is the idempotency test: if it violates, this delivery is a duplicate.
CREATE TABLE payment_event (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider          TEXT        NOT NULL,
    provider_event_id TEXT        NOT NULL,
    session_id        UUID        REFERENCES payment_session (id) ON DELETE SET NULL,
    received_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT payment_event_unique UNIQUE (provider, provider_event_id)
);

-- A Ticket Code is never stored. code_lookup is 128 random bits that only find the row; the
-- code the buyer sees is that value plus a MAC computed with a key held outside the schema,
-- so a leaked database is not a set of working tickets (nfr.md). Reissuing is a new
-- code_lookup: the old code stops resolving, which is KB invariant 15.
CREATE TABLE ticket (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    buyer_user_id   UUID        NOT NULL REFERENCES app_user (id),
    order_id        UUID        NOT NULL REFERENCES ticket_order (id) ON DELETE CASCADE,
    event_id        UUID        NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    event_seat_id   UUID        NOT NULL REFERENCES event_seat (id) ON DELETE CASCADE,
    seat_label      TEXT        NOT NULL,
    tier_name       TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'VALID'
                    CHECK (status IN ('VALID', 'REDEEMED', 'VOID')),
    code_lookup     TEXT        NOT NULL,
    code_version    SMALLINT    NOT NULL DEFAULT 1,
    issued_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    redeemed_at     TIMESTAMPTZ,
    CONSTRAINT ticket_code_lookup_unique UNIQUE (code_lookup)
);
CREATE INDEX ticket_order_id_idx ON ticket (order_id);
CREATE INDEX ticket_event_idx    ON ticket (event_id);

-- KB invariant 4: a Seat Hold and a Ticket may not exist for the same Event Seat, and no seat
-- is ever sold twice. The transaction that issues tickets is what makes issuance atomic; this
-- is what survives a bug in it.
CREATE UNIQUE INDEX ticket_one_per_seat ON ticket (event_seat_id) WHERE status <> 'VOID';

-- requirements/006 criterion 8: delivery failure is recorded and retried, never silently
-- swallowed. A buyer who did not receive their ticket is the failure this system exists to
-- prevent, and an interface that throws into a log is not a record of anything.
CREATE TABLE email_delivery (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient       TEXT        NOT NULL,
    subject         TEXT        NOT NULL,
    body            TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts        INT         NOT NULL DEFAULT 0,
    last_error      TEXT,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ
);
CREATE INDEX email_delivery_pending_idx ON email_delivery (next_attempt_at)
    WHERE status = 'PENDING';

-- ---------------------------------------------------------------------------
-- Seat availability transitions.
--
-- These three functions are the only things that write availability onto an Event Seat, and
-- they are SECURITY DEFINER because a buyer must be able to take a hold on an Organization
-- they are not a member of. Widening the event_seat policy to let them would also let them
-- change for_sale on any published event; a function whose whole body is auditable is the
-- narrower grant.
--
-- Each takes its lock with ORDER BY id, so two buyers whose selections overlap can never
-- deadlock by locking the same seats in opposite orders. Under READ COMMITTED, Postgres
-- re-checks the predicate after the lock is granted, so the loser of a race sees a live
-- held_until and comes away with fewer seats than it asked for - which is exactly the signal
-- requirements/004 criterion 6 needs in order to name the seats that got away.
-- ---------------------------------------------------------------------------

CREATE FUNCTION hold_seats(p_event_id UUID, p_seat_ids UUID[], p_order_id UUID, p_until TIMESTAMPTZ)
RETURNS TABLE (seat_id UUID)
LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp AS $$
    WITH free AS (
        SELECT id FROM event_seat
         WHERE id = ANY(p_seat_ids)
           AND event_id = p_event_id
           AND for_sale
           AND sold_at IS NULL
           AND (held_until IS NULL OR held_until <= now())
         ORDER BY id
         FOR UPDATE
    )
    UPDATE event_seat s
       SET held_until = p_until, held_by_order_id = p_order_id
      FROM free
     WHERE s.id = free.id
    RETURNING s.id;
$$;

/*
 * Abandoning an Order (requirements/004 criterion 11) releases its holds at once rather than
 * waiting for expiry. Ownership is not checked here: the caller reached this Order through
 * row-level security, which already refused it to anyone else.
 */
CREATE FUNCTION release_seats(p_order_id UUID) RETURNS INTEGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    released INTEGER;
BEGIN
    UPDATE event_seat
       SET held_until = NULL, held_by_order_id = NULL
     WHERE held_by_order_id = p_order_id
       AND sold_at IS NULL;
    GET DIAGNOSTICS released = ROW_COUNT;
    RETURN released;
END;
$$;

/*
 * Confirmation. Returns only the seats whose hold was still alive, so the caller compares the
 * count to what the Order bought: a shortfall is requirements/005 criterion 9, a payment that
 * arrived after the holds lapsed. Asking "did the holds survive?" and then selling would be
 * two statements with a race between them; this is one.
 */
CREATE FUNCTION sell_seats(p_order_id UUID)
RETURNS TABLE (seat_id UUID)
LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp AS $$
    WITH held AS (
        SELECT id FROM event_seat
         WHERE held_by_order_id = p_order_id
           AND held_until > now()
           AND sold_at IS NULL
         ORDER BY id
         FOR UPDATE
    )
    UPDATE event_seat s
       SET sold_at = now(), held_until = NULL, held_by_order_id = NULL
      FROM held
     WHERE s.id = held.id
    RETURNING s.id;
$$;

/*
 * Housekeeping, not correctness. The seats of a lapsed Order are already free - held_until is
 * simply in the past - and a payment attempt against one is refused by the Order itself. This
 * exists so that an Order the buyer walked away from stops calling itself "awaiting payment"
 * in their own list of orders.
 *
 * SECURITY DEFINER because the scheduler has no tenant at all: no organization and no user
 * with which to satisfy a policy.
 */
CREATE FUNCTION expire_lapsed_orders() RETURNS INTEGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    expired INTEGER;
BEGIN
    UPDATE ticket_order
       SET status = 'EXPIRED', hold_expires_at = NULL
     WHERE status = 'AWAITING_PAYMENT'
       AND hold_expires_at IS NOT NULL
       AND hold_expires_at <= now();
    GET DIAGNOSTICS expired = ROW_COUNT;
    RETURN expired;
END;
$$;

GRANT EXECUTE ON FUNCTION hold_seats(UUID, UUID[], UUID, TIMESTAMPTZ) TO eventticket_app;
GRANT EXECUTE ON FUNCTION expire_lapsed_orders() TO eventticket_app;
GRANT EXECUTE ON FUNCTION release_seats(UUID) TO eventticket_app;
GRANT EXECUTE ON FUNCTION sell_seats(UUID) TO eventticket_app;

-- ---------------------------------------------------------------------------
-- Row-level security.
--
-- A buyer is not a member of the Organization they are buying from, and has no active
-- Organization at all while browsing their own orders. So every table a buyer touches carries
-- buyer_user_id and its policy has two branches: the Organization's staff, or the buyer
-- themselves. requirements/006 criterion 5 - all of a buyer's Orders across Organizations in
-- one place - is that second branch.
--
-- WITH CHECK admits the same two, because a buyer genuinely does create rows in an
-- Organization that is not theirs. What they cannot do is write anything for somebody else.
-- ---------------------------------------------------------------------------

ALTER TABLE ticket_order ENABLE ROW LEVEL SECURITY;
ALTER TABLE ticket_order FORCE  ROW LEVEL SECURITY;
ALTER TABLE order_seat   ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_seat   FORCE  ROW LEVEL SECURITY;
ALTER TABLE ticket       ENABLE ROW LEVEL SECURITY;
ALTER TABLE ticket       FORCE  ROW LEVEL SECURITY;

CREATE POLICY ticket_order_access ON ticket_order
    USING (organization_id = current_organization_id()
           OR buyer_user_id = current_app_user_id())
    WITH CHECK (organization_id = current_organization_id()
                OR buyer_user_id = current_app_user_id());

CREATE POLICY order_seat_access ON order_seat
    USING (organization_id = current_organization_id()
           OR buyer_user_id = current_app_user_id())
    WITH CHECK (organization_id = current_organization_id()
                OR buyer_user_id = current_app_user_id());

CREATE POLICY ticket_access ON ticket
    USING (organization_id = current_organization_id()
           OR buyer_user_id = current_app_user_id())
    WITH CHECK (organization_id = current_organization_id()
                OR buyer_user_id = current_app_user_id());
