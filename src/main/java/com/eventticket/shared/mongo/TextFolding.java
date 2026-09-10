package com.eventticket.shared.mongo;

import java.text.Normalizer;
import java.util.Locale;

/**
 * {@code lower(unaccent(x))}, in Java, because MongoDB will not do it for a substring search.
 *
 * <h2>Why this class has to exist</h2>
 *
 * <p>Collation solves accent-insensitivity for <em>comparisons</em> - equality, ranges, sorts -
 * and an index built with a collation serves them. That is real, and it is what
 * {@link Collations} is for.
 *
 * <p><strong>It does not apply to {@code $regex}.</strong> MongoDB's regex operator ignores the
 * query's collation entirely: it offers an {@code i} option for case and has nothing at all for
 * accents. A title search is {@code like '%…%'} - a substring match - so collation cannot serve
 * it, and the MongoDB answer is to store a folded copy of the field and search that.
 *
 * <p>So this is a straightforward loss, and the shape of it is worth naming: <strong>MongoDB
 * made us denormalize.</strong> Postgres computed the folding at query time with {@code
 * lower(unaccent(title))}; here it is a second field on every Event, written whenever the title
 * is, and wrong forever if a write path forgets. Postgres could not index that expression
 * either - {@code unaccent} is {@code STABLE}, not {@code IMMUTABLE} - so neither system indexes
 * an unanchored substring search, and the honest comparison is a wash on speed and a loss on
 * complexity.
 *
 * <h2>Đ</h2>
 *
 * <p>The one character that makes this more than three lines. NFD decomposition separates a
 * base letter from its combining marks, so stripping {@code \p{M}} handles à, ế, ộ and the rest
 * - but {@code Đ} is not a decomposable letter-plus-mark, it is its own codepoint, and it
 * survives untouched. Postgres {@code unaccent} folds it to {@code D}.
 *
 * <p>That exact disagreement already cost this project once: an earlier version folded the
 * search term in Java and the column in Postgres, the two disagreed about {@code Đ}, and typing
 * a title exactly as written found nothing. Both sides fold here, with this method, which is
 * the only arrangement where they cannot drift.
 */
public final class TextFolding {

    private TextFolding() {}

    public static String fold(String text) {
        if (text == null) {
            return null;
        }
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return decomposed
                .replace('Đ', 'D')
                .replace('đ', 'd')
                .toLowerCase(Locale.ROOT);
    }
}
