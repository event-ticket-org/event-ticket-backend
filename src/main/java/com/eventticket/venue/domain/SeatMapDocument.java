package com.eventticket.venue.domain;

import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Venue's Seat Map, whole. Stored as one JSONB column and replaced in one request
 * (requirements/002 criterion 9): editing a 2,000-seat map is a bulk edit, and a single
 * atomic replacement is easier to reason about than a stream of granular operations.
 *
 * <p>The rules the map has to obey live here rather than in {@code ReplaceSeatMap}, because
 * they are true of a map wherever it came from. A duplicate label cannot be a unique index -
 * the map is a document, not rows - so this is the constraint, and the only one.
 */
public record SeatMapDocument(List<MapSeat> seats, List<MapElement> elements) {

    public SeatMapDocument {
        seats = seats == null ? List.of() : List.copyOf(seats);
        elements = elements == null ? List.of() : List.copyOf(elements);
    }

    public static SeatMapDocument empty() {
        return new SeatMapDocument(List.of(), List.of());
    }

    /**
     * requirements/002 criteria 5 and 6. Two seats may not share a label, because a label is
     * what a person reads off their ticket and looks for on the wall; and two seats may not
     * share a position, because one of them would be unclickable and therefore unsellable.
     */
    public SeatMapDocument validated() {
        Set<String> labels = new HashSet<>();
        Set<String> positions = new HashSet<>();
        for (MapSeat seat : seats) {
            if (seat.label() == null || seat.label().isBlank()) {
                throw new ApiException(ErrorCodes.VALIDATION_FAILED, "Every seat needs a label.");
            }
            if (!labels.add(seat.label())) {
                throw new ApiException(ErrorCodes.DUPLICATE_SEAT_LABEL,
                        "Two seats are labelled " + seat.label() + ". Seat labels must be unique "
                                + "within a venue, because a label is how someone finds their seat.",
                        Map.of("label", seat.label()));
            }
            if (!positions.add(seat.x() + "," + seat.y())) {
                throw new ApiException(ErrorCodes.VALIDATION_FAILED,
                        "Seat " + seat.label() + " sits on top of another seat.",
                        Map.of("label", seat.label()));
            }
        }
        return this;
    }

    /** The Pricing Tier names in use, in the order they first appear on the map. */
    public Set<String> tierNames() {
        return seats.stream().map(MapSeat::tierName)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    public boolean hasSeats() {
        return !seats.isEmpty();
    }
}
