# PostgreSQL → MongoDB

The exercise, in three documents. Read them in order; each assumes the one before.

| | |
|---|---|
| [`01-document-model.md`](01-document-model.md) | **The design.** The four-question rule for embedding, the full collection map, why `$lookup` is not a join. |
| [`02-comparison.md`](02-comparison.md) | **What it cost and bought**, measured. Every claim has a number or a file behind it. |
| [`03-viva.md`](03-viva.md) | **The questions, answered from this branch** — including four traps where the obvious answer is wrong. |

## The state of it

**192 of 192 tests pass.** Same API, byte-identical contract, `main` still on PostgreSQL for
comparison.

**That is the most misleading true sentence here**, and `03-viva.md` says why: four of those green
tests assert a *loss*. `PublishedSeatMapIsFrozenTest` proves a published event's seat map can now
be corrupted. The two tenancy tests count a collection unfiltered and assert they see **every**
tenant's rows. They pass by proving the guarantee is gone.

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
