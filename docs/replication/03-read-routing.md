# Routing reads to a replica

The cluster in [`README.md`](README.md) exists so the application can use it. This is how it
does, what had to be true first, and the one thing that still does not work.

```bash
docker compose -f compose.replication.yaml up -d

DATABASE_URL=jdbc:postgresql://localhost:5100/eventticket \
APP_DATASOURCE_REPLICA_URL=jdbc:postgresql://localhost:5101/eventticket \
  ./mvnw spring-boot:run
```

With `APP_DATASOURCE_REPLICA_URL` unset — a fresh clone, the test suite, the single-node dev
stack — none of this exists and the application behaves exactly as it did before.

---

## The rule

```
not read-only                        -> primary
read-only, no recent write by you    -> replica
read-only, you wrote recently        -> primary, until the replica has replayed your write
```

`@Transactional(readOnly = true)` is sufficient and safe on its own. A new read endpoint inherits
the guarantee without its author knowing any of this is here.

**The alternative was classifying the eighteen read-only use cases by hand**, and it was rejected
because the classification has to be re-made by every future developer — and because it had
already begun to slip. [`02-this-project.md`](02-this-project.md) calls three of them "mild risk,
acceptable", which is a judgement, not a standard.

---

## Two addresses, neither of which is a hostname

| Port | Reaches | Chosen by |
|---|---|---|
| 5100 | the node answering `pg_is_in_recovery() = false` | health check |
| 5101 | the standby serving reads | health check |

HAProxy asks each node what it is rather than trusting its name, and this cluster had already
demonstrated why: after the promotion drill in [`01-what-went-wrong.md`](01-what-went-wrong.md),
the container called `pg-primary` reported `pg_is_in_recovery() = true` while `pg-standby-1`
served writes. **Names do not survive a failover. Nothing renames a container.**

`option pgsql-check` cannot express this — it proves a server accepts a startup packet and
nothing more — so the image carries `psql` and an `external-check` script.

Three things about that script were learned by running it:

- **`external-check` sanitises the environment.** Variables set on the container in
  `compose.yaml` never arrive: `CHECK_PASSWORD: parameter not set`. The credentials moved to a
  baked `.pgpass`.
- **It sanitises `PATH` too.** The second symptom was `tr: not found` for a binary that is
  plainly installed. Nothing about the environment can be assumed.
- **macOS binds port 5000 to the AirPlay Receiver.** Publishing HAProxy there gives you a
  listener that accepts connections and is not a database; the application hangs at
  `HikariPool-1 - Starting...` with no error at either end. Hence 5100/5101.

---

## The three pieces in the application

| | |
|---|---|
| `ReplicaRoutingConfig` | conditional on the property; builds both targets **inside** the bean method |
| `ReadWriteRoutingDataSource` | the rule above |
| `ReplicaFreshness` + `LastWriteStore` | where each user's write reached, and how far the replica has replayed |

### Exactly one `DataSource` bean, and it is not a style choice

Boot's `DataSourceAutoConfiguration` backs off on `@ConditionalOnMissingBean(DataSource.class)`,
and `JdbcTemplateAutoConfiguration` needs `@ConditionalOnSingleCandidate`. `PersistenceConfig`,
`TenantPublisher` and `ApiTest`'s `JdbcTemplate` all inject by type — and `ApiTest` is the base
class of every HTTP test. A second unqualified `DataSource` bean would take the whole suite down,
so the primary and replica are plain objects, never beans.

### The bug that made all of this do nothing

The first working version routed on
`TransactionSynchronizationManager.isCurrentTransactionReadOnly()`. It compiled, it ran, the
suite passed, and **every single query went to the primary.**

`AbstractPlatformTransactionManager` starts a transaction in this order:

```
doBegin(transaction, definition);            // the connection is acquired here
prepareSynchronization(status, definition);  // the read-only flag is published here
```

