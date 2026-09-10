# What the migration cost, and what it bought

Measured against the same suite, running the same API, from the same commit.

## The number

| | |
|---|---|
| Tests passing on MongoDB | **192 of 192** |
| Of which, new tests written for this migration | 7 |
| Predicted before starting | 113 free / 61 rework / 11 impossible |
| Defects found *after* the suite was green, by running the system | **2** |

The migration is complete: the same API, the same contract, every test green.

**The prediction was wrong in both directions**, and that is the first finding. It said eleven
tests could never pass; all of them do, because the four that tested a *database* guarantee were
rewritten to assert the loss instead - they pass by proving the guarantee is gone. It was made by
reading the code: counting which tests touched SQL and which did not. Every defect that actually
mattered was invisible to that reading, because each was a *behavioural* difference between two
datastores rather than a syntactic one between two query languages.

## The three that stopped everything

Each was found by running the system, none by reading it.

Two more were found **after** the suite was green, by starting the application by hand — the
wrong database and the losers' 500. They are at the bottom of this page under
[what a green suite does not prove](#what-a-green-suite-does-not-prove), and they are the two
most worth understanding, because nothing in the test run was capable of reporting either.

### 1. There is no dirty checking

The largest correctness hazard in the migration, and the one to be able to explain.

Under JPA, an entity loaded inside a transaction is **managed**: mutating it is enough, and the
flush at commit writes the change. MongoDB has no persistence context and no managed state, so
an object loaded, mutated and not saved **was never changed**.

```java
AppUser user = users.findOrThrow(token.userId());
user.markEmailVerified();          // correct under JPA. A no-op here.
```

Nothing warns. Nothing fails. The transaction commits successfully having done nothing at all.
`VerifyEmail` logged success and returned a session for an account it had not verified — and 148
of 185 tests then failed at the *next* step, reporting "email not verified" from a different
class. **The symptom appears nowhere near the cause.** Thirteen sites had this shape.

### 2. A UUID has four incompatible encodings

Postgres has a native `uuid` type: one representation, no ambiguity. MongoDB stores one as BSON
Binary with a *subtype*, and four are in circulation — the modern standard plus three legacy byte
orders from the old Java, C# and Python drivers. A UUID written under one and read under another
comes back as **a different UUID**, silently.

The driver therefore refuses to encode a UUID until told which to use. Every primary key here is
a UUID, so nothing could be written at all. That is the *good* failure mode. The bad one is
picking a legacy representation and discovering later that documents cannot be found by their own
ids.

### 3. Transactions require a replica set

MongoDB implements transactions on the oplog, and a standalone `mongod` has none — it does not
run them slowly, it **refuses them**. Testcontainers 1.x initiated a replica set by default; 2.x
made it opt-in, so the container ran standalone and every transaction failed.

The application started cleanly and the smoke test passed, because nothing asks whether the
datastore can do transactions until something tries one.

## What was lost

### Row-level security — the big one

Ten policies, gone, with nothing to replace them. In Postgres the tenant was published into the
database session and the database applied it to every statement, so **forgetting the tenant
filter was safe**: the query returned nothing. Here, forgetting it returns *another
organization's data*, with a 200.

The policies were also not one rule. Three shapes: owned-by-tenant; owned-by-tenant-**or
published** (a published event is public on purpose); owned-by-tenant-**or buyer** (a buyer is
not a member of the organization they buy from). A naive "add `organizationId` everywhere"
reproduces the first and silently breaks the other two.

`TenantScopeTest` fails the build when a repository exposes a query that narrows by nothing.
That is a weaker guarantee, honestly stated: Postgres made the mistake *impossible*; the best
available here is making it *detectable*.

And be precise about how much weaker. Twenty of the twenty-eight queries on those collections
narrow by a **parent** — an event id, an order id — and are safe only because the caller loaded
that parent and checked it first. Under RLS they were safe regardless. That list is not an
exemption list, it is the **risk register**: the exact set of places where isolation stopped
being a mechanism and became a convention.

### Triggers

`$jsonSchema` validators cannot see a document's **previous value**, so "a published seat map is
immutable" (KB invariant 12) is inexpressible. `PublishedSeatMapIsFrozenTest` now asserts the
opposite — that a published event's seats can be relabelled and its `publishedAt` nulled, and
nothing objects. Those tests pass, and they are failures.

Losing `venue_in_use` also forced an **architectural** compromise, not just a code change. This
codebase deliberately chose a trigger over an `if`, because asking "does a published event use
this venue?" in Java makes `venue` depend on `event`, which already depends on `venue`. The check
is back in application code, querying the `event` collection *by name* so Modulith still passes —
honest about the package graph, dishonest about the truth.

### `SELECT … FOR UPDATE`

There is no pessimistic lock, and this cost more than anything else on this list.

Putting the condition in the filter is *exactly* as correct per document — no read-then-write
gap, so no check-then-act. The loss is across documents: `updateMulti` is atomic per document,
so an order for three seats can win two and must release them. Nothing is double-sold; the
invariant holds.

**The part that reading the code did not predict** is what happens to the losers. Postgres
*queued* them: `SELECT … FOR UPDATE` made the second buyer block, wake when the winner
committed, re-evaluate, and receive a 409 naming the seats that had gone. A MongoDB transaction
is optimistic, so there is nothing to wait on — the loser's transaction is **aborted**:

```
WriteConflict (112)   errorLabels: ["TransientTransactionError"]
```

The driver's contract is that the caller retries the whole transaction. Nothing did, so eleven
of twelve racing buyers received `500 "The request could not be completed."` where Postgres had
given eleven civil 409s. `TransientRetry` now restores the 409 by re-running the transaction.

**Behaviour matches again. The cost profile does not.** Postgres queued each loser once;
retrying makes them redo the entire unit of work — the Event, the seats, the pricing tiers —
and a retry storm is the worst possible load to add at an on-sale spike, which is the one
moment this is guaranteed to happen. That is the trade in its honest form: **identical API,
strictly worse behaviour under pressure.**

### Smaller, real

- **~30 foreign keys.** No cascades. Deleting a venue now deletes its draft events by hand, in
  order, and will keep needing to as collections are added.
- **`BIGSERIAL`.** No auto-increment. Either an `ObjectId` (timestamp-prefixed, roughly
  monotonic) or a sequence collection bumped by `findAndModify` — one extra round trip and one
  contended document per insert.
- **Startup-time query checking.** A JPQL constructor expression failed at boot. A pipeline is
  assembled at runtime out of nested `Document`s: a mistake surfaces as a wrong number several
  layers away, or as nothing at all. Both aggregation bugs in this migration were that shape.
- **Accent-insensitive substring search.** See the correction below.

## What was gained

Fewer than the losses, and not where a beginner would guess.

- **Embedding deletes whole classes of bug.** With `orderSeats` inside the Order, the money bug
  that needed a CTE — summing across a join multiplies every order by its seat count — *cannot be
  written*, because there is no join. Two hand-written application-side joins in the listings
  disappeared for the same reason.
- **Embedding removed a tenancy field.** `OrderSeat` carried `organizationId` and `buyerUserId`
  only so an RLS policy had something local to test; a policy cannot see a parent row. An embedded
  document is reached *through* its parent and inherits its scope.
- **An entire class of JPA bug leaves with JPA.** `@Modifying(clearAutomatically = true,
  flushAutomatically = true)` — which silently discarded a password change in this project — has
  no counterpart, because there is no context to flush or clear.
- **TTL indexes** expire the token collections automatically. Postgres needs a sweeper.
- **`findOneAndUpdate`** expresses the door — *one conditional update whose modified count is the
  answer* — more directly than SQL does. This is the single place the migration reads better.
- **Plain unique indexes port exactly** — including `payment_event`'s webhook idempotency key.
  Partial ones port *nearly*: see the correction below.

## Two corrections worth carrying into the viva

### Partial unique indexes do **not** port one-to-one

Written up as a straight win before it was tried. A `partialFilterExpression` admits `$eq`,
`$exists`, the range operators, `$type`, `$and`, `$or` and `$in` — and **rejects `$ne`**:

```
CannotCreateIndex: Expression not supported in partial index: $not
```

Both of this system's partial indexes were negations (`WHERE status <> 'VOID'`,
`WHERE status <> 'REFUND_FAILED'`), so both had to become **enumerations of every other value**.
That is not the same statement: `<> 'VOID'` adapts to a new status automatically and correctly,
while `$in: ["VALID", "REDEEMED"]` silently stops covering one. Add a ticket status tomorrow and
the uniqueness rule quietly narrows — no error, no failing test.

