package com.eventticket.shared.mongo;

/**
 * What replaced {@code lower()} and {@code unaccent()}.
 *
 * <p>Postgres folded case and accents by wrapping both sides of a comparison in a function, and
 * paid for it twice: {@code app_user_email_key} had to be an expression index on
 * {@code lower(email)}, and {@code V10} could not index the search at all, because
 * {@code unaccent} is {@code STABLE} rather than {@code IMMUTABLE}.
 *
 * <p>MongoDB folds in the comparison itself, through ICU collation, and an index built with a
 * collation serves queries using the same one. So the accent-insensitive title search that
 * Postgres had to run unindexed is indexable here. This is one of the few places the migration
 * is a straight improvement.
 *
 * <p><strong>The strengths are not interchangeable and the difference is the whole point.</strong>
 * An index built at one strength does not serve a query at another, so these two constants are
 * also the contract with {@code MongoIndexes}.
 */
public final class Collations {

    /**
     * Case-insensitive, accent-<em>sensitive</em>. For email and city, where {@code Ha Noi} and
     * {@code ha noi} are one place but {@code Hà Nội} is deliberately a different string.
     */
    public static final String CASE_INSENSITIVE = "{ 'locale': 'en', 'strength': 2 }";

    /**
     * Case- and accent-insensitive, so {@code Da Nang} finds {@code Đà Nẵng}.
     *
     * <p>{@code vi} rather than {@code en}, because the locale decides the collation table and
     * this market writes with diacritics and types without them. Note it also folds {@code Đ}
     * to {@code D} - the exact disagreement that broke the first Postgres attempt, where Java's
     * normalizer stripped combining marks and left {@code Đ} alone while Postgres {@code unaccent}
     * folded it. Doing the folding in one place, inside the database, is what removes that class
     * of bug rather than fixing this instance of it.
     */
    public static final String ACCENT_INSENSITIVE = "{ 'locale': 'vi', 'strength': 1 }";

    private Collations() {}
}
