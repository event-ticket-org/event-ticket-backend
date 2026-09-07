package com.eventticket.event.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * How many seats an Event has on sale, and how many of those are still free
 * (requirements/009 criterion 3).
 *
 * <p>Both numbers or neither. One of them cannot say how nearly gone an Event is - four left
 * means something different in a room of twenty and a room of two thousand - so they are
 * counted in one pass and travel together.
 *
 * <p>A projection rather than {@code Object[]}, so the columns keep their names on the way out
 * of the query. {@link #of} is where the default lives, and the default is the part that
 * matters: an Event with no seats on sale produces no row at all - GROUP BY has nothing to
 * group - and reading that absence as "unknown" rather than as zero is how an event nobody can
 * buy a seat at would come back looking available.
 */
public record SeatCounts(UUID eventId, long available, long total) {

    private static final SeatCounts NONE = new SeatCounts(null, 0, 0);

    public static Map<UUID, SeatCounts> asMap(List<SeatCounts> counted) {
        return counted.stream().collect(Collectors.toMap(SeatCounts::eventId, Function.identity()));
    }

    public static SeatCounts of(Map<UUID, SeatCounts> counted, UUID eventId) {
        return counted.getOrDefault(eventId, NONE);
    }
}