### A unique index treats a missing field as null; Postgres treats NULLs as distinct

The difference that would have caused a production incident. `V8` dropped `NOT NULL` from
`refund.provider_ref` *precisely* so many refused refunds — which never reached a provider and so
have no reference — could coexist. Postgres allows that for free.

Ported literally, that index permits **exactly one refused refund in the entire system** and
rejects every one after it, and the failure arrives on the second refund a provider declines.
The fix is a partial index on `providerRef: {$exists: true}`. Found by a test that was not
looking for it.

## A third correction

**Collation is a win for comparisons and not for the title search.** Case- and accent-insensitive
*equality* (email, city) folds inside the comparison and is indexable — genuinely better than
Postgres's expression index.

But **`$regex` ignores collation entirely**: an `i` option for case, nothing for accents. A title
search is a substring match, so it cannot use collation, and the MongoDB answer is a denormalized
folded copy of the field maintained on write (`Event.titleFolded`). Postgres could not index that
expression either — `unaccent` is `STABLE`, not `IMMUTABLE` — so the honest verdict is **a wash on
speed and a loss on complexity**, not the improvement it first looks like.

## Would this migration be worth doing?

For this system, **no**, and the reason is specific rather than general.

This application's two hardest requirements are a multi-tenant isolation boundary and a seat-hold
race. Postgres answers both *in the database*: policies that cannot be forgotten, and row locks
that produce exactly one winner. MongoDB answers neither, and the replacements are application
code that must be right everywhere, forever, with a build-time test as the only backstop.

