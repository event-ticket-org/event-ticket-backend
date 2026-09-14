# Two things this cluster does not use

Both come up whenever Postgres clustering does, and both are easy to reach for at the wrong
moment. Neither is in use here, and this page is mostly about why — which turns out to be more
useful than instructions would be.

---

# Connection pooling

## There are three pools, not two

`shared/persistence/ReplicaRoutingConfig.java` builds one more than the obvious count:

| pool | built by | what it serves |
|---|---|---|
| primary | `dataSource()` | writes |
| replica | `dataSource()` | routed reads |
| replica, again | `replicaFreshness()` | the 200 ms LSN poller |

The third is separate deliberately. `ReplicaFreshness` exists to answer "has the replica replayed
past this caller's last write", and asking the *router* for a connection in order to decide what
the router should do is circular — so it holds the replica directly. That is correct, and it is
still a full connection pool serving five queries a second.

**Nothing sets `maximumPoolSize`.** Not in `application.yml`, not in the config class. All three
take Spring Boot's default of **10**, so a single application instance can hold **thirty
connections**.

## The arithmetic that makes it matter

Measured on the deployment: `max_connections = 100` on every node,
`superuser_reserved_connections = 3`, eight cores.

| instances | connections | |
|---|---|---|
| 1 | 30 | today |
| 2 | 60 | fine |
| 3 | 90 | three left for the superuser, and no room to restart |
| 4 | — | the fourth instance fails to start |

The ceiling is not the interesting part, though. **Postgres does not get faster past roughly two
to four times the core count in *active* connections.** Every connection is an operating-system
process with memory behind it; beyond that point they contend rather than contribute. On eight
cores, ninety connections is *slower* than thirty.

So the failure mode is not "we ran out" — it is that adding instances to serve more traffic makes
each query slower, and the graph that would show you this looks like normal load.

## The cheap half, stated and not applied

The freshness poller runs one query every 200 ms. It needs one connection, or two for safety, not
ten. That is roughly eight connections per instance reclaimed for nothing, by setting a number.

It is not set here because nothing is near the ceiling yet, and a number changed without a reason
is a number nobody can later justify.

## PgBouncer, and why transaction mode happens to be safe here

The real fix, when there are several instances, is an external pool: many client connections
multiplexed onto few server connections. PgBouncer in **transaction** mode gives a client a
server connection for the length of a transaction and takes it back at `COMMIT`.

Transaction mode normally breaks anything that keeps state on the session, because the next
transaction gets a different server connection — and this application keeps a great deal of state
per transaction. It publishes the tenant that every row-level security policy reads.

It is safe anyway, and by luck earned from a decision made for another reason:

```sql
-- shared/tenancy/TenantSql.java
select set_config('app.organization_id', ?, true), set_config('app.user_id', ?, true)
```

**The trailing `true` is `is_local`.** It is the function form of `SET LOCAL`, chosen — per the
javadoc on `TenantSql` and `TenantAwareTransactionManager` — because the statement form cannot
take a bind parameter and the alternative was concatenating a tenant id into SQL.

Beside it, `SET LOCAL ROLE eventticket_app` is transaction-scoped too, for an unrelated reason:
[ADR-0002](../adr/0002-application-runs-as-an-unprivileged-role.md) drops to an unprivileged role
because a superuser bypasses every policy and `FORCE ROW LEVEL SECURITY` does not change that.

Both die at `COMMIT`. The server connection goes back to the pool carrying nothing.

> Had either been published with a session-scoped `SET`, transaction pooling would hand one
> organization's role to another organization's query, silently, and the only safe setting would
> be session mode — which pools almost nothing. Two decisions taken for unrelated reasons — not
> concatenating an id into SQL, and not trusting a superuser — are what make the pooler viable
> years later.

**The one thing that does break:** pgjdbc promotes a statement to a server-side prepared statement
after five executions, and a server-side prepare belongs to the connection that made it. Under
transaction pooling the next execution may land elsewhere. PgBouncer 1.21 and later track them;
before that, `prepareThreshold=0` in the JDBC URL is the fix.

**Where it goes:** one PgBouncer per node, *behind* HAProxy — so the proxy still decides which
node is the primary by asking `pg_is_in_recovery()`, and the pooler only pools.

## Not yet

One instance, thirty connections, a hundred available. Introducing a pooler now would add a
process between the application and its database, a second thing to monitor, and a new way for
connections to be held open — to solve a problem that does not exist.

The condition to watch for is a second application instance, not a number of connections.

---

# Logical replication

## What it actually is

This project replicates **physically**: the primary ships 8KB WAL blocks, and each standby is a
byte-identical copy of the entire cluster — read-only, same major version, all databases.

Logical replication decodes that same WAL into **row changes** — this insert, that update — and
replays them through a `PUBLICATION` on the source and a `SUBSCRIPTION` on the target. The target
is an ordinary, writable database that happens to be receiving changes.

| | physical (this cluster) | logical |
|---|---|---|
| unit | 8KB blocks | row changes |
| scope | the whole cluster | chosen tables |
| target | read-only standby | a normal, writable database |
| major versions | must match | may differ |
| DDL | replicated | **not replicated** |
| sequences | replicated | **not replicated** |

The two rows in bold are the whole story.

## Where it would genuinely earn its place

**A major-version upgrade with seconds of downtime.** Build a PostgreSQL 19 cluster, subscribe it
to the 18 primary, let it catch up, then stop writes and point the application at it. Physical
replication cannot do this at all — both ends must be the same major version.

**A reporting copy.** A writable target can carry indexes the transactional database should not,
and a heavy analytical query there costs the primary nothing. Physical standbys can serve reads
but cannot be indexed differently, because they are byte-identical by definition.

## Why it is not a replacement for the standbys

**DDL does not replicate.** Add a column on the source and the subscriber does not get it —
replication of that table then breaks. Every Flyway migration has to be applied on both sides, in
the right order, by hand or by something built for the purpose. On a project with thirteen
migrations and more coming, that is not a one-off cost; it is a standing hazard attached to every
release.

**Sequences do not replicate**, and this one should feel familiar. `01-what-went-wrong.md` records
what happened after the failover drill: an id jumped from 1 to 34, and when the old primary came
back there were two primaries that **both allocated id 34 to different rows** — same primary key,
different content, each server believing it was authoritative.

Physical replication produced that by accident, during a failure. **Logical replication has it as
a property**: the subscriber's sequences simply are not the publisher's, so any switchover means
advancing every sequence by hand first, and forgetting one means duplicate keys in a table that
had none.

**There is no `pg_rewind` equivalent.** After a switchover the old publisher is not a subscriber,
and there is no tool that rewinds it into one. Failover semantics are yours to build.

## The verdict

Logical replication is an **upgrade and data-distribution** tool. Physical replication is the
availability and read-scaling tool. They solve different problems and are not alternatives — and
reaching for logical replication to get a standby is how a project acquires a replica that
silently stops receiving a table the first time somebody adds a column.

---

## What both sections have in common

Each describes a well-made tool that this deployment should not currently adopt, for the same
underlying reason: **the problem it solves is one instance or one machine away, and adopting it
early buys complexity now against a benefit later.**

The conditions are specific, and worth knowing rather than guessing at:

- **PgBouncer** when there is a second application instance.
- **Logical replication** when there is a major version to cross, or a reporting workload heavy
  enough to be worth isolating.
