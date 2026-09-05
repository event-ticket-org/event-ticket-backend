package com.eventticket.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Opaque tokens for email verification and refresh.
 *
 * <p>Issued with 256 bits of entropy from {@link SecureRandom} and never derived from a
 * database identifier - the same rule {@code nfr.md} sets for Ticket Codes, and for the same
 * reason: a guessable token is the whole security boundary.
 *
 * <p>Only the hash is stored. A leaked token table must not be a set of working links, and
 * these tokens are bearer credentials with no second factor behind them. SHA-256 rather than
 * a password hash is deliberate: these are already high-entropy random values, so there is
 * nothing for a slow hash to defend against, and verification happens on every refresh.
 */
final class SecureTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecureTokens() {}

    static String issue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required and was not available", e);
        }
    }
}
