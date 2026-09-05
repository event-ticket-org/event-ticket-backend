package com.eventticket.event.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The three transitions a seat's availability can make, each one statement in the database.
 *
 * <p>They are SQL functions rather than JPA writes for two reasons. A buyer must be able to
 * take a hold on an Organization they are not a member of, which the {@code event_seat} policy
 * rightly refuses - so the functions are {@code SECURITY DEFINER}, a much narrower grant than
 * widening that policy would be. And each of them resolves a race by locking, in one
 * statement, which is what KB invariant 5 means by "not a check in application code".
 *
 * <p>Each returns the seats it actually changed, never a boolean. That difference is the whole
 * interface: {@code hold} returning fewer seats than were asked for is how requirements/004
 * criterion 6 knows which seats to name, and {@code sell} returning fewer is how
 * requirements/005 criterion 9 knows the holds lapsed before the money arrived.
 */
@Repository
public class EventSeatAvailability {

    private final JdbcTemplate jdbc;

    public EventSeatAvailability(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The seats that were free and are now held. Anything missing was taken by someone else. */
    public List<UUID> hold(UUID eventId, List<UUID> seatIds, UUID orderId, Instant until) {
        return jdbc.query(
                // The array is built on the statement's own connection. Borrowing another one
                // for it would leave the transaction this must run inside.
                connection -> {
                    var statement = connection.prepareStatement("select seat_id from hold_seats(?, ?, ?, ?)");
                    statement.setObject(1, eventId);
                    statement.setArray(2, connection.createArrayOf("uuid", seatIds.toArray()));
                    statement.setObject(3, orderId);
                    statement.setTimestamp(4, Timestamp.from(until));
                    return statement;
                },
                (rs, row) -> rs.getObject(1, UUID.class));
    }

    /** requirements/004 criterion 11: abandoning releases the seats at once, not at expiry. */
    public int release(UUID orderId) {
        Integer released = jdbc.queryForObject("select release_seats(?)", Integer.class, orderId);
        return released == null ? 0 : released;
    }

    /** The seats whose hold was still alive and are now sold. */
    public List<UUID> sell(UUID orderId) {
        return jdbc.query("select seat_id from sell_seats(?)",
                (rs, row) -> rs.getObject(1, UUID.class), orderId);
    }
}
