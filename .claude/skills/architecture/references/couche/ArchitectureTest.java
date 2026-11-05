// architecture: couche
// Généré par la Claude Factory (/init-architecture). Ne pas modifier les règles : corriger le code.
package __BASE_PACKAGE__;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

@AnalyzeClasses(packages = "__BASE_PACKAGE__", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule layers_are_respected = layeredArchitecture()
        .consideringOnlyDependenciesInLayers()
        .layer("Controller").definedBy("..controller..")
        .layer("Service").definedBy("..service..")
        .layer("Mapper").definedBy("..mapper..")
        .layer("Repository").definedBy("..repository..")
        .layer("Entity").definedBy("..entity..")
        .layer("Dto").definedBy("..dto..")
        .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
        .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller", "Service")
        .whereLayer("Mapper").mayOnlyBeAccessedByLayers("Service")
        .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service")
        .whereLayer("Entity").mayOnlyBeAccessedByLayers("Repository", "Service", "Mapper")
        .whereLayer("Dto").mayOnlyBeAccessedByLayers("Controller", "Service", "Mapper")
        .because("controller → service → repository → entity, sans saut de couche");

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
}
