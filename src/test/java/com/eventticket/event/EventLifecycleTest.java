package com.eventticket.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.api.model.Error;
import com.eventticket.api.model.ErrorCode;
import com.eventticket.api.model.Event;
import com.eventticket.api.model.EventPatch;
import com.eventticket.api.model.EventSeatMap;
import com.eventticket.api.model.EventStatus;
import com.eventticket.api.model.PricingTier;
import com.eventticket.api.model.PublicEvent;
import com.eventticket.api.model.SeatAvailability;
import com.eventticket.api.model.SeatMap;
import com.eventticket.api.model.TokenPair;
import com.eventticket.api.model.Venue;
import com.eventticket.api.model.Money;
import com.eventticket.api.model.PricingTierInput;
import java.util.List;
import com.eventticket.support.ApiTest;
import com.eventticket.support.SeatMaps;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

/**
 * Knowledge base requirements/003.
 *
 * <p>Publishing gets four refusal tests rather than one, because "this event cannot be
 * published" is not something a manager can act on. Each precondition is failed on its own.
 */
class EventLifecycleTest extends ApiTest {

    private static final OffsetDateTime NEXT_MONTH = OffsetDateTime.now().plus(30, ChronoUnit.DAYS);

    @Test
    @DisplayName("a draft's pricing tiers come from the venue's seat map, unpriced")
    void draftTakesItsTiersFromTheSeatMap() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 5));

        Event event = createEvent(manager, venue.getId(), "Live in Saigon", NEXT_MONTH);

        assertThat(event.getStatus()).isEqualTo(EventStatus.DRAFT);
        assertThat(event.getPricingTiers()).singleElement().satisfies(tier -> {
            assertThat(tier.getName()).isEqualTo("Standard");
            assertThat(tier.getPrice()).isNull();
        });
    }

    @Test
    @DisplayName("a draft follows the venue's seat map as it is edited")
    void draftTracksTheVenue() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 1, 4));
        Event event = createEvent(manager, venue.getId(), "Live in Saigon", NEXT_MONTH);

        // A second tier appears on the map; the draft now has a second tier to price.
        SeatMap widened = SeatMaps.of(
                SeatMaps.seat("A1", 1, 1, "Standard"),
                SeatMaps.seat("A2", 2, 1, "Standard"),
                SeatMaps.seat("V1", 1, 5, "VIP"));
        putSeatMap(manager, venue.getId(), widened);

        Event reread = exchange(HttpMethod.GET, "/events/" + event.getId(), manager, null, Event.class)
                .getBody();

        assertThat(reread.getPricingTiers()).extracting(PricingTier::getName)
                .containsExactlyInAnyOrder("Standard", "VIP");
    }

    @Test
    @DisplayName("publishing is refused while the organization is unapproved")
    void unapprovedOrganizationCannotPublish() {
        TokenPair manager = manager();                       // deliberately not approved
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 2));
        Event event = createEvent(manager, venue.getId(), "Live in Saigon", NEXT_MONTH);
        priceTier(manager, event.getId(), "Standard", 250_000);

        ResponseEntity<Error> refused = publish(manager, event.getId(), Error.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().getCode()).isEqualTo(ErrorCode.ORGANIZATION_NOT_APPROVED);
    }

    @Test
    @DisplayName("publishing is refused while a tier in use has no price, and names it")
    void unpricedTierCannotPublish() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.of(
                SeatMaps.seat("A1", 1, 1, "Standard"),
                SeatMaps.seat("V1", 1, 5, "VIP")));
        Event event = createEvent(manager, venue.getId(), "Live in Saigon", NEXT_MONTH);
        priceTier(manager, event.getId(), "Standard", 250_000);

        ResponseEntity<Error> refused = publish(manager, event.getId(), Error.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().getCode()).isEqualTo(ErrorCode.PUBLISH_PRECONDITION_FAILED);
        assertThat(refused.getBody().getMessage()).contains("VIP").doesNotContain("Standard");
    }

    @Test
    @DisplayName("publishing is refused when the event has already started")
    void pastStartTimeCannotPublish() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 2));
        Event event = createEvent(manager, venue.getId(), "Yesterday's Show",
                OffsetDateTime.now().minus(1, ChronoUnit.DAYS));
        priceTier(manager, event.getId(), "Standard", 250_000);

        ResponseEntity<Error> refused = publish(manager, event.getId(), Error.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().getMessage()).contains("starts in the past");
    }

    @Test
    @DisplayName("publishing is refused when the venue has no seats")
    void emptySeatMapCannotPublish() {
        TokenPair manager = approvedManager();
        Venue venue = createVenue(manager, "Empty Room", "Ho Chi Minh City");
        Event event = createEvent(manager, venue.getId(), "Live in Saigon", NEXT_MONTH);

        ResponseEntity<Error> refused = publish(manager, event.getId(), Error.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().getMessage()).contains("no seats");
    }

    @Test
    @DisplayName("publishing copies the seat map, and later edits to the venue do not reach it")
    void publishingSnapshotsTheSeatMap() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 5));
        Event event = publishedEvent(manager, venue, "Live in Saigon");

        // The room is re-drawn afterwards - a smaller layout, different labels, a new tier.
        putSeatMap(manager, venue.getId(), SeatMaps.of(SeatMaps.seat("Z9", 9, 9, "Rearranged")));

        EventSeatMap frozen = exchange(HttpMethod.GET,
                "/public/events/" + event.getId() + "/seat-map", null, null, EventSeatMap.class)
                .getBody();

        assertThat(frozen.getSeats()).hasSize(10);
        assertThat(frozen.getSeats()).extracting(s -> s.getLabel()).contains("A1", "B5")
                .doesNotContain("Z9");
        assertThat(frozen.getSeats()).allSatisfy(seat ->
                assertThat(seat.getAvailability()).isEqualTo(SeatAvailability.AVAILABLE));
        assertThat(frozen.getElements()).singleElement()
                .satisfies(element -> assertThat(element.getLabel()).isEqualTo("Stage"));
    }

    @Test
    @DisplayName("after publishing, the title may change and the start time reports who was told")
    void publishedEventStaysPartlyEditable() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 2));
        Event event = publishedEvent(manager, venue, "Live in Saigon");

        // Rescheduling moves the whole event, window included. Moving only the start is the
        // next test, and it is refused.
        OffsetDateTime moved = NEXT_MONTH.plus(1, ChronoUnit.DAYS);
        EventPatch renamed = new EventPatch();
        renamed.setTitle("Live in Saigon (rescheduled)");
        renamed.setStartsAt(moved);
        renamed.setDoorsOpenAt(moved.minusHours(1));
        renamed.setEndsAt(moved.plusHours(4));

        Event updated = exchange(HttpMethod.PATCH, "/events/" + event.getId(), manager,
                renamed, Event.class).getBody();

        assertThat(updated.getTitle()).isEqualTo("Live in Saigon (rescheduled)");
        assertThat(updated.getStatus()).isEqualTo(EventStatus.PUBLISHED);
        assertThat(updated.getEndsAt()).isEqualTo(moved.plusHours(4));
        // Nobody holds a ticket yet, so the honest count is zero rather than absent.
        assertThat(updated.getNotifyCount()).isZero();
    }

    /**
     * requirements/003 criterion 9. Found by probing the deployed server, which accepted it.
     *
     * <p>The damage is criterion 9's own promise: moving a published Event emails everybody
     * holding a ticket, so this mails them a date that has already been and gone - and leaves a
     * door that will not open, because the admission window closed before the message arrived.
     */
    @Test
    @DisplayName("a published event cannot be moved into the past")
    void aPublishedEventCannotGoBackwards() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 2));
        Event event = publishedEvent(manager, venue, "Live in Saigon");

        OffsetDateTime lastWeek = OffsetDateTime.now().minus(7, ChronoUnit.DAYS);
        EventPatch backwards = new EventPatch();
        backwards.setStartsAt(lastWeek);
        backwards.setDoorsOpenAt(lastWeek.minusHours(1));
        backwards.setEndsAt(lastWeek.plusHours(3));

        ResponseEntity<Error> refused = exchange(HttpMethod.PATCH, "/events/" + event.getId(),
                manager, backwards, Error.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().getMessage()).contains("into the past");

        // And it did not half-happen: the Event is where it was.
        //
        // Compared against what the API returned when it was published, not against the
        // constant it was built from. `NEXT_MONTH` is `OffsetDateTime.now()`, which carries
        // nanoseconds; Postgres `timestamptz` keeps microseconds, so anything re-read has been
        // truncated. This assertion passed locally and failed in CI on the last three digits -
        // the difference being that a PATCH response is built from the entity still in the
        // persistence context, while this GET goes back to the database.
        assertThat(exchange(HttpMethod.GET, "/events/" + event.getId(), manager, null, Event.class)
                .getBody().getStartsAt()).isEqualTo(event.getStartsAt());
    }

    /**
     * A Draft is deliberately left alone. Criterion 5 makes publishing the gate, and being
     * stopped there with a reason beats being stopped while typing a date.
     */
    @Test
    @DisplayName("a draft may sit in the past, and is refused at publish rather than at edit")
    void aDraftMaySitInThePast() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 2));
        OffsetDateTime lastWeek = OffsetDateTime.now().minus(7, ChronoUnit.DAYS);

        var input = new com.eventticket.api.model.EventInput("Last Tuesday", venue.getId(), lastWeek);
        input.setDoorsOpenAt(lastWeek.minusHours(1));
        input.setEndsAt(lastWeek.plusHours(3));
        Event draft = exchange(HttpMethod.POST, "/events", manager, input, Event.class).getBody();
        assertThat(draft.getStatus()).isEqualTo(EventStatus.DRAFT);

        priceTier(manager, draft.getId(), "Standard", 250_000);
        ResponseEntity<Error> refused = publish(manager, draft.getId(), Error.class);

        assertThat(refused.getBody().getCode()).isEqualTo(ErrorCode.PUBLISH_PRECONDITION_FAILED);
        assertThat(refused.getBody().getMessage()).contains("past");
    }

    /**
     * The contract has said {@code minimum: 0} on {@code Money.amount} since the beginning, and
     * the server answered a negative price with 500 and a stack trace.
     *
     * <p>The cause is not this endpoint. A request body that is a top-level array is not
     * validated element by element: the generated signature reads
     * {@code List<@Valid PricingTierInput>}, and Spring validates the list - which has no
     * constraints - and never descends into it. So the assertions below are about the answer a
     * caller gets, and they are deliberately the same assertions an object body would satisfy.
     */
    @Test
    @DisplayName("a price the contract forbids is refused as a bad request, not as a server error")
    void anArrayBodyIsCheckedElementByElement() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 2));
        Event event = createEvent(manager, venue.getId(), "Live in Saigon", NEXT_MONTH);

        var negative = new PricingTierInput("Standard", new Money(-5_000L, Money.CurrencyEnum.VND));
        ResponseEntity<Error> refused = exchange(HttpMethod.PUT,
                "/events/" + event.getId() + "/pricing-tiers", manager, List.of(negative), Error.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        // Which element and which field, because a table of prices needs to know which row.
        assertThat(refused.getBody().getDetails()).containsKey("[0].price.amount");
    }

    /** Zero is a price. Free events are real, and the contract's minimum is 0 rather than 1. */
    @Test
    @DisplayName("a tier may cost nothing")
    void aTierMayBeFree() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 2));
        Event event = createEvent(manager, venue.getId(), "Free Night", NEXT_MONTH);

        priceTier(manager, event.getId(), "Standard", 0);

        assertThat(publish(manager, event.getId(), Event.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("publishing is refused without an admission window")
    void noAdmissionWindowCannotPublish() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 2));

        // Built by hand: the shared fixture sets a window, and this is the test that must not.
        var input = new com.eventticket.api.model.EventInput("Live in Saigon", venue.getId(), NEXT_MONTH);
        Event event = exchange(HttpMethod.POST, "/events", manager, input, Event.class).getBody();
        priceTier(manager, event.getId(), "Standard", 250_000);

        ResponseEntity<Error> refused = publish(manager, event.getId(), Error.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().getCode()).isEqualTo(ErrorCode.PUBLISH_PRECONDITION_FAILED);
        assertThat(refused.getBody().getMessage()).contains("doors open");
    }

    @Test
    @DisplayName("an event cannot be moved past its own end")
    void movingTheStartPastTheEndIsRefused() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 2));
        Event event = publishedEvent(manager, venue, "Live in Saigon");

        EventPatch tooFar = new EventPatch();
        tooFar.setStartsAt(NEXT_MONTH.plus(1, ChronoUnit.DAYS));   // the window stays put

        ResponseEntity<Error> refused = exchange(HttpMethod.PATCH, "/events/" + event.getId(),
                manager, tooFar, Error.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(422));
        assertThat(refused.getBody().getMessage()).contains("must end after it starts");
    }

    @Test
    @DisplayName("seats can be held back from sale after publishing")
    void seatsCanBeWithheldFromSale() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 1, 4));
        Event event = publishedEvent(manager, venue, "Live in Saigon");

        EventSeatMap map = exchange(HttpMethod.GET, "/public/events/" + event.getId() + "/seat-map",
                null, null, EventSeatMap.class).getBody();
        UUID first = map.getSeats().get(0).getId();

        EventPatch withhold = new EventPatch();
        withhold.setUnsellableSeatIds(java.util.List.of(first));
        exchange(HttpMethod.PATCH, "/events/" + event.getId(), manager, withhold, Event.class);

        EventSeatMap after = exchange(HttpMethod.GET, "/public/events/" + event.getId() + "/seat-map",
                null, null, EventSeatMap.class).getBody();

        assertThat(after.getSeats()).filteredOn(seat -> seat.getId().equals(first))
                .singleElement()
                .satisfies(seat -> assertThat(seat.getAvailability())
                        .isEqualTo(SeatAvailability.NOT_FOR_SALE));
    }

    @Test
    @DisplayName("closing sales leaves the event published and readable")
    void closingSalesStopsNewOrdersOnly() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 2));
        Event event = publishedEvent(manager, venue, "Live in Saigon");

        Event closed = exchange(HttpMethod.POST, "/events/" + event.getId() + "/close-sales",
                manager, null, Event.class).getBody();

        assertThat(closed.getStatus()).isEqualTo(EventStatus.SALES_CLOSED);
        assertThat(exchange(HttpMethod.GET, "/public/events/" + event.getId(), null, null,
                PublicEvent.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a published event has a public page anyone can read; a draft does not")
    void publicPageIsReachableByLink() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 2));
        Event draft = createEvent(manager, venue.getId(), "Still Cooking", NEXT_MONTH);
        Event published = publishedEvent(manager, venue, "Live in Saigon");

        // No session at all: this is a stranger with a link.
        PublicEvent page = exchange(HttpMethod.GET, "/public/events/" + published.getId(),
                null, null, PublicEvent.class).getBody();

        assertThat(page.getTitle()).isEqualTo("Live in Saigon");
        assertThat(page.getOrganizationName()).isEqualTo("Acme Events");
        assertThat(page.getVenueName()).isEqualTo("Hoa Binh Theatre");
        assertThat(page.getCity()).isEqualTo("Ho Chi Minh City");
        assertThat(page.getTimezone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(page.getPriceFrom().getAmount()).isEqualTo(250_000);

        assertThat(exchange(HttpMethod.GET, "/public/events/" + draft.getId(), null, null,
                Error.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("an unlisted event keeps its link but leaves the listing")
    void unlistingRemovesAnEventFromTheListingOnly() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 2));
        Event event = publishedEvent(manager, venue, "Live in Saigon");

        assertThat(publicListing("Ho Chi Minh City")).extracting(e -> e.getTitle())
                .contains("Live in Saigon");

        EventPatch unlist = new EventPatch();
        unlist.setListed(false);
        exchange(HttpMethod.PATCH, "/events/" + event.getId(), manager, unlist, Event.class);

        assertThat(publicListing("Ho Chi Minh City")).isEmpty();
        assertThat(exchange(HttpMethod.GET, "/public/events/" + event.getId(), null, null,
                PublicEvent.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("the public listing filters by city")
    void publicListingFiltersByCity() {
        TokenPair manager = approvedManager();
        Venue saigon = venueWithSeats(manager, SeatMaps.block("Standard", 2, 2));
        publishedEvent(manager, saigon, "Live in Saigon");

        assertThat(publicListing("Ho Chi Minh City")).hasSize(1);
        assertThat(publicListing("Da Nang")).isEmpty();
        assertThat(publicListing(null)).hasSize(1);
    }

    @Test
    @DisplayName("a closed or cancelled event leaves the listing and keeps its link")
    void theListingIsPublishedEventsOnly() {
        TokenPair manager = approvedManager();
        Venue venue = venueWithSeats(manager, SeatMaps.block("Standard", 2, 2));
        Event closing = publishedEvent(manager, venue, "Sales Closing");
        Event cancelling = publishedEvent(manager, venue, "Cancelled Show");

        assertThat(publicListing(null)).extracting(e -> e.getTitle())
                .containsExactlyInAnyOrder("Sales Closing", "Cancelled Show");

        exchange(HttpMethod.POST, "/events/" + closing.getId() + "/close-sales", manager, null,
                Event.class);
        assertThat(exchange(HttpMethod.POST, "/events/" + cancelling.getId() + "/cancel", manager,
                new com.eventticket.api.model.RefundRequest("The venue flooded."),
                com.eventticket.api.model.EventCancellation.class).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);

        // requirements/009 criterion 10. Both were published once, and "has ever been
        // published" is not the question the listing is asking.
        assertThat(publicListing(null)).isEmpty();
        assertThat(publicListing("Ho Chi Minh City")).isEmpty();

        // Criterion 9: an existing link never breaks because the event's status changed. A
        // buyer holding a ticket for a cancelled show is exactly who follows one.
        assertThat(exchange(HttpMethod.GET, "/public/events/" + closing.getId(), null, null,
                PublicEvent.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange(HttpMethod.GET, "/public/events/" + cancelling.getId(), null, null,
                PublicEvent.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private java.util.List<com.eventticket.api.model.PublicEventSummary> publicListing(String city) {
        String path = city == null ? "/public/events" : "/public/events?city={city}";
        return exchange(HttpMethod.GET, path, null, null,
                com.eventticket.api.model.PublicEventPage.class,
                city == null ? java.util.Map.of() : java.util.Map.of("city", city))
                .getBody().getItems();
    }

    private Event publishedEvent(TokenPair manager, Venue venue, String title) {
        Event event = createEvent(manager, venue.getId(), title, NEXT_MONTH);
        priceTier(manager, event.getId(), "Standard", 250_000);
        ResponseEntity<Event> published = publish(manager, event.getId(), Event.class);
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
        return published.getBody();
    }

    private Venue venueWithSeats(TokenPair manager, SeatMap map) {
        Venue venue = createVenue(manager, "Hoa Binh Theatre", "Ho Chi Minh City");
        putSeatMap(manager, venue.getId(), map);
        return venue;
    }

    private TokenPair approvedManager() {
        TokenPair alice = signUp("alice@example.com");
        var organization = createOrganization(alice, "Acme Events");
        approve(organization);
        return switchTo(alice, organization);
    }

    private TokenPair manager() {
        TokenPair alice = signUp("alice@example.com");
        return switchTo(alice, createOrganization(alice, "Acme Events"));
    }
}
