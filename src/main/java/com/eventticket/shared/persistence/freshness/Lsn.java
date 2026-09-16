package com.eventticket.shared.persistence.freshness;

/**
 * A write-ahead log position, and the comparison that has to be right.
 *
 * <p>Postgres prints an LSN as two hexadecimal halves joined by a slash - {@code 0/3DAF1088} -
 * and the obvious implementation compares those as strings. <strong>That is wrong, and wrong in
 * the unsafe direction.</strong> Lexicographically {@code "0/9"} sorts after {@code "0/10"},
 * while numerically nine comes before sixteen. A string comparison therefore reports "the
 * replica has caught up" at exactly the moments it has not, and only for some values, which is
 * the hardest kind of bug to see.
 *
 * <p>So each half is parsed as hex and compared numerically, high half first. Each half is 32
 * bits, which fits a {@code long} with room to spare, so there is no unsigned arithmetic to get
 * wrong either.
 */
record Lsn(long high, long low) implements Comparable<Lsn> {

    static Lsn parse(String text) {
        int slash = text.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("Not a Postgres LSN: " + text);
        }
        return new Lsn(
                Long.parseLong(text.substring(0, slash).trim(), 16),
                Long.parseLong(text.substring(slash + 1).trim(), 16));
    }

    @Override
    public int compareTo(Lsn other) {
        int byHigh = Long.compare(high, other.high);
        return byHigh != 0 ? byHigh : Long.compare(low, other.low);
    }

    /** True when this position is at or beyond {@code target} - i.e. everything up to it is replayed. */
    boolean hasReached(Lsn target) {
        return compareTo(target) >= 0;
    }
}