and `TenantAwareTransactionManager.doBegin` needs a real connection immediately, to issue
`SET LOCAL ROLE` before any application query runs. So the router was asked which node to use at
a moment when the flag read `false` for everybody.
`LazyConnectionDataSourceProxy` does not rescue this — it defers the connection until the first
statement, and `doBegin` *is* the first statement.

**Nothing about this failure is visible.** No error, no warning, correct data every time — the
replica simply never receives a query. It was found by pausing replication, writing a row
straight to the primary, and watching the application return it anyway:

```
primary really has  : 3 events
replica really has  : 2 events
the APP returns     : 3 events      <- reading the primary
```

The fix is `RoutingContext`: the transaction manager is handed the `TransactionDefinition` and
therefore knows the answer before anybody asks, so it publishes it before calling `super`.

> A routing bug in this direction fails safe, serves correct data, and quietly does nothing.
> That is precisely why the check has to assert the replica **is** used, rather than inferring it
> from the configuration.

---

## Proving the guard, rather than trusting it

With replay paused on the replica, at the same instant, two readers:

```
the writer GET /venues      -> 1 venue    (their own, created seconds earlier)
the replica actually holds  -> 1 venue    (a different organization's)
the primary actually holds  -> 3 venues

anonymous public listing    -> 2 events   (stale on purpose; the primary has 3)
```

The writer's organization was created while replay was paused, so **the replica has never heard
of it** — `orgs named 'Guard Org' — primary: 2, replica: 0`. Their read returning a venue is only
possible from the primary.

Same replica, same moment, opposite routing, decided by whether that person had written.

Resume replay and the anonymous listing returns 3: back on the replica, and fresh.

### Comparing log positions is where this would silently break

Postgres prints an LSN as `0/3DAF1088`, and the obvious implementation compares those as strings.
Lexicographically `"0/9"` sorts **after** `"0/10"`, while `0x9` is plainly before `0x10` — so a
string comparison reports "caught up" at exactly the moments it is not, for some values and not
others. `Lsn` parses each half as hex and compares numerically; `LsnTest` pins it.

And the position must be read **after** the commit. `insert … returning pg_current_wal_lsn()`
evaluates before the commit record is written, and reports SAFE while the replica is a row
behind. Tested against a paused replica; see [`02-this-project.md`](02-this-project.md).

---

## What still does not work: failover is not transparent

HAProxy follows a promotion perfectly. The **application** does not.

Stopping the primary and promoting `pg-standby-1`:

```
:5100 -> in_recovery=false, server=192.168.158.4   (the newly promoted node, no config change)
:5101 -> connection refused                        (no standby left in the reader pool)
```

A `psql` through both paths confirmed the infrastructure was healthy. The running application
was not:

```
HikariPool-1 - Connection is not available, request timed out after 30002ms (total=0, active=0, idle=0)
Caused by: org.postgresql.util.PSQLException: This connection has been closed.
```

Every request returned 500 for as long as it was left. **After a restart, everything worked** —
reads 200, writes 202, landing on the new primary, with reads correctly falling back to the
primary because the reader pool is empty.

So the honest statement is: *this survives a failover with an application restart.* The pool does
not recover on its own.

`on-marked-down shutdown-sessions` is necessary — without it HikariCP would keep writing to a
node the health check had already condemned — but it is not sufficient. Closing the sessions is
what the pool then has to recover from, and with a 30-second `connectionTimeout` and a primary
that was disappearing and reappearing to the health checker, it did not.

Worth being clear about what would close this gap, none of which is done here:

- a shorter Hikari `connectionTimeout` and a `keepaliveTime`, so dead connections are discovered
  rather than waited on
- `targetServerType=primary` with both hosts in the JDBC URL, letting the driver find the primary
  itself rather than relying only on the proxy
- **Patroni**, which is the real answer: it performs the promotion rather than following one, and
  the rest of the stack is built to be told about it

That last point is the theme of this whole exercise. PR #30 records that Postgres has no
elections and that promotion is a human step. This document records the other half: **an
application does not automatically survive the promotion either.**
