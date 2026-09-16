/**
 * Platform administration: Organization approval.
 *
 * <p>One class per use case, named after what the user does. No PlatformService.
 * See {@code docs/adr/0001-use-case-classes-not-services.md}.
 *
 * <p>Acts on organizations from outside the tenant model, so it depends on organization.
 * Nothing depends on platform.
 *
 * <p>It depends on event as well, for the curated row (requirements/009 criterion 14). The
 * arrow runs this way and not the other because of where the guard is: curation is refused by
 * {@code PlatformAdmins.requireCallerIsPlatformAdmin}, which lives here, and moving the use
 * case into event would mean event depending on platform while platform depends on event.
 *
 * <p>It is also the arrangement CLAUDE.md describes for a generated interface that spans two
 * modules: {@code PlatformAdminApi} covers Organization approval and Event curation, so it is
 * implemented in the module that may see both.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        allowedDependencies = {"shared", "organization", "event"})
package com.eventticket.platform;
