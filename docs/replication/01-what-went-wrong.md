# Everything that broke, and what it looked like from outside

Eight failures. Five were hit while building the cluster and three were caused deliberately.
Every number here came from running it.

---

## Getting it running: five traps

### 1. The base backup succeeds, then the server refuses to start

```
"root" execution of the PostgreSQL server is not permitted.
```

A standby cannot be `initdb`-ed into existence — it must be *cloned*, because WAL is a stream of
physical changes to specific blocks and the two servers must start byte-identical. That needs a
custom entrypoint, and overriding the image's entrypoint also removes the job nobody thinks
about: **stepping down from root**. The clone works perfectly and the container exits 1.

**The shape to remember:** replacing an entrypoint silently drops everything else it did.

### 2. Two standbys cloning at once destroy each other's WAL

```
ERROR: requested WAL segment 000000010000000000000002 has already been removed
```

Each `pg_basebackup` forces a checkpoint, and one checkpoint recycled a segment the other's WAL
receiver still needed. The fix is the runbook advice everyone repeats without saying why:
**create the replication slot first, then take the backup** — rather than letting
`pg_basebackup -C` create it along the way.

### 3. …and creating the slot first changed nothing

Same error. The slot existed, was listed in `pg_replication_slots`, and looked entirely healthy.

```sql
pg_create_physical_replication_slot('standby1')        -- restart_lsn NULL: reserves NOTHING
pg_create_physical_replication_slot('standby1', true)  -- reserves WAL immediately
```

The second argument is `immediately_reserve`. Without it a slot reserves no WAL **until a
streaming client first attaches** — so "create the slot before the backup" is advice that does
nothing unless you also pass `true`. Every diagnostic says the slot is present.

### 4. `ALTER SYSTEM` succeeds and changes nothing

`ALTER SYSTEM SET synchronous_standby_names` returned success, `pg_reload_conf()` returned
success, and `SHOW synchronous_standby_names` still returned the old value. No warning, no
conflict, no log line.

```
command line  >  postgresql.auto.conf (ALTER SYSTEM)  >  postgresql.conf
```

A `-c` flag in the compose `command:` outranks `ALTER SYSTEM` permanently. **Anything intended to
be tuned at runtime must not be a command-line flag**, which is why `synchronous_standby_names`
is not one in `compose.replication.yaml`.

### 5. Synchronous replication silently never happens

`synchronous_standby_names = 'ANY 1 (standby1, standby2)'` matched nobody. It matches on
**`application_name`**, not slot name, not hostname — and both standbys reported the driver
default, `walreceiver`. `sync_state` simply stayed `async` for everyone.

The name comes from `primary_conninfo`, so `pg_basebackup` must be given a full conninfo
containing `application_name=` for `-R` to record it.

**The pattern across 3, 4 and 5:** a setting that is present, parses, reports success, and does
nothing. None of them produce an error. Only `SHOW` and `pg_stat_replication` tell the truth.

---

## Breaking it on purpose: three failures

### 6. A dead replica fills the primary's disk

A replication slot guarantees a standby can always catch up, by making the primary retain WAL
for it. That guarantee **is** the failure mode.

One standby stopped, ordinary write load:

| Elapsed | WAL pinned by the inactive slot |
|---|---|
| 25 s | 99 MB |
| 50 s | **246 MB** |

Roughly **4 MB/s, or 14 GB/hour, from one offline standby**. A replica that dies on Friday
evening fills the primary's disk over the weekend — and a Postgres that cannot write WAL stops
accepting writes. **A dead replica takes down the primary.**

**The protection, and what it costs.** `max_slot_wal_keep_size = 64MB`:

```
LOG:  invalidating obsolete replication slot "standby2"
DETAIL:  The slot's restart_lsn 0/1CBCAE80 exceeds the limit by 272847232 bytes.
```

```
wal_status          | lost
invalidation_reason | wal_removed
```

WAL is released, the primary is safe, and the standby is now **permanently dead**:

```
FATAL: could not start WAL streaming: ERROR: can no longer access replication slot "standby2"
```

retrying forever. The only way back is a **full re-clone** — there is no incremental repair.

> There is no safe value. Unbounded slots risk the primary; bounded slots sacrifice the standby.
> You choose which failure you prefer, and then you monitor for the one you chose.

And while permanently dead, that standby reported `healthy`, `accepting connections`, and served
**223,841 rows against the primary's 296,564** — 153 seconds behind and diverging, with nothing
to indicate it.

### 7. The primary vacuums rows out from under a standby's query

