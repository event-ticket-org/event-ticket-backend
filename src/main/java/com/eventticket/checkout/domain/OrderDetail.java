package com.eventticket.checkout.domain;

import java.util.List;

/**
 * An Order with the two things it is meaningless without: which seats, and which event.
 *
 * <p>The Event's title is carried rather than looked up by the caller, because an Order is
 * shown to a buyer who has no access to the Organization that runs the event and no reason
 * to make a second request to find out what they bought a ticket to.
 */
public record OrderDetail(Order order, String eventTitle, List<OrderSeat> seats,
                          String buyerEmail) {

    /**
     * The buyer's own view, which does not carry their address: they know it, and the list
     * they are reading is already theirs. It is filled for the organizer's list, where the
     * Order is about somebody the organizer may have to answer to.
     */
    public OrderDetail(Order order, String eventTitle, List<OrderSeat> seats) {
        this(order, eventTitle, seats, null);
    }
}
