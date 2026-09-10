package com.eventticket.event.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The two reads that are not a lookup by key, kept behind an interface so
 * {@code EventSeatRepository} keeps the signature the rest of the application already calls.
 *
 * <p>A Spring Data fragment rather than {@code @Aggregation} on the interface, for one reason:
 * both of these bind a parameter into the middle of a pipeline, and a pipeline written as an
 * annotation string is a pipeline with no type checking and no binding - the {@code now} below
 * would have to be interpolated into JSON by hand.
 */
public interface EventSeatQueries {

    public List<String> findTierNames(UUID eventId);

    public List<SeatCounts> countSeats(List<UUID> eventIds, Instant now);
}
