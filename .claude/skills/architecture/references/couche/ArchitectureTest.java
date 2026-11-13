// architecture: couche
// Généré par la Claude Factory (/init-architecture). Ne pas modifier les règles : corriger le code.
package __BASE_PACKAGE__;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

@AnalyzeClasses(packages = "__BASE_PACKAGE__", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule layers_are_respected = layeredArchitecture()
        .consideringOnlyDependenciesInLayers()
        .layer("Controller").definedBy("..controller..")
        .layer("Mcp").definedBy("..mcp..")
        .layer("Service").definedBy("..service..")
        .layer("Mapper").definedBy("..mapper..")
        .layer("Repository").definedBy("..repository..")
        .layer("Entity").definedBy("..entity..")
        .layer("Dto").definedBy("..dto..")
        .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
        .whereLayer("Mcp").mayNotBeAccessedByAnyLayer()
        .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller", "Mcp", "Service")
        .whereLayer("Mapper").mayOnlyBeAccessedByLayers("Service")
        .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service")
        .whereLayer("Entity").mayOnlyBeAccessedByLayers("Repository", "Service", "Mapper")
        .whereLayer("Dto").mayOnlyBeAccessedByLayers("Controller", "Mcp", "Service", "Mapper")
        .because("controller (ou outil MCP) → service → repository → entity, sans saut de couche");

    @ArchTest
    static final ArchRule services_do_not_know_http = noClasses().that().resideInAPackage("..service..")
        .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..", "jakarta.servlet..")
        .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.http.ResponseEntity")
        .because("un service ne connaît pas HTTP : il lève des exceptions métier");

    @ArchTest
    static final ArchRule entities_are_independent = noClasses().that().resideInAPackage("..entity..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..controller..", "..service..", "..repository..", "..dto..", "..mapper..", "org.springframework.web..")
        .because("une entité ne dépend d'aucune autre couche");

    @ArchTest
    static final ArchRule features_talk_only_through_service_and_dto = slices().matching("__BASE_PACKAGE__.(*)..")
        .should().notDependOnEachOther()
        .ignoreDependency(alwaysTrue(), resideInAnyPackage(
            "__BASE_PACKAGE__.*.service..", "__BASE_PACKAGE__.*.dto..", "__BASE_PACKAGE__.*.exception..",
            "__BASE_PACKAGE__.shared..", "__BASE_PACKAGE__.config.."))
        .ignoreDependency(resideInAPackage("__BASE_PACKAGE__.config.."), alwaysTrue())
        .because("une feature n'utilise une autre feature que par son service, ses DTO et ses exceptions");
}
