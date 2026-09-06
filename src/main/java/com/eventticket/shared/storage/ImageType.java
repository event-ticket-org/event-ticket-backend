package com.eventticket.shared.storage;

import java.util.Optional;

/**
 * What a file actually is, decided by its own first bytes.
 *
 * <p>The content type on an upload is a claim made by whoever uploaded it, and the whole
 * reason to check is that they may be wrong or lying (nfr.md). Every format here announces
 * itself in its first few bytes, which is why a dozen of them are enough to answer a question
 * the declared type cannot.
 *
 * <p>Deliberately a short list. Every format admitted is one every browser renders and one we
 * are willing to serve for years; a format nobody asked for is a format nobody tested.
 */
public enum ImageType {

    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp"),
    AVIF("image/avif", "avif");

    /** Twelve bytes reaches the marker inside a RIFF or ISO-BMFF container. */
    public static final int LEADING_BYTES = 12;

    private final String contentType;
    private final String extension;

    ImageType(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String contentType() {
        return contentType;
    }

    public String extension() {
        return extension;
    }

    public static Optional<ImageType> of(byte[] leading) {
        if (leading == null || leading.length < 4) {
            return Optional.empty();
        }
        if (starts(leading, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(JPEG);
        }
        if (starts(leading, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(PNG);
        }
        // RIFF....WEBP - the four bytes between are the file length, so they are skipped
        // rather than matched.
        if (starts(leading, 'R', 'I', 'F', 'F') && at(leading, 8, 'W', 'E', 'B', 'P')) {
            return Optional.of(WEBP);
        }
        // ISO base media: a four-byte length, then "ftyp", then the brand. AVIF is the brand,
        // which is why the marker is at 4 and the answer is at 8.
        if (at(leading, 4, 'f', 't', 'y', 'p') && (at(leading, 8, 'a', 'v', 'i', 'f')
                || at(leading, 8, 'a', 'v', 'i', 's'))) {
            return Optional.of(AVIF);
        }
        return Optional.empty();
    }

    private static boolean starts(byte[] bytes, int... expected) {
        return at(bytes, 0, expected);
    }

    private static boolean at(byte[] bytes, int offset, int... expected) {
        if (bytes.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((bytes[offset + i] & 0xFF) != (expected[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }
}
