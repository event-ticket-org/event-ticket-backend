package com.eventticket.event.domain;

/**
 * What a buyer sees before signing in: the Event, plus the names it is meaningless without.
 *
 * <p>A buyer needs the Venue's name, city and timezone and the Organization's name, and none
 * of those live on the Event. Assembling them into one value here keeps the controller from
 * having to know where each piece came from, and keeps a use case from returning four things.
 *
 * <p>The two seat counts are the fields here that are not names: they answer the question a
 * buyer asks second, after what the event is, and until requirements/009 criterion 3 asked for
 * them the only way to learn either was to open the event and count the seat map. Both, because
 * one of them cannot say how nearly gone an event is - four left means something different in a
 * room of twenty and a room of two thousand.
 */
public record PublicEventView(Event event, String organizationName, String venueName,
                              String city, String citySlug, String timezone,
                              EventPricing pricing, long seatsAvailable, long seatsTotal) {

    /**
     * The Category's slug and name come off the Event itself rather than being carried here,
     * because unlike the Venue and the Organization it is an association Hibernate already
     * loaded - eagerly, precisely so that this mapping can happen in the controller outside
     * the use case's transaction.
     */
    public String categorySlug() {
        return event.category().slug();
    }

    public String categoryName() {
        return event.category().name();
    }
}
