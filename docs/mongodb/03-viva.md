# Viva preparation

The questions a teacher is likely to ask about this migration, and answers grounded in what
actually happened here rather than in general knowledge. Where a claim has a number behind it,
the number is real and reproducible from this branch.

---

## "When do you embed, and when do you reference?"

Four questions, in order. This is the whole framework and it fits on one hand.

1. **Is it ever read without its parent?** If yes, embedding forces every reader to fetch the
   parent too.
2. **How many, and is it bounded?** The 16MB limit is the famous answer and rarely the operative
   one — MongoDB rewrites the *whole document* on update, so the practical ceiling is far lower.
3. **Is it written independently, and under contention?** A document is the unit of atomicity
   **and** the unit of contention. Embedding buys free atomicity across children and pays for it
   by putting every writer onto one document.
4. **Does it need a constraint that only works collection-wide?** A unique index applies across a
   collection and **cannot** enforce uniqueness inside an array.

**Give the two concrete cases from this system:**

- `orderSeat` → **embedded.** 1–8 per order, never read without the order, immutable once
  written. All four questions favour it.
- `eventSeat` → **referenced.** Up to 2,000 per event. Embedded that is ~500 KB rewritten on
  *every single hold*, with 500 concurrent buyers serialised onto one document. Questions 2 and
  3 fail badly.

**Same domain, same kind of relationship, opposite answers.** That contrast is the answer.

---

## "What is `$lookup`? Is it a join?"

It is MongoDB's left outer join, and it is **not** a relational join. No foreign key, no
cross-collection query planner, and it executes per input document. It belongs in reporting, not
on a request path.

The third option — and the one used here — is an **application-side join**: fetch the parents,
collect the child ids, fetch them with one `$in`. Chattier on paper, usually faster, because you
control the batching and nothing runs per row. One `$in` per relationship, never one query per
parent; that is the N+1 problem, and MongoDB gives you no planner to hide it behind.

**The example to cite** is the public listing. The SQL ended:

```sql
and e.organization_id in (select o.id from organization o where o.status = 'APPROVED')
```

MongoDB cannot express that in a query at all. Doing it in application code costs three things:
a second round trip; an **unbounded intermediate list** whose size the query author does not
control; and **two snapshots** — an organization approved between the two calls is seen by one
and not the other, where Postgres read both from one snapshot.

---

## "Is MongoDB ACID?"

Yes, with two qualifications that matter more than the label.

- A write to a **single document** is atomic, always, with no transaction, no lock hint and no
  replica set. This has been true since long before multi-document transactions existed, and it is
  what the document model is *for*.
- **Multi-document** transactions exist since 4.0 (replica sets) and 4.2 (sharded), and they
  **require a replica set** — a standalone `mongod` refuses them outright, which this migration hit
  for real.

**The interesting half of the answer:** conflicts *abort* rather than queue. Postgres under
`SELECT … FOR UPDATE` makes the second writer wait; MongoDB raises a write conflict and you retry
the whole transaction. Under high contention, retries thrash.

---

## "Why do people say not to use MongoDB transactions? Did you?"

Three different reasons get compressed into one slogan:

1. **History.** They did not exist until 2018. Much of the advice predates them.
2. **The real argument:** needing one *often* is evidence the document model is wrong. That is a
   claim about your schema, not about the feature.
3. **Real costs:** replica set required, 60-second default lifetime, conflicts abort and retry.

**Used them here, deliberately, and the split is by contention, not principle:**

| Flow | Contention | Decision |
|---|---|---|
| Seat hold | High — 500 buyers, same event | **No transaction.** Redesigned. |
| `ConfirmPayment` | Low — one webhook per order, seats already held by it | **Transaction.** |
| Door scan | High on one ticket | **Neither** — single-document `findOneAndUpdate`. |

And be ready for the follow-up: **`ConfirmPayment`'s multi-document transaction is not a symptom
of bad schema design.** Even the ideal document model touches two aggregates — the order and the
event's seats — because there are 2,000 of the latter under concurrent writes. They are genuinely
different aggregates. This is the case where "align aggregates to documents" runs out.

---

## "What did you lose?"

Lead with the sentence, then the list:

> In Postgres, forgetting the tenant filter was **safe** — the policy caught you. In MongoDB,
> forgetting it is a **data breach**.

- **10 row-level security policies**, in three distinct shapes.
- **3 triggers.** `$jsonSchema` validators cannot see a document's previous value, so
  "immutable once published" is inexpressible.
- **`SELECT … FOR UPDATE`.** Two losers instead of one winner, under contention.
- **~30 cascading foreign keys.**
- **Startup-time query checking.**

