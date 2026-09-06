-- requirements/003 criteria 17-21, ADR-0006: an Event's cover is a file we store, not a link
-- somebody pasted.
--
-- `cover_image_url` stays and keeps its meaning - where a browser fetches the picture - but is
-- now written only by the upload flow. Two columns join it:
--
--   cover_image_key   where the object actually lives, which is what deleting needs. Deriving
--                     it by subtracting a base URL would break the moment anything is put in
--                     front of the bucket, and deleting the wrong object is not a mistake with
--                     a quiet failure mode.
--   cover_image_alt   what the picture shows, written by the organizer (criterion 20).
--
-- The URL is a cache of a pure function of the key and the configured base. That is a
-- deliberate trade: resolving it on every read instead would put the object store into the
-- eight places that build an Event view, and into a mapper that is otherwise static. The
-- constraint below is what keeps the cache honest - the two are written and cleared together
-- by one method on Event, and the database refuses any state in which they disagree.
ALTER TABLE event ADD COLUMN cover_image_key TEXT;
ALTER TABLE event ADD COLUMN cover_image_alt TEXT;

ALTER TABLE event ADD CONSTRAINT event_cover_is_whole CHECK (
    (cover_image_key IS NULL) = (cover_image_url IS NULL)
    AND (cover_image_alt IS NULL OR cover_image_key IS NOT NULL)
);
