package com.eventticket.event.repository;

import com.eventticket.event.domain.Event;
import com.eventticket.shared.error.ApiException;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * <strong>Nothing here narrows a read to a tenant, and that is now a hazard rather than a
 * design.</strong>
 *
 * <p>Row-level security used to do it: the {@code event_tenant_isolation} policy admitted the
 * active Organization's rows or any published Event, so a query with no tenant predicate was
 * already safe, and the public listing could run with no tenant at all. That is why this
 * interface never mentioned one.
 *
 * <p>MongoDB applies no such policy. Every caller must now compose
 * {@code TenantScope.ownedByTenantOrPublished()} itself, and a caller that forgets returns
 * another Organization's drafts with a 200. The queries that need it live in
 * {@link EventQueries}, where the criteria are explicit and reviewable; what is left here is
 * lookup by key, which is scoped by the key.
 *
 * <p>Both listings still page by keyset rather than by offset: an offset shifts under inserts,
 * so a buyer scrolling while an organization publishes would see rows twice or not at all.
 *
 * <p>One idiom did not survive and did not need to. Every filter used to be expressed as the
 * widest value it could take - an absent status was the whole enum, an absent bound the edge of
 * time, an absent search {@code %} - because Postgres cannot infer the type of a bare parameter
 * in {@code ? is null}. Criteria composed in Java can simply omit a clause, so
 * {@link EventQueries#findPublicPage} takes a nullable {@code venueIds} and drops the filter
 * rather than widening it. See {@code PageCursor}.
 */
public interface EventRepository extends MongoRepository<Event, UUID>, EventQueries {

    public default Event findOrThrow(UUID id) {
        return findById(id).orElseThrow(() -> ApiException.notFound("Event"));
    }
}
