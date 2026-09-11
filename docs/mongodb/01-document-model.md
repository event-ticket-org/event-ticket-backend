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

## Joining without embedding: `$lookup`, `$in`, and denormalisation

An earlier version of this section was titled *"why `$lookup` is not a join"* and said it had
"no cross-collection query planner", that it "executes per input document", and that it "belongs
in reporting, not on a request path". **All three were wrong**, and measuring them on the
MongoDB this project actually runs is what showed it.

### What `$lookup` really does, measured

Server 8.0.30, against this system's own collections:

| Situation | Strategy the planner chose |
|---|---|
| `event → organization` on an indexed field | **`IndexedLoopJoin`** (uses the index) |
| `event → ticket` on a field with no index | **`HashJoin`** |

So there *is* a planner and it *does* choose a join algorithm — the same two algorithms a
relational engine picks between. And it streams rather than materialising: asking for a page out
of 20,000 events where only 10% survive a post-join filter,

| Page size | Documents examined |
|---|---|
| 21 | 448 |
| 51 | 1,984 |
| 101 | 4,032 |

It stops as soon as the page is full. It does not scan the collection.

**It also expresses the query this page used to claim was impossible.** The listing's
`... and e.organization_id in (select o.id from organization where o.status = 'APPROVED')` is a
`$lookup` followed by a `$match`, and suspending one organization drops exactly that
organization's events from the result. Verified by planting the suspension and watching the count
fall from 7 to 6 and back.

### The differences that are real

1. **The output is an array, not a row product.** `as: "org"` nests the matches in a field. This
   is a genuine *advantage*: the money bug that needed a CTE in Postgres — summing across a join
   multiplies every order by its seat count — cannot be written this way.
2. **No referential integrity.** A dangling reference produces `[]`, and a later
   `$match` on a field inside it silently drops the document. A data bug is then indistinguishable
   from a business rule. A foreign key made the dangling case impossible in the first place.
3. **Stage order is the author's problem.** `$limit` before the post-join `$match` returns short
   pages and reports no error: asking for 21 returned **3**. There is no SQL equivalent of that
   mistake, because a `WHERE` clause has no position to get wrong.
4. **No join reordering across many collections.** A relational planner reorders an N-way join on
   statistics. Irrelevant at two collections, real at five. Not this system's problem, and it
   should not be claimed as one.
5. **Tenancy, and this is the one that actually decided it here.** Under RLS, joining applied the
   policy to *both* sides automatically. A `$lookup` reads the foreign collection **unfiltered**
   unless its sub-pipeline says otherwise — and `TenantScopeTest`, which inspects repository
   method signatures, cannot see inside a hand-assembled pipeline at all. Every `$lookup` is a
   hole in the one mechanism that replaced row-level security.

### So the three options, honestly

**1. Embed.** No join at all. Preferred wherever the four questions allow it.

**2. `$lookup`.** A real join, indexed, streaming, page-friendly. Reach for it when the
relationship is genuinely a join and the tenancy is handled explicitly in the sub-pipeline.

**3. Application-side join** — fetch the parents, collect the ids, fetch them with one `$in`.
What this codebase uses, and **the reason is not performance**. It is that a `$in` batch is an
ordinary repository method that the build-time tenancy test can see and check, where a pipeline
is opaque to it. That is a statement about *this* system's missing RLS, not about `$lookup` being
slow. One `$in` per relationship, never one query per parent — that is the N+1 problem, and here
the planner genuinely cannot help, because the queries are issued from Java.

**4. Denormalise**, which the earlier version failed to mention and which is the answer an
experienced MongoDB developer gives first. `Event.titleFolded` already does it for search. Copying
the organization's approval onto the event would remove the join entirely — paid for with a
fan-out write whenever an organization is approved or suspended, and the risk of the copy going
stale. *That* is the document-model trade, and it is the one worth arguing about.

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
