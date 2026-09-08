# event-ticket-backend

Spring Boot 4.1 · Java 21 · PostgreSQL · Flyway · Testcontainers · Maven.

## The knowledge base is normative

Requirements, domain model, the 23 invariants and the API contract live in
[event-ticket-kb](https://github.com/event-ticket-org/event-ticket-kb). Read
`design/domain-model.md` there before changing anything with rules attached.

`contracts/openapi.yaml` here is a **vendored copy** pinned to a merged KB revision recorded
in `contracts/KB_REVISION`. To change the contract: open a PR in the KB, merge it, copy the
file, update `KB_REVISION`, regenerate. A local or unmerged contract commit never authorises
dependent work here.

`openapi-generator` turns that spec into the Spring interfaces the controllers implement, so
a contract the server no longer matches fails the build.

## Where code goes

Every class lives in a sub-package. A module's root holds `package-info.java` and nothing
else.

```
com.eventticket.<feature>
├── domain/       entities, value objects, and rules that belong on them
├── repository/   Spring Data interfaces
├── usecase/      one class per thing a user can do
├── web/          the controller, and the records it maps to
└── support/      feature-local infrastructure, where a feature needs any
```

`shared/` is subdivided by capability instead: `audit/`, `email/`, `error/`, `money/`,
`page/`, `tenancy/`. The bar for adding to `shared` is that **every** feature needs it.

A use case is a class named after the action — `PublishEvent`, `CreateSeatHold`, `ScanTicket`
— with a method named for the domain verb. Every endpoint gets one, reads included;
controllers call use cases, never repositories. The use case is the transaction boundary, and
reads are `@Transactional(readOnly = true)`. Shared logic goes **down** into the domain or the
repository, never sideways into a helper between use cases. Full reasoning in
[ADR-0001](docs/adr/0001-use-case-classes-not-services.md).

Generated API types stay in `web/`. Use cases take and return domain types and the controller
maps between them, so the published contract never becomes the domain model.

A Venue's Seat Map is a `jsonb` document; an Event's is rows, from publish onward. The reasons
and everything that follows are in
[ADR-0003](docs/adr/0003-seat-maps-are-documents-until-they-are-published.md).

## Module boundaries

Declared in each feature's root `package-info.java` with
`@ApplicationModule(type = OPEN, allowedDependencies = {…})`, and enforced by
`ModularityTest`. `OPEN` makes sub-packages importable; `allowedDependencies` is the real
constraint. `shared` depends on nothing. `organization` depends on `shared` only and reaches
people through `shared`'s `UserDirectory`, so it and `identity` can never become mutually
dependent.

The arrows run one way: `venue` <- `event` <- `checkout` -> `payment`, `ticket`, and
`admission` -> `ticket`, `event`, `organization`. When a module
needs one fact about a module that already depends on it, ask the database rather than inverting
an interface: `DELETE /venues/{id}` is refused by a trigger, not by a query into `event`
([ADR-0003](docs/adr/0003-seat-maps-are-documents-until-they-are-published.md)).

**Do not draw a boundary through a transaction.** Confirming a payment sells the seats, marks
the Order paid and issues its Tickets at once (requirements/005 criterion 8), so `checkout` owns
that step and depends on `payment` and `ticket` rather than being called by them. `payment` is
the provider abstraction and nothing else; `ticket` takes the seats to issue for rather than an
Order to read them from. Splitting the transaction across modules forces the boundary to be
crossed in both directions, and Modulith is right to reject that.

A generated API interface groups by OpenAPI **tag**, not by module, so one interface can span
two. `CheckoutApi` covers orders and payment sessions: implement it in the module that may see
both, rather than bending the contract to match the packages.

Prefer the framework's own mechanism over a hand-built one. Modulith's annotations replaced
six bespoke interfaces, records and adapters written to do the same job in an earlier attempt.

## Tenancy: how row-level security actually holds

The tenant reaches Postgres through `TenantAwareTransactionManager`, which at the start of
every transaction issues `SET LOCAL ROLE eventticket_app` and then publishes
`app.organization_id` and `app.user_id`. Policies read those settings.

Three things make it real, and each one looks redundant until it is missing:

- **`FORCE ROW LEVEL SECURITY`** on every tenant-scoped table, or the schema owner bypasses
  every policy.
- **`SET LOCAL ROLE`**, because a superuser bypasses policies *even with* `FORCE`, and the
  connection user is a superuser in development and test. Without this the policies enforce
  nothing, silently. See [ADR-0002](docs/adr/0002-application-runs-as-an-unprivileged-role.md).
- **`set_config(name, value, true)`** rather than the `SET LOCAL` statement, because the
  statement form takes no bind parameter and the tenant would have to be concatenated in.

Each guard catches a different caller, which is why removing one looks harmless: the **policy**
is what filters the application's role, **`FORCE`** is what stops the *schema owner* (migrations,
a `psql` session) from sailing past it, and **`SET LOCAL ROLE`** is what stops the connection
user being a superuser, for whom neither of the other two applies.

