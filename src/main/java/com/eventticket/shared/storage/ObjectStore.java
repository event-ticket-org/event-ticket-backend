package com.eventticket.shared.storage;

import java.util.Optional;

/**
 * Somewhere to put files that are too big to be rows.
 *
 * <p>Behind an interface for the same reason {@code EmailTransport} is: neither the test suite
 * nor local development should need a cloud account. Unlike payment providers, object stores
 * differ in credentials rather than in flow, so there is nothing here to model a session for -
 * but there is one thing worth noticing, which is that {@link #presignUpload} hands out
 * authority. Everything that narrows it lives in the implementation of that one method.
 */
public interface ObjectStore {

    /**
     * Authorises one upload to exactly one key, within a size ceiling, for a limited time.
     *
     * <p>The caller chooses the key. That is the whole of the tenancy guarantee here: whoever
     * holds the returned form can write that object and no other, so a key built from the
     * organization and the event is a key nobody else's request can reach.
     */
    UploadForm presignUpload(String key);

    /** Empty when nothing was uploaded, which is the ordinary answer for an abandoned form. */
    Optional<StoredObject> describe(String key);

    /**
     * The first bytes of an object, for deciding what it actually is.
     *
     * <p>A ranged read rather than a download: a file type is decided by its first few bytes,
     * and pulling five megabytes through the application to look at twelve of them would give
     * back the cost that uploading directly to the store was meant to save.
     */
    byte[] readLeadingBytes(String key, int count);

    /**
     * Copy then delete, giving the copy a content type.
     *
     * <p>Used to promote an accepted upload out of the pending prefix. The type is set here
     * rather than at upload because here is the first place anybody knows it: it comes from
     * the file's own bytes, not from what a client said it was sending.
     */
    void promote(String fromKey, String toKey, String contentType);

    /** Silent about an object that is not there, because both callers want it gone either way. */
    void delete(String key);

    /** Where a browser fetches this object from. */
    String publicUrl(String key);
}
