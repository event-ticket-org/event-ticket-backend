package com.eventticket.event.domain;

import com.eventticket.venue.domain.MapElement;
import java.util.List;

/**
 * The frozen map plus the furniture, as a buyer sees it.
 *
 * <p>Availability is not in here. It is live, it is assembled from Seat Holds and Tickets that
 * do not exist yet (requirements/004 and 006), and it is the one part of this response that is
 * true only at the instant it is read.
 */
public record EventSeatMapView(List<EventSeat> seats, List<MapElement> elements) {
}
