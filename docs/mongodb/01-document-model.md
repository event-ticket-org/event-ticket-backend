# The document model

How eighteen PostgreSQL tables become MongoDB collections, and why each decision went the way
it did.

## Three models, not one

Confusing these is the commonest mistake in a migration like this one, so they are named
separately here and kept separate throughout.

| Model | What it is | Does it change? |
|---|---|---|
| **The API contract** | `contracts/openapi.yaml` | **No.** Byte-identical. A datastore migration a client can detect is a failed one. |
| **The domain model** | `Event`, `Order`, `Ticket` — Java classes, aggregate boundaries, invariants | **Only where the document model forces it**, and every such case is recorded. |
| **The document model** | Collections, embedding, indexes | **Designed from scratch.** This document. |

The reason to hold the middle one still is not conservatism. Postgres remains on `main`, and the
value of this exercise is the comparison between the two. If the database *and* the domain change
at once, no observed difference can be attributed to either.

## The decision rule

Every embed-or-reference call below is decided by four questions, in this order.

**1. Is it ever read without its parent?**
If it is, embedding forces every reader to fetch the parent too. A Ticket is read at a door from
a scanned code, with no Order in hand — that alone settles it.

**2. How many are there, and is the number bounded?**
The 16MB document limit is the famous answer and rarely the operative one. The real limit is
lower, because **MongoDB rewrites the whole document on update**. An unbounded child list is a
document that grows until it is slow, then until it is illegal.

**3. Is it written independently, and under contention?**
A document is the unit of atomicity *and* the unit of contention. Embedding gives you free
atomicity across the children and, in exchange, puts every writer of every child onto one
document. For 500 concurrent buyers this is the difference between a system and an outage.

**4. Does it need a constraint that only works collection-wide?**
A unique index applies across a collection. It **cannot** enforce uniqueness inside an array.
Any child carrying a uniqueness rule has to be its own document to keep it.

## The map

### Embedded

| Parent | Embedded child | Why |
|---|---|---|
| `venue` | `seatMap` (seats + elements) | Already a JSONB document in Postgres for the same reason. ~2,000 seats ≈ 150–180 KB, replaced whole on every edit, and **no query ever asks about one seat in it**. Q1 no, Q2 bounded, Q3 one writer. |
| `event` | `mapElements` | Non-sellable furniture. Same argument as the venue's map. |
| `ticketOrder` | `orderSeats` (1–8) | The textbook embed: bounded, immutable once written, and meaningless without the order. Also captures the price at hold time (invariant 10), so it *must* travel with the order rather than being re-derived. |

### Referenced

| Collection | Why not embedded in its obvious parent |
|---|---|
| `eventSeat` | **The central decision.** Up to 2,000 per event. Embedded that is ~500 KB rewritten on every single hold, with all 500 concurrent buyers serialised onto one document. Q2 and Q3 both fail, badly. |
| `ticket` | Read at the door by `codeLookup` with no Order in hand (Q1). Embedded in the order, every scan would search arrays across the whole collection. It also carries `ticket_one_per_seat`, a partial unique index (Q4). |
| `refund` | Looks like an embed — ≤1 live per order. But `refund_one_live_per_order` is a *partial unique index*, and Mongo cannot enforce uniqueness inside an array (Q4). Embedding would silently drop the guarantee. |
| `eventPricingTier` | **Referenced, and it should not be** — see the judgement call below. This is the one row in this table that records what the code does rather than what the rule says. |
| `membership` | N:M between user and organization, queried from both sides. |
| `scan`, `auditEntry`, `paymentEvent` | Append-only and unbounded (Q2). `auditEntry` is invariant 23; `paymentEvent` carries the webhook idempotency key as a collection-wide unique index (Q4). |
| `paymentSession` | Found by `providerRef` from a webhook that arrives with **no tenant and no order** (Q1). |
| `emailDelivery` | A worker queue with no tenant at all. |
| `appUser`, `organization` | Roots. |
| verification / reset / refresh tokens | Looked up by hash alone, never with the user (Q1). |