A new tenant-scoped table needs `organization_id`, RLS enabled **and** forced, and a policy.

**Tenant isolation outranks a helpful error message.** Another Organization's Ticket is
invisible to a door, so a scan of one comes back `UNKNOWN_CODE` rather than `WRONG_EVENT` -
telling the other organization's staff that a code is "for a different event" would confirm who
sold it. `WRONG_EVENT` is the same-Organization case, which is also the realistic one: a venue
running two halls.

**A buyer is not a member of the Organization they buy from**, and usually has no active
Organization at all. So `ticket_order`, `order_seat` and `ticket` carry `buyer_user_id` and
their policies admit either the Organization's staff or the buyer — in `WITH CHECK` too, because
a buyer genuinely creates rows in an Organization that is not theirs.

`payment_session`, `payment_event` and `email_delivery` are **not** tenant-scoped. A webhook
arrives with no tenant and must find the session before it can know whose it is; scoping that
table makes it a chicken and egg solvable only by punching a hole in the policies. The session
carries `organization_id` and `buyer_user_id` so the webhook can `TenantPublisher.adopt` them
and obey the policies from there on.

**A platform administrator is a member of nothing, so the membership policy hides everything
from them.** That is correct - they are not in any Organization - and it is why the approval
queue came back with every Organization and an empty owner list beside each one. Reading owners
across Organizations goes through `organization_owner_ids` (V12), a definer function that returns
ids and nothing else. Widening `membership_tenant_isolation` to admit administrators would grant
every Membership in the system for every purpose in order to serve one screen. `DecideOrganization`
does not need it: it adopts the tenant of the Organization it is deciding on, which is one
Organization and works for a decision, and cannot work for a list.

**Writes a policy must refuse belong in a `SECURITY DEFINER` function, not in a wider policy.**
A buyer holding a seat is the case: `hold_seats`, `release_seats` and `sell_seats` write
`event_seat` on their behalf. Widening the policy instead would also let them change `for_sale`
on any published event.

**A published Event is public, on purpose.** The `event`, `event_seat`, `event_pricing_tier`
and `venue` policies read "this tenant's rows, **or** whatever a published Event already shows
the world", because requirements/003 criterion 13 gives every published Event a page anyone can
open. So a tenancy test for these tables must use a **Draft** — counting published rows proves
nothing, and 009's cross-organization listing depends on exactly this.

Seat availability lives on `event_seat` — see
[ADR-0004](docs/adr/0004-a-seat-hold-is-a-column-not-a-table.md). The public seat map is read
with no tenant, so every input to availability has to be readable without one; that is why
`sold_at` is a column and not a join to `ticket`.

Rules the knowledge base states absolutely belong in the schema. The seat map of a published
Event is immutable (KB invariant 12), so `event_seat_frozen` and `event_frozen` refuse the
change in `V4`, and `PublishedSeatMapIsFrozenTest` attacks them with raw SQL as the superuser.
An application check cannot be tested that way and protects only the paths that remember it.

