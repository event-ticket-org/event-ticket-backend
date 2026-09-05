package com.eventticket;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Enforces the module boundaries declared in each module's {@code package-info.java}.
 *
 * <p>Splitting each feature into sub-packages cost us Java's package-private enforcement:
 * everything crossing a sub-package had to become public. This puts enforcement back one
 * level up. The modules are open, so a module's sub-packages are importable, but
 * {@code allowedDependencies} says which module may reach which - and a dependency nobody
 * declared fails the build instead of review.
 */
class ModularityTest {

    /**
     * The generated contract types are excluded. They live under {@code com.eventticket.api}
     * because that is where openapi-generator puts them, not because they are a feature: they
     * are shared vocabulary every module's web layer speaks, and treating them as a module
     * would report every controller as a violation.
     */
    static final ApplicationModules MODULES = ApplicationModules.of(
            BackendApplication.class,
            new DescribedPredicate<>("generated API contract types") {
                @Override
                public boolean test(JavaClass type) {
                    return type.getPackageName().startsWith("com.eventticket.api");
                }
            });

    @Test
    void modulesRespectTheirDeclaredDependencies() {
        MODULES.verify();
    }
}
