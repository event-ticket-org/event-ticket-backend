# PostgreSQL → MongoDB

The exercise, in three documents. Read them in order; each assumes the one before.

| | |
|---|---|
| [`01-document-model.md`](01-document-model.md) | **The design.** The four-question rule for embedding, the full collection map, and what `$lookup` measurably is and is not. |
| [`02-comparison.md`](02-comparison.md) | **What it cost and bought**, measured. Every claim has a number or a file behind it. |
| [`03-viva.md`](03-viva.md) | **The questions, answered from this branch** — including four traps where the obvious answer is wrong. |

## The state of it

**192 of 192 tests pass.** Same API, byte-identical contract, `main` still on PostgreSQL for
comparison. Both builds were then run side by side and driven through the same journey by hand:
**40 API steps compared, all identical**, plus three concurrency races.

**"192 of 192" is the most misleading true sentence here**, for two separate reasons.

*The first is what the green tests assert.* Four of them assert a **loss**.
`PublishedSeatMapIsFrozenTest` proves a published event's seat map can now be corrupted. The two
tenancy tests count a collection unfiltered and assert they see **every** tenant's rows. They
pass by proving the guarantee is gone.

*The second is what no test could reach.* Four real defects were found **after** the suite was
green — by starting the application, then by racing it, then by putting the real frontend in
front of it:

- The whole application was reading and writing MongoDB's default database, `test`, because
  Boot 4 removed the `spring.data.mongodb.*` connection properties and an unknown property is
  ignored in silence. Testcontainers supplies the connection directly, so **no test reads those
  properties at all**.
- Eleven of twelve racing buyers were told `500 "The request could not be completed."` where
  PostgreSQL had given them `409 SEATS_UNAVAILABLE`. A MongoDB transaction aborts on conflict
  instead of queueing, and nothing retried. The concurrency test counted anything that was not a
  201 as a refusal, so it never saw it.
- **Every registration could 500.** With the real frontend in front of it, React's development
  mode fires the verify-email effect twice; the two requests arrive ~5 ms apart, one verifies and
  the other loses a write conflict. A hand-written test script is a well-behaved client and never
  double-submits — the browser does.
- **Password reset had the identical defect**, found by looking for the shape once the first two
  were understood, and proven with a test before anything was changed.

All four are fixed, and they are in `02-comparison.md` under *what a green suite does not
prove*. Two lessons come out of them, and they are the most useful things in the exercise:

1. **A test that counts outcomes cannot see a change in what an outcome is.** One winner and
   eleven losers was asserted, and delivered — with the eleven holding a 500.
2. **Any transaction two callers can enter for the same document is a 500 waiting to happen.**
   A row lock made that case boring; an optimistic transaction makes it a defect, and only two
   genuinely simultaneous requests reveal it.

## The one-paragraph answer

The migration works and the system is not equivalent. Its two hardest requirements — a
multi-tenant isolation boundary and a seat-hold race — were both answered by PostgreSQL *inside
the database*, with policies that could not be forgotten and row locks that produced exactly one
winner. MongoDB answers neither, and the replacements are application code that must be right
everywhere, forever, with a build-time test as the only backstop.

What MongoDB is genuinely good at — an aggregate that is one document, read whole, written whole —
this domain has in exactly two places, and both were *already* modelled that way under PostgreSQL:
one as `jsonb`, one as a small child table.

So the conclusion is not "MongoDB is worse". It is that **the document/relational choice should
follow the shape of the invariants, not the shape of the reads.**
