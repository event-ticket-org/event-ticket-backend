#!/usr/bin/env bash
#
# Runs once, inside the primary, the first time its data directory is created.
#
# Two things have to be true before any standby can attach, and neither is on by default:
# a role that is allowed to stream, and a pg_hba rule that lets it in over the network.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-SQL
    -- REPLICATION is a role attribute, not a grant on a table: it permits attaching to the
    -- WAL stream and nothing else. The account therefore cannot read a single row of
    -- application data, which is the point - a standby needs the write-ahead log, not SELECT.
    CREATE ROLE ${REPLICATION_USER} WITH REPLICATION LOGIN PASSWORD '${REPLICATION_PASSWORD}';
SQL

# initdb writes rules for `replication` over loopback only. A standby in another container
# arrives over the bridge network, so without this line it is refused with
# "no pg_hba.conf entry for replication connection" - which reads like a password problem
# and is not one.
#
# Appended rather than templated because the rest of the file is the image's own, and the one
# line this setup needs should be visible as the one line this setup needs.
cat >> "$PGDATA/pg_hba.conf" <<-HBA

	# --- added by docker/replication/primary-init.sh -----------------------------------
	# Streaming replication for the standbys. `all` is the client address range, which is
	# safe here only because this cluster lives on a private compose network and is never
	# published. A deployment narrows it to the standby subnet.
	host    replication    ${REPLICATION_USER}    all    scram-sha-256
HBA

echo "primary-init: replication role '${REPLICATION_USER}' created and pg_hba.conf extended"
