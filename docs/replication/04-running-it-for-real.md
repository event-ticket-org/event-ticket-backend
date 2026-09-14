# Running it for real

The first three pages are about a cluster built to be broken: three containers, a synthetic
workload, failures produced on purpose. This one is about the same ideas applied to a deployment
with real orders in it, and it is a different subject.

Everything below was learned by moving `tickets.aibles-java.site` onto a replicated cluster while
it was serving. The site stayed up throughout, except for one deliberate stop and one outage I
caused.

---

## The database should not have been a container

It was, and the argument that changed it is not about Postgres at all.

MinIO archived its community edition and **withdrew its Docker Hub repository**. Every
`minio/minio` tag became unpullable at once — the pinned release included. The running container
survived only because its image happened to still be on the disk, and a cleanup that judged the
image "no longer referenced" removed it. It was referenced.

That is the whole lesson, and it generalises past MinIO:

> **A container is disposable by definition. State is not.** The dividing line is not "containers
> bad" — it is which things you cannot reconstruct. Lose a backend image and you rebuild it from
> source. Lose a database volume and you have lost the orders.

Pinning a version protects you from a bad upgrade and **not at all from a publisher leaving**.

So: stateless services in containers, stateful ones on the host under systemd. What that buys
beyond safety is the packaging, which is better than it gets credit for — `pg_upgradecluster` for
major versions, apt security updates, systemd ordering that starts the database before Docker,
and `pg_createcluster` for running several clusters side by side. The primary and both standbys
are three clusters on one host, each with its own data directory, port, configuration tree and
unit, sharing only the `postgres` OS user. That is closer to production practice than three
containers, not further from it.

---

## Adding replication to a live primary, without restarting it

The rig in `01` was built replicated from nothing. A running deployment is not, and the question
is what a primary needs before a standby can clone it — and how much of that forces a restart.

Almost none of it:

| | | |
|---|---|---|
| `wal_level = replica` | already the default | — |
| `max_wal_senders = 10` | already the default | — |
| `max_replication_slots = 10` | already the default | — |
| a `replicator` role | `CREATE ROLE` | catalogue only |
| `host replication` in `pg_hba` | append and reload | SIGHUP |
| `max_slot_wal_keep_size` | `ALTER SYSTEM` + reload | SIGHUP |
| `wal_log_hints` | **not needed** | would have forced a restart |

That last row is the one worth knowing. `wal_log_hints` exists so `pg_rewind` can work, and
turning it on requires a restart. **PostgreSQL 18 enables data checksums at initdb, and checksums
give `pg_rewind` the same guarantee** — so a cluster created with `--data-checksums` never needs
it. Passing that flag explicitly rather than inheriting the default is worth the keystrokes,
because it is what keeps the statement above true if the default ever moves.

Assert the settings rather than assume them. A primary initialised by some other hand may not
have them, and a standby that clones and then cannot start is a much worse way to find out.

### `pg_dump` does not carry roles, and this schema depends on one

Moving the database between clusters is `pg_dump` plus `pg_restore`, and that is not enough.

Every transaction here begins `SET LOCAL ROLE eventticket_app`. The migration that created that
role will never run again — the restored `flyway_schema_history` says it is already applied. So a
restore without `pg_dumpall --roles-only` **succeeds**, and the application then fails at its
first request, which is the worst possible place to discover it.

---

## Three things must agree before anything can reach the database

`listen_addresses`, `pg_hba.conf`, and the host firewall.

The firewall is the one that fails silently, and it cost time three separate times:

> **`ufw` governs the INPUT chain, which is where container-to-host traffic lands.** Docker's
> `DOCKER-USER` bypass does not apply to it. And ufw **drops** rather than refuses — so the
> symptom is a connection that hangs until it times out, which reads exactly like the database
> being down rather than like a rule being missing.

The second and third times were standbys, and the reason is worth stating on its own:

> **A standby inherits none of the primary's access configuration.** `pg_createcluster` writes a
> fresh configuration tree; `listen_addresses` and `pg_hba.conf` live in `/etc` on Debian, and
> `pg_basebackup` copies only the data directory. Every standby needs its own three.

One more from the same family: `pg_basebackup` copies the primary's `port` into
`postgresql.auto.conf`, which is read **last** and so beats the cluster's own configuration. Both
standbys would have tried to bind the primary's port. Remove the line rather than override it.

---

## `max_slot_wal_keep_size` defaults to unlimited, and that is the dangerous direction

Failure 6 in `01` showed a dead replica pinning 246MB of WAL in 50 seconds. What it did not say is
what to do about it, and the answer is not obvious, because both settings lose something:

- **Unlimited** (the default): a standby that stops consuming pins WAL until the disk fills, and
  a full disk stops the **primary**. You trade a broken replica for a broken site.
- **Bounded**: a standby down longer than the bound has its slot invalidated and must be rebuilt
  from a fresh basebackup.

Bound it. Losing a replica is recoverable in minutes on a small database; losing the primary to a
full disk is an outage. **Choosing which failure you get is the whole of the decision** — there is
no setting that has neither.

---

## Why not Patroni

