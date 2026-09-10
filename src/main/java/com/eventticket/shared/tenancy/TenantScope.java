package com.eventticket.shared.tenancy;

import org.springframework.data.mongodb.core.query.Criteria;

/**
 * What replaced row-level security, and the honest account of what was lost doing it.
 *
 * <h2>The difference that matters</h2>
 *
 * <p>In Postgres the tenant was published <em>into the database session</em> - {@code SET LOCAL
 * ROLE eventticket_app} and two {@code set_config} calls at the start of every transaction -
 * and the database applied it to every statement whether the query asked for it or not. A
 * developer who forgot the tenant filter wrote a query that returned nothing, or that failed a
 * {@code WITH CHECK}. <strong>Forgetting was safe.</strong>
 *
 * <p>MongoDB has no equivalent at any level: no policies, no session-scoped predicate, no way
 * to make the server narrow a query the client did not narrow. The filter is now the
 * application's, and <strong>forgetting is a data breach</strong> - one organizer's query
 * returning another's orders, silently, with a 200.
 *
 * <p>That inversion is the single largest cost of this migration and it cannot be engineered
 * away. What it can be is <em>loud</em>, and {@code TenantScopeTest} is what makes it so: every
 * query on a collection that used to carry a policy must narrow by the tenant, be a lookup by
 * key, or be listed with the reason it does not need to - and the build fails otherwise.
 *
 * <p><strong>Be accurate about what that buys.</strong> Most of this application's queries
 * narrow by a <em>parent</em> - an event id, an order id - and are safe only because the caller
 * loaded that parent and checked it first. Under Postgres they were safe regardless. So the
 * BY_PARENT list in that test is not an exemption list, it is the risk register: the places
 * where isolation is now a convention rather than a mechanism.
 *
 * <p>The criteria below are the vocabulary for the cases that <em>can</em> be narrowed directly,
 * and for a stronger design than this one. The strongest available - a {@code MongoTemplate}
 * subclass that injects the tenant into every query, including Spring Data's derived ones - is
 * not built here, and that is a gap rather than a considered omission. Postgres made the mistake
 * impossible; this makes it detectable before it ships, which is a weaker guarantee honestly
 * stated.
 *
 * <h2>Three shapes, not one</h2>
 *
 * <p>The ten policies were not one rule repeated, and this is where a naive port leaks. A
 * single "add organizationId to every query" reproduces {@link #ownedByTenant} and silently
 * breaks the other two - the public event page stops working for signed-out visitors, and a
 * buyer can no longer see the ticket they just bought.
 */
public final class TenantScope {

    private TenantScope() {}

    /**
     * Plain tenant isolation: {@code membership}, {@code audit_entry}, {@code scan}.
     *
     * <p>Mirrors {@code organization_id = current_organization_id()}.
     */
    public static Criteria ownedByTenant() {
        return Criteria.where("organizationId").is(TenantContext.requireOrganizationId());
    }

    /**
     * The tenant's own rows, <em>or</em> anything a published Event already shows the world:
     * {@code venue}, {@code event}, {@code event_pricing_tier}, {@code event_seat}.
     *
     * <p>Mirrors {@code organization_id = current_organization_id() OR published_at IS NOT NULL}.
     * requirements/003 criterion 13 gives every published Event a page anyone can open, so this
     * branch is deliberate and load-bearing rather than a loosening - and it is why a tenancy
     * test for these collections has to use a <em>draft</em>. Counting published documents
     * proves nothing.
     *
     * <p>Note the tenant half tolerates no active organization, because the public half must
     * work for a visitor who is not signed in at all.
     */
    public static Criteria ownedByTenantOrPublished() {
        Criteria published = Criteria.where("publishedAt").ne(null);
        return TenantContext.organizationId() == null
                ? published
                : new Criteria().orOperator(
                        Criteria.where("organizationId").is(TenantContext.organizationId()),
                        published);
    }

    /**
     * The tenant's staff <em>or</em> the buyer: {@code ticket_order}, {@code order_seat},
     * {@code ticket}.
     *
     * <p>Mirrors {@code organization_id = current_organization_id() OR buyer_user_id =
     * current_app_user_id()}. <strong>A buyer is not a member of the Organization they buy
     * from</strong>, and usually has no active Organization at all - so a query narrowed only
     * by tenant hides a buyer's own order from them.
     *
     * <p>The Postgres policy carried the same two branches in {@code WITH CHECK} as well as
     * {@code USING}, because a buyer genuinely <em>creates</em> rows in an Organization that is
     * not theirs. There is no {@code WITH CHECK} here: nothing at all constrains what this
     * application writes, and a write to the wrong tenant is now an ordinary bug rather than a
     * refused statement.
     */
    public static Criteria ownedByTenantOrBuyer() {
        Criteria buyer = Criteria.where("buyerUserId").is(TenantContext.requireUserId());
        return TenantContext.organizationId() == null
                ? buyer
                : new Criteria().orOperator(
                        Criteria.where("organizationId").is(TenantContext.organizationId()),
                        buyer);
    }
}