### The judgement call, and the one place this document was wrong

Pricing tiers are the row that could honestly go either way, and an earlier version of this page
claimed they were embedded. **They are not.** `PricingTier` carries
`@Document(collection = "eventPricingTier")` and has its own repository. The claim was written
from the design and never checked against the code, which is the same mistake as every other one
in this migration: *a claim in a document is worth nothing until something executes it.* It was
caught by reading the published event's actual BSON during an end-to-end run, not by reviewing
the design.

**Apply the four questions honestly and embedding wins:**

1. *Ever read without its parent?* **No.** The repository has exactly two methods,
   `findByEventId` and `findByEventIdIn`, and both are keyed by the parent. Nothing looks a tier
   up any other way.
2. *How many, bounded?* Two to six. Trivially bounded.
3. *Written independently under contention?* No. `SetPricingTiers` replaces the set, before
   publish, by one organizer.
4. *Needs a collection-wide constraint?* No unique index is declared on it.

**So what is the cost of leaving it referenced?** Two concrete things, and naming them is more
useful than the verdict:

- **An application-side join that would not otherwise exist.** `findByEventIdIn` is exactly the
  `$in`-batched join described below, and both listings pay it — one extra round trip per page,
  purely to reassemble something that could have arrived inside the event document.
- **A tenancy field that is now vestigial.** `PricingTier` carries `organizationId` for the same
  reason `OrderSeat` used to: an RLS policy needed something local to test. This page counts the
  removal of that field from `OrderSeat` as one of the migration's few genuine *gains*. Keeping
  tiers referenced is the same gain, declined.

**Why it is being left alone rather than quietly fixed:** changing it now would mean rewriting
the entity, the repository and eight call sites to make a document match a document, at the end
of an exercise whose whole subject is measured before-and-after behaviour. The discrepancy is
worth more written down than tidied away — it is the clearest example on this page of the gap
between a design that reasons correctly and an implementation that ported a table because it was
a table.

The trigger to revisit it is unchanged: if tiers ever become independently queryable — "every
event with a tier under 50,000 ₫" — question 1 flips and referencing becomes right for a reason
rather than by inheritance.

## Application-side joins, and why `$lookup` is not a join

Three ways to bring related documents together:

**1. Embed.** No join. The reason embedding is preferred where it fits.

**2. `$lookup` in an aggregation pipeline.** MongoDB's left outer join. Not a relational join:
there is no foreign key, no cross-collection query planner, and it executes per input document.
It is a tool for reporting, not for a request path.

**3. Application-side join.** Two queries: fetch the parents, collect the child ids, fetch them
with one `$in`. Chattier on paper and usually *faster*, because you control the batching and
nothing is executed per row.

This codebase uses **(3)** wherever a join survives — for example the seats of an event, or the
tickets of an order. One `$in` query per relationship, never one query per parent. Getting that
wrong is the N+1 problem, and MongoDB gives you no query planner to hide it behind.

## What no longer has a home

Recorded here because it is the substance of the comparison, not an afterthought.

- **10 row-level security policies.** No equivalent. Tenancy moves into application code, where
  forgetting the filter changes from *safe* (Postgres refuses) to *a data breach*.
- **3 immutability triggers.** `$jsonSchema` validators cannot see a document's previous value,
  so "a published seat map is immutable" cannot be enforced by the database at all.
- **~30 foreign keys** with `ON DELETE CASCADE` / `SET NULL`. Deletion becomes an application
  concern, in order, by hand.
- **`SELECT … FOR UPDATE`.** No pessimistic locking. See `02-comparison.md`.

## What is better

- **TTL indexes** expire the three token collections automatically. Postgres needs a sweeper.
- **Collation at strength 1** does accent-insensitive Vietnamese search natively *and can be
  indexed* — where `unaccent` is `STABLE`, not `IMMUTABLE`, so `V10` deliberately created no index.
- **`findOneAndUpdate`** expresses the door — "one conditional update whose row count is the
  answer" — more directly than SQL does.
