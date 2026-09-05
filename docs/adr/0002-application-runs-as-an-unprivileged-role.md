# The application drops to an unprivileged role for every transaction

`TenantAwareTransactionManager` issues `SET LOCAL ROLE eventticket_app` at the start of every
transaction, before publishing the tenant. The role is created in `V3__runtime_role.sql` as
`NOSUPERUSER NOBYPASSRLS` and holds only DML grants.

We did this because row-level security has one absolute exemption: **a superuser bypasses
every policy, and `FORCE ROW LEVEL SECURITY` does not change that.** The connection user owns
the schema and runs migrations, and in development and test it is a superuser — so the
policies from `V2` were enforcing nothing at all, silently.

## How we found it

The first three isolation tests passed while the isolation was entirely fake. Each went
through `ListMembers`, which also filters by organization in its own query, so they would
have passed with row-level security switched off — they were asserting the application's
`WHERE` clause, not the database's policy.

The test that caught it counts the whole table with no predicate:

```java
long visible = countAllMembershipsAs(aliceId, acme.getId());   // expected 2, got 3
```

Keep that test. A tenancy test that goes through a repository method which already filters by
tenant proves nothing, and reads as though it proves everything.

## Consequences

Migrations keep the owner's rights, because Flyway runs outside this transaction manager.
Application queries do not: anything the runtime role has not been granted fails at runtime
rather than at review. `ALTER DEFAULT PRIVILEGES` covers tables added later, so a new domain
table is reachable without anyone remembering to grant it — but a new *sequence style*,
extension, or function may still need an explicit grant.

Deployed environments should go further and give the application a login role that is not a
superuser and not the schema owner, with migrations run separately. `SET LOCAL ROLE` then
becomes belt and braces rather than the only thing standing between tenants.