What MongoDB is good at — an aggregate that is genuinely one document, read whole, written whole —
this domain has in exactly two places: the venue seat map and the order with its seats. Both were
*already* modelled that way in Postgres, one as `jsonb` and one as a small child table.

The interesting conclusion is not "MongoDB is worse". It is that **the document/relational choice
should follow the shape of the invariants, not the shape of the reads.** A system whose rules are
mostly single-aggregate would come out the other way, and the parts of this system that *are*
single-aggregate ported cleanly and in some cases improved.

## What a green suite does not prove

Both of these were found after "192 of 192" was written down, by starting the application and
looking at what it actually did. Neither could have failed a test.

### The application was using the wrong database entirely

Spring Boot 4 moved the MongoDB connection properties out of Spring Data into their own module
and renamed the prefix: `spring.data.mongodb.uri` and `spring.data.mongodb.database` are
deprecated at **level `error`**, which means removed. An unknown property is ignored in
silence, so the whole block was inert, the driver used its defaults, and every collection and
every document lived in MongoDB's default database, `test`.

The suite cannot see this. Testcontainers' service connection hands Boot a client built from
the container, so **no test reads those properties at all**. It also hid a second bug behind
the same cause: `MongoUuidConfig` existed to set the UUID representation on the client builder,
and its javadoc blamed Testcontainers for the property "not reaching" the client. The name
`spring.data.mongodb.uuid-representation` was simply dead too. Using
`spring.mongodb.representation.uuid` deleted the entire class with no behaviour change.

### Eleven of twelve buyers were told the server had broken

Covered under `SELECT … FOR UPDATE` above. The point *here* is why the suite reported success:

```java
(response.getStatusCode() == HttpStatus.CREATED ? created : refused).incrementAndGet();
```

**Anything that was not a 201 counted as a civil refusal.** One winner and eleven losers is what
the test asserted, and one winner and eleven losers is exactly what it got — with the eleven
receiving a 500. The A/B table comparing the two builds had the identical flaw: it counted
winners, both stacks said "1 of 6", and it printed `same`.

The lesson generalises past MongoDB. **A test that counts outcomes cannot see a change in what
an outcome *is*.** Assert the refusal a caller receives, not the absence of success — and when
two systems are being compared, compare the losing path, because that is where a datastore's
concurrency model actually shows.
