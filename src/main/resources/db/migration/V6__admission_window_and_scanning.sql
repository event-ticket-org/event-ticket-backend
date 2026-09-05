-- The admission window (KB requirements/003 criterion 16) and scanning at the door (007).
--
-- The window arrives late, in the phase that needed it rather than the phase that owns it.
-- The contract published EVENT_NOT_OPEN and EVENT_ENDED as scan outcomes while an Event had
-- only a start time, so both refusals were opinions. A start time cannot decide whether a door
-- is open: people arrive before an event begins and leave after it ends, and nfr.md's
-- 45-minute arrival window was already being measured against a time nothing recorded.

ALTER TABLE event
    ADD COLUMN doors_open_at TIMESTAMPTZ,
    ADD COLUMN ends_at       TIMESTAMPTZ;

-- Nullable, because a Draft is allowed to be incomplete. Publishing is what requires them,
-- which is where everything a sold Ticket depends on already has to be settled - the same gate
-- that refuses an unpriced tier. Enforced in PublishEvent rather than by a CHECK, so that a
-- manager gets a sentence explaining which of the five preconditions failed.
--
-- The freeze trigger from V4 needs no change: it names label, x, y and tier_name, so a column
-- that stays editable after publish is permitted by construction.

ALTER TABLE event
    ADD CONSTRAINT event_admission_window_ordered
    CHECK (doors_open_at IS NULL OR ends_at IS NULL
           OR (doors_open_at <= starts_at AND ends_at > starts_at));

-- ---------------------------------------------------------------------------
-- Redemption.
--
-- KB invariant 13: one Ticket admits one person once, redeemed by exactly one Scan. Two
-- devices reading the same code at the same instant is the normal case at a door with four
-- scanners and a queue, not an edge case - so redemption is one conditional UPDATE whose row
-- count is the answer, exactly like taking a Seat Hold. The loser blocks on the row lock,
-- re-evaluates once the winner commits, updates nothing, and reads back who got in first.
--
-- requirements/007 criterion 5 is why the device is stored and not only the instant: it is how
-- staff tell "you already went in" from "someone else used your ticket".
-- ---------------------------------------------------------------------------

ALTER TABLE ticket
    ADD COLUMN redeemed_by_user_id UUID REFERENCES app_user (id),
    ADD COLUMN redeemed_device_id  TEXT;

-- Every attempt, whatever the outcome (criterion 6, KB invariant 14). Including codes that
-- were never ours: a refused scan is the only record that somebody stood at a door with a
-- ticket that did not work, and "the system said no" is not an answer anyone can act on
-- afterwards without this.
CREATE TABLE scan (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID        NOT NULL REFERENCES organization (id) ON DELETE CASCADE,
    event_id          UUID        NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    -- Null when the code resolved to nothing. The row still exists, which is the point.
    ticket_id         UUID        REFERENCES ticket (id) ON DELETE SET NULL,
    scanned_by_user_id UUID       NOT NULL REFERENCES app_user (id),
    device_id         TEXT        NOT NULL,
    outcome           TEXT        NOT NULL
                      CHECK (outcome IN ('ADMITTED', 'ALREADY_REDEEMED', 'WRONG_EVENT',
                                         'TICKET_VOID', 'EVENT_NOT_OPEN', 'EVENT_ENDED',
                                         'UNKNOWN_CODE')),
    occurred_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX scan_event_idx  ON scan (event_id, occurred_at DESC);
CREATE INDEX scan_ticket_idx ON scan (ticket_id) WHERE ticket_id IS NOT NULL;

-- A Scan belongs to the Organization whose door it happened at, and to nobody else. There is
-- no buyer branch here on purpose: a buyer has no business reading the log of who was refused
-- entry, and Gate Staff reading their own Organization's is what the policy already allows.
ALTER TABLE scan ENABLE ROW LEVEL SECURITY;
ALTER TABLE scan FORCE  ROW LEVEL SECURITY;

CREATE POLICY scan_tenant_isolation ON scan
    USING (organization_id = current_organization_id())
    WITH CHECK (organization_id = current_organization_id());
