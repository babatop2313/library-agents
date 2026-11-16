// architecture: hexagonal
// Règles ArchUnit propres au cache Redis, ajoutées en complément d'ArchitectureTest (qui n'est pas modifié).
package com.example.library;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

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
}
