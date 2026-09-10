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

**"MongoDB is schemaless, so migration is easy."** The schema moved into the application, it did
not disappear. Every rule Postgres enforced is now code that must be right everywhere, forever.

**"Collation makes accent-insensitive search better."** True for *equality* and indexable. **False
for the title search** — `$regex` ignores collation entirely, so it needs a denormalized folded
field. A wash on speed, a loss on complexity.

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

1. **Run it sooner.** The prediction made by reading code (113 free / 61 rework / 11 impossible)
   was wrong in both directions. Every defect that mattered was behavioural, and no amount of
   reading would have found the missing `save()` calls.
2. **Intercept at `MongoTemplate`, not at the repository.** Tenant filtering is enforced by
   convention plus a build-time test. The stronger design subclasses `MongoTemplate` so every
   query — including Spring Data's derived ones — passes through one choke point. It was not built
   here because of time, and that is a real gap rather than a considered omission.

---

## The state of the work, stated accurately

**181 of 191 tests pass** (6 written for this migration). The 10 failures:

- **4** — seat counts. The aggregation demonstrably returns the right values under the right id;
  something between it and the DTO drops them. Not diagnosed. Do not claim it is.
- **2** — `RedemptionConcurrencyTest`. No row locks. Expected, and the point.
- **2** — the rewritten tenancy tests, whose new expectations are still guessed rather than
  measured.
- **1** — `DeploymentConfigurationTest`, which reads `application.yml` for a datasource that no
  longer exists.
- **1** — `AdmissionTest`.

If asked whether the migration is finished: **no.** It runs, the hard problems are solved and
measured, and ten tests are outstanding — four of them a real defect that is not yet understood.
