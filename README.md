# event-ticket-backend

Spring Boot backend for the event ticketing platform.

**The knowledge base is normative.** Requirements, domain model, invariants and the API
contract live in [event-ticket-kb](https://github.com/event-ticket-org/event-ticket-kb).
Read `design/domain-model.md` there before changing anything with rules attached — the 23
invariants are what this code exists to uphold. The contract vendored at
`contracts/openapi.yaml` is pinned to a merged KB revision recorded in `contracts/KB_REVISION`
and must never be edited here: change it in the KB, merge it, then re-pin.

## Stack

Java 21 · Spring Boot 4.1.1 · PostgreSQL · Flyway · Testcontainers · Maven

## Running

```bash
docker compose up -d          # Postgres on 5432
./mvnw spring-boot:run
```

## How the code is organised

Package-by-feature, and **one class per use case** — `PublishEvent`, `CreateSeatHold`,
`ScanTicket`. There is no `EventService`. Controllers never touch repositories. See
[ADR-0001](docs/adr/0001-use-case-classes-not-services.md); it is short and it is the thing
to read before adding a class.

Each feature is subdivided the same way, so the same file is in the same place in every
feature:

```
com.eventticket
├── identity/       accounts, sessions, tokens
│   ├── domain/         AppUser, RefreshTokenRecord, Session
│   ├── repository/     Spring Data interfaces
│   ├── security/       JWT issuing, filter chain, token hashing
│   ├── support/        feature-local adapters
│   ├── usecase/        RegisterUser, VerifyEmail, Login, RefreshSession…
│   └── web/            AuthController
├── organization/   Organizations, Memberships, Roles
│   └── domain/ repository/ usecase/ web/
├── platform/       Organization approval
│   └── support/ usecase/ web/
├── shared/
│   ├── audit/          AuditTrail, AuditEntry
│   ├── email/          EmailSender and implementations
│   ├── error/          ApiException, the error envelope, error codes
│   └── tenancy/        TenantContext, TenantAwareTransactionManager
└── venue/ event/ checkout/ payment/ ticket/ admission/     (to come)
```

Boundaries between modules are declared in each feature's root `package-info.java` and
enforced by Spring Modulith in `ModularityTest` — an undeclared dependency fails the build,
not review.

`usecase/` is where the work is. `domain/` holds entities and the rules that belong on them;
`support/` is for feature-local infrastructure and stays small or empty.

## The API contract compiles into the build

`openapi-generator` turns `contracts/openapi.yaml` into Spring interfaces and models under
`target/generated-sources`. Controllers implement those interfaces, so if the contract and
the server disagree, the build fails. Generated types are web-layer only and are mapped to
domain types at the controller — never passed into a use case.

## Schema and tenancy

Flyway owns the schema; Hibernate is set to `validate` and will never create a table. Every
domain table carries `organization_id` with row-level security enabled against it — the
tenant boundary is enforced by Postgres, not by remembering a `WHERE` clause.

Two details make that real rather than decorative, and both are easy to undo by accident:

- **`FORCE ROW LEVEL SECURITY`** on each table, or the schema owner bypasses every policy.
- **`SET LOCAL ROLE eventticket_app`** at the start of every transaction, because a superuser
  bypasses policies even with `FORCE`. See
  [ADR-0002](docs/adr/0002-application-runs-as-an-unprivileged-role.md).

When adding a tenant-scoped table: give it `organization_id`, enable **and** force row-level
security, and add a policy. Then write a test that counts the table with no `WHERE` clause —
a test that goes through a repository method which already filters by tenant proves nothing.

## Tests

Integration tests run against real Postgres via Testcontainers. The three things worth
testing hard are seat-hold concurrency, redemption atomicity across simultaneous scanners,
and webhook idempotency; none of them fail under mocked repositories.

```bash
./mvnw test
```