---

## "Give me a concrete example of something that broke"

Five, and they are better than the general answers because each one has a symptom, a cause and
a fix that are all in different places. Tell them as stories. The last two were found **after**
the suite was green, which is the point of telling them.

### The door started confirming a rival's ticket

CLAUDE.md's rule: **tenant isolation outranks a helpful error message.** Another organization's
ticket must come back `UNKNOWN_CODE`, never `WRONG_EVENT` — because `WRONG_EVENT` tells the staff
holding the scanner that this code *is* a real ticket, sold by someone else.

The `ticket_access` policy used to narrow that lookup. Without it, `findByCodeLookup` found the
rival's ticket, the next line compared event ids, and the door answered `WRONG_EVENT`. **Nothing
failed.** A test written months before the migration is the only reason it was caught.

**The part worth telling:** `TenantScopeTest` had classified that lookup as *safe by key* — a
ticket code is unguessable, so knowing it is the authorisation. That reasoning sounds right and
is wrong. **A structural test can check that a query is narrowed; it cannot check that it is
narrowed *enough*.** The behavioural test caught what the architecture test waved through, and
you need both.

### The door's transaction turned a resolvable race into a 500

Under Postgres, `@Transactional` on the scan was free and correct: the conditional `UPDATE` took
a row lock, three simultaneous losers blocked on it, and each woke to find a status that was no
longer `VALID`. One winner, three civil refusals.

The identical update inside a MongoDB transaction produces:

```
WriteConflict: Write conflict during plan execution
errorLabels: ["TransientTransactionError"]
```

and the three losers get a 500. **MongoDB transactions are optimistic — a conflict aborts rather
than queues**, and the driver expects the caller to retry the whole thing. At a gate with four
scanners on one code, that is a retry storm in place of a queue.

**The fix was not to retry.** It was that the door never needed a transaction: one conditional
update to one document is atomic on its own, with no replica set and no lock hint. The annotation
was carrying nothing and costing everything. What genuinely weakens: the scan row and the ticket
update are no longer atomic together, so a process dying between them loses an audit row.

### A null that was never written

The last four failures, and the one to lead with if asked about *silent* bugs.

```java
{ $eq: ["$soldAt", null] }     // looks right. is not.
```

A seat that has never been sold has **no `soldAt` field at all** — Spring Data omits nulls when
it writes — so this compared a *missing* field to null and did not do what it reads like. Every
seat counted as unavailable, and the public listing told buyers **"0 of 6 left"** for an event
with every seat free.

Nothing threw. The `total` beside it was correct, which cost an hour: the aggregation
demonstrably returned `total=6` under the right `eventId`, so the defect *looked* downstream, and
it was one field to the left the whole time. `$not` is the reliable form — true for missing, null
and false alike.

**Why this is the shape that matters:** it was the third bug in this migration with the same
signature. A SQL projection is checked when the application starts; `sum(case when s.sold_at is
null ...)` either parses or it does not. A pipeline is assembled at runtime out of nested
`Document`s, and a mistake does not throw — **it returns a plausible wrong number in a field
somebody reads and believes.**

### Eleven of twelve buyers were told the server had broken

The one to lead with if asked about **concurrency**, because it is the whole Postgres/MongoDB
difference reduced to one HTTP status code.

Twelve buyers press "buy" on the same seat at the same instant.

| | Postgres | MongoDB |
|---|---|---|
| Winner | 1 | 1 |
| Losers get | `409 SEATS_UNAVAILABLE`, naming the seats | `500 "The request could not be completed."` |

**Postgres locks pessimistically.** `SELECT … FOR UPDATE` made the eleven losers *block*. They
waited, woke when the winner committed, re-evaluated, and were refused with a status about
seats. The database queued the contention.

**MongoDB transactions are optimistic.** There is nothing to wait on, so the loser's
transaction is aborted: `WriteConflict (112)`, labelled `TransientTransactionError`. The
driver's contract is that the caller retries the whole transaction. Nothing retried, so the
abort surfaced as a `DataIntegrityViolationException` and reached a buyer as a 500.

The fix is `TransientRetry`: run the transaction again, up to five times, with jittered
backoff — jittered because twelve buyers aborted by one winner would otherwise all retry in the
same millisecond and race each other. **The 409 is back. The cost is not the same.** Postgres
queued each loser exactly once; retrying makes them redo the whole unit of work, and a retry
storm arrives precisely at an on-sale spike.

**It was not one use case, and that is the real answer to this question.** The same abort turned
up in three places, each found a different way:

