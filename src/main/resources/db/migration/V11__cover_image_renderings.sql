-- requirements/003 criterion 22: a cover is offered in several sizes.
--
-- What was actually produced, rather than a constant the code can look up. Three reasons, and
-- the first two are correctness rather than taste:
--
--   * Only sizes smaller than the upload are made, so a 400px cover has one rendering and a
--     200px cover has none. The set is a property of the file, not of the system.
--   * A format nothing can decode has no renderings at all (ADR-0006). Same column, same
--     answer, no special case anywhere else.
--   * Changing the widths later must not break the covers already stored. Derive the list
--     from a constant and every existing Event immediately advertises files that were never
--     written, which surfaces as a broken image on somebody else's page.
--
-- The keys are stored and not rebuilt, unlike everywhere else in this system where keys are
-- built from ids. A rendering's extension is not the upload's - a WebP is decoded and written
-- back as JPEG, because the classpath has a WebP reader and no writer - so the served name
-- cannot be derived from anything the Event knows. Storing it also makes deletion exact,
-- which is the operation that quietly leaks a bucket when it is approximate.
ALTER TABLE event ADD COLUMN cover_image_renderings JSONB;

-- The existing constraint says the three cover columns are all present or all absent. This
-- one is weaker on purpose: renderings are an optimisation, so a cover may have none, but
-- renderings without a cover would be files nothing points at.
ALTER TABLE event ADD CONSTRAINT event_cover_renderings_need_a_cover CHECK (
    cover_image_renderings IS NULL OR cover_image_key IS NOT NULL
);