**Insert an Event's seats before marking it published.** The freeze trigger can see this
transaction's own flushed update, so setting `published_at` first makes the seat inserts
refuse themselves. `PublishEvent` flushes the seats explicitly for that reason.

**Unauthenticated flows cannot read tenant-scoped tables.** At login nobody is authenticated
yet, so `current_app_user_id()` is null and the membership policy matches nothing — which is
correct. `TenantPublisher.adopt` is the deliberate exception, for a use case that establishes
identity as part of its own work (token refresh) or acts on an organization from outside it
(platform approval). Anywhere else, the tenant should have come from the access token.

## Object storage

Cover images go straight from the browser to the store and are checked afterwards (ADR-0006).
`shared/storage` is the port and the S3 adapter; `event` uses it and nothing else does.

**The presigned POST policy is written by hand, and that is not an oversight.** The Java SDK v2
presigns GET and PUT and has no POST policy at all, where the JavaScript and Python SDKs do. A
presigned PUT would have been less code and a worse guarantee: it has no equivalent of
`content-length-range`, so the size ceiling would live in a number the client tells us rather
than in a condition the store enforces. Code that computes a signature is either exactly right
or completely broken and reading it proves neither - `CoverImageUploadTest` performs real
uploads against a real MinIO, and that is what says it works.

**Uploading directly moves the validation, it does not remove it.** We choose the key, so a
caller can only write inside their own Event; the signed conditions cap the size; and the file's
own leading bytes decide what it is, at confirmation, because a declared content type is a claim
made by whoever is uploading. A file that fails is deleted rather than left in the bucket at a
key its uploader knows.

**The store has three addresses and they are three different things.** `endpoint` is how this
application reaches it, `upload-base-url` is where a browser POSTs a cover, `public-base-url` is
where a browser fetches one. On a laptop all three are localhost, which is what hides the
distinction until the backend runs in a container - then `endpoint` becomes a name only the
container network resolves, and a form action built from it fails at DNS in the browser for a
reason no server log mentions. Pointing the form elsewhere is safe because a POST policy signs
the policy document and not the host, so the same signature is valid at any address reaching the
same bucket.

**A cover is stored at several sizes, and which ones is recorded rather than assumed.** Only
widths smaller than the upload are produced - enlarging invents detail and charges bandwidth for
it - so the set is a property of the file. Deriving it from a constant would mean every existing
Event advertising files that were never written the day the constant changed.

**The renderings' keys are the one exception to building keys from ids.** A rendering's
extension is not the upload's: the classpath has a WebP *reader* and no writer, so a WebP cover
is decoded and written back as JPEG, and nothing the Event knows says what a rendering became.
Storing the key also makes deletion exact - and deletion is where this leaks, because three
orphaned renderings per cover point at nothing, break nothing, and are invisible until somebody
reads a bill. `Event.coverKeys()` exists so neither caller has to remember they exist.

**The upload ceiling bounds file size, not decode cost, and those differ by two orders of
magnitude.** nfr.md caps a cover at five megabytes; a smooth 12000x8000 JPEG is one and a half.
So a file that passes every check can ask the application to allocate about 380MB, four bytes a
pixel, and a few at once is an outage on a container sized for an application that never does
that. An organizer does not have to mean any harm - a camera produces these. `ImageRenderer`
reads the header, which is free, and declines anything past fifty megapixels. Declining to
render is not declining to serve: no renderings is a state that already existed.

**The JVM reads JPEG and PNG; WebP needs a library and AVIF has no decoder worth having.** An
AVIF cover is therefore served exactly as uploaded, with no smaller sizes. That is deliberate
(ADR-0006): refusing the format would narrow the contract to fit an implementation detail, and
failing the upload would turn an optimisation into an outage.

**Keys are built, not remembered** — `pending/{org}/{event}/{uploadId}` and
`covers/{org}/{event}/{uploadId}.{ext}`. That is why an upload needs no row to confirm it, and
it is also the tenancy: both ids come from a request that has already been checked. The upload
id is pattern-checked before it is concatenated into a path, or `../` would address the rest of
the bucket.