| Use case | How it was found | What the user saw |
|---|---|---|
| `BeginCheckout` | six scripted simultaneous buyers | 500 instead of "those seats have gone" |
| `VerifyEmail` | **running the real frontend** — React fires the effect twice, two requests 5 ms apart | 500 on the registration happy path |
| `ResetPassword` | looking for the shape, then proving it with a test | 500 instead of "that link was already used" |

So the general rule, which is what a teacher is actually asking for: **any transaction two
callers can enter for the same document is a 500 waiting to happen.** Under a row lock that case
was boring — the second caller waited and then read the truth. Under an optimistic transaction it
is a defect, and nothing reveals it until two requests genuinely arrive at once.

**And be ready for "why didn't a test catch it", because that is the real question.**
`SeatHoldConcurrencyTest` said:

```java
(response.getStatusCode() == HttpStatus.CREATED ? created : refused).incrementAndGet();
```

Anything that was not a 201 counted as a refusal. It asserted one winner and eleven losers and
got exactly that — with the eleven holding a 500. **A test that counts outcomes cannot see a
change in what an outcome is.**

The A/B table built to compare the two running stacks had the *same* flaw and printed `same`,
because it counted winners too. The difference was only ever visible in the losers' response
bodies. **When comparing two datastores, compare the losing path** — the winner's path is where
they agree, and the concurrency model only shows itself in what happens to whoever lost.

### The application ran perfectly against the wrong database

The best of the four, because it survived a **green suite** and a working API at the same time.

After every test passed, the application was started by hand for the first time and driven
through the whole journey - register, publish, buy, scan. All of it worked. Then a check of
what the server actually held:

```
> db.adminCommand('listDatabases')
admin  config  local  test
```

There is no `eventticket` database. Sixteen collections, every document, in MongoDB's default
database `test`, because **Spring Boot 4 moved the MongoDB properties out of Spring Data into
their own module and renamed the prefix**: `spring.data.mongodb.uri` and
`spring.data.mongodb.database` are deprecated at level `error`, which means removed. An unknown
property is not an error - it is ignored in silence - so the whole block was inert, the driver
fell back to its defaults, and `localhost:27017` with no database named is `test`.

Two things make it worth telling:

- **No test could have caught it.** Testcontainers' service connection hands Boot a client
  built from the container, so the suite never reads those properties at all. 192 green tests
  say nothing whatsoever about how this application connects to a database in a deployment.
- **It hid a second bug behind the same cause.** `MongoUuidConfig` existed to set the UUID
  representation on the client builder, and its javadoc blamed Testcontainers for the property
  "not reaching" the client. Testcontainers had nothing to do with it: the name
  `spring.data.mongodb.uuid-representation` was equally dead. Once
  `spring.mongodb.representation.uuid` was used instead, the entire class deleted with no
  behaviour change. **A wrong explanation that produces a working workaround is expensive** -
  it stops the search.

**The general point, and the one to make out loud:** the migration's other three defects were
differences between two datastores. This one was not - it was a configuration key nobody typed
wrong, that simply no longer meant anything. The suite passing is evidence that the code is
right. It is not evidence that the system is *configured*, and the only thing that produces
that evidence is starting it and looking at what it touched.

---

## "What did you gain?"

Be honest that it is a shorter list, and make the strongest point first:

- **Embedding deleted a class of bug.** The money bug that needed a CTE cannot be *written* once
  the seats are inside the order, because there is no join to multiply.
- **Embedding removed a tenancy field**, because an embedded document inherits its parent's scope.
- **TTL indexes** expire the token collections; Postgres needed a sweeper.
- **`findOneAndUpdate`** expresses the door better than SQL does — the one place the migration
  reads *better*.
- **Plain unique indexes port exactly**, including the webhook idempotency key. (Partial ones do
  not — see the traps below, and do not claim they do.)
- An entire class of JPA bug — `@Modifying` flush/clear — leaves with JPA.

---

## Traps: questions where the obvious answer is wrong

**"Your design document says pricing tiers are embedded."** They are not, and admitting it is
the better answer. `PricingTier` is its own collection, the design page claimed otherwise, and
the claim was never checked against the code until an end-to-end run showed the published event's
actual BSON. Applying the four questions honestly, embedding wins on all four — so the cost of
the mismatch is real and nameable: one application-side join per listing that need not exist, and
an `organizationId` on the tier that exists only because an RLS policy once needed something
local to test. **The design reasoned correctly; the implementation ported a table because it was
a table.** `01-document-model.md` now records that rather than hiding it.

