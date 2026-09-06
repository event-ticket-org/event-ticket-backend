package com.eventticket.event.domain;

/**
 * What a buyer sees before signing in: the Event, plus the names it is meaningless without.
 *
 * <p>A buyer needs the Venue's name, city and timezone and the Organization's name, and none
 * of those live on the Event. Assembling them into one value here keeps the controller from
 * having to know where each piece came from, and keeps a use case from returning four things.
 *
 * <p>{@code seatsAvailable} is the one field here that is not a name: it is the answer to the
 * question a buyer asks second, after what the event is, and until requirements/009 criterion
 * 3 asked for it the only way to learn it was to open the event and count the seat map.
 */
public record PublicEventView(Event event, String organizationName, String venueName,
                              String city, String timezone, EventPricing pricing,
                              long seatsAvailable) {
}
