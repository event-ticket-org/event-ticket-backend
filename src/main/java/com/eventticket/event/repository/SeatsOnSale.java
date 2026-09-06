package com.eventticket.event.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * How many seats one Event still has on sale (requirements/009 criterion 3).
 *
 * <p>A projection rather than {@code Object[]}, so the two columns keep their names on the way
 * out of the query. {@code asMap} is here because every caller wants the same thing and the
 * default is the part that matters: an Event with no free seats produces no row at all - GROUP
 * BY has nothing to group - and reading that absence as "unknown" rather than as zero is
 * exactly how a sold-out event would come back looking available.
 */
public record SeatsOnSale(UUID eventId, long seats) {

    public static Map<UUID, Long> asMap(List<SeatsOnSale> counted) {
        return counted.stream().collect(Collectors.toMap(SeatsOnSale::eventId, SeatsOnSale::seats));
    }

    public static long of(Map<UUID, Long> counted, UUID eventId) {
        return counted.getOrDefault(eventId, 0L);
    }
}
