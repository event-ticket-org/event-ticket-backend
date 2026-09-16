#!/usr/bin/env bash
# Measures the public listing's title search, before and after the index V14 adds.
#
# It runs against a throwaway container and never against a deployment. The point is scale: the
# live database holds ten published Events, where a sequential scan and an index scan are both
# a fraction of a millisecond and the comparison says nothing. Generating the volume is the
# experiment; running it anywhere real would only put invented Events on a public listing.
#
#   scripts/search-benchmark.sh            200k rows, the default
#   scripts/search-benchmark.sh 1000000    more, if you want to watch it diverge
#
# What it answers:
#   1. Is event_title_trgm_idx actually used, or is it an index nothing reaches?
#   2. What does it save at this size?
#   3. Which shapes does it not help at all?
set -euo pipefail

ROWS=${1:-200000}
CONTAINER=event-ticket-search-bench
PORT=${PORT:-55433}
PSQL=(docker exec -i "$CONTAINER" psql -U postgres -d postgres -X -q)

cleanup() { docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup

echo "==> postgres 18, throwaway"
docker run -d --rm --name "$CONTAINER" -e POSTGRES_PASSWORD=bench -p "$PORT":5432 \
    postgres:18 >/dev/null
for _ in $(seq 1 60); do
    docker exec "$CONTAINER" pg_isready -q 2>/dev/null && break
    sleep 1
done

echo "==> schema, and $ROWS events"
"${PSQL[@]}" <<SQL
CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- The same wrapper V14 creates, qualified for the same reason.
CREATE FUNCTION immutable_unaccent(TEXT) RETURNS TEXT
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    AS \$\$ SELECT public.unaccent('public.unaccent'::regdictionary, \$1) \$\$;

CREATE TABLE event (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title         TEXT        NOT NULL,
    description   TEXT,
    category_slug TEXT        NOT NULL,
    starts_at     TIMESTAMPTZ NOT NULL,
    listed        BOOLEAN     NOT NULL DEFAULT TRUE,
    status        TEXT        NOT NULL DEFAULT 'PUBLISHED'
);

-- Vietnamese words, so the folding is exercised rather than assumed. A generator that wrote
-- ASCII titles would measure a different query from the one this market sends: every row would
-- fold to itself and unaccent would cost nothing.
WITH w(word) AS (VALUES
    ('Đêm'),('Nhạc'),('Cuối'),('Năm'),('Hòa'),('Tấu'),('Sân'),('Khấu'),('Kịch'),('Nói'),
    ('Liveshow'),('Concert'),('Festival'),('Hội'),('Chợ'),('Triển'),('Lãm'),('Giao'),
    ('Hưởng'),('Dân'),('Ca'),('Bolero'),('Rock'),('Jazz'),('Acoustic'),('Thiếu'),('Nhi'))
INSERT INTO event (title, description, category_slug, starts_at)
SELECT (SELECT string_agg(word, ' ')
          FROM (SELECT word FROM w ORDER BY random() LIMIT 4) picked)
        || ' ' || g,
       (SELECT string_agg(word, ' ')
          FROM (SELECT word FROM w ORDER BY random() LIMIT 12) picked2),
       (ARRAY['nhac-song','san-khau-nghe-thuat','the-thao','hoi-thao-workshop',
              'tham-quan-trai-nghiem','khac'])[1 + (g % 6)],
       now() + ((g % 400) || ' days')::interval
  FROM generate_series(1, $ROWS) g;

CREATE INDEX event_public_idx ON event (starts_at, id)
    WHERE status = 'PUBLISHED' AND listed;
ANALYZE event;
SQL

run() {  # run <label> <sql>
    local label=$1 sql=$2
    # Three runs, best of three reported: the first pays for cold cache and says more about the
    # page cache than about the plan.
    local best=999999
    for _ in 1 2 3; do
        local ms
        ms=$("${PSQL[@]}" -At -c "EXPLAIN (ANALYZE, TIMING OFF, FORMAT JSON) $sql" \
             | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["Execution Time"])')
        best=$(python3 -c "print(min($best, $ms))")
    done
    printf '    %-34s %9.2f ms\n' "$label" "$best"
}

plan() {
    "${PSQL[@]}" -At -c "EXPLAIN (FORMAT JSON) $1" \
        | python3 -c '
import json, sys
def walk(n):
    yield n["Node Type"] + (" on " + n["Index Name"] if "Index Name" in n else "")
    for c in n.get("Plans", []):
        yield from walk(c)
print(" -> ".join(walk(json.load(sys.stdin)[0]["Plan"])))'
}

TITLE_Q="select id from event where status = 'PUBLISHED' and listed
         and lower(immutable_unaccent(title)) like lower(immutable_unaccent('%nhac cuoi%')) escape '\\'
         order by starts_at, id limit 20"
FOUR_Q="select id from event where status = 'PUBLISHED' and listed
        and (lower(immutable_unaccent(title)) like lower(immutable_unaccent('%nhac cuoi%')) escape '\\'
             or lower(immutable_unaccent(description)) like lower(immutable_unaccent('%nhac cuoi%')) escape '\\')
        order by starts_at, id limit 20"

echo
echo "==> without the index (what V10 deliberately left)"
run "title only"            "$TITLE_Q"
run "title or description"  "$FOUR_Q"
echo "    plan: $(plan "$TITLE_Q")"

echo
echo "==> building event_title_trgm_idx"
"${PSQL[@]}" -c "CREATE INDEX event_title_trgm_idx ON event
                 USING gin (lower(immutable_unaccent(title)) gin_trgm_ops);" >/dev/null
"${PSQL[@]}" -c "ANALYZE event;" >/dev/null
echo "    index size: $("${PSQL[@]}" -At -c \
    "select pg_size_pretty(pg_relation_size('event_title_trgm_idx'))")"

echo
echo "==> with the index"
run "title only"            "$TITLE_Q"
run "title or description"  "$FOUR_Q"
echo "    plan: $(plan "$TITLE_Q")"

echo
echo "==> the assertion this script exists for"
if plan "$TITLE_Q" | grep -q event_title_trgm_idx; then
    echo "    event_title_trgm_idx is reached by the listing's query."
else
    echo "    FAIL: the planner ignored event_title_trgm_idx. The index expression and the"
    echo "          query expression have to match exactly - check immutable_unaccent."
    exit 1
fi
echo
echo "    The second row is the one to read twice: only the title is indexed, so a query"
echo "    spanning the description is still a scan and the index buys it almost nothing."
