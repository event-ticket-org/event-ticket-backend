# PostgreSQL replication

A three-node cluster — one primary, two streaming standbys — and what running it taught.

```bash
docker compose -f compose.replication.yaml up -d
```

Ports 5433 (primary), 5434, 5435. The everyday single-node stack on 5432 is untouched and can
run at the same time; `docker compose up -d` still does what it always did.

| | |
|---|---|
| [`01-what-went-wrong.md`](01-what-went-wrong.md) | **Every failure, measured.** Eight things that broke, what each looked like from outside, and what to monitor so it does not surprise you twice. |
| [`02-this-project.md`](02-this-project.md) | **What this application would have to solve** to use a replica: which of its 18 read-only use cases could tolerate lag, and which are read-your-own-writes. |

---

## The headline, stated carefully

**Postgres replication is not MongoDB's replica set, and the difference is not cosmetic.**

The MongoDB migration needed a replica set because a standalone `mongod` *refuses transactions* —
without one, the application did not work at all. It was a precondition. Postgres does
transactions perfectly well on a single node, so replication here is a choice, and it buys
different things:

| | MongoDB replica set | Postgres primary/standby |
|---|---|---|
| Why you need it | transactions refuse to run without it | you do not *need* it |
| What it gives | correctness | read capacity, failover, backups off the hot node |
| Automatic failover | **yes** — majority election, built in | **no** — nothing is promoted, ever |
| Cost of skipping it | the application does not work | it works, until a disk dies |

### "2N+1 nodes" means something different here

This cluster has three nodes, and the reason is **not** the reason MongoDB has three.

MongoDB elects a primary by majority vote, so an odd count avoids ties. **Postgres has no
election and no consensus.** This was not taken on trust — the primary was killed with SIGKILL
and both standbys sat there, `in_recovery`, *reporting healthy*, serving reads, for as long as
they were left alone. Nothing promoted anything. The cluster was read-only until a human ran
`pg_promote()`.

What the third node actually buys is **quorum commit**, and that is measurable. With 50 ms of
artificial latency on one standby:

| Configuration | Commit latency |
|---|---|
| asynchronous | 1.39 ms |
| synchronous on the fast standby | 1.98 ms |
| synchronous on the **slow** standby | **57.71 ms** |
| **`ANY 1 (standby1, standby2)`** | **1.97 ms** |
| `ANY 2` — both must acknowledge | 57.33 ms |

`ANY 1` gives synchronous durability **at the speed of the fastest standby**. A single
synchronous standby ties every commit on the system to that one node's latency, permanently.

**Automatic failover needs Patroni or repmgr on top of this.** That is a deliberate omission
here, not an oversight, and it is the single biggest gap between this cluster and a
production one.

---

## The one lesson that outranks the rest

Eight distinct failures were produced on purpose. **The standard health checks were green
through every single one.**

| Failure | What `pg_isready` and Docker said |
|---|---|
| Synchronous replication with no standby left | `healthy` — writes frozen indefinitely |
| Replication slot invalidated, standby permanently dead | `healthy` — serving 223,841 rows against the primary's 296,564 |
| Standby query killed by recovery conflict | `healthy` — the error only ever reaches the client |
| Primary dead, nothing promoted | `healthy` on both standbys — cluster read-only |

`pg_isready` answers "is a process listening". It cannot tell you whether that process is
replicating, how far behind it is, whether it can still accept a write, or whether it is a
primary at all. **Every one of those is a separate query, and a deployment that does not run
them is not monitoring its cluster.** The list is at the end of
[`01-what-went-wrong.md`](01-what-went-wrong.md).
