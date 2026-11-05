// architecture: hexagonal
// Généré par la Claude Factory (/init-architecture). Ne pas modifier les règles : corriger le code.
package com.example.library;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.example.library", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domain_is_pure = noClasses().that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework..", "jakarta.persistence..", "jakarta.validation..", "lombok..",
            "..application..", "..adapter..", "..config..")
        .because("le domaine ne dépend d'aucun framework ni d'aucune couche externe");

    @ArchTest
    static final ArchRule application_does_not_know_adapters = noClasses().that().resideInAPackage("..application..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..adapter..", "..config..", "jakarta.persistence..",
            "org.springframework.web..", "org.springframework.data..")
        .because("l'application parle à l'extérieur uniquement via ses ports");

    @ArchTest
    static final ArchRule inbound_adapters_use_input_ports_only = noClasses().that().resideInAPackage("..adapter.in..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..adapter.out..", "..application.usecase..", "..application.port.out..")
        .because("un contrôleur n'appelle qu'un use case via son interface");

    @ArchTest
    static final ArchRule outbound_adapters_implement_output_ports_only = noClasses().that().resideInAPackage("..adapter.out..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..adapter.in..", "..application.usecase..", "..application.port.in..")
        .because("un adapter sortant ne déclenche pas de use case");
}
