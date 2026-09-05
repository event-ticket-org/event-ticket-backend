# A Seat Hold is a column on the seat, not a table beside it

`event_seat` carries `held_until`, `held_by_order_id` and `sold_at`. There is no `seat_hold`
table, and no `SeatHold` entity.

We did this because the obvious design cannot be built. KB invariant 5 wants "at most one
active Seat Hold per Event Seat" enforced by the database, which reads as a partial unique
index:

```sql
CREATE UNIQUE INDEX ON seat_hold (event_seat_id)
    WHERE released_at IS NULL AND expires_at > now();   -- illegal
```

Postgres refuses it: `now()` is not immutable and cannot appear in an index predicate. Working
around that means the index ignores expiry, a lapsed hold stays "occupied" until something
releases it, and expiry becomes an event that has to happen — a sweeper, a `released_at`
column, and two readers who disagree about whether a seat is free.

One row per seat removes the problem rather than working around it.

## What it buys

**A second hold has nowhere to exist.** That is stronger than a constraint forbidding a second
row, and it is what invariant 5 is actually asking for.

**Expiry stops being an event.** A lapsed hold is a timestamp in the past. Nothing releases it,
nothing sweeps it, and the next buyer's `UPDATE` overwrites it. Invariant 7 — "expiry releases
the Event Seat with no trace on the buyer's Order" — is literally true instead of approximately
true.

**One place to ask whether a seat is free.** `for_sale`, `sold_at`, `held_until`: three columns
on one row, no joins. This matters more than it looks. The public seat map is read by anyone
with a link and **no tenant at all**, and a Ticket is not readable without one — so if "sold"
were the existence of a Ticket rather than a column here, the map would show sold seats as
available to exactly the people about to try buying them.

**The race resolves itself.** Under `READ COMMITTED` Postgres re-evaluates the predicate after
granting a row lock, so a loser sees a live `held_until` and comes away with fewer seats than
it asked for. Nothing adjudicates; the shortfall *is* the answer, and it is also what
requirements/004 criterion 6 needs in order to name the seats that got away.

## How it is written

Only three SQL functions in `V5__checkout_payment_and_tickets.sql` write these columns:
`hold_seats`, `release_seats`, `sell_seats`. Each is `SECURITY DEFINER`, because a buyer must
be able to hold a seat in an Organization they are not a member of and the `event_seat` policy
rightly refuses that — a function whose whole body is auditable is a much narrower grant than
widening the policy, which would also let a buyer change `for_sale` on any published event.

Each takes its lock with `ORDER BY id`, so two buyers with overlapping selections cannot
deadlock by locking the same seats in opposite orders. `SeatHoldConcurrencyTest` shuffles the
selection per buyer specifically to provoke that.

Each returns the seats it actually changed, never a boolean. `hold` returning fewer than asked
is criterion 6; `sell` returning fewer than the Order owes is requirements/005 criterion 9 — a
payment that arrived after the holds lapsed. Asking "did the holds survive?" and then selling
would be two statements with a race between them; this is one.

## Consequences

There is no history of holds, and there should not be: invariant 7 says expiry leaves no trace.

`ExpireLapsedOrders` exists but is housekeeping only — it moves an abandoned Order from
`AWAITING_PAYMENT` to `EXPIRED` so the status a buyer sees is honest and so `EXPIRED`, which
the contract publishes, is a status the system produces. It releases nothing; the seats were
free the moment the timestamp passed.

The freeze trigger from V4 needed no change. It refuses updates to `label`, `x`, `y` and
`tier_name` specifically, so columns describing availability are permitted by construction —
which is the contract's own line: *"Frozen at publish. Availability is live."*

This diverges from how KB invariant 5 was originally worded, so the KB was changed first
(event-ticket-kb#3) rather than left describing a table that does not exist.
