/**
 * Accounts, authentication and sessions.
 *
 * <p>One identity for everyone: buying tickets and working for an Organization are things a
 * User does, not different kinds of account. Roles live on Memberships in the organization
 * package, not here.
 *
 * <p>One class per use case, named after what the user does. There is no AuthService. See
 * {@code docs/adr/0001-use-case-classes-not-services.md}.
 */
package com.eventticket.identity;
