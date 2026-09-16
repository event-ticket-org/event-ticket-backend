package com.eventticket.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * A foreign key is a field, never a mapped association.
 *
 * <p>Every entity here holds its references as the key itself - {@code organizationId},
 * {@code venueId}, {@code eventId}, {@code buyerUserId}, {@code categorySlug},
 * {@code citySlug} - and whoever needs the row behind one asks a repository for it. There is
 * no {@code @ManyToOne} anywhere, and this test is what keeps that true.
 *
 * <p>The convention had been followed everywhere and never written down, which is how two
 * associations were added without anyone noticing there was a rule. They were removed; this
 * exists so the next one fails a build instead of a review.
 *
 * <p>What the rule buys, in this codebase specifically:
 *
 * <ul>
 *   <li><strong>Every read is visible at its call site.</strong> {@code ListPublicEvents} reads
 *       the Category and City vocabularies once for a whole page. A mapped association would
 *       have fetched them per row, and the query nobody wrote is the one nobody profiles.</li>
 *   <li><strong>Nothing depends on being inside a transaction to be readable.</strong> Mapping
 *       to a DTO happens in the controller, outside the use case's transaction, so a lazy
 *       association fails there and an eager one is a join on every read that never needed it.
 *       A plain field has neither failure mode.</li>
 *   <li><strong>Module boundaries stay where Modulith can see them.</strong> An association
 *       from {@code event} to a {@code venue} entity is a compile-time edge between two
 *       modules' domain types; a UUID is not.</li>
 * </ul>
 *
 * <p>Planted before being trusted, the way {@code ModularityTest} was: a {@code @ManyToOne}
 * added back to {@code Venue} fails this test naming that field, and removing it passes.
 */
class NoOrmAssociationsTest {

    private static final List<Class<? extends Annotation>> FORBIDDEN =
            List.of(ManyToOne.class, OneToMany.class, OneToOne.class, ManyToMany.class,
                    JoinColumn.class, JoinTable.class, ElementCollection.class);

    @Test
    @DisplayName("no entity maps a relationship - a foreign key is a field")
    void noEntityMapsARelationship() throws Exception {
        List<String> offences = new ArrayList<>();

        for (Class<?> entity : entities()) {
            for (Field field : entity.getDeclaredFields()) {
                for (Class<? extends Annotation> forbidden : FORBIDDEN) {
                    if (field.isAnnotationPresent(forbidden)) {
                        offences.add("%s.%s is annotated @%s".formatted(
                                entity.getSimpleName(), field.getName(),
                                forbidden.getSimpleName()));
                    }
                }
            }
        }

        assertThat(offences)
                .describedAs("A foreign key is a field, and the row behind it is a repository "
                        + "call. See the javadoc on this test for why, and CLAUDE.md for the "
                        + "shape to use instead.")
                .isEmpty();
    }

    /** Proves the scan reaches something, so an empty result above is a pass and not a no-op. */
    @Test
    @DisplayName("the scan finds the entities it is supposed to be checking")
    void theScanFindsEntities() throws Exception {
        assertThat(entities())
                .describedAs("if this is empty, the test above passes by finding nothing")
                .hasSizeGreaterThan(10)
                .anyMatch(type -> type.getSimpleName().equals("Event"))
                .anyMatch(type -> type.getSimpleName().equals("Venue"));
    }

    private static List<Class<?>> entities() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        List<Class<?>> found = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("com.eventticket")) {
            found.add(ClassUtils.forName(candidate.getBeanClassName(),
                    NoOrmAssociationsTest.class.getClassLoader()));
        }
        return found;
    }
}
