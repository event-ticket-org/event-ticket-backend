package com.eventticket.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.admission.repository.ScanRepository;
import com.eventticket.checkout.repository.OrderRepository;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.event.repository.EventSeatRepository;
import com.eventticket.organization.repository.MembershipRepository;
import com.eventticket.shared.audit.AuditEntryRepository;
import com.eventticket.ticket.repository.TicketRepository;
import com.eventticket.venue.repository.VenueRepository;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The closest thing to row-level security that survives, and an honest account of how much
 * closer that is not.
 *
 * <h2>What this replaces</h2>
 *
 * <p>Ten Postgres policies applied a tenant predicate to every statement, in the database,
 * whether the query asked for it or not. A developer who forgot wrote a query that returned
 * nothing. <strong>Forgetting was safe.</strong>
 *
 * <p>MongoDB has no equivalent at any level. Forgetting now returns another organization's rows
 * with a 200. The mistake cannot be made impossible, so the goal here is the weaker one that is
 * actually available: <strong>make it impossible to make it silently.</strong>
 *
 * <h2>What the test asserts</h2>
 *
 * <p>Every query method on a tenant-scoped repository must fall into one of three declared
 * categories. A method that fits none fails the build, so a new unscoped finder cannot be added
 * without either narrowing it or writing down why it does not need to be.
 *
 * <p>The three categories are not equally strong, and pretending otherwise would be the whole
 * problem in miniature:
 *
 * <ul>
 *   <li><strong>By tenant</strong> - names {@code organizationId}. Isolation is in the query.
 *       This is the only category that is self-sufficient.</li>
 *   <li><strong>By key</strong> - an id, or a value that is itself a secret (a ticket code
 *       lookup, a token hash, a provider reference). Knowing the key is the authorisation, which
 *       was true under Postgres too.</li>
 *   <li><strong>By parent</strong> - narrows by {@code eventId}, {@code orderId} or
 *       {@code userId}. <strong>These are not isolated by themselves.</strong> They are safe only
 *       because the caller has already loaded the parent and checked it belongs to the tenant.
 *       Under RLS they were safe regardless; now they are safe by convention, and the list below
 *       is exactly the set of places that convention is load-bearing.</li>
 * </ul>
 *
 * <p>So the list of BY_PARENT methods is not an exemption list, it is the <em>risk register</em>.
 * It is the price of losing the policies, written down.
 */
class TenantScopeTest {

    /** The collections that had a row-level security policy in Postgres. */
    private static final List<Class<?>> TENANT_SCOPED = List.of(
            VenueRepository.class,
            EventRepository.class,
            EventSeatRepository.class,
            OrderRepository.class,
            TicketRepository.class,
            MembershipRepository.class,
            ScanRepository.class,
            AuditEntryRepository.class);

    /** Narrowed by the tenant itself. The only category that needs nothing else to be true. */
    private static final Set<String> BY_TENANT = Set.of("organizationid");

    /**
     * Also narrowed by the tenant, but by a parameter rather than by a name a reflection test
     * can see. Listed rather than pattern-matched, because "trust me, there is an organizationId
     * in there somewhere" is exactly the assurance this file exists to stop accepting.
     */
    private static final Map<String, String> BY_TENANT_PARAMETER = Map.of(
            "EventRepository.findPage",
            "takes organizationId and EventRepositoryImpl filters on it as the first criterion");

    /**
     * Authorised by knowing an unguessable value, which was equally true under Postgres.
     *
     * <p>{@code findByCodeLookup} used to be on this list and it was wrong to be. A ticket code
     * is unguessable, so "knowing it is the authorisation" sounded right - but the Postgres
     * policy narrowed that read by tenant as well, and for a reason: without it the door
     * answers {@code WRONG_EVENT} for a rival's code instead of {@code UNKNOWN_CODE}, which
     * confirms the code is real and somebody else sold it. It is now
     * {@code findByCodeLookupAndOrganizationId} and belongs to BY_TENANT.
     *
     * <p>Worth keeping as a note rather than deleting: this test classified that method as safe
     * and a behavioural test proved it was not. A reflection test can check that a query is
     * narrowed; it cannot check that it is narrowed <em>enough</em>.
     */
    private static final Set<String> BY_KEY = Set.of(
            "findbyid", "existsbyid", "deletebyid", "findbyidin", "findallbyid",
            "findbytoken", "findbyproviderref", "redeem", "findorthrow");

