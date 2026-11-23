// architecture: hexagonal
// Règles ArchUnit propres au cache Redis, ajoutées en complément d'ArchitectureTest (qui n'est pas modifié).
package com.example.library;

import com.example.library.catalog.application.port.out.AffectsAvailableBooks;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.example.library", importOptions = ImportOption.DoNotIncludeTests.class)
class CacheArchitectureTest {

    @ArchTest
    static final ArchRule domain_and_application_know_no_cache_technology = noClasses()
            .that().resideInAnyPackage("..domain..", "..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.cache..", "org.springframework.data.redis..", "io.lettuce..")
            .because("le domaine et l'application ignorent le cache (RC-03, RC-04)");

    @ArchTest
    static final ArchRule redis_classes_only_in_cache_adapter_and_config = noClasses()
            .that().resideOutsideOfPackages("..adapter.out.cache..", "..config..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.data.redis..", "io.lettuce..")
            .because("toute classe Redis est dans adapter.out.cache ou dans config (RC-04)");

    @ArchTest
    static final ArchRule no_spring_cache_annotations = noClasses()
            .should().beAnnotatedWith("org.springframework.cache.annotation.Cacheable")
            .orShould().beAnnotatedWith("org.springframework.cache.annotation.CacheEvict")
            .orShould().beAnnotatedWith("org.springframework.cache.annotation.CachePut")
            .orShould().beAnnotatedWith("org.springframework.cache.annotation.Caching")
            .because("le cache est un décorateur d'adapter, jamais une annotation Spring Cache (RC-03)");

    @ArchTest
    static final ArchRule no_method_with_spring_cache_annotations = com.tngtech.archunit.lang.syntax.ArchRuleDefinition
            .noMethods()
            .should().beAnnotatedWith("org.springframework.cache.annotation.Cacheable")
            .orShould().beAnnotatedWith("org.springframework.cache.annotation.CacheEvict")
            .orShould().beAnnotatedWith("org.springframework.cache.annotation.CachePut")
            .orShould().beAnnotatedWith("org.springframework.cache.annotation.Caching")
            .because("aucune méthode de use case, de domaine ou de port ne porte d'annotation de cache (RC-03)");

    /** RM-CAT-42 : un port d'écriture qui modifie les livres disponibles est décoré par le cache. */
    @ArchTest
    static final ArchRule affects_available_books_ports_are_implemented_by_the_cache_adapter = classes()
            .that().areAnnotatedWith(AffectsAvailableBooks.class)
            .should(new ArchCondition<JavaClass>("être implémentée par AvailableBooksCacheAdapter (RM-CAT-42)") {
                @Override
                public void check(JavaClass port, ConditionEvents events) {
                    boolean decorated = port.getAllSubclasses().stream()
                            .anyMatch(c -> c.getName().equals(
                                    "com.example.library.catalog.adapter.out.cache.AvailableBooksCacheAdapter"));
                    events.add(new SimpleConditionEvent(port, decorated,
                            port.getName() + (decorated ? " est" : " n'est PAS")
                                    + " implémentée par AvailableBooksCacheAdapter"));
                }
            })
            .because("toute opération qui modifie les exemplaires disponibles invalide le cache après commit (RM-CAT-42)");

    @ArchTest
    static final ArchRule affects_available_books_annotation_only_on_out_ports = classes()
            .that().areAnnotatedWith(AffectsAvailableBooks.class)
            .should().beInterfaces()
            .andShould().resideInAPackage("..application.port.out..")
            .because("l'annotation marque un port de sortie d'écriture (RM-CAT-42)");
}
