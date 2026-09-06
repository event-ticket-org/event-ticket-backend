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

The three things worth testing hard, per the KB's NFRs: seat-hold concurrency, redemption
atomicity across simultaneous scanners, and webhook idempotency. None of them fail under
mocked repositories.

## Mixing JPA and JdbcTemplate

They share the transaction and not the persistence context. `save()` only queues an INSERT, so
a raw SQL statement in the same method cannot see the row — `BeginCheckout` needs
`saveAndFlush` before `hold_seats`, or the hold's foreign key fails against an order the
database has not been told about yet.

## Queries

**Postgres cannot infer the type of a bare parameter in `? is null`.** A query written the
obvious way for an optional filter —

```sql
and (:startsAfter is null or e.startsAt >= :startsAfter)
```

— fails at execution with `could not determine data type of parameter $4`, and only for the
call that leaves it null. Express an absent filter as its widest value instead of as null: the
edge of time for a bound or a cursor (`PageCursor.FIRST_ASCENDING`), every member of the enum
for a status. No casts, no null tests, and the plan is better.

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

**The Stripe bean is conditional on its key.** No key, no provider, and `StartPayment` says
there is no provider by that name - which is true. The suite and a fresh clone need no account
and no network.

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