**`mc anonymous set download` grants `ListBucket` as well as `GetObject`.** It is the obvious
command and it makes the bucket enumerable - and the keys carry organization and event ids, so a
listable bucket publishes which organizations exist and how many events each has, drafts and
unlisted ones included. `compose.yaml` sets an explicit policy with the one action serving a
picture needs; the test container does the same.

**A multipart POST must have a known content length.** `HttpRequest.BodyPublishers.ofByteArrays`
reports its length as unknown, so the JDK client sends the form chunked, and MinIO answers a
chunked POST with `EmptyRequestBody` - which reads like a bug in the body you built and is not.
Concatenate and use `ofByteArray`.

## No credential has a default

`application.yml` names every credential with no fallback - `${JWT_SECRET}`, never
`${JWT_SECRET:something}` - so an application started without one refuses to boot. The
development values live in `application-dev.yml`, which is committed on purpose: a published
credential is safe exactly as long as it can only ever be used locally.

The profile is active for `./mvnw spring-boot:run` (configured in the POM) and for the suite
(`src/test/resources/application.properties`), so a clone still runs with no setup. A container
does not get it and must supply the environment instead.

This is the only arrangement where forgetting is safe. A default in the main file is a value a
deployment inherits by never noticing, and `app.tickets.keys` is not a small one to inherit: a
ticket code is a random lookup plus a MAC under that key, so a deployment still holding the
development key issues tickets anybody with this repository can mint - and it looks, from the
outside and from the logs, exactly like a working system. `DeploymentConfigurationTest` reads
the file and fails if a fallback reappears.

Stripe's two keys are the deliberate exception, written `${STRIPE_SECRET_KEY:}`. Absent means
there is no Stripe provider at all rather than one that fails on first use, which is what lets
a fresh clone and the whole suite run with no account. **Mail is the second exception and the
same shape**: `MAIL_HOST` absent means there is no mail server, and the application logs its
mail instead.

## Mail

`EmailTransport` has two implementations and `EmailTransportConfiguration` picks one from
`MAIL_HOST`. That class exists because the alternative failed silently for the whole life of
the project: `LoggingEmailTransport` was an unconditional `@Component` and the only
implementation, its javadoc claimed "AWS SES replaces it in deployed environments", and nothing
ever did. Every deployment wrote its mail to a log and marked every row `SENT` - so nobody could
verify an address, nobody could buy a ticket, and the database said delivery had succeeded.

**One `@Bean` with an `if`, not two conditional components.** `@ConditionalOnMissingBean` on a
scanned `@Component` resolves by whatever order beans happen to be defined in, which is exactly
how a fallback beats the real thing. One bean and one decision has no ordering to get wrong.

**Read `spring.mail.host` as a property, not off `MailProperties`.** That class lives in Boot's
auto-configuration and moved from `org.springframework.boot.autoconfigure.mail` to
`org.springframework.boot.mail.autoconfigure` in Boot 4 - it broke on the first compile. The
property name is the published contract; the class holding it is not.

**An empty variable is an absent one.** Every mail property is written `${MAIL_HOST:}`, so a
compose file that declares `MAIL_HOST` with nothing in the environment hands the application an
empty string rather than nothing. `StringUtils.hasText`, never a null check.

**Mail is not a liveness condition, and `management.health.mail.enabled` is `false`.** The
starter also brings a health indicator that opens an SMTP connection. Combined with the empty
variable above it is a trap: a deployment declaring `MAIL_HOST` with nothing in the environment
gets a sender aimed at `localhost:587` and reports the whole application DOWN, so
`depends_on: service_healthy` never starts the frontend - a deployment refusing to boot over a
variable meaning "we do not send mail". It is wrong configured, too: the outbox has already
committed the row and retries five times, so a provider blip is not an outage and should not ask
an orchestrator to restart a container selling tickets fine. `email_delivery` records every
attempt and its error, which answers "did this person hear from us" better than a probe can.

The outbox was already right and is untouched: `OutboxEmailSender` writes the row inside the
caller's transaction and attempts delivery after it commits, and `DispatchPendingEmails` retries
five times before leaving the row `FAILED`. A transport is one attempt, and its whole contract is
to throw when the attempt did not happen.

