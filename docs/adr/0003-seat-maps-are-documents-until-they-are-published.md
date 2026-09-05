# A Seat Map is a document until it is published, and rows afterwards

A Venue's Seat Map is a single `jsonb` column on `venue`. An Event's Seat Map is rows in
`event_seat`. Publishing is the moment one becomes the other.

We did this because the two answer different questions.

The Venue's map is written whole by `PUT /venues/{id}/seat-map` and read whole. Nothing ever
asks about one seat in it, so rows would buy nothing and cost a two-thousand-row diff on
every edit. As a document it is one statement, and there is no add-seat / move-seat /
delete-seat API to keep consistent with itself — the editor works out what the room should
look like and sends the result.

The Event's map is the opposite. Its seats are held, sold and scanned one at a time, and KB
invariant 5 makes "at most one active Seat Hold per seat" a **database** constraint. A
constraint needs something to constrain.

## What follows from it

**A Draft Event has no seats of its own.** It shows the Venue's current map and reflects
edits to it (requirements/003 criterion 2) by reading that map, not by keeping a copy in step
with one. Nothing has to propagate when a room is re-drawn.

**Publishing copies rather than locks** (KB invariant 8). The Venue's map stays editable for
the next Event held there; this Event simply stops reading it. That is the whole of
requirements/002 criterion 9 — "edits never affect an Event that is already published" —
with nothing to enforce.

**Seats gain identity at publish.** The contract's `SeatMapSeat` has no `id` and its
`EventSeat` does, which is not an oversight: before publish a seat is a mark on a drawing,
and `EventPatch.unsellableSeatIds` has no identifiers to name until it is a thing that can be
sold. Holding a seat back from sale is therefore a published-Event operation, which is also
when it matters (criterion 11's capacity changes).

**The two modules stay independent.** `event` reads a Venue's map at publish; `venue` never
reads an Event. The one question a Venue does need answered about Events — whether a
published one uses it, so `DELETE /venues/{id}` can be refused — is answered by a trigger in
`V4__venues_and_events.sql` rather than by a repository call, because asking it in Java would
close the loop into a cycle that `ModularityTest` rejects.

## Consequences

Seat labels are unique within a Venue's map by a check in `SeatMapDocument.validated()`, not
by an index — a document has nothing to index. The same rule becomes a real unique constraint
on `event_seat` at publish, which is where a duplicate would actually sell two people the same
chair.

A Pricing Tier is linked to seats by *name*, in both representations, because the document has
nothing to point at. `PublishEvent` reconciles the tier rows against the map it is freezing,
so from publish onward the rows are the authoritative list.

Editing a 2,000-seat map stays one `UPDATE`. Publishing one is 2,000 inserts, once.
