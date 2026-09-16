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
import com.eventticket.event.usecase.ListFeaturedEvents;
import com.eventticket.event.usecase.ListTrendingEvents;
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
 * <p>The curated and ranked rows answer an empty array when they are not shown, which for
 * those two is the truth rather than a placeholder: there is no such thing as a featured row
 * that exists but has nothing in it.
 */
@RestController
public class PublicEventController implements PublicApi {

    private final ListPublicEvents listPublicEvents;
    private final GetPublicEvent getPublicEvent;
    private final GetPublicEventSeatMap getPublicEventSeatMap;
    private final ListCategories listCategories;
    private final ListCities listCities;
    private final ListFeaturedEvents listFeaturedEvents;
    private final ListTrendingEvents listTrendingEvents;

    public PublicEventController(ListPublicEvents listPublicEvents, GetPublicEvent getPublicEvent,
                          GetPublicEventSeatMap getPublicEventSeatMap,
                          ListCategories listCategories, ListCities listCities,
                          ListFeaturedEvents listFeaturedEvents,
                          ListTrendingEvents listTrendingEvents) {
        this.listPublicEvents = listPublicEvents;
        this.getPublicEvent = getPublicEvent;
        this.getPublicEventSeatMap = getPublicEventSeatMap;
        this.listCategories = listCategories;
        this.listCities = listCities;
        this.listFeaturedEvents = listFeaturedEvents;
        this.listTrendingEvents = listTrendingEvents;
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
     * The curated row. An empty array means the row is not shown - which covers both "nobody
     * has placed anything" and "what was placed no longer qualifies", because to a client they
     * are the same instruction: draw nothing.
     */
    @Override
    public ResponseEntity<List<PublicEventSummary>> publicFeaturedEventsGet() {
        return ResponseEntity.ok(listFeaturedEvents.list().stream()
                .map(EventMapper::toSummaryDto)
                .toList());
    }

    /**
     * The ranked row, numbered from one.
     *
     * <p>The rank is assigned here, from the order the use case answered in, because that is
     * what a rank is - a position in a list. Nothing carries the sales figures that produced
     * it (requirements/009 criterion 15): a position says one Event outsold another, where a
     * count says what an Organization took, to anybody who loads the page.
     */
    @Override
    public ResponseEntity<List<TrendingEvent>> publicTrendingEventsGet() {
        var ranked = listTrendingEvents.list();
        var dto = new java.util.ArrayList<TrendingEvent>(ranked.size());
        for (int index = 0; index < ranked.size(); index++) {
            dto.add(new TrendingEvent(index + 1, EventMapper.toSummaryDto(ranked.get(index))));
        }
        return ResponseEntity.ok(dto);
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
