# The application layer is one class per use case, not a service per feature

Code is organised package-by-feature, and within a feature each thing a user can do is its
own class named after the action — `PublishEvent`, `CreateSeatHold`, `ScanTicket` — with a
method named for the domain verb. There is no `EventService`. We did this because a service
class per feature accumulates every operation that touches the feature until no one can see
what a single operation does, and this system's correctness lives in individual operations:
publishing freezes a seat map, holding a seat resolves a race, scanning redeems exactly once.

This is not Clean Architecture. There are no ports, adapters, or domain/application/
infrastructure layers. The only claim is that a use case is a class.

## Consequences

**Every endpoint gets a use-case class, reads included.** Controllers never call a repository
directly. An earlier draft exempted trivial reads, which required judging whether a read "has
logic" — a judgment made differently by different people on different days. A rule with no
exceptions is worth more than the dozen thin classes it costs, and a thin read class stops
being thin the first time it grows a filter.

**The use case is the transaction boundary.** One class, one transaction, one invariant to
uphold, all visible at once. Reads are `@Transactional(readOnly = true)`.

**Shared logic goes down, never sideways.** Into the domain model (`event.canPublish()`) or
the repository (`findOrThrow`) — never into a helper shared between use cases, which is how
the god service returns under another name. The tenant check specifically is not application
code at all: row-level security owns it (knowledge base ADR-0004).

**Use cases do not call other use cases.** That nests transactions and makes the boundary a
lie. Shared behaviour is extracted downward; genuinely asynchronous work goes through an
event. Bulk refund on event cancellation is the proving case — the knowledge base requires
per-order status because it will partially fail, so it cannot be one transaction.

**Generated API types stay at the web layer.** Use cases take and return domain types; the
controller maps. Without this rule the generated DTOs quietly become the domain model, which
inverts the point of a hand-written contract.

## Considered Options

A conventional layered structure (`controller/`, `service/`, `repository/`) is what most
Spring codebases look like and what most readers expect. It was rejected because it scatters
one feature across four packages and puts unrelated operations in one class.
