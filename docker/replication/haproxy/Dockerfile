# HAProxy that can tell a primary from a standby.
#
# The stock image cannot. HAProxy ships `option pgsql-check`, which opens a connection and sends
# a startup packet - it proves a server is alive and says nothing about whether it accepts
# writes. That distinction is the entire reason this proxy exists, so the image needs a Postgres
# client and a script that asks the server directly.
FROM haproxy:3.0-alpine

USER root
RUN apk add --no-cache postgresql16-client
COPY check-role.sh /usr/local/bin/check-role.sh
RUN chmod 0755 /usr/local/bin/check-role.sh

# Baked in rather than bind-mounted: libpq refuses a password file that is group- or
# world-readable, and a bind mount carries the host's permissions, which vary by machine.
COPY pgpass /etc/haproxy/pgpass
RUN chmod 0600 /etc/haproxy/pgpass && chown haproxy:haproxy /etc/haproxy/pgpass

USER haproxy