## Logging

Every use case that changes state logs one line at INFO; every refusal that a human might
have to explain logs at WARN. `LogContextFilter` puts a request id into the MDC and returns
it as `X-Request-Id`; `JwtTenantFilter` adds the user and organization once authenticated, so
messages carry identifiers without repeating them.

Logs and the audit trail answer different questions and both are needed. The `audit_entry`
table is durable business record — who removed an owner, KB invariant 23. Logs are operations:
why a request failed at 2am, and everything about authentication, which the audit table does
not touch at all. **Failed sign-ins log the attempted address** — personal data, logged
deliberately, because without it credential stuffing against one account is indistinguishable
from noise.

**A mistake in a request is not a server error.** A wrong method, an unparseable body, a
content type nothing reads, an id that is not a UUID - all four used to answer 500 with "The
request could not be completed" and log an ERROR with a stack trace. The first cost is a caller
told the server broke when it understood perfectly; the second is worse, because a client
looping on a wrong URL then buries every real defect in the log. `ApiExceptionHandler` takes
the status Spring already worked out, through the `ErrorResponse` interface rather than a list
of exception classes - except `HttpMessageNotReadableException` and
`MethodArgumentTypeMismatchException`, which do not implement it and have to be named. None of
them carry a new error code: a client branches on the code for domain outcomes, and a request
that never reached a use case has no outcome.

**A bad request body arrives as one of two exceptions, and only one of them was mapped.** An
object body fails as `MethodArgumentNotValidException`, from the body resolver. A body that is a
top-level array fails as `ConstraintViolationException`, from `MethodValidationInterceptor` -
`@Valid @RequestBody List<@Valid T>` becomes an AOP check around the controller method rather
than work the resolver does. Nothing mapped the second, so a price of -1 answered 500 and "the
request could not be completed" and wrote an ERROR with a stack trace, while the identical
mistake in an object body answered 400 and named the field.

Worth knowing because it reads as a missing feature and is not: `Money.amount` has carried
`minimum: 0` since the beginning and validation was running the whole time. Only the answer was
wrong. The first fix attempted here was a hand-written validator for array bodies, built on the
assumption that the elements were never checked - the stack trace said otherwise, and the real
change was eight lines in the handler.

Its property path is `method.parameter[0].price.amount`. Trim to `[0].price.amount`: the method
and parameter names are generated and mean nothing to a caller, and the index is the part that
says which row of the table to look at.

Name a new servlet filter for what it does, not `RequestContextFilter`: Spring Boot's WebMvc
auto-configuration registers a bean of that name and a second one stops the app booting.

## Testing

Integration tests run against real Postgres via Testcontainers. `ApiTest` drives the app over
HTTP; extend it.

**A tenancy test that goes through a tenant-filtered repository method is vacuous.**
`ListMembers` filters by organization in its own query, so tests through it pass with RLS
switched off entirely — three did. Prove isolation by counting the table with **no**
predicate:

```java
long visible = countAllMembershipsAs(aliceId, acme.getId());   // 2, not 3
```

The same check applies to any test asserting a guarantee the database is supposed to make:
plant the violation, watch it fail, remove it. `ModularityTest` was verified this way too.

**Never assert a re-read instant equals an in-memory one.** `OffsetDateTime.now()` carries
nanoseconds and Postgres `timestamptz` keeps microseconds, so anything that has been through the
database has been truncated. The trap is that it half-works: a response built from the entity
still in the persistence context - a PATCH result, say - keeps the nanoseconds, so the same
assertion passes there and fails on a GET that re-reads. It also passes on a machine whose clock
happens to land on a round value, which is how one of these passed locally and failed in CI on
the last three digits. Compare against a value the API has already returned.

The three things worth testing hard, per the KB's NFRs: seat-hold concurrency, redemption
atomicity across simultaneous scanners, and webhook idempotency. None of them fail under
mocked repositories.

## Mixing JPA and JdbcTemplate

