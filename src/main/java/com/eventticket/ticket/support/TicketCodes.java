package com.eventticket.ticket.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Makes and reads Ticket Codes.
 *
 * <p>A code is {@code ET<version>-<lookup>-<tag>}: 128 random bits that find the row, and a
 * truncated HMAC of them under a key that lives in the environment rather than the schema.
 * Nothing that grants entry is stored, so a leaked database is not a set of working tickets -
 * an attacker holding every lookup value still cannot produce a single code that scans.
 *
 * <p>It also makes the door cheaper. A forged or mistyped code fails the MAC and is refused
 * before any database round-trip, which matters for requirements/007's 500 ms budget and for
 * rate limiting something an attacker can generate for free.
 *
 * <p>Uppercase hexadecimal because QR codes encode uppercase alphanumerics densely, and
 * because a code that has to be read aloud over a radio at a gate should have no case in it.
 *
 * <p>The version is in the code so that keys can be rotated: sign with the current one, keep
 * accepting the previous, and tickets already in circulation stay valid. Lose every key and
 * every outstanding ticket becomes unverifiable - which is a heavier failure than losing the
 * JWT secret, and the reason the version exists at all.
 */
@Component
@ConfigurationProperties("app.tickets")
public class TicketCodes {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "ET";
    private static final int LOOKUP_BYTES = 16;   // nfr.md: minimum 128 bits of entropy
    private static final int TAG_BYTES = 8;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    /** Version to secret. Every version here is accepted; only the current one is issued. */
    private Map<Short, String> keys = Map.of();
    private short currentKeyVersion = 1;

    public void setKeys(Map<Short, String> keys) {
        this.keys = keys;
    }

    public void setCurrentKeyVersion(short currentKeyVersion) {
        this.currentKeyVersion = currentKeyVersion;
    }

    /** What to store, and what to show. The lookup is stored; the code never is. */
    public record Issued(String lookup, short version, String code) {}

    public Issued issue() {
        byte[] lookup = new byte[LOOKUP_BYTES];
        RANDOM.nextBytes(lookup);
        String hex = HEX.formatHex(lookup);
        return new Issued(hex, currentKeyVersion, format(hex, currentKeyVersion));
    }

    /** Rebuilds the code for display. The ticket page calls this; nothing caches the result. */
    public String format(String lookup, short version) {
        return PREFIX + version + "-" + lookup + "-" + HEX.formatHex(tag(lookup, version));
    }

    /**
     * The lookup value inside a code, if the code is one we issued. Empty for anything else,
     * so a caller cannot accidentally query with an unverified value.
     */
    public Optional<String> lookupIn(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String[] parts = code.trim().toUpperCase().split("-");
        if (parts.length != 3 || !parts[0].startsWith(PREFIX)) {
            return Optional.empty();
        }
        short version;
        try {
            version = Short.parseShort(parts[0].substring(PREFIX.length()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (!keys.containsKey(version) || parts[1].length() != LOOKUP_BYTES * 2) {
            return Optional.empty();
        }
        // Constant-time: a comparison that stops at the first wrong byte tells an attacker
        // how much of a guessed tag was right.
        if (!MessageDigest.isEqual(tag(parts[1], version), decode(parts[2]))) {
            return Optional.empty();
        }
        return Optional.of(parts[1]);
    }

    private byte[] tag(String lookup, short version) {
        String secret = keys.get(version);
        if (secret == null) {
            throw new IllegalStateException("No ticket code key configured for version " + version);
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] full = mac.doFinal(decode(lookup));
            byte[] truncated = new byte[TAG_BYTES];
            System.arraycopy(full, 0, truncated, 0, TAG_BYTES);
            return truncated;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Ticket codes cannot be computed", e);
        }
    }

    private static byte[] decode(String hex) {
        try {
            return HEX.parseHex(hex);
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }
}