```
ERROR:  canceling statement due to conflict with recovery
DETAIL:  User query might have needed to see row versions that must be removed.
```

A 25-second read on the standby was killed after about two. It did nothing wrong — the *primary*
vacuumed rows it was still reading, and replaying that vacuum conflicted with its snapshot.
`pg_stat_database_conflicts.confl_snapshot` counts these.

`hot_standby_feedback = on` fixes it: the standby tells the primary which row versions it still
needs. The identical transaction then completed. The cost, measured on the primary immediately
after:

```
dead tuples in pgbench_accounts that VACUUM could not remove: 21,431
```

**The bloat is bounded by the longest query running on the standby**, which is why production
pairs `hot_standby_feedback = on` with a `statement_timeout` — otherwise one forgotten report
holds the primary's garbage collection open indefinitely.

Note this is a **standby** setting. Setting it on the primary parses, appears in `SHOW`, and does
nothing at all — which is what this repository did until `SHOW hot_standby_feedback` was run on
all three nodes and answered `on`, `off`, `off`.

### 8. Synchronous replication takes the database down

With `ANY 1 (standby1, standby2)`:

| State | Result |
|---|---|
| both standbys up | committed |
| the fast standby stopped | committed — quorum falls back to the slow one |
| **both stopped** | **commits hang indefinitely** |

And from outside, in that last state:

```
pg_isready       → "accepting connections"
container health → healthy
reads            → instant
writes           → frozen
```

**The subtlest part, and the one with real consequences for application code:** a commit blocked
waiting for a standby **has already committed locally**. An `INSERT` was timed out at the client —
which received nothing and would reasonably conclude the write failed — and a separate session
could immediately read the rows.

> The client believes the write failed. The data is there. Everyone else can see it.

Any code that retries on timeout will write twice. This is the argument for idempotency keys on
anything that moves money or issues a ticket.

---

## Failover, and what does not survive it

The primary was killed with SIGKILL. **Nothing happened** — both standbys stayed in recovery,
stayed healthy, kept serving reads. `pg_promote()` on one of them produced a new primary on
**timeline 2**.

Four things then needed doing by hand:

1. **Replication slots do not replicate.** The new primary had none; every standby's slot had to
   be recreated on it.
2. **Every other standby is orphaned**, still retrying the dead host — and `healthy` while doing
   it. Each needs `primary_conninfo` repointed.
3. **Sequences jump.** An id went from 1 to **34**: `last_value=34, log_cnt=32`, because Postgres
   WAL-logs sequence values in blocks of 32 and a failover skips the remainder of the block.
   Harmless for surrogate keys, fatal for anything that must be gapless.
4. **The old primary comes back as a primary.** Restarting it produced two primaries, neither
   aware of the other, both accepting writes — and both allocated **id 34** to different rows.
   Same primary key, different content, two servers each believing they are authoritative.

`pg_rewind` resolves it: it found the divergence point, copied **198 MB of 402 MB** (cheaper than
a re-clone), and the zombie's write was **discarded permanently**. That is not a bug — rewinding
*means* choosing one history and throwing the other away.

Two preconditions that must be arranged in advance:

- **`wal_log_hints = on`** must already be set. You cannot turn it on after you need it.
- **A clean shutdown.** `pg_rewind` refuses a crashed data directory.

And one trap after: **`pg_rewind -R` writes the credentials you ran the rewind with**, which is an
admin account, not the replication role. The node then failed with `no pg_hba.conf entry for
replication connection ... user "eventticket"` and retried forever until `primary_conninfo` was
rewritten.

---

## What to monitor, since none of this is visible by default

`pg_isready` answers "is a process listening" and nothing else. Each line below detects a
failure above that it cannot:

```sql
-- 6: a slot retaining WAL, and whether it has been invalidated
select slot_name, active, wal_status, invalidation_reason,
       pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) as retained
from pg_replication_slots;

-- 8 and failover: how many standbys are connected, and are they satisfying the quorum
select application_name, state, sync_state,
       pg_wal_lsn_diff(sent_lsn, replay_lsn) as replay_lag_bytes
from pg_stat_replication;

-- on each standby: how far behind in TIME, which is what users feel
select now() - pg_last_xact_replay_timestamp() as lag;

-- 7: queries being killed by recovery conflicts
select * from pg_stat_database_conflicts;

-- am I even the primary any more
select pg_is_in_recovery();
```

**Alert on the count in `pg_stat_replication` falling below what your quorum requires**, not on
the database being up. In failure 8 the database was up the entire time.
