package com.eventticket.event.web;

import com.eventticket.api.PublicApi;
import com.eventticket.api.model.Category;
import com.eventticket.api.model.CategoryFacet;
import com.eventticket.api.model.City;
import com.eventticket.api.model.EventSeatMap;
import com.eventticket.api.model.PublicEvent;
import com.eventticket.api.model.PublicEventPage;
import com.eventticket.api.model.PublicEventSort;
import com.eventticket.api.model.PublicEventSummary;
import com.eventticket.api.model.TrendingEvent;
import com.eventticket.event.usecase.GetPublicEvent;
import com.eventticket.event.usecase.GetPublicEventSeatMap;
import com.eventticket.event.usecase.ListCategories;
import com.eventticket.event.usecase.ListPublicEvents;
import com.eventticket.venue.usecase.ListCities;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The unauthenticated half of the API. No tenant reaches these requests, and none is needed:
 * the row-level security policies admit a published Event to everyone, so the same queries
 * that a manager runs return the public rows here and nothing else.
 *
 * <p>The Category and City sets need no policy at all, which is a different thing from being
 * exempt from one: they are not tenant-scoped, every Organization sees the same rows, and a
 * visitor with no tenant sees them too. That is what makes them answerable here.
 *
 * <p>{@code Category} and {@code City} exist as both generated and domain types, like
 * {@code Venue} before them. The generated ones are imported and the domain ones qualified.
 *
 * <p>The curated and ranked rows are not implemented here yet. The generated interface answers
 * 501 for each until they are, which is a truthful answer and a better one than an empty array
 * - an empty row means "nothing is featured today", and that is not what is happening.
 */
@RestController
public class PublicEventController implements PublicApi {

    private final ListPublicEvents listPublicEvents;
    private final GetPublicEvent getPublicEvent;
    private final GetPublicEventSeatMap getPublicEventSeatMap;
    private final ListCategories listCategories;
    private final ListCities listCities;

    public PublicEventController(ListPublicEvents listPublicEvents, GetPublicEvent getPublicEvent,
                          GetPublicEventSeatMap getPublicEventSeatMap,
                          ListCategories listCategories, ListCities listCities) {
        this.listPublicEvents = listPublicEvents;
        this.getPublicEvent = getPublicEvent;
        this.getPublicEventSeatMap = getPublicEventSeatMap;
        this.listCategories = listCategories;
        this.listCities = listCities;
    }

    @Override
    public ResponseEntity<PublicEventPage> publicEventsGet(String q, PublicEventSort sort,
                                                           String categorySlug, String citySlug,
                                                           OffsetDateTime startsAfter,
                                                           OffsetDateTime startsBefore,
                                                           Integer limit, String cursor) {
        var listing = listPublicEvents.list(q, sort == PublicEventSort.RELEVANCE, categorySlug,
                citySlug, at(startsAfter), at(startsBefore), limit, cursor);

        var dto = new PublicEventPage();
        listing.page().items().forEach(view -> dto.addItemsItem(EventMapper.toSummaryDto(view)));
        dto.setNextCursor(listing.page().nextCursor());
        // Absent after the first page rather than empty: an empty array would say every
        // Category matches nothing, which is a different claim from "not counted here".
        listing.facets().forEach(facet -> dto.addCategoryFacetsItem(
                new CategoryFacet(facet.slug(), facet.name(), (int) facet.count())));
        return ResponseEntity.ok(dto);
    }

    @Override
    public ResponseEntity<List<Category>> publicCategoriesGet() {
        return ResponseEntity.ok(listCategories.list().stream()
                .map(category -> new Category(category.slug(), category.name()))
                .toList());
    }

    @Override
    public ResponseEntity<List<City>> publicCitiesGet() {
        return ResponseEntity.ok(listCities.list().stream()
                .map(city -> new City(city.slug(), city.name()))
                .toList());
    }


    /**
     * The curated and ranked rows, recognised and not yet built.
     *
     * <p>501 rather than an empty array, and the distinction is the whole reason these are
     * written out rather than left to a generated default. An empty array is an answer: it says
     * nothing is featured today, which a client will render as a row it drew and found bare.
     * 501 says the server has not implemented this, which is what is true - and it is the
     * status a client can branch on to draw nothing at all.
     *
     * <p>The contract landed whole because it is one coherent revision; the implementation
     * follows it in sequence. {@code EventDiscoveryTest} asserts this answer, so the day these
     * are built is the day a test tells somebody to delete this comment.
     */
    @Override
    public ResponseEntity<List<PublicEventSummary>> publicFeaturedEventsGet() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @Override
    public ResponseEntity<List<TrendingEvent>> publicTrendingEventsGet() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @Override
    public ResponseEntity<PublicEvent> publicEventsEventIdGet(UUID eventId) {
        return ResponseEntity.ok(EventMapper.toPublicDto(getPublicEvent.get(eventId)));
    }

    @Override
    public ResponseEntity<EventSeatMap> publicEventsEventIdSeatMapGet(UUID eventId) {
        return ResponseEntity.ok(EventMapper.toDto(getPublicEventSeatMap.get(eventId)));
    }

    private static Instant at(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
