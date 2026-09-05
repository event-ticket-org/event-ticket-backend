package com.eventticket.event.domain;

/**
 * What a buyer sees before signing in: the Event, plus the names it is meaningless without.
 *
 * <p>A buyer needs the Venue's name, city and timezone and the Organization's name, and none
 * of those live on the Event. Assembling them into one value here keeps the controller from
 * having to know where each piece came from, and keeps a use case from returning four things.
 */
public record PublicEventView(Event event, String organizationName, String venueName,
                              String city, String timezone, EventPricing pricing) {
}