The obvious next step after manual promotion, and on a single machine it does not earn its place.

**All three clusters share a logical volume.** The failure Patroni exists for — a dead primary
with healthy standbys — barely exists here in isolation: whatever kills the primary takes the
disk, the kernel and the power supply with it. Patroni cannot promote a standby whose storage
died alongside the primary's.

And it would add **etcd on the same host**. If the DCS becomes unreachable, Patroni does the
correct thing for a distributed system and **demotes the primary** rather than risk split-brain —
so a local coordination hiccup takes the site read-only. Manual promotion has no such failure
mode.

> On one machine, Patroni can *lower* availability. It earns its keep at two machines and
> properly at three, where a node can fail independently and the DCS can be quorate somewhere the
> primary is not.

The honest ranking of what improves reliability here, cheapest first:

1. `Restart=on-failure` on the cluster units — Debian ships `Restart=no`, so a postgres killed by
   the OOM killer stays down until a human notices. That is the likeliest real failure, and
   restarting in five seconds beats promoting a standby: it leaves no diverged node to rebuild.
2. Monitoring that actually runs.
3. Backups that have been restored.
4. A second machine. Everything above is preparation for this; nothing replaces it.

---

## Monitoring is a thing that runs, not a list of queries

`01` ends with the queries worth running. Writing them down is not monitoring, and the proof
arrived unprompted.

The nightly backup had been **failing for two days** and nothing said so. Two of this week's
migrations broke it — six `docker compose exec -T postgres` calls against a service that no
longer existed, and a mirror aimed at a host that was gone. It failed loudly every time, into a
log nobody was reading, while the site looked perfect and the cluster was genuinely healthy.

So the queries became a script on a five-minute timer that exits non-zero, which puts the failure
in `systemctl --failed` — somewhere a person already looks. It asks:

- is the primary actually a primary, and each standby actually in recovery
- is each standby **streaming**, not merely alive, and how far behind
- are slots active and not `lost`
- does `synchronous_standby_names` name a standby that is gone — the failure that blocks every
  commit while `pg_isready` answers healthy
- is the newest backup recent, and did its restore-check pass
- did the backup unit itself fail

**The checker had a bug, and running it is what found it.** Postgres resolves `text || boolean`
through `anynonarray` and renders it `true`/`false`, where psql *displays* a boolean column as
`t`/`f`. A test against `'t'` marked every healthy slot inactive — exactly the failure the script
exists to catch, arriving in the script itself. A check that cries wolf is worse than none.

---

## A replica is not a backup, and an untested backup is not one either

Replication copies every mistake instantly. A `DELETE` with a bad `WHERE` is on all three nodes
before you have finished reading it. Standbys are for availability and read capacity; backups are
for being wrong.

Two properties make a backup real, and both are refusals:

- **It has been read back.** The nightly job restores its own dump into a scratch database and
  compares row counts against live, then writes the result to a file. Nothing downstream trusts a
  backup lacking that file. A dump that exists is a hypothesis.
- **It is not on the same disk as the database.** Until it is elsewhere, one failure takes the
  data and every copy of it — and the restore-check goes on passing right up to the moment there
  is nothing left to check.

And a third thing, specific to this schema: the archive must contain `.env`. A ticket code is a
random lookup plus a MAC under `TICKET_CODE_KEY`, and the code itself is never stored — so a
database restored without that key leaves every outstanding ticket unverifiable. A full house at
the door and no way to admit anyone. Which also means the archive **can mint tickets**, so it is
encrypted before it leaves the machine, and the passphrase has to live somewhere other than the
machine it protects.

---

## Operating a promotion

`01` established that nothing promotes anything. The remaining question is what a human does, and
what they must not do.

**Stop the old primary first.** Two nodes answering `pg_is_in_recovery() = false` means HAProxy
keeps both in the write pool and balances writes across a real primary and a diverged one. That
is worse than the outage being fixed, and it is why the promotion script refuses rather than
warns.

**HAProxy needs nothing.** It asks each node what it is and moves the write door within seconds.
That is the entire reason it asks rather than trusting a hostname: after a promotion the names
are wrong and nothing renames them.

**The application does not recover on its own.** `on-marked-down shutdown-sessions` closes the
pool's connections to the condemned node — necessary, because HikariCP would otherwise keep
writing to it for minutes — and the pool has been observed not to recover from that inside its
30-second `connectionTimeout`. Plan on restarting it.

**The old primary is not a standby.** It is a diverged node on an older timeline, and starting it
as-is gives you two primaries. `pg_rewind` it, or rebuild it — which on a small database takes
seconds and has no way to go subtly wrong.

---

## What this setup does not buy

Stated plainly, because every page before this one describes machinery and machinery is
persuasive:

**Not availability.** Three clusters, one disk, one kernel, one power supply. What it buys is
read capacity, a backup target off the node serving traffic, read-your-own-writes correctness,
and somewhere to rehearse a failover before performing one for real.

The single change that would alter that sentence is a second machine. Everything here is already
shaped to accept one: the standbys are ordinary clusters, HAProxy already routes by asking rather
than by name, and the application already treats a replica as something that may be stale or
absent.
