#!/usr/bin/env bash
#
# Turns an empty postgres container into a standby of pg-primary, then starts it.
#
# A standby is not configured into existence - it is *cloned*. Postgres replication ships the
# write-ahead log, which is a stream of physical changes to specific blocks in specific files,
# so the two servers must start from byte-identical data directories. That is what
# pg_basebackup does, and it is why this script exists at all: the official image knows how to
# initdb a brand new cluster, and a standby must emphatically not be one.
set -euo pipefail

PGDATA=${PGDATA:-/var/lib/postgresql/data}
PRIMARY_HOST=${PRIMARY_HOST:-pg-primary}
export PGPASSWORD=${REPLICATION_PASSWORD:?REPLICATION_PASSWORD is required}

# This script replaces the image's entrypoint, so it inherits that entrypoint's other job:
# stepping down from root. Postgres refuses to run as root outright -
#
#   "root" execution of the PostgreSQL server is not permitted.
#
# - which is what the first version of this file did, after a base backup that had worked
# perfectly. The clone succeeds, the server will not start, and the container exits 1.
#
# Everything below therefore runs the Postgres tools through gosu, exactly as the official
# entrypoint does, and the data directory is made to belong to postgres first: a fresh named
# volume is created by Docker owned by root when the path does not exist in the image, and
# PGDATA here is a path the image does not ship.
mkdir -p "$PGDATA"
chown postgres:postgres "$PGDATA"
chmod 0700 "$PGDATA"

if [ -s "$PGDATA/PG_VERSION" ]; then
    echo "standby[$REPLICATION_SLOT]: data directory already present, starting"
else
    echo "standby[$REPLICATION_SLOT]: empty data directory, cloning from $PRIMARY_HOST"

    # The primary has to be accepting connections before a base backup can start. Compose's
    # depends_on: service_healthy covers the usual case; this loop covers a restart where the
    # primary is still replaying its own WAL and is not ready the instant the port opens.
    until pg_isready -h "$PRIMARY_HOST" -U "$REPLICATION_USER" -q; do
        echo "standby[$REPLICATION_SLOT]: waiting for $PRIMARY_HOST"
        sleep 2
    done

    # The slot is created FIRST, as its own step, and that ordering is the fix for a real
    # failure rather than a stylistic preference.
    #
    # pg_basebackup can create the slot itself with -C, and doing it that way broke: both
    # standbys cloned at the same moment, each base backup forced a checkpoint, and one of
    # those checkpoints recycled a WAL segment the other still needed -
    #
    #   ERROR: requested WAL segment 000000010000000000000002 has already been removed
    #
    # A slot pins WAL from the moment it exists. Creating it before the backup starts closes
    # the window; creating it as part of the backup leaves the window open. The same applies
    # in production whenever a clone runs against a busy primary, and it is the reason
    # runbooks say "create the slot, then take the backup" rather than treating -C as a
    # convenience.
    #
    # The second argument is `immediately_reserve`, and leaving it out is why the first
    # attempt at this fix changed nothing.
    #
    # pg_create_physical_replication_slot('name') creates a slot whose restart_lsn is NULL: it
    # exists, it is listed in pg_replication_slots, and it reserves **no WAL whatsoever** until
    # a streaming client first connects to it. So the slot was created before the backup, as
    # intended, and the checkpoint still recycled the segment, because nothing was pinned. The
    # error is identical and the slot looks present in every diagnostic - which is what makes
    # this one expensive to find.
    #
    # With `true`, the slot reserves WAL from the moment it is created, which is what "create
    # the slot before you take the backup" has to mean to be worth anything.
    #
    # WHERE NOT EXISTS rather than a plain call, so a container that is restarted after a
    # half-finished clone does not fail on a slot it made itself last time.
    gosu postgres psql -h "$PRIMARY_HOST" -U "$REPLICATION_USER" -d postgres -v ON_ERROR_STOP=1 -c \
        "SELECT pg_create_physical_replication_slot('$REPLICATION_SLOT', true)
         WHERE NOT EXISTS (
             SELECT 1 FROM pg_replication_slots WHERE slot_name = '$REPLICATION_SLOT');"
    echo "standby[$REPLICATION_SLOT]: replication slot ready"

    # -R    writes standby.signal and primary_conninfo into the new data directory. Before
    #       Postgres 12 this was recovery.conf, which is why so much documentation is wrong.
    # -Xs   streams WAL during the backup, so the copy is consistent without the primary
    #       having to retain every segment generated while it ran.
    # -S    uses the slot created above. A slot makes the primary keep WAL until this standby
    #       has consumed it, which guarantees a standby can always catch up - and means a
    #       standby that stays offline fills the primary's disk. Replication without a slot
    #       loses the standby; replication with one can lose the primary. There is no third
    #       option, only a choice about which failure you prefer and how you monitor it.
    #       Phase 4 breaks the cluster with this deliberately.
    # -d with a full conninfo rather than --host/--username, and the reason is application_name.
    #
    # synchronous_standby_names matches standbys by their **application_name**, not by their
    # slot name and not by their hostname. A standby that does not set one reports the driver
    # default, `walreceiver`, and every standby reports the same thing - so
    # `ANY 1 (standby1, standby2)` matches nothing at all, sync_state stays `async` for all of
    # them, and synchronous replication silently does not happen. Nothing errors; the setting
    # simply never applies to anybody.
    #
    # -R records this conninfo into postgresql.auto.conf as primary_conninfo, so the name
    # survives restarts without a second file to keep in step.
    gosu postgres pg_basebackup \
        --dbname="host=$PRIMARY_HOST user=$REPLICATION_USER application_name=$REPLICATION_SLOT" \
        --pgdata="$PGDATA" \
        --format=plain \
        --wal-method=stream \
        --write-recovery-conf \
        --slot="$REPLICATION_SLOT" \
        --progress --verbose

    # initdb would have done this; pg_basebackup does not. Postgres refuses to start on a data
    # directory that group or world can read.
    chmod 0700 "$PGDATA"
    echo "standby[$REPLICATION_SLOT]: clone complete"
fi

exec gosu postgres "$@"
