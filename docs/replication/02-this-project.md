# What this application would have to solve to use a replica

The cluster exists. Nothing in the application uses it, and this page is the reason: routing
reads to a standby is not a configuration change, it is **eighteen separate judgements**, and
most of them come out "no".

---

## The trap in the number

Eighteen use cases carry `@Transactional(readOnly = true)`. That looks like eighteen replica
candidates and it is not.

**`readOnly` is a hint to the driver and an assertion about SQL. It says nothing about whether
stale data is safe.** It means "this will not write". The question a replica asks is entirely
different: *does anything break if this answer is a few hundred milliseconds out of date?*

Two of the eighteen are not even read endpoints. `BeginCoverUpload` reads an Event to sign an
upload policy, in the middle of a write flow. `CancelEvent.progress` is the **poll** a manager
watches while refunds are running — a method whose entire purpose is to observe writes that are
happening right now. Routing either to a replica would be actively wrong.

---

## The decision rule

For each read, in order:

1. **Did the same user write this, moments ago?** If yes, a replica will show them their own
   change missing. This is *read-your-own-writes* and it is the dominant case in this
   application.
2. **Does someone act on the answer?** A stale seat map is a buyer clicking a seat that is gone.
   A stale refund list is a manager refunding somebody twice.
3. **Is it public, anonymous and high-volume?** Then it is a genuine candidate — and this is also
   where the read traffic actually is.

---

## The eighteen, classified

### Safe on a replica

| Use case | Why, and what staleness costs |
|---|---|
| `ListPublicEvents` | Anonymous browsing. Nobody wrote it; a few seconds old is invisible. **The single highest-volume read in the system.** |
| `GetPublicEvent` | Same, with one caveat: it carries `seatsAvailable`. Stale means a buyer sees a seat that has gone — and they already can, because someone may take it between the page load and the click. Checkout re-checks on the primary and refuses with `SEATS_UNAVAILABLE`. **The race already exists; the replica only widens it.** |
| `GetPublicEventSeatMap` | Identical reasoning. |
| `ListVenues`, `GetVenue` | An organizer's own venues, rarely read straight after a write. Mild read-your-writes risk on the editor's save-then-reload; acceptable. |
| `ListMembers` | The team list. Adding a member then not seeing them is annoying, not incorrect. |

### Not safe — read-your-own-writes

| Use case | The write it immediately follows |
|---|---|
| `GetOrder` | **The worst one.** The browser navigates to `/orders/{id}` the instant `POST /checkout` returns. A lagging replica answers *404 on an order that was just created*, with the buyer's seats held and their money about to move. |
| `ListOrderTickets` | Read the moment payment is confirmed. Stale means "you have no tickets" after paying. |
| `GetMe` | Read immediately after `verify-email` signs the user in. Stale means "email not verified" on an account just verified. |
| `GetEvent`, `ListEvents` | The manage screens re-read after every publish, price change and schedule edit. |
| `GetSeatMap` | The editor reloads after saving. Stale means edits appear to have been lost — and the user's fix for that is to save again. |
| `ListOrders` | "My tickets", opened straight after buying. |
| `ListEventOrders` | An organizer watching sales arrive. |
| `ListOrderRefunds` | Read after requesting a refund. |
| `ListPendingOrganizations` | The admin queue, re-read after each approval — an org just approved still shows pending, inviting a second approval. |
| `BeginCoverUpload` | Reads the Event that was created seconds earlier. A stale replica 404s an event the organizer is looking at. |
| `CancelEvent.progress` | Polls for writes **in flight**. Guaranteed to mislead. |

**Roughly five of eighteen can move.** That sounds like a poor return and is not, because the five
include the public listing and event pages — the only endpoints an anonymous internet crowd hits,
and the only ones whose volume is unbounded. **Everything behind a login is read-your-own-writes;
everything in front of it is cacheable.** That line is not a coincidence, and it is the useful
generalisation.

---

## Two things that can never leave the primary

### `SELECT … FOR UPDATE` is refused outright

```
ERROR: cannot execute SELECT FOR UPDATE in a read-only transaction
```

The seat hold is built on exactly this lock — it is what makes one buyer win a contested seat
and the other get a civil refusal. **The seat-hold path is pinned to the primary permanently.**
Not a tuning decision, a hard constraint of the datastore.

### Anything that decides admission or money

The door, `ConfirmPayment`, `RefundOrder`. All write, so the question never arises — but worth
stating, because the failure would be a person refused entry on a stale ticket status.

---

## If you did want read-your-own-writes on a replica

Postgres 18 has no `pg_wal_replay_wait()` — checked, it does not exist here. The pattern has to
be assembled from two functions:

```sql
-- on the primary, AFTER the write commits
select pg_current_wal_lsn();

-- on the standby, before serving that user's next read
select pg_last_wal_replay_lsn() >= $1::pg_lsn;   -- false: use the primary instead
```

Carry that LSN in the user's session for a few seconds; while the replica has not reached it,
send that user's reads to the primary. Everyone else keeps using the replica.

**One trap, found by testing it rather than reasoning about it.** The obvious implementation is
wrong:

```sql
insert into … returning pg_current_wal_lsn();   -- WRONG
```

`pg_current_wal_lsn()` inside the transaction is evaluated **before the commit record is
written**, so the LSN it returns does not include the commit. Tested against a deliberately
paused replica, the guard reported **SAFE** while the replica was genuinely a row behind. Capture
it **after** commit, in its own statement, and the same test correctly reports `STALE`.

That is the whole lesson of this page in miniature: a correctness guard that has never been shown
to fail is not a guard.

---

## So should this project use a replica?

**Not for read scaling. Yes, eventually, for the other two reasons.**

`nfr.md` specifies a single instance and no high availability. There is no read-capacity problem
to solve, and five endpoints' worth of offloading does not justify the failure modes in
[`01-what-went-wrong.md`](01-what-went-wrong.md) — a dead replica that fills the primary's disk
is a *worse* availability story than no replica at all, for a system that currently cannot lose
data it has not backed up.

What a replica genuinely buys this system, in order of value:

1. **A backup target that is not the production node.** `pg_basebackup` from a standby costs the
   primary nothing.
2. **A recovery position after hardware failure.** Today, losing the disk loses the product.
3. **Read capacity** — last, and only when the public listing is actually the bottleneck.

**And the honest conclusion is the one the MongoDB exercise reached from the other direction:**
this system's hardest requirements — tenant isolation and the seat-hold race — are answered
*inside the database*, by policies and row locks. Replication does not weaken either of those
(RLS was verified working on a standby, policies and all, which is more than the MongoDB
migration could say). It simply cannot help with them, and it introduces four new ways to be
down. Adopt it for durability, not for speed.
