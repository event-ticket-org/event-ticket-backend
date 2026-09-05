/**
 * Events, Pricing Tiers, publishing and the frozen Event Seat Map.
 *
 * <p>Reads a Venue's Seat Map at publish and copies it; a Venue never reads an Event. That is
 * the only direction the dependency runs, which is what keeps the two modules independent -
 * see {@code com.eventticket.venue}.
 *
 * <p>One class per use case, named after what the user does. No EventService.
 * See {@code docs/adr/0001-use-case-classes-not-services.md}.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        allowedDependencies = {"shared", "organization", "venue"})
package com.eventticket.event;
