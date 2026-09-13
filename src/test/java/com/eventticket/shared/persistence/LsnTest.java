package com.eventticket.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The comparison the whole guard rests on.
 *
 * <p>Every other part of read routing is visible when it misbehaves - a read goes to the wrong
 * node, a poller stops, a bean is missing. This one is not: get it wrong and the guard reports
 * "the replica has caught up" for some pairs of positions and not others, so a user occasionally
 * sees a stale version of their own write and nothing anywhere logs a thing.
 */
class LsnTest {

    @Test
    @DisplayName("a later position is greater, even where string order says otherwise")
    void comparesNumericallyNotLexicographically() {
        // The case that motivates the class. As text, "0/9" sorts AFTER "0/10" because '9' is a
        // higher character than '1' - while 0x9 is plainly before 0x10. A string comparison here
        // would decide the replica had replayed past a position it has not reached.
        assertThat("0/9".compareTo("0/10")).isGreaterThan(0);
        assertThat(Lsn.parse("0/9").compareTo(Lsn.parse("0/10"))).isLessThan(0);
    }

    @Test
    @DisplayName("the high half outranks the low half")
    void comparesHighHalfFirst() {
        // A wrap of the low half advances the high one, so a large low value on an older high
        // value is still older - a naive comparison of only the low half would invert this.
        assertThat(Lsn.parse("1/0").compareTo(Lsn.parse("0/FFFFFFFF"))).isGreaterThan(0);
    }

    @Test
    @DisplayName("hasReached is inclusive of the exact position")
    void hasReachedIsInclusive() {
        // Replaying exactly the user's own commit is enough; requiring strictly greater would
        // send them to the primary for one extra poll interval every time they wrote.
        assertThat(Lsn.parse("0/3DAF1088").hasReached(Lsn.parse("0/3DAF1088"))).isTrue();
        assertThat(Lsn.parse("0/3DAF1087").hasReached(Lsn.parse("0/3DAF1088"))).isFalse();
        assertThat(Lsn.parse("0/3DAF1089").hasReached(Lsn.parse("0/3DAF1088"))).isTrue();
    }

    @Test
    @DisplayName("real positions from the cluster parse")
    void parsesTheFormatPostgresActuallyPrints() {
        // Taken from pg_current_wal_lsn() on the running cluster, lower-case hex included.
        assertThat(Lsn.parse("0/3DAF1088")).isEqualTo(new Lsn(0L, 0x3DAF1088L));
        assertThat(Lsn.parse("0/3daf1088")).isEqualTo(new Lsn(0L, 0x3DAF1088L));
    }
}
