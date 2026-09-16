-- Platform vocabulary for the listing (KB requirements/009 criteria 12 and 13), and the
-- index the title search should always have had.
--
-- Two reference tables, and they are the first tables here that belong to nobody. Every
-- other table carries organization_id and lives behind a policy; these are the set an
-- Organization chooses *from*. That difference is the whole point of criterion 12 - a
-- taxonomy anybody may add to stops being one, and the rows it groups stop being
-- comparable - so it is enforced below rather than left to the application to respect.

-- ---------------------------------------------------------------------------
-- Categories
-- ---------------------------------------------------------------------------

-- The slug is the primary key, not a surrogate id. It is what the contract exposes, what a
-- URL carries and what a client sends back, and it is stable by definition: renaming a
-- Category changes `name`, never this. A uuid here would buy nothing and cost a join on
-- every row of every listing.
CREATE TABLE event_category (
    slug     TEXT PRIMARY KEY,
    name     TEXT    NOT NULL,
    position INTEGER NOT NULL UNIQUE
);

INSERT INTO event_category (slug, name, position) VALUES
    ('nhac-song',             'Nhạc sống',               1),
    ('san-khau-nghe-thuat',   'Sân khấu & Nghệ thuật',   2),
    ('the-thao',              'Thể thao',                3),
    ('hoi-thao-workshop',     'Hội thảo & Workshop',     4),
    ('tham-quan-trai-nghiem', 'Tham quan & Trải nghiệm', 5),
    -- The catch-all. Not a failure state: an Event that fits nothing else belongs here
    -- permanently, and without it the taxonomy would have to be either complete - a claim
    -- about events nobody has thought of yet - or optional, which puts every Event that
    -- skipped the question into a bucket the listing cannot show.
    ('khac',                  'Khác',                    6);

-- ---------------------------------------------------------------------------
-- Cities
-- ---------------------------------------------------------------------------

CREATE TABLE city (
    slug     TEXT PRIMARY KEY,
    name     TEXT    NOT NULL,
    position INTEGER NOT NULL UNIQUE
);

-- Deliberately incomplete, and it grows by INSERT rather than by migration - which is the
-- reason criterion 13 says the platform defines the set rather than naming its members.
-- Vietnam reorganised its provinces in 2025; seeding a remembered list would have written a
-- wrong one into the schema and given it the authority of one somebody checked.
INSERT INTO city (slug, name, position) VALUES
    ('ha-noi',         'Hà Nội',          1),
    ('tp-ho-chi-minh', 'TP Hồ Chí Minh',  2),
    ('da-nang',        'Đà Nẵng',         3),
    ('hai-phong',      'Hải Phòng',       4),
    ('can-tho',        'Cần Thơ',         5),
    ('hue',            'Huế',             6),
    ('nha-trang',      'Nha Trang',       7),
    ('da-lat',         'Đà Lạt',          8),
    ('vung-tau',       'Vũng Tàu',        9),
    ('quy-nhon',       'Quy Nhơn',       10);

-- ---------------------------------------------------------------------------
-- Venue: city text becomes a reference
-- ---------------------------------------------------------------------------

ALTER TABLE venue ADD COLUMN city_slug TEXT REFERENCES city (slug);

-- Match on the folded name, because the column held whatever somebody typed: `Hà Nội`,
-- `Ha Noi` and `ha noi` are one city and three strings. Both sides are folded by the same
-- function, for the same reason the title search folds both sides - folding one is a
-- comparison between two different normalisations.
UPDATE venue v
   SET city_slug = c.slug
  FROM city c
 WHERE lower(unaccent(v.city)) = lower(unaccent(c.name));

-- Anything still unmatched becomes a City of its own rather than failing the migration. A
-- deployment holding a city nobody seeded is a deployment that must still start, and the
-- alternative - refusing to migrate until a human maps it - turns an unknown string into an
-- outage. The slug is derived from what was already there, so nothing is invented.
INSERT INTO city (slug, name, position)
SELECT DISTINCT ON (slug)
       slug,
       name,
       (SELECT max(position) FROM city) + row_number() OVER (ORDER BY slug)
  FROM (
        SELECT DISTINCT
               regexp_replace(
                   regexp_replace(lower(unaccent(v.city)), '[^a-z0-9]+', '-', 'g'),
                   '(^-|-$)', '', 'g') AS slug,
               v.city                  AS name
          FROM venue v
         WHERE v.city_slug IS NULL
       ) unmapped
 WHERE slug <> ''
 ORDER BY slug, name;