**"MongoDB is schemaless, so migration is easy."** The schema moved into the application, it did
not disappear. Every rule Postgres enforced is now code that must be right everywhere, forever.

**"Collation makes accent-insensitive search better."** True for *equality* and indexable. **False
for the title search** — `$regex` ignores collation entirely, so it needs a denormalized folded
field. A wash on speed, a loss on complexity.

**"The tests pass, so the migration works."** They prove the code is right and say nothing
about the configuration: the whole application ran against MongoDB's default database
`test` with 192 tests green, because Testcontainers supplies the connection and no test
ever reads the connection properties.

**"You just swap the repository layer."** 96 compile errors, then three behavioural defects that
compiled perfectly and were invisible to the compiler.

**"The 16MB document limit is the constraint on embedding."** Rarely. The real one is that the
whole document is rewritten on update, and that it is the unit of contention.

**"Unique constraints port straight across."** Plain ones do. But a `partialFilterExpression`
**rejects `$ne`**, so a partial index written as a negation has to become an enumeration of every
other value — correct today, quietly wrong the day a status is added. And a MongoDB unique index
indexes a **missing field as null**, where Postgres treats NULLs as distinct: ported literally,
`refund_provider_ref_unique` would have allowed exactly one refused refund in the entire system.

---

## If asked what you would do differently

Two honest answers:

1. **Run it sooner** — and this is the strongest lesson in the exercise, because the evidence
   kept arriving after each point where the work looked finished. The prediction made by reading
   code (113 free / 61 rework / 11 impossible) was wrong in both directions. Then the suite went
   green, and starting the application by hand immediately found that it had been using the
   wrong database the whole time. Then the journeys matched on all 40 steps, and running a
   *race* found eleven of twelve buyers holding a 500.

   Then the races matched too, and running the **real frontend** found that every registration
   had been able to 500, because React fires its effect twice and the second verify-email
   request lost a write conflict.

   Each stage was a real check and each one was passed. **Every stage also missed something only
   the next stage could see**, and they get progressively harder to fake:

   | Stage | What it caught | What it could not see |
   |---|---|---|
   | Reading the code | the 96 compile errors | anything behavioural |
   | The test suite | 3 defects that stopped everything | the wrong database; the losers' status |
   | Running both stacks, same journey | nothing — 40 of 40 steps matched | anything needing two callers |
   | Running them under contention | seat-hold 500s | anything only a real client does |
   | Running the **real frontend** | verify-email 500s | — |

   The last row is the one to make out loud: **a hand-written script is a well-behaved client**,
   and well-behaved clients do not double-submit. The browser did.
2. **Intercept at `MongoTemplate`, not at the repository.** Tenant filtering is enforced by
   convention plus a build-time test. The stronger design subclasses `MongoTemplate` so every
   query — including Spring Data's derived ones — passes through one choke point. It was not built
   here because of time, and that is a real gap rather than a considered omission.

---

## The state of the work, stated accurately

**192 of 192 tests pass**, seven of them written for this migration. The API is unchanged, the
contract is byte-identical, and the suite is green.

Both builds were then started side by side — PostgreSQL on 8081, MongoDB on 8090 — and driven
through the same journey by hand: register, verify, publish, price, buy, pay, issue tickets,
scan at the door, refund. **40 API steps, every one identical**, plus three concurrency races
with matching outcomes down to the losers' status codes.

Be precise about what that does *not* mean:

- **Four of those green tests assert a loss.** `PublishedSeatMapIsFrozenTest` now proves a
  published event's seats *can* be relabelled and its `publishedAt` nulled. `EventTenancyTest`
  and `OrderTenancyTest` count a collection with no filter and assert they see **every**
  tenant's rows. They pass, and they are failures - receipts for guarantees that were traded
  away.
- **Tenant isolation is a convention backed by a build-time test**, not a mechanism. Twenty of
  twenty-eight queries are safe only because a caller checked a parent first.
- **The stronger design was not built.** A `MongoTemplate` subclass injecting the tenant into
  every query, including Spring Data's derived ones, is the answer that would make forgetting
  impossible again. It is a gap, not a considered omission.
- **Retrying restores the behaviour and not the cost.** `TransientRetry` gives the losing buyer
  their 409 back by re-running the whole transaction. PostgreSQL queued each loser once; this
  makes them redo the work, and it does so at an on-sale spike, which is the only time it
  happens. The API is identical; the load profile is worse.

If asked whether the migration is finished: **the port is; the system is not equivalent.** It
does the same things and it defends itself less well, and the tests that pass are the evidence
for both halves of that sentence.
