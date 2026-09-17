-- What the search index has to be told about, and nothing else.
--
-- A notification outbox, not a payload outbox: a row says "this Event changed, go and read it",
-- and carries no copy of what it changed to. That is the whole design, and it buys three things
-- that a payload would have had to be engineered for.
--
--   **Duplicates are free.** Indexing by document id is already an upsert, so replaying a
--   notification lands the same state. There is no dedupe table here because there is nothing
--   to deduplicate.
--
--   **Order does not matter.** Every message means the same thing - re-read this Event - so a
--   retry that arrives after a newer change still ends on the current state. A payload outbox
--   has the opposite property: applying an older payload over a newer one is a silent
--   regression, and guarding against it needs a version column the Event does not have.
--
--   **Ten edits collapse into one read.** `select distinct event_id` is the whole batching
--   strategy.
--
-- What it costs is a read per dispatch, which at this size is a primary key lookup.

CREATE TABLE search_outbox (
    -- BIGSERIAL rather than a uuid: this is a queue and the order rows were written in is the
    -- order to work through them. The same choice audit_entry made, for the same reason.
    id         BIGSERIAL PRIMARY KEY,
    event_id   UUID        NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    -- Written in the caller's transaction, so a notification cannot exist for a change that
    -- rolled back, and a change cannot commit without its notification.
    queued_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX search_outbox_queued_idx ON search_outbox (id);

-- No row-level security, and this one is not like the others.
--
-- event_category and city have no policy because they are vocabulary nobody owns; featured_slot
-- has none because it belongs to the platform. This has none because it is not data - it is a
-- queue of identifiers, written by whoever changed an Event and drained by a scheduled job that
-- runs as nobody. A policy would have to admit both, which is every caller there is.
--
-- The ids in it are not a disclosure either: an Event id is in every public URL of an Event
-- that is published, and an id here for one that is not published tells a reader only that
-- something changed - and nothing can read this table through the API at all.