UPDATE venue v
   SET city_slug = c.slug
  FROM city c
 WHERE v.city_slug IS NULL
   AND lower(unaccent(v.city)) = lower(unaccent(c.name));

ALTER TABLE venue ALTER COLUMN city_slug SET NOT NULL;
ALTER TABLE venue DROP COLUMN city;
CREATE INDEX venue_city_idx ON venue (city_slug);

-- ---------------------------------------------------------------------------
-- Event: every Event has exactly one Category (KB invariant 24)
-- ---------------------------------------------------------------------------

-- Backfilled to the catch-all, then the default is dropped. The default lives for the length
-- of this migration and no longer: the contract requires categorySlug on creation, and a
-- column that quietly supplied one would let a caller omit it forever and never find out.
-- Existing rows get `khac` because they predate the question, which is what a catch-all is.
ALTER TABLE event ADD COLUMN category_slug TEXT REFERENCES event_category (slug)
    NOT NULL DEFAULT 'khac';
ALTER TABLE event ALTER COLUMN category_slug DROP DEFAULT;
CREATE INDEX event_category_idx ON event (category_slug);

-- ---------------------------------------------------------------------------
-- The vocabulary is the platform's, and the database is what says so
-- ---------------------------------------------------------------------------

-- V3 grants the application role INSERT, UPDATE and DELETE on every table through default
-- privileges, so without this the application *could* extend the taxonomy - and criterion 12
-- would be a convention rather than a rule. Revoking is the same argument as putting the seat
-- map freeze in a trigger: a rule the knowledge base states absolutely belongs in the schema,
-- because an application check protects only the paths that remember it.
--
-- No row-level security on either table. They are not tenant-scoped and have nothing to
-- filter by: every Organization sees the same six Categories, and so does a visitor with no
-- tenant at all - which is what makes /public/categories answerable.
REVOKE INSERT, UPDATE, DELETE ON event_category FROM eventticket_app;
REVOKE INSERT, UPDATE, DELETE ON city           FROM eventticket_app;

-- ---------------------------------------------------------------------------
-- The title index V10 could not create
-- ---------------------------------------------------------------------------

-- V10 added unaccent and deliberately added no index, because unaccent() is STABLE and an
-- index expression must be IMMUTABLE. It is STABLE only because the one-argument form looks
-- up the default text search dictionary at run time; naming the dictionary removes the
-- lookup, and with it the reason.
--
-- This is the wrapper V10 said would be needed. It is IMMUTABLE by assertion rather than by
-- proof: the guarantee is that the `unaccent` dictionary is not redefined underneath it,
-- which is a deployment fact and not a SQL one. That is the accepted cost, and it is why the
-- dictionary is named here rather than left to the search path.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Both names are schema-qualified, and both had to be, for two different reasons found in
-- the order they are written:
--
--   `unaccent('unaccent', $1)` does not compile at all. A bare literal in a function body is
--   typed `unknown`, and nothing resolves unaccent(unknown, text) - so the cast to
--   regdictionary is not decoration.
--
--   `'unaccent'::regdictionary` then compiles and fails later, at CREATE INDEX, with "text
--   search dictionary unaccent does not exist" - about a dictionary that does exist, in
--   public, with public on the search path. Inlining the function into an index expression
--   resolves the literal in a context where the search path does not apply, so the dictionary
--   has to be named in full. Qualifying the function too costs nothing and removes the same
--   question about it.
--
-- Verified against Postgres 18 rather than reasoned about: the unqualified form creates
-- cleanly and fails on the index, which is the worst shape a mistake like this can take.
CREATE FUNCTION immutable_unaccent(TEXT) RETURNS TEXT
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    AS $$ SELECT public.unaccent('public.unaccent'::regdictionary, $1) $$;

-- Trigram rather than tsvector, because the query is a substring match with a leading
-- wildcard and full text search cannot answer that at all: to_tsquery matches whole lexemes,
-- so `onc` would not find `Concert` under it and does under LIKE. Which of the two the
-- listing means is a decision about search behaviour, not an indexing tactic, and it is not
-- this migration's to make.
CREATE INDEX event_title_trgm_idx ON event
    USING gin (lower(immutable_unaccent(title)) gin_trgm_ops);