They share the transaction and not the persistence context. `save()` only queues an INSERT, so
a raw SQL statement in the same method cannot see the row — `BeginCheckout` needs
`saveAndFlush` before `hold_seats`, or the hold's foreign key fails against an order the
database has not been told about yet.

**`@Modifying(clearAutomatically = true)` without `flushAutomatically = true` throws away
uncommitted work.** Clearing the persistence context discards pending changes rather than
writing them, and `flushAutomatically` defaults to false - so a use case that changes an entity
and then runs a bulk update loses the change and commits a transaction that did half its job.

`ResetPassword` is exactly that shape: set the password, mark the address verified, then revoke
every refresh token. The revoke cleared the context and both mutations vanished. It surfaced as
three failing tests saying the new password did not work and the link could be used twice,
which reads like a bug in the reset and is a bug in the annotation. Two of the four
`@Modifying` queries here already paired the flags; `revokeAllFor` did not, and had been safe
only because its other caller - `Logout` - changes nothing first.

Pair the two flags every time. The cost of the flush is nothing next to the cost of finding
this.

## Queries

**An aggregate over a join counts the join, not the thing.** `countsFor` reads seats and money
for a page of Events in one query, and it joins `ticket_order` to `order_seat` - so an Order
appears once per seat, and `sum(o.total_amount)` over that join multiplies every Order by how
many seats it has. Two seats and three seats at 250,000 reports 3,250,000 instead of 1,250,000:
a plausible number, wrong, in the field an organizer checks first. Aggregate the Orders in a CTE
first, then sum. The test buys multi-seat Orders for exactly this reason - single-seat Orders
would pass either version.

**Postgres cannot infer the type of a bare parameter in `? is null`.** A query written the
obvious way for an optional filter —

```sql
and (:startsAfter is null or e.startsAt >= :startsAfter)
```

— fails at execution with `could not determine data type of parameter $4`, and only for the
call that leaves it null. Express an absent filter as its widest value instead of as null: the
edge of time for a bound or a cursor (`PageCursor.FIRST_ASCENDING`), every member of the enum
for a status. No casts, no null tests, and the plan is better.

**A text filter is `%` when absent, not a null test.** The same idiom as the date bounds, one
step further: an absent search matches everything, so there is no branch and no second query
shape to keep correct. The wildcards in what the visitor typed are escaped first - they are the
pattern's syntax, not theirs, and a search for "50%" that returned the whole listing reads as a
broken filter rather than as a character having meant something.

**Fold both sides of a comparison with the same function, in the database.** Searching is
accent-insensitive because this market writes with diacritics and types without them.
`lower(unaccent(title)) like lower(unaccent(:pattern))` - doing either side in Java instead
splits the folding across two implementations that disagree: Java's normalizer strips combining
marks and leaves `Đ` alone, Postgres `unaccent` folds it to `D`. The first version of this
unaccented only the column, so typing a title exactly as written found nothing.

`unaccent` is a trusted extension from Postgres 13, so a plain database owner can create it and
the deployed application needs no superuser (verified against a `NOSUPERUSER` role, because the
connection user is a superuser in development and test and would have proved nothing).

Listings page by keyset, never by offset — an offset shifts under inserts, so a buyer scrolling
while someone publishes sees rows twice or not at all. Fetch `limit + 1` to learn whether
another page exists without counting the table.

## Spring Boot 4 differences that cost time

- `TestRestTemplate` is gone. Use `RestTemplate` with `JdkClientHttpRequestFactory` — the
  default `HttpURLConnection` client cannot issue `PATCH`, which the contract uses.
- `LocalServerPort` is `org.springframework.boot.test.web.server.LocalServerPort`.
- Starters are per-feature: `spring-boot-starter-webmvc`, and matching `*-test` artifacts.
- 422 is `HttpStatus.UNPROCESSABLE_CONTENT` now; `UNPROCESSABLE_ENTITY` is gone.
- `DefaultUriBuilderFactory` encodes the whole template, so a query value encoded by hand is
  encoded twice and matches nothing. Pass URI variables (`?city={city}`) and let it expand them.
