package com.eventticket.checkout.domain;

import java.util.List;

/**
 * An Order with the two things it is meaningless without: which seats, and which event.
 *
 * <p>The Event's title is carried rather than looked up by the caller, because an Order is
 * shown to a buyer who has no access to the Organization that runs the event and no reason
 * to make a second request to find out what they bought a ticket to.
 */
public record OrderDetail(Order order, String eventTitle, List<OrderSeat> seats) {
}
