/**
 * Accounts, authentication and sessions.
 *
 * <p>One identity for everyone: buying tickets and working for an Organization are things a
 * User does, not different kinds of account. Roles live on Memberships in the organization
 * package, not here.
 *
 * <p>One class per use case, named after what the user does. There is no AuthService. See
 * {@code docs/adr/0001-use-case-classes-not-services.md}.
 *
 * <p>May depend on organization, because a session's active Organization has to be checked
 * against live Membership. Nothing may depend on identity except through shared's
 * UserDirectory.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        allowedDependencies = {"shared", "organization"})
package com.eventticket.identity;
