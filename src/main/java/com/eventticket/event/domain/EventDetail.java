package com.eventticket.event.domain;

/**
 * An Event together with the prices a manager needs to see beside it.
 *
 * <p>{@code notifiedCount} is set only by the one operation that notifies anybody - moving a
 * published Event's start time - and is null everywhere else, which is how the contract's
 * {@code notifyCount} is meant to read: "how many people the request that produced this
 * response emailed", not a standing property of the Event.
 */
public record EventDetail(Event event, EventPricing pricing, Integer notifiedCount) {

    public static EventDetail of(Event event, EventPricing pricing) {
        return new EventDetail(event, pricing, null);
    }
}