- **Jackson 3.** `tools.jackson.databind`, not `com.fasterxml.jackson.databind`; `JsonNode.asText()`
  is `asString()`; its exceptions are unchecked.
- `ContentCachingRequestWrapper` has no single-argument constructor any more — a cache limit is
  required.

Other traps, both hit in this codebase:

- **Spring Data does not scan repository interfaces nested inside entity classes.** Top-level
  interfaces only.
- **`JwtClaimsSet.Builder.claim` rejects null.** Omit a claim rather than setting it null; an
  absent `org` claim is how "signed in, no organization chosen" is represented.
- **A JSONB column maps with `@JdbcTypeCode(SqlTypes.JSON)`** over a record or a `List` of
  them; Hibernate handles it through Jackson and `ddl-auto: validate` accepts it.

## Webhooks and ticket codes

Verify the **raw bytes**. A body parsed and re-serialised has a different signature — key order,
whitespace, number formatting — and the mismatch appears only against a real provider, never
against a test that round-trips through the same library. `WebhookBodyFilter` keeps the bytes;
the parsed argument the generated interface takes is ignored.

Almost every webhook outcome is a 204. An unknown or already-settled session is acknowledged
and ignored (requirements/005 criterion 6), because a provider that gets an error retries, and
answering "I could not use this" with a failure turns one stale delivery into a loop. Only a bad
signature is refused, with 401.

**A Ticket Code is never logged, and never stored.** What is stored is the random lookup; the
code is that plus a MAC under a key from the environment, so a leaked database is not a set of
working tickets (nfr.md). Log the count, not the codes — a code arrives in a scan request body,
which makes the request log the easiest place to leak every code presented at a door.

## What a second payment provider changed

Stripe is the first provider whose flow differs from the fake's, and ADR-0002 predicted that
adding one would find things. It found two, both in code that looked finished:

**`verify` answers `Optional`, because most deliveries are about nothing.** A single Stripe
payment produces a dozen events and two of them matter. The port could only say "here is a
confirmation", so every other event had to be dressed up as a confirmation of nothing - and
arrived at `ConfirmPayment` indistinguishable from a delivery for a session nobody has, which
is a genuine warning. Ten warnings per sale bury the one worth reading. Empty now means
authentic and not actionable; the fake never returns it, which is why nothing noticed.

**Stripe refunds a PaymentIntent, and a Payment Session holds a Checkout Session.** So
`refund` fetches the session to find the charge. That extra call is the price of the port being
right: a provider's reference is whatever it handed back at the start, and requiring every
provider to return the same *kind* of handle would put Stripe's internals into a port that also
has to fit a bank transfer.

**Amounts are passed through untouched.** Stripe takes the currency's smallest unit and VND has
no minor unit, so the dong is that unit - confirmed against the live API, which echoed
`amount_total: 500000` for two 250,000 seats. A multiplication here would charge a hundred
times the price and look plausible in every log.

**A provider that answers is not a provider that is down.** Every `StripeException` became "The
payment provider could not be reached. Try again in a moment." Stripe had usually been reached and
had refused - `amount_too_small` for a price below its floor - and would refuse again every time,
so a buyer was invited to keep pressing a button that could never work while the organizer whose
pricing caused it heard nothing. `isTransient` splits on the only question an error message has to
answer: could the same request succeed later. A connection that never landed, a rate limit, and
Stripe's own 5xx are worth retrying; an invalid request, a decline, a bad key are answers, and
answers do not change by being asked again.

Stripe's own words are logged and not returned. "Must convert to at least 50 cents" is about an
account's presentment currency and is written for whoever wrote this code, and the error envelope
is the one place a request's own content should not be echoed back.

**A provider's floor is the reason a price has one.** Stripe refuses below roughly 12.500 ₫ today,
so a tier at 30 ₫ is not cheap, it is unsellable - and the smallest possible Order is one seat, so
the first person who wants exactly one meets it. `app.pricing.minimum-amount` refuses it in
`SetPricingTiers`, where the organizer is setting the number, rather than at checkout where a buyer
would discover it. It is configuration and not a constant because the provider's floor is
denominated in dollars: a limit tracking a foreign currency will eventually cross one that does not.
Zero is not small, it is free, and a provider asked for nothing does not refuse - checked against
the real account rather than assumed.

