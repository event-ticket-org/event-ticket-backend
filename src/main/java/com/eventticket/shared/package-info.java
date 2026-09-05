/**
 * Cross-feature primitives only: Money, tenant context, error mapping.
 *
 * <p>One class per use case, named after what the user does. No SharedService.
 * See {@code docs/adr/0001-use-case-classes-not-services.md}.
 *
 * <p>Open, so its sub-packages are importable from any module: every feature legitimately
 * uses tenancy, the error envelope, audit and email. It is allowed to depend on nothing -
 * the moment shared needs a feature, it is not shared.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        allowedDependencies = {})
package com.eventticket.shared;
