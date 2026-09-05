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

`shared/` is subdivided by capability instead: `audit/`, `email/`, `error/`, `tenancy/`. The
bar for adding to `shared` is that **every** feature needs it.

A use case is a class named after the action — `PublishEvent`, `CreateSeatHold`, `ScanTicket`
— with a method named for the domain verb. Every endpoint gets one, reads included;
controllers call use cases, never repositories. The use case is the transaction boundary, and
reads are `@Transactional(readOnly = true)`. Shared logic goes **down** into the domain or the
repository, never sideways into a helper between use cases. Full reasoning in
[ADR-0001](docs/adr/0001-use-case-classes-not-services.md).

Generated API types stay in `web/`. Use cases take and return domain types and the controller
maps between them, so the published contract never becomes the domain model.

## Module boundaries

Declared in each feature's root `package-info.java` with
`@ApplicationModule(type = OPEN, allowedDependencies = {…})`, and enforced by
`ModularityTest`. `OPEN` makes sub-packages importable; `allowedDependencies` is the real
constraint. `shared` depends on nothing. `organization` depends on `shared` only and reaches
people through `shared`'s `UserDirectory`, so it and `identity` can never become mutually
dependent.

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

A new tenant-scoped table needs `organization_id`, RLS enabled **and** forced, and a policy.

**Unauthenticated flows cannot read tenant-scoped tables.** At login nobody is authenticated
yet, so `current_app_user_id()` is null and the membership policy matches nothing — which is
correct. `TenantPublisher.adopt` is the deliberate exception, for a use case that establishes
identity as part of its own work (token refresh) or acts on an organization from outside it
(platform approval). Anywhere else, the tenant should have come from the access token.

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

## Spring Boot 4 differences that cost time

- `TestRestTemplate` is gone. Use `RestTemplate` with `JdkClientHttpRequestFactory` — the
  default `HttpURLConnection` client cannot issue `PATCH`, which the contract uses.
- `LocalServerPort` is `org.springframework.boot.test.web.server.LocalServerPort`.
- Starters are per-feature: `spring-boot-starter-webmvc`, and matching `*-test` artifacts.

Other traps, both hit in this codebase:

- **Spring Data does not scan repository interfaces nested inside entity classes.** Top-level
  interfaces only.
- **`JwtClaimsSet.Builder.claim` rejects null.** Omit a claim rather than setting it null; an
  absent `org` claim is how "signed in, no organization chosen" is represented.

## Bulk refactors

Rewriting imports by simple class name collides with the generated contract types: `Role`,
`Membership`, `Organization` and `OrganizationStatus` all exist in both
`com.eventticket.api.model` and the domain. Skip any file that already binds that name, and
qualify the domain side where both are genuinely in scope.