**The Stripe bean is conditional on its key.** No key, no provider, and `StartPayment` says
there is no provider by that name - which is true. The suite and a fresh clone need no account
and no network.

## A settled refund is not a finished one

requirements/008 criterion 11, learned the expensive way. Stripe answers a refund that will
fail with `refund.created`, `refund.updated` and `charge.refund.updated` all saying
**succeeded**, and only then `refund.failed`. We settled on the first, told the buyer their
money was back, and put the seat on sale; Stripe's own record said `failed`.

Two lines let it through, and both looked correct:

- `refund.failed` was not in the handled event types. The event named after the outcome was
  the one not being read.
- `ConfirmRefund` returned `ALREADY_SETTLED` for anything arriving after a settlement, which
  is right for a re-delivery and wrong for a reversal. **A failure for a refund that succeeded
  is not a duplicate.**

The reversal sets `refund_required` rather than a new flag - it already means "money taken for
seats that were not delivered", which is exactly the state - and `requireRefundable` now admits
a REFUNDED Order that carries it, because a flag nobody may act on is the failure criterion 10
describes. The Order's status stays REFUNDED: a refund *was* attempted and *was* reported
settled, and rewriting that to PAID erases the only clue to why a buyer is holding an email
saying they were refunded.

Seats are left on sale. They were released when the refund settled and may have been sold
since; taking one back from a second buyer to fix the first one's money is the wrong trade.

## Money moves on a callback, both ways

Neither half of the money can be completed from a browser, and that is the design rather than a
gap: an Order becomes paid only on a provider confirmation (requirements/005 criterion 3) and a
refund settles only on one (requirements/008 criterion 2). So on a laptop both leave somebody
waiting for a webhook that nobody is going to send.

`scripts/confirm-payment.sh` and `scripts/confirm-refund.sh` are what send it. They post a real
signed webhook to the real endpoint rather than reaching past the API to set a status, because a
shortcut that did would stop exercising the parts most likely to be wrong - the signature over
raw bytes, idempotency, and the tenant a webhook has to adopt before it can touch a row.

```bash
scripts/confirm-payment.sh <order-id>                    # and --fail, --twice
scripts/confirm-refund.sh  <order-id>                    # and --fail "why"
scripts/confirm-refund.sh  --event <event-id>            # settle a whole cancellation
```

A refund refused before any provider was asked has no `provider_ref`, so the scripts skip it -
that is what makes it a refusal rather than a failure, and there is nothing to confirm.

## The door

Redemption is one conditional `UPDATE` whose row count is the answer - 1 admits, 0 means someone
else got there first. The loser blocks on the row lock, re-evaluates, and reads back *when* and
*which device*, which is the difference between telling somebody they have already been in and
telling them their ticket was used by someone else.

**There is no override** (requirements/007 criterion 7), and that is a property of the shape
rather than a missing permission: the only way in is to win that update, and it is conditional
on the Ticket being valid.

A refusal is a **200 with a reason**, not an HTTP error. A client renders an error as "something
went wrong", which is the one message that helps nobody at a gate with a queue. Only two things
are errors: 403 for a caller who may not scan, 429 for a device over its limit.

Rate limiting is in memory and per instance, which is a reading of nfr.md - one application
instance, one Postgres, no high availability - not a shortcut. Keep the limit generous: a
scanner reads continuously, so a camera resting on one code fires the same scan many times a
second, and a door that fails because somebody held their phone still is worse than the abuse
it prevents.

## Bulk refactors

Rewriting imports by simple class name collides with the generated contract types: `Role`,
`Membership`, `Organization` and `OrganizationStatus` all exist in both
`com.eventticket.api.model` and the domain. Skip any file that already binds that name, and
qualify the domain side where both are genuinely in scope.
