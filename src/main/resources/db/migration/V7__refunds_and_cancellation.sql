-- requirements/008. The second money flow, and the first one that can partially fail.
--
-- A refund is not a boolean on an Order. It is an attempt against a provider with a lifecycle
-- of its own, confirmed by a callback exactly as a payment is (KB ADR-0002), which is why it
-- gets a table rather than a column. The column that does exist - ticket_order.refund_required,
-- written since V5 and read by nothing - is what this phase finally acts on.
-- ---------------------------------------------------------------------------

-- REFUNDED joins the Order statuses. It is not CANCELLED: cancelling is a buyer walking away
-- from an unpaid Order, refunding is money going back, and a ledger that collapsed the two
-- would lose the distinction that matters most in it.
ALTER TABLE ticket_order DROP CONSTRAINT ticket_order_status_check;
ALTER TABLE ticket_order ADD CONSTRAINT ticket_order_status_check
    CHECK (status IN ('AWAITING_PAYMENT', 'PAID', 'EXPIRED', 'CANCELLED', 'REFUNDED'));

-- An Event carries its own cancellation rather than a separate row, because there is at most
-- one and it is a fact about the Event. The per-Order progress requirements/008 criterion 7
-- asks for is the refund table below, queried for this Event's Orders - which keeps one
-- record of each refund instead of a second copy that can disagree with the first.
ALTER TABLE event ADD COLUMN cancelled_at  TIMESTAMPTZ;
ALTER TABLE event ADD COLUMN cancel_reason TEXT;

-- One attempt at giving money back. KB invariant 22's bulk cancellation is many of these.
--
-- Not tenant-scoped, for the same reason as payment_session: a provider's callback arrives
-- with no tenant and has to find this row before it can know whose it is. It carries the
-- organization and the buyer so the callback can adopt them, and everything after that runs
-- under the policies like any other request. Reaching a refund from outside a callback goes
-- through its Order, which is scoped.
CREATE TABLE refund (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id        UUID        NOT NULL REFERENCES ticket_order (id) ON DELETE CASCADE,
    organization_id UUID        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    buyer_user_id   UUID        NOT NULL REFERENCES app_user (id),
    provider        TEXT        NOT NULL,
    -- The provider's handle for the reversal. A callback names this, never our id.
    provider_ref    TEXT        NOT NULL,
    amount          BIGINT      NOT NULL CHECK (amount >= 0),
    currency        TEXT        NOT NULL DEFAULT 'VND' CHECK (currency IN ('VND')),
    status          TEXT        NOT NULL DEFAULT 'REFUND_PENDING'
                    CHECK (status IN ('REFUND_PENDING', 'REFUNDED', 'REFUND_FAILED')),
    -- Shown to the buyer in the email they get. Required by the contract for a reason:
    -- "your order was refunded" with no reason generates the support request it replaces.
    reason          TEXT        NOT NULL,
    failure_reason  TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    settled_at      TIMESTAMPTZ,
    CONSTRAINT refund_provider_ref_unique UNIQUE (provider, provider_ref)
);
CREATE INDEX refund_order_idx ON refund (order_id);

-- At most one refund in flight or settled per Order. v1.1 refunds an Order whole - there are
-- no partial refunds in the contract - so a second attempt against an Order that already has
-- one is a mistake, and a partial unique index says so once rather than every caller checking
-- for it. A failed attempt is excluded, because a refund that the provider rejected is
-- exactly the case where a second attempt is the right answer.
CREATE UNIQUE INDEX refund_one_live_per_order
    ON refund (order_id) WHERE status <> 'REFUND_FAILED';

-- The delivery that settled a refund, so one idempotency table covers both flows. A payment
-- delivery names a session and a refund delivery names a refund; neither names both.
ALTER TABLE payment_event ADD COLUMN refund_id UUID REFERENCES refund (id) ON DELETE SET NULL;

-- requirements/008 criterion 5. A voided Ticket's seat goes back on sale, but only while the
-- Event is still selling: a cancelled or closed Event has nothing to put it back into.
--
-- The seats are found through order_seat rather than through event_seat.held_by_order_id,
-- because selling clears that column - after sell_seats a seat no longer remembers which
-- Order took it, and order_seat is the record that does.
--
-- SECURITY DEFINER for the same reason as the other three: the event_seat policy rightly
-- refuses writes to an Organization the caller is not a member of, and a narrow function whose
-- whole body is auditable is a smaller grant than widening that policy.
CREATE FUNCTION release_sold_seats(p_order_id UUID) RETURNS INTEGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    released INTEGER;
BEGIN
    UPDATE event_seat s
       SET sold_at = NULL
      FROM order_seat os
      JOIN ticket_order o ON o.id = os.order_id
      JOIN event e        ON e.id = o.event_id
     WHERE os.order_id = p_order_id
       AND s.id = os.event_seat_id
       AND s.sold_at IS NOT NULL
       AND e.status = 'PUBLISHED';
    GET DIAGNOSTICS released = ROW_COUNT;
    RETURN released;
END;
$$;

-- Only the function. V3 set default privileges on new tables, so refund is already reachable;
-- functions are not covered by those, which is why the other three are granted by hand too.
GRANT EXECUTE ON FUNCTION release_sold_seats(UUID) TO eventticket_app;
