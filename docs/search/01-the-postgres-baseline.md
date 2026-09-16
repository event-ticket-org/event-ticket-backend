# 01 — The Postgres baseline

The listing's text search, measured before anything is replaced. This is the control that
makes the Elasticsearch comparison mean something: without it, the comparison would be against
a query that was never indexed, which proves only that an unindexed scan is slow.

Reproduce with `scripts/search-benchmark.sh`. It runs against a throwaway container and never
against a deployment.

## What the query was

`V10__unaccented_title_search.sql` added the `unaccent` extension and **deliberately added no
index**, with the reason written down: `unaccent()` is `STABLE`, an index expression must be
`IMMUTABLE`, and the wrapper that bridges the two "is a lie the planner believes". It closed
with *"revisit with a measurement, not with a hunch"*. This is the measurement.

So every search ran:

```sql
lower(unaccent(title)) LIKE lower(unaccent('%…%')) ESCAPE '\'
```

Leading wildcard, no index, title only.

## The numbers

200,000 events, Vietnamese titles, Postgres 18, best of three:

| | without the index | with it |
|---|---|---|
| title only | **65.51 ms** | **0.11 ms** |
| title or description | 190.07 ms | 189.77 ms |

Plans, which matter more than the milliseconds:

```
without   Limit -> Gather Merge -> Sort -> Seq Scan
with      Limit -> Sort -> Bitmap Heap Scan -> Bitmap Index Scan on event_title_trgm_idx
```

The index is 6.8 MB for 200,000 rows.

## Three things worth taking from this

**The index is reached.** That is not a formality — a trigram index on an expression is used
only when the query's expression matches it *exactly*, and `unaccent` versus
`immutable_unaccent` is enough to miss. The benchmark asserts the index name appears in the
plan and fails if it does not, because an index nothing reaches costs writes and saves nothing,
and looks identical from the outside to one that works.

That assertion caught its own bug first: it read only the top three plan nodes, and the index
scan is the fourth. It reported failure against an index that was working, which is the
harmless direction — the other way round it would have passed forever.

**The second row is the interesting one.** Widening the query to the description (requirements/009
criterion 18) costs 190 ms with or without the index, because only the title is indexed. Adding
the other three fields would mean three more GIN indexes on a table written far more often than
this listing is read. That trade is not made here; the number is recorded so it can be made
later on evidence.

**At this project's real scale, none of this matters.** The live database holds ten published
events. Both plans are a fraction of a millisecond there, and the sequential scan V10 chose was
the right call for the data that existed. What changed is not the performance, it is that a
second ordering and a wider query are now in the contract — and the index is what keeps the
first field of the widened query from getting worse as the catalogue grows.

## What this does not measure

Relevance. There is none: the listing has one ordering it can compute, and `sort=RELEVANCE` is
refused rather than quietly downgraded. Ranking, typo tolerance, and facet counts computed by
the search engine rather than by a second `GROUP BY` are what the comparison is actually about,
and none of them are things a `LIKE` can be made to do faster.