    /**
     * Narrowed by a parent the caller is expected to have checked. Each entry names the check
     * that makes it safe; if that check is ever removed, the isolation goes with it and nothing
     * here will notice.
     */
    private static final Map<String, String> BY_PARENT = new TreeMap<>(Map.ofEntries(
            Map.entry("VenueRepository.findByIdIn",
                    "ids come from findIdsByCity for a public listing, which is tenant-free on purpose"),
            Map.entry("VenueRepository.findIdsByCityInternal",
                    "public discovery: requirements/009 lets anyone search by city"),
            Map.entry("VenueRepository.findIdsByCity",
                    "the default method wrapping findIdsByCityInternal; same public listing"),
            Map.entry("EventSeatRepository.findByEventIdOrderByLabelAsc",
                    "caller loaded the Event first and called requireBelongsTo"),
            Map.entry("EventSeatRepository.findByEventIdAndIdIn",
                    "same; and the seat ids are checked against the Event"),
            Map.entry("EventSeatRepository.countByEventIdAndForSaleTrue",
                    "counts only, for an Event the caller has already resolved"),
            Map.entry("EventSeatRepository.findTierNames",
                    "counts only, for an Event the caller has already resolved"),
            Map.entry("EventSeatRepository.countSeats",
                    "public listing: counts for Events the listing query already narrowed"),
            Map.entry("OrderRepository.findPageForBuyer",
                    "narrowed by buyerUserId, which is the buyer branch of the old policy"),
            Map.entry("OrderRepository.findPageForEvent",
                    "caller loaded the Event and called requireBelongsTo"),
            Map.entry("OrderRepository.findByEventIdAndStatus",
                    "cancellation: caller owns the Event"),
            Map.entry("OrderRepository.countByEventIdAndRefundRequiredTrue",
                    "counts only, for an Event the caller has already resolved"),
            Map.entry("TicketRepository.findByOrderIdOrderBySeatLabelAsc",
                    "caller loaded the Order and called requireBuyerOrOrganization"),
            Map.entry("TicketRepository.findByEventId",
                    "cancellation voids every Ticket; caller owns the Event"),
            Map.entry("TicketRepository.countByOrderId", "counts only, for a resolved Order"),
            Map.entry("TicketRepository.countByOrderIdAndStatus", "counts only, for a resolved Order"),
            Map.entry("MembershipRepository.findByUserId",
                    "a User's own memberships, for /me - not a tenant question"),
            Map.entry("ScanRepository.findByEventIdOrderByOccurredAtDesc",
                    "caller owns the Event"),
            Map.entry("ScanRepository.countByEventId", "counts only, for a resolved Event"),
            Map.entry("EventRepository.findPublicPage",
                    "public discovery, deliberately tenant-free (requirements/009)"),
            Map.entry("EventRepository.countsFor",
                    "counts for Events the caller has already resolved")));

    @Test
    @DisplayName("every query on a tenant-scoped collection is narrowed, or says why it is not")
    void noQueryIsSilentlyUnscoped() {
        List<String> unaccounted = TENANT_SCOPED.stream()
                .flatMap(repository -> declaredQueries(repository)
                        .map(method -> repository.getSimpleName() + "." + method.getName()))
                .distinct()
                .filter(name -> !isAccountedFor(name))
                .sorted()
                .toList();

        assertThat(unaccounted)
                .describedAs("""
                        These read a collection that used to carry a row-level security policy, \
                        and narrow it by nothing. Under Postgres that was safe. It is not now: \
                        the query returns every organization's rows.

                        Narrow it by organizationId, or add it to BY_PARENT with the check that \
                        makes it safe - and understand that BY_PARENT is a promise the compiler \
                        cannot keep for you.""")
                .isEmpty();
    }

    /**
     * The risk register is only useful if it is current. An entry naming a method that no longer
     * exists is a line nobody will delete and everybody will trust.
     */
    @Test
    @DisplayName("the by-parent list has no entries for methods that no longer exist")
    void theRiskRegisterIsNotStale() {
        Set<String> actual = TENANT_SCOPED.stream()
                .flatMap(repository -> declaredQueries(repository)
                        .map(method -> repository.getSimpleName() + "." + method.getName()))
                .collect(Collectors.toSet());

        assertThat(BY_PARENT.keySet()).allSatisfy(declared ->
                assertThat(actual).describedAs("stale entry: " + declared).contains(declared));
        assertThat(BY_TENANT_PARAMETER.keySet()).allSatisfy(declared ->
                assertThat(actual).describedAs("stale entry: " + declared).contains(declared));
    }

    private static java.util.stream.Stream<Method> declaredQueries(Class<?> repository) {
        return java.util.Arrays.stream(repository.getMethods())
                .filter(method -> method.getDeclaringClass() != Object.class)
                // Spring Data's own CRUD surface is by key or is not a read.
                .filter(method -> method.getDeclaringClass().getName().startsWith("com.eventticket"));
    }

    private static boolean isAccountedFor(String qualified) {
        String method = qualified.substring(qualified.indexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
        return BY_TENANT.stream().anyMatch(method::contains)
                || BY_TENANT_PARAMETER.containsKey(qualified)
                || BY_KEY.stream().anyMatch(method::startsWith)
                || BY_PARENT.containsKey(qualified);
    }
}
