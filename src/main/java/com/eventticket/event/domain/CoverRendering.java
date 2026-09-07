package com.eventticket.event.domain;

/**
 * One smaller copy of an Event's cover: how wide it is, where it lives, and where a browser
 * fetches it from.
 *
 * <p>The key is stored rather than rebuilt, which is the exception to how every other key in
 * this system works. A rendering's extension is not the upload's - a WebP is decoded and
 * written back as JPEG, because the classpath carries a WebP reader and no writer - so nothing
 * the Event knows is enough to name the file. It also makes deleting exact, and deletion is
 * the operation that leaks a bucket when it is approximate.
 *
 * <p>The URL is a cache of a pure function of the key, exactly as {@code cover_image_url} is,
 * and for the reason V9 gives: resolving it on read would put the object store into every
 * place that builds an Event view, including a mapper that is otherwise static.
 */
public record CoverRendering(int width, String key, String url) {
}
