package com.eventticket.shared.storage;

import java.time.Instant;
import java.util.Map;

/**
 * A form a browser can post one file to, described rather than named.
 *
 * <p>The fields carry the authorisation and the conditions it was signed under - the key, the
 * size ceiling, the required content type - so a client copies them across unchanged and
 * altering any of them invalidates the upload. Nothing here says which store answered, which
 * is the point: this is ADR-0002's rule applied to a second thing, and a client that reads the
 * answer works against any of them.
 */
public record UploadForm(String url, Map<String, String> fields, String fileField,
                         long maxBytes, Instant expiresAt) {
}
