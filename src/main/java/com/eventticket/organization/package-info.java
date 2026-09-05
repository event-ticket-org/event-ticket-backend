/**
 * Organizations, Memberships and Roles. The tenant boundary.
 *
 * <p>One class per use case, named after what the user does. No OrganizationService.
 * See {@code docs/adr/0001-use-case-classes-not-services.md}.
 *
 * <p>Depends on shared and nothing else. In particular not on identity: it asks about people
 * through shared's UserDirectory, so the two can never become mutually dependent.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        allowedDependencies = {"shared"})
package com.eventticket.organization;
