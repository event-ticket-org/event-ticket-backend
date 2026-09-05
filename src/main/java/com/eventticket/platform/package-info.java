/**
 * Platform administration: Organization approval.
 *
 * <p>One class per use case, named after what the user does. No PlatformService.
 * See {@code docs/adr/0001-use-case-classes-not-services.md}.
 *
 * <p>Acts on organizations from outside the tenant model, so it depends on organization.
 * Nothing depends on platform.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        allowedDependencies = {"shared", "organization"})
package com.eventticket.platform;
